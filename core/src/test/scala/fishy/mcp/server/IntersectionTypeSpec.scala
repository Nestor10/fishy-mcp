package fishy.mcp.server

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.DispatchResult.*
import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, ToolContext}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.test.*
import zio.test.Assertion.*

/** Regression test for GitHub Issue #1: serveHttp rejects intersection R types.
  *
  * Before the R-erasure refactor, composing tools with different environment requirements (e.g.
  * Tool[ServiceA] and Tool[ServiceB]) caused a Tag synthesis failure at compile time because ZIO
  * could not derive Tag[ServiceA & ServiceB].
  *
  * After R-erasure, registries capture the environment at layer construction, so all services above
  * the registry boundary are R-free. This test proves that intersection types work correctly
  * end-to-end.
  */
object IntersectionTypeSpec extends ZIOSpecDefault:

  // Two independent service traits
  trait FileService:
    def read(path: String): UIO[String]

  trait DbService:
    def query(sql: String): UIO[String]

  final case class FileInput(path: String)
  object FileInput:
    given Schema[FileInput] = DeriveSchema.gen

  final case class DbInput(sql: String)
  object DbInput:
    given Schema[DbInput] = DeriveSchema.gen

  // Tool requiring FileService
  val fileTool: fishy.mcp.domain.model.Tool[FileService] =
    Tool("read_file").description("Read a file").handle[FileService, FileInput] { (in, _) =>
      ZIO.serviceWithZIO[FileService](_.read(in.path)).map(Content.Text(_))
    }

  // Tool requiring DbService
  val dbTool: fishy.mcp.domain.model.Tool[DbService] =
    Tool("query_db").description("Query database").handle[DbService, DbInput] { (in, _) =>
      ZIO.serviceWithZIO[DbService](_.query(in.sql)).map(Content.Text(_))
    }

  // Tool with no environment requirement
  val pureTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("ping").description("Ping").noInput { ZIO.succeed("pong") }

  // Stub layers for the two services
  val fileServiceLayer: ULayer[FileService] = ZLayer.succeed(
    new FileService:
      def read(path: String): UIO[String] = ZIO.succeed(s"content of $path")
  )

  val dbServiceLayer: ULayer[DbService] = ZLayer.succeed(
    new DbService:
      def query(sql: String): UIO[String] = ZIO.succeed(s"result of $sql")
  )

  // Build server with intersection type R = FileService & DbService
  val serverLayer =
    (fileServiceLayer ++ dbServiceLayer) >>>
      MCPServer
        .withName("intersection-test")
        .withVersion("1.0.0")
        .withTools(fileTool)
        .withTools(dbTool)
        .withTools(pureTool)
        .buildLayers

  def request(id: Long, method: String, params: Option[Json] = None): Request =
    Request("2.0", method, params, Some(RequestId.NumberId(id)))

  def spec = suite("Intersection type R (Issue #1)")(
    test("initialize succeeds with intersection-type server") {
      for
        result <- McpDispatcher.dispatch(
          request(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("test"), "version" -> Json.Str("1.0"))
            ))
          ),
          None
        )
        single <- result.toOption
      yield assertTrue(
        single.isDefined,
        single.get.isRight
      )
    },
    test("tools/list returns tools from both service environments") {
      for
        _ <- McpDispatcher.dispatch(
          request(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("test"), "version" -> Json.Str("1.0"))
            ))
          ),
          None
        )
        _ <- McpDispatcher.dispatch(
          Request("2.0", "notifications/initialized", None, None),
          Some("session-1")
        )
        result <- McpDispatcher.dispatch(
          request(2, "tools/list"),
          Some("session-1")
        )
        single <- result.toOption
      yield
        val json = single.get.toOption.get.result.toString
        assertTrue(
          json.contains("read_file"),
          json.contains("query_db"),
          json.contains("ping")
        )
    },
    test("tools/call dispatches to FileService tool") {
      for
        _ <- McpDispatcher.dispatch(
          request(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("test"), "version" -> Json.Str("1.0"))
            ))
          ),
          None
        )
        _ <- McpDispatcher.dispatch(
          Request("2.0", "notifications/initialized", None, None),
          Some("session-1")
        )
        result <- McpDispatcher.dispatch(
          request(
            3,
            "tools/call",
            Some(Json.Obj(
              "name" -> Json.Str("read_file"),
              "arguments" -> Json.Obj("path" -> Json.Str("/etc/hosts"))
            ))
          ),
          Some("session-1")
        )
        single <- result.toOption
      yield
        val json = single.get.toOption.get.result.toString
        assertTrue(json.contains("content of /etc/hosts"))
    },
    test("tools/call dispatches to DbService tool") {
      for
        _ <- McpDispatcher.dispatch(
          request(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("test"), "version" -> Json.Str("1.0"))
            ))
          ),
          None
        )
        _ <- McpDispatcher.dispatch(
          Request("2.0", "notifications/initialized", None, None),
          Some("session-1")
        )
        result <- McpDispatcher.dispatch(
          request(
            4,
            "tools/call",
            Some(Json.Obj(
              "name" -> Json.Str("query_db"),
              "arguments" -> Json.Obj("sql" -> Json.Str("SELECT 1"))
            ))
          ),
          Some("session-1")
        )
        single <- result.toOption
      yield
        val json = single.get.toOption.get.result.toString
        assertTrue(json.contains("result of SELECT 1"))
    }
  ).provideShared(serverLayer)
