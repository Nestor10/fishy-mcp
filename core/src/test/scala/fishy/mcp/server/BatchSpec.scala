package fishy.mcp.server

import fishy.mcp.adapters.inbound.http.{HttpSecurityPolicy, HttpTransport}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.domain.model.mcp.*
import fishy.mcp.application.ports.{SessionStore, ToolRegistry}
import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.bootstrap.{AppConfig, MCPServer}
import fishy.mcp.domain.model
import fishy.mcp.domain.model.{Content, ToolContext}
import fishy.mcp.dsl.Tool
import zio.*
import zio.http.{Body, Header, MediaType, Method, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.test.*
import zio.test.Assertion.*

object BatchSpec extends ZIOSpecDefault:

  final case class AddInput(a: Int, b: Int)
  object AddInput:
    given Schema[AddInput] = DeriveSchema.gen

  val addTool: model.Tool[Any] =
    Tool("add").description("Adds two numbers").handle { (in: AddInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Result: ${in.a + in.b}"))
    }

  val serverLayer = MCPServer
    .withName("batch-test")
    .withVersion("1.0.0")
    .withTools(addTool)
    .buildLayers

  val transportLayer = AppConfig.testDefaults >>> serverLayer >>> HttpTransport.layer

  private def postMcp(body: String): ZIO[HttpTransport, Throwable, zio.http.Response] =
    for
      transport <- ZIO.service[HttpTransport]
      response <- ZIO.scoped {
        transport.routes.runZIO(
          zio.http.Request.post(URL.root / "mcp", Body.fromString(body))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
      }
    yield response

  private def bodyJson(response: zio.http.Response): ZIO[Any, Throwable, String] =
    response.body.asString

  def spec = suite("JSON-RPC Batch")(
    test("batch of two requests returns array of two responses") {
      val batch = Json.Arr(
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("ping"),
          "id" -> Json.Num(1)
        ),
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("ping"),
          "id" -> Json.Num(2)
        )
      ).toJson

      for
        response <- postMcp(batch)
        body <- bodyJson(response)
        arr = Json.decoder.decodeJson(body).toOption.get
      yield assertTrue(
        response.status == Status.Ok,
        arr.isInstanceOf[Json.Arr],
        arr.asInstanceOf[Json.Arr].elements.size == 2
      )
    },
    test("batch with notification omits notification from response array") {
      val batch = Json.Arr(
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("ping"),
          "id" -> Json.Num(1)
        ),
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("notifications/initialized")
        )
      ).toJson

      for
        response <- postMcp(batch)
        body <- bodyJson(response)
        arr = Json.decoder.decodeJson(body).toOption.get.asInstanceOf[Json.Arr]
      yield assertTrue(
        response.status == Status.Ok,
        arr.elements.size == 1
      )
    },
    test("batch of only notifications returns 202 Accepted") {
      val batch = Json.Arr(
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("notifications/initialized")
        ),
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("notifications/something")
        )
      ).toJson

      for
        response <- postMcp(batch)
      yield assertTrue(response.status == Status.Accepted)
    },
    test("batch containing initialize is rejected") {
      val batch = Json.Arr(
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("initialize"),
          "id" -> Json.Num(1),
          "params" -> Json.Obj(
            "protocolVersion" -> Json.Str("2024-11-05"),
            "capabilities" -> Json.Obj(),
            "clientInfo" -> Json.Obj("name" -> Json.Str("test"), "version" -> Json.Str("1.0"))
          )
        ),
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method" -> Json.Str("ping"),
          "id" -> Json.Num(2)
        )
      ).toJson

      for
        response <- postMcp(batch)
        body <- bodyJson(response)
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("initialize must not be sent inside a batch")
      )
    },
    test("empty batch array returns error") {
      for
        response <- postMcp("[]")
        body <- bodyJson(response)
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Invalid request")
      )
    },
    test("single request still works (not a batch)") {
      val single = Json.Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "method" -> Json.Str("ping"),
        "id" -> Json.Num(1)
      ).toJson

      for
        response <- postMcp(single)
        body <- bodyJson(response)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("\"result\":{}")
      )
    },
    test("invalid JSON returns parse error") {
      for
        response <- postMcp("not valid json{{{")
        body <- bodyJson(response)
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Parse error")
      )
    }
  ).provideShared(transportLayer)
