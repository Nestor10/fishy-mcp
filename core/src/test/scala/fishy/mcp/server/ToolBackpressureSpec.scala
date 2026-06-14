package fishy.mcp.server

import fishy.mcp.application.ports.*
import fishy.mcp.application.usecase.*
import fishy.mcp.adapters.storage.{InMemoryBackend, InMemorySubscriptionRegistry}
import fishy.mcp.bootstrap.TracingLayers
import fishy.mcp.domain.model.{DispatchResult, McpError, RequestId}
import fishy.mcp.dsl.Tool
import zio.*
import zio.json.ast.Json
import zio.test.*

/** Locks the `maxInFlight` budget: once the cap is reached, further tool calls
  * are rejected with `-32099` rather than accumulating fiber state. Covers both
  * the streaming and sync paths (both share `InFlightCalls.reserve`). */
object ToolBackpressureSpec extends ZIOSpecDefault:

  private def isOverload(r: DispatchResult): Boolean =
    r match
      case DispatchResult.Single(p) => p.outcome.left.toOption.exists(_.code == -32099)
      case _                        => false

  private val syncParams = Json.Obj("name" -> Json.Str("block"), "arguments" -> Json.Obj())
  private val streamParams = Json.Obj(
    "name"      -> Json.Str("block"),
    "arguments" -> Json.Obj(),
    "_meta"     -> Json.Obj("progressToken" -> Json.Str("t"))
  )

  private def executor(max: Int, tool: fishy.mcp.domain.model.Tool[Any]): ZLayer[Any, Nothing, ToolExecutor] =
    ZLayer.make[ToolExecutor](
      ToolRegistry.layer(List(tool)),
      ToolExecutor.layerWith(publishToolEvents = false, maxInFlight = max),
      ClientRequester.layer,
      NotificationSender.layer,
      InMemorySubscriptionRegistry.layer,
      InMemoryBackend.layer,
      TracingLayers.noop
    )

  def spec = suite("ToolExecutor backpressure (maxInFlight)")(
    test("streaming: the 3rd concurrent call beyond maxInFlight=2 is rejected -32099") {
      // Streaming reserves its slot synchronously when `call` returns, so the
      // first two hold the budget without even being consumed.
      val tool = Tool.noInput("block", "blocks until interrupted")(ZIO.never.as("x"))
      ZIO.scoped {
        for
          exec <- executor(2, tool).build.map(_.get[ToolExecutor])
          r1   <- exec.call(RequestId.NumberId(1), Some(streamParams), Some("s"))
          r2   <- exec.call(RequestId.NumberId(2), Some(streamParams), Some("s"))
          r3   <- exec.call(RequestId.NumberId(3), Some(streamParams), Some("s"))
          _    <- exec.cancel(RequestId.NumberId(1))
          _    <- exec.cancel(RequestId.NumberId(2))
        yield assertTrue(
          r1.isInstanceOf[DispatchResult.Streaming],
          r2.isInstanceOf[DispatchResult.Streaming],
          isOverload(r3)
        )
      }
    },
    test("sync: a call beyond maxInFlight=2 is rejected -32099") {
      ZIO.scoped {
        for
          gate <- Queue.unbounded[Unit]
          tool  = Tool.noInput("block", "signals then blocks")(gate.offer(()) *> ZIO.never.as("x"))
          exec <- executor(2, tool).build.map(_.get[ToolExecutor])
          // Sync calls block on the tool, so fork them; the gate confirms both
          // are running (hence both slots reserved) before the 3rd call.
          f1 <- exec.call(RequestId.NumberId(1), Some(syncParams), None).fork
          f2 <- exec.call(RequestId.NumberId(2), Some(syncParams), None).fork
          _  <- gate.take *> gate.take
          r3 <- exec.call(RequestId.NumberId(3), Some(syncParams), None)
          _  <- f1.interrupt
          _  <- f2.interrupt
        yield assertTrue(isOverload(r3))
      }
    }
  ) @@ TestAspect.timeout(20.seconds)
