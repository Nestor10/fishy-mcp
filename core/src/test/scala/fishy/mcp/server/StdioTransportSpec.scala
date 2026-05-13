package fishy.mcp.server

import fishy.mcp.adapters.inbound.stdio.StdioTransport
import fishy.mcp.bootstrap.AppConfig
import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, RequestId, ToolContext}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

/** Unit tests for the stdio transport using simulated streams. */
object StdioTransportSpec extends ZIOSpecDefault:

  final case class EchoInput(msg: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  // Define test tools
  val echoTool =
    Tool("echo").description("Echoes input").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Echo: ${in.msg}"))
    }

  // Helper to create a test request
  def mkRequest(id: Long, method: String, params: Option[Json] = None): String =
    Request(
      jsonrpc = "2.0",
      method = method,
      params = params,
      id = Some(RequestId.NumberId(id))
    ).toJson

  def spec = suite("StdioTransportSpec")(
    test("processes initialize request and returns response") {
      val initRequest = mkRequest(
        1,
        "initialize",
        Some(InitializeParams(
          protocolVersion = "2024-11-05",
          capabilities = ClientCapabilities(),
          clientInfo = ClientInfo("test-client", "1.0.0")
        ).toJsonAST.toOption.get)
      )

      val input = ZStream.fromIterable(List(initRequest))
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.nonEmpty) &&
        assert(lines.head)(containsString("test-server")) &&
        assert(lines.head)(containsString("capabilities"))
    },
    test("processes tools/list request") {
      val listRequest = mkRequest(2, "tools/list")

      val input = ZStream.fromIterable(List(listRequest))
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.nonEmpty) &&
        assert(lines.head)(containsString("echo"))
    },
    test("processes tools/call request") {
      val callRequest = mkRequest(
        3,
        "tools/call",
        Some(ToolCallParams(
          name = "echo",
          arguments = Some(Json.Obj("msg" -> Json.Str("Hello stdio!")))
        ).toJsonAST.toOption.get)
      )

      val input = ZStream.fromIterable(List(callRequest))
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.nonEmpty) &&
        assert(lines.head)(containsString("Echo: Hello stdio!"))
    },
    test("handles multiple requests in sequence") {
      val requests = List(
        mkRequest(1, "ping"),
        mkRequest(2, "tools/list"),
        mkRequest(3, "ping")
      )

      val input = ZStream.fromIterable(requests)
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.length == 3)
    },
    test("returns parse error for invalid JSON") {
      val input = ZStream.fromIterable(List("not valid json"))
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.nonEmpty) &&
        assert(lines.head)(containsString("-32700")) // Parse error code
    },
    test("skips empty lines") {
      val requests = List(
        "",
        mkRequest(1, "ping"),
        "   ",
        mkRequest(2, "ping")
      )

      val input = ZStream.fromIterable(requests)
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.length == 2) // Only 2 actual requests
    },
    test("notifications produce no output") {
      val notification = Request(
        jsonrpc = "2.0",
        method = "notifications/initialized",
        params = None,
        id = None
      ).toJson

      val requests = List(
        notification,
        mkRequest(1, "ping")
      )

      val input = ZStream.fromIterable(requests)
      val outputRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
      }
      val output = ZSink.foreach[Any, Nothing, String](line => outputRef.update(_ :+ line))

      val serverLayer = MCPServer
        .withName("test-server")
        .withVersion("1.0.0")
        .withTools(echoTool)
        .buildLayers

      val testLayer = AppConfig.testDefaults >>> serverLayer >>> StdioTransport.withStreams(input, output)

      for
        _ <- StdioTransport.run.provide(testLayer)
        lines <- outputRef.get
      yield assertTrue(lines.length == 1) // Only ping, not the notification
    }
  )
