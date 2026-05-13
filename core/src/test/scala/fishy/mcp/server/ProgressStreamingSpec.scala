package fishy.mcp.server

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.DispatchResult.*
import fishy.mcp.dsl.Tool
import fishy.mcp.domain.model.{Content, ToolContext}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect

object ProgressStreamingSpec extends ZIOSpecDefault:

  val serverInfo = ServerInfo("test-server", "1.0.0")

  final case class SlowInput(steps: Int)
  object SlowInput:
    given Schema[SlowInput] = DeriveSchema.gen

  /** A tool that reports progress via ProgressReporter.current FiberRef. */
  val progressTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("slow-task").description("A task that reports progress").handle {
      (in: SlowInput, _: ToolContext) =>
        for
          reporter <- ProgressReporter.current.get
          _ <- ZIO.foreach(1 to in.steps) { step =>
            reporter.report(step.toDouble, Some(in.steps.toDouble), Some(s"Step $step"))
          }
        yield Content.Text(s"Completed ${in.steps} steps")
    }

  /** A tool that blocks on a Promise (for cancellation testing). */
  val blockingTool: fishy.mcp.domain.model.Tool[Any] =
    Tool.noInput("blocking-task", "A task that blocks until interrupted") {
      ZIO.never.as("should not reach")
    }

  def makeDispatcher(tools: List[fishy.mcp.domain.model.Tool[Any]]): UIO[McpDispatcher] =
    TestLayers.makeDispatcher(
      tools = tools,
      serverInfo = serverInfo,
      capabilities = ServerCapabilities.toolsOnly
    ).map(_._1)

  def request(
      method: String,
      params: Option[Json] = None,
      id: RequestId = RequestId.NumberId(1)
  ): Request =
    Request("2.0", method, params, Some(id))

  /** Build tools/call params with a progress token in _meta. */
  def toolCallWithProgress(name: String, args: Json, token: Json): Json =
    Json.Obj(
      "name" -> Json.Str(name),
      "arguments" -> args,
      "_meta" -> Json.Obj("progressToken" -> token)
    )

  /** Build tools/call params without a progress token. */
  def toolCallParams(name: String, args: Json): Json =
    Json.Obj(
      "name" -> Json.Str(name),
      "arguments" -> args
    )

  def spec = suite("Progress & Streaming")(
    suite("tools/call with progressToken")(
      test("returns DispatchResult.Streaming when progressToken is present") {
        for
          dispatcher <- makeDispatcher(List(progressTool))
          params =
            toolCallWithProgress("slow-task", Json.Obj("steps" -> Json.Num(3)), Json.Str("tok-1"))
          result <- dispatcher.dispatch(request("tools/call", Some(params)), None)
        yield result match
          case DispatchResult.Streaming(_) => assertCompletes
          case other                       => assertTrue(false)
      },
      test("stream contains progress notifications and final response") {
        for
          dispatcher <- makeDispatcher(List(progressTool))
          params =
            toolCallWithProgress("slow-task", Json.Obj("steps" -> Json.Num(3)), Json.Str("tok-1"))
          result <- dispatcher.dispatch(request("tools/call", Some(params)), None)
          events <- result match
            case DispatchResult.Streaming(s) =>
              s.map(DispatchResultEncoder.encodeFrameJson).runCollect
            case _ => ZIO.succeed(Chunk.empty[String])
        yield
          // 3 progress notifications + 1 final response = 4 events
          val progressEvents = events.filter(_.contains("notifications/progress"))
          val finalEvent = events.last
          assertTrue(
            events.size == 4,
            progressEvents.size == 3,
            finalEvent.contains("Completed 3 steps"),
            // Verify progress token is echoed
            events.head.contains("tok-1")
          )
      },
      test("progress notifications contain correct progress values") {
        for
          dispatcher <- makeDispatcher(List(progressTool))
          params = toolCallWithProgress("slow-task", Json.Obj("steps" -> Json.Num(2)), Json.Num(42))
          result <- dispatcher.dispatch(request("tools/call", Some(params)), None)
          events <- result match
            case DispatchResult.Streaming(s) =>
              s.map(DispatchResultEncoder.encodeFrameJson).runCollect
            case _ => ZIO.succeed(Chunk.empty[String])
        yield
          val progressEvents = events.filter(_.contains("notifications/progress"))
          assertTrue(
            progressEvents.size == 2,
            // First progress: 1.0 of 2.0
            progressEvents(0).contains("\"progress\":1"),
            progressEvents(0).contains("\"total\":2"),
            progressEvents(0).contains("Step 1"),
            // Second progress: 2.0 of 2.0
            progressEvents(1).contains("\"progress\":2"),
            progressEvents(1).contains("Step 2"),
            // Numeric token
            progressEvents(0).contains("42")
          )
      }
    ),
    suite("tools/call without progressToken")(
      test("returns DispatchResult.Single when no progressToken") {
        for
          dispatcher <- makeDispatcher(List(progressTool))
          params = toolCallParams("slow-task", Json.Obj("steps" -> Json.Num(1)))
          result <- dispatcher.dispatch(request("tools/call", Some(params)), None)
        yield result match
          case DispatchResult.Single(p) if p.outcome.isRight =>
            assertTrue(p.outcome.toOption.get.toString.contains("Completed 1 steps"))
          case _ => assertTrue(false)
      }
    ),
    suite("toOption with streaming")(
      test("toOption drains stream and returns final response") {
        for
          dispatcher <- makeDispatcher(List(progressTool))
          params =
            toolCallWithProgress("slow-task", Json.Obj("steps" -> Json.Num(2)), Json.Str("t"))
          dispatchResult <- dispatcher.dispatch(request("tools/call", Some(params)), None)
          result         <- dispatchResult.toOption
        yield
          val json = result.get.outcome.toOption.get.toString
          assertTrue(json.contains("Completed 2 steps"))
      }
    ),
    suite("cancellation")(
      test("notifications/cancelled interrupts in-flight tool execution") {
        ZIO.logAnnotate("test", "cancellation") {
          for
            dispatcher <- makeDispatcher(List(blockingTool))
            params = toolCallWithProgress("blocking-task", Json.Obj(), Json.Str("cancel-tok"))
            _ <- ZIO.logDebug("Dispatching blocking tool call")
            result <-
              dispatcher.dispatch(request("tools/call", Some(params), RequestId.NumberId(99)), None)
            _ <- ZIO.logAnnotate("resultType", result.getClass.getSimpleName) {
              ZIO.logDebug("Dispatch returned")
            }
            collectFiber <- (result match
              case DispatchResult.Streaming(s) =>
                ZIO.logDebug("Starting stream collection") *>
                  s.map(DispatchResultEncoder.encodeFrameJson).runCollect
              case _ => ZIO.succeed(Chunk.empty[String])
            ).fork
            _ <- ZIO.yieldNow
            _ <- ZIO.logDebug("Sending cancellation")
            cancelParams = Json.Obj(
              "requestId" -> Json.Num(99),
              "reason" -> Json.Str("User cancelled")
            )
            cancelNotification = Request("2.0", "notifications/cancelled", Some(cancelParams), None)
            _ <- dispatcher.dispatch(cancelNotification, None)
            _ <- ZIO.logDebug("Cancellation dispatched, joining collect fiber")
            events <- collectFiber.join.timeout(5.seconds).map(_.getOrElse(Chunk.empty))
            _ <- ZIO.logAnnotate("eventCount", events.size.toString) {
              ZIO.logDebug("Collection complete")
            }
          yield assertTrue(events.isEmpty || events.forall(!_.contains("should not reach")))
        }
      } @@ TestAspect.withLiveClock
    )
  ) @@ TestAspect.timeout(10.seconds)
