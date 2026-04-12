package fishy.mcp.server

import fishy.mcp.application.ports.ToolRegistry
import fishy.mcp.domain.model.*
import fishy.mcp.dsl.Tool
import zio.*
import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.schema.Schema
import zio.test.*
import zio.test.Assertion.*

object ToolRegistrySpec extends ZIOSpecDefault:

  final case class EchoInput(message: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  final case class FailInput(input: String)
  object FailInput:
    given Schema[FailInput] = DeriveSchema.gen

  // Test tools
  val echoTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("echo").description("Echo the input").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(in.message))
    }

  val failingTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("fail").description("Always fails").handle { (_: FailInput, _: ToolContext) =>
      ZIO.fail(ToolError("Intentional failure"))
    }

  val scopedTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("admin-echo").description("Needs admin scope").requireScopes("admin", "echo:write").handle {
      (in: EchoInput, _: ToolContext) =>
        ZIO.succeed(Content.Text(in.message))
    }

  def spec = suite("ToolRegistry")(
    suite("list")(
      test("returns all registered tools") {
        for
          registry <- TestLayers.makeRegistry(List(echoTool, failingTool))
          tools <- registry.list
        yield assertTrue(
          tools.length == 2,
          tools.map(_.name).toSet == Set("echo", "fail")
        )
      },
      test("returns empty list when no tools registered") {
        for
          registry <- TestLayers.makeRegistry(Nil)
          tools <- registry.list
        yield assertTrue(tools.isEmpty)
      }
    ),
    suite("call")(
      test("executes tool with valid input") {
        val ctx = ToolContext("test", None, None)
        for
          registry <- TestLayers.makeRegistry(List(echoTool))
          result <- registry.call("echo", Json.Obj("message" -> Json.Str("hello")), ctx)
        yield assertTrue(result == Content.Text("hello"))
      },
      test("returns NotFound for unknown tool") {
        val ctx = ToolContext("test", None, None)
        for
          registry <- TestLayers.makeRegistry(List(echoTool))
          result <- registry.call("unknown", Json.Obj(), ctx).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.isInstanceOf[ToolError.NotFound])
        )
      },
      test("propagates tool execution errors") {
        val ctx = ToolContext("test", None, None)
        for
          registry <- TestLayers.makeRegistry(List(failingTool))
          result <- registry.call("fail", Json.Obj("input" -> Json.Str("test")), ctx).either
        yield assertTrue(result.isLeft)
      }
    ),
    suite("requireScopes")(
      test("allows call when caller has all required scopes") {
        val auth = AuthContext(sub = "user1", scopes = Set("admin", "echo:write", "extra"))
        val ctx = ToolContext("test", None, None, auth = Some(auth))
        for
          registry <- TestLayers.makeRegistry(List(scopedTool))
          result <- registry.call("admin-echo", Json.Obj("message" -> Json.Str("hi")), ctx)
        yield assertTrue(result == Content.Text("hi"))
      },
      test("denies call when caller is missing scopes") {
        val auth = AuthContext(sub = "user1", scopes = Set("admin"))
        val ctx = ToolContext("test", None, None, auth = Some(auth))
        for
          registry <- TestLayers.makeRegistry(List(scopedTool))
          result <- registry.call("admin-echo", Json.Obj("message" -> Json.Str("hi")), ctx).either
        yield assertTrue(
          result == Left(ToolError.PermissionDenied("Missing scopes: echo:write"))
        )
      },
      test("allows call when no auth context present (stdio / no security policy)") {
        val ctx = ToolContext("test", None, None, auth = None)
        for
          registry <- TestLayers.makeRegistry(List(scopedTool))
          result <- registry.call("admin-echo", Json.Obj("message" -> Json.Str("hi")), ctx)
        yield assertTrue(result == Content.Text("hi"))
      },
      test("tool without scopes allows unauthenticated calls") {
        val ctx = ToolContext("test", None, None, auth = None)
        for
          registry <- TestLayers.makeRegistry(List(echoTool))
          result <- registry.call("echo", Json.Obj("message" -> Json.Str("open")), ctx)
        yield assertTrue(result == Content.Text("open"))
      },
      test("list includes requiredScopes in ToolInfo") {
        for
          registry <- TestLayers.makeRegistry(List(scopedTool, echoTool))
          tools <- registry.list
          scoped = tools.find(_.name == "admin-echo")
          open = tools.find(_.name == "echo")
        yield assertTrue(
          scoped.exists(_.requiredScopes == Set("admin", "echo:write")),
          open.exists(_.requiredScopes.isEmpty)
        )
      }
    )
  )
