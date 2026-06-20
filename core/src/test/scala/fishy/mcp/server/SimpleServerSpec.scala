package fishy.mcp.server

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.bootstrap.AppConfig
import fishy.mcp.domain.model.DispatchResult.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.test.*
import zio.test.Assertion.*

import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, RequestId, ToolContext}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.domain.model.mcp.*

/** Integration test that builds a simple MCP server and exercises the full dispatch path. */
object SimpleServerSpec extends ZIOSpecDefault:

  final case class EchoInput(message: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  final case class AddInput(a: Int, b: Int)
  object AddInput:
    given Schema[AddInput] = DeriveSchema.gen

  // Define tools using the DSL
  val echoTool =
    Tool("echo").description("Echoes the input back").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Echo: ${in.message}"))
    }

  val addTool =
    Tool("add").description("Adds two numbers").handle { (in: AddInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Result: ${in.a + in.b}"))
    }

  val greetTool = Tool("greet").description("Returns a greeting").noInput {
    ZIO.succeed("Hello, MCP!")
  }

  // Build the server layers
  val serverLayer = AppConfig.testDefaults >>> MCPServer
    .withName("test-server")
    .withVersion("1.0.0")
    .withTools(echoTool, addTool, greetTool)
    .buildLayers

  // Helper to create a request
  def request(id: Long, method: String, params: Option[Json] = None): Request =
    Request(
      jsonrpc = "2.0",
      method = method,
      params = params,
      id = Some(RequestId.NumberId(id))
    )

  def spec = suite("SimpleServerSpec")(
    suite("Full MCP lifecycle")(
      test("initialize returns server info and capabilities") {
        for
          response <- McpDispatcher.dispatch(
            request(
              id = 1,
              method = "initialize",
              params = Some(
                InitializeParams(
                  protocolVersion = "2024-11-05",
                  capabilities = ClientCapabilities(),
                  clientInfo = ClientInfo("test-client", "1.0.0")
                ).toJsonAST.toOption.get
              )
            )
          ).flatMap(_.toOption)
        yield
          val json = response.get.outcome.toOption.get.toString
          assert(json)(containsString("test-server")) &&
          assert(json)(containsString("1.0.0")) &&
          assert(json)(containsString("tools"))
      },
      test("tools/list returns all registered tools") {
        for
          response <- McpDispatcher.dispatch(
            request(id = 2, method = "tools/list")
          ).flatMap(_.toOption)
        yield
          val json = response.get.outcome.toOption.get.toString
          assert(json)(containsString("echo")) &&
          assert(json)(containsString("add")) &&
          assert(json)(containsString("greet"))
      },
      test("tools/call executes echo tool") {
        for
          response <- McpDispatcher.dispatch(
            request(
              id = 3,
              method = "tools/call",
              params = Some(
                ToolCallParams(
                  name = "echo",
                  arguments = Some(Json.Obj("message" -> Json.Str("Hello World")))
                ).toJsonAST.toOption.get
              )
            )
          ).flatMap(_.toOption)
        yield
          val json = response.get.outcome.toOption.get.toString
          assert(json)(containsString("Echo: Hello World"))
      },
      test("tools/call executes add tool with two parameters") {
        for
          response <- McpDispatcher.dispatch(
            request(
              id = 4,
              method = "tools/call",
              params = Some(
                ToolCallParams(
                  name = "add",
                  arguments = Some(Json.Obj(
                    "a" -> Json.Num(5),
                    "b" -> Json.Num(7)
                  ))
                ).toJsonAST.toOption.get
              )
            )
          ).flatMap(_.toOption)
        yield
          val json = response.get.outcome.toOption.get.toString
          assert(json)(containsString("Result: 12"))
      },
      test("tools/call returns a tool error as an isError result, not a protocol error") {
        // A missing required argument must come back as an isError tool result (which MCP
        // clients surface to the model), not a JSON-RPC error (which clients genericize).
        for
          response <- McpDispatcher.dispatch(
            request(
              id = 9,
              method = "tools/call",
              params = Some(
                ToolCallParams(
                  name = "add",
                  arguments = Some(Json.Obj("a" -> Json.Num(5))) // missing required "b"
                ).toJsonAST.toOption.get
              )
            )
          ).flatMap(_.toOption)
        yield
          val outcome       = response.get.outcome
          val resultFields  = outcome.toOption.flatMap(_.asObject).map(_.fields.toMap)
          assert(outcome.toOption)(isSome(anything)) && // a result, not a JSON-RPC protocol error
          assertTrue(resultFields.exists(_.get("isError").contains(Json.Bool(true))))
      },
      test("tools/call executes zero-parameter greet tool") {
        for
          response <- McpDispatcher.dispatch(
            request(
              id = 5,
              method = "tools/call",
              params = Some(
                ToolCallParams(
                  name = "greet",
                  arguments = None
                ).toJsonAST.toOption.get
              )
            )
          ).flatMap(_.toOption)
        yield
          val json = response.get.outcome.toOption.get.toString
          assert(json)(containsString("Hello, MCP!"))
      },
      test("ping returns empty object") {
        for
          response <- McpDispatcher.dispatch(
            request(id = 6, method = "ping")
          ).flatMap(_.toOption)
        yield assertTrue(response.get.outcome.toOption.get == Json.Obj())
      }
    )
  ).provideShared(serverLayer)
