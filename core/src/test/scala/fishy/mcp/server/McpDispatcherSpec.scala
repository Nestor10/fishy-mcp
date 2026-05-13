package fishy.mcp.server

import fishy.mcp.application.ports.SessionStore
import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.*
import fishy.mcp.dsl.Tool
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.test.*
import zio.test.Assertion.*

object McpDispatcherSpec extends ZIOSpecDefault:

  final case class EchoInput(msg: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  val echoTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("echo").description("Echo input").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(in.msg))
    }

  val serverInfo = ServerInfo("test-server", "1.0.0")

  def makeDispatcher(
      tools: List[fishy.mcp.domain.model.Tool[Any]] = Nil,
      capabilities: ServerCapabilities = ServerCapabilities.toolsOnly
  ): UIO[(McpDispatcher, SessionStore)] =
    TestLayers.makeDispatcher(tools = tools, serverInfo = serverInfo, capabilities = capabilities)

  def request(
      method: String,
      params: Option[Json] = None,
      id: RequestId = RequestId.NumberId(1)
  ): Request =
    Request("2.0", method, params, Some(id))

  def spec = suite("McpDispatcher")(
    suite("initialize")(
      test("returns server capabilities") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isRight,
            inner.outcome.toOption.get.toString.contains("protocolVersion")
          )
      },
      test("advertises tools capability when tools are registered") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val json = result.get.outcome.toOption.get.toString
          assertTrue(
            json.contains("\"tools\""),
            !json.contains("\"resources\""),
            !json.contains("\"prompts\""),
            !json.contains("\"logging\"")
          )
      },
      test("omits tools capability when no tools registered") {
        for
          (dispatcher, _) <- makeDispatcher(capabilities = ServerCapabilities())
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val json = result.get.outcome.toOption.get.toString
          assertTrue(!json.contains("\"tools\""))
      }
    ),
    suite("ping")(
      test("returns empty object") {
        for
          (dispatcher, _) <- makeDispatcher()
          result <- dispatcher.dispatch(request("ping"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isRight,
            inner.outcome.toOption.get == Json.Obj()
          )
      }
    ),
    suite("tools/list")(
      test("returns registered tools") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <- dispatcher.dispatch(request("tools/list"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isRight,
            inner.outcome.toOption.get.toString.contains("echo")
          )
      }
    ),
    suite("tools/call")(
      test("executes tool and returns result") {
        val params = Json.Obj(
          "name" -> Json.Str("echo"),
          "arguments" -> Json.Obj("msg" -> Json.Str("hello world"))
        )
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <-
            dispatcher.dispatch(request("tools/call", Some(params)), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isRight,
            inner.outcome.toOption.get.toString.contains("hello world")
          )
      },
      test("returns error for missing params") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <- dispatcher.dispatch(request("tools/call", None), None).flatMap(_.toOption)
        yield assertTrue(result.get.outcome.isLeft)
      }
    ),
    suite("notifications")(
      test("notifications/initialized returns None") {
        val notification = Request("2.0", "notifications/initialized", None, None)
        for
          (dispatcher, _) <- makeDispatcher()
          result <- dispatcher.dispatch(notification, None).flatMap(_.toOption)
        yield assertTrue(result.isEmpty)
      },
      test("unknown notifications are silently ignored") {
        val notification = Request("2.0", "notifications/something/unknown", None, None)
        for
          (dispatcher, _) <- makeDispatcher()
          result <- dispatcher.dispatch(notification, None).flatMap(_.toOption)
        yield assertTrue(result.isEmpty)
      }
    ),
    suite("initialization gate")(
      test("rejects tools/list before initialization when session exists") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <-
            dispatcher.dispatch(request("tools/list"), Some("session-1")).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isLeft,
            inner.outcome.left.toOption.get.code == ErrorCode.InvalidRequest
          )
      },
      test("allows tools/list after initialization") {
        for
          (dispatcher, store) <- makeDispatcher(List(echoTool))
          _ <- store.markInitialized("session-1")
          result <-
            dispatcher.dispatch(request("tools/list"), Some("session-1")).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(inner.outcome.isRight)
      },
      test("skips init gate when sessionId is None") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          result <- dispatcher.dispatch(request("tools/list"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(inner.outcome.isRight)
      },
      test("allows ping before initialization") {
        for
          (dispatcher, _) <- makeDispatcher()
          result <- dispatcher.dispatch(request("ping"), Some("session-1")).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(inner.outcome.isRight)
      },
      test("notifications/initialized marks session as initialized") {
        for
          (dispatcher, _) <- makeDispatcher(List(echoTool))
          // Before: tools/list should fail
          beforeResult <-
            dispatcher.dispatch(request("tools/list"), Some("session-1")).flatMap(_.toOption)
          // Send notifications/initialized
          notification = Request("2.0", "notifications/initialized", None, None)
          _ <- dispatcher.dispatch(notification, Some("session-1"))
          // After: tools/list should succeed
          afterResult <-
            dispatcher.dispatch(request("tools/list"), Some("session-1")).flatMap(_.toOption)
        yield assertTrue(
          beforeResult.get.outcome.isLeft,
          afterResult.get.outcome.isRight
        )
      }
    ),
    suite("unknown method")(
      test("returns MethodNotFound error") {
        for
          (dispatcher, _) <- makeDispatcher()
          result <- dispatcher.dispatch(request("unknown/method"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.outcome.isLeft,
            inner.outcome.left.toOption.get.code == ErrorCode.MethodNotFound
          )
      }
    )
  )
