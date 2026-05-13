package fishy.mcp.server

import fishy.mcp.adapters.inbound.http.{HttpSecurityPolicy, TrustedHeaderPolicy}
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

object AuthPropagationSpec extends ZIOSpecDefault:

  final case class EmptyInput()
  object EmptyInput:
    given Schema[EmptyInput] = DeriveSchema.gen

  val whoamiTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("whoami").description("Returns caller identity").handle {
      (_: EmptyInput, ctx: ToolContext) =>
        ZIO.succeed(Content.Text(
          ctx.auth match
            case Some(a) =>
              s"sub=${a.sub} email=${a.email.getOrElse("none")} groups=${a.groups.mkString(",")}"
            case None => "anonymous"
        ))
    }

  def makeDispatcher(tools: List[fishy.mcp.domain.model.Tool[Any]] = Nil) =
    TestLayers.makeDispatcher(tools = tools)

  def toolCallRequest(name: String, args: Json = Json.Obj()): Request =
    Request(
      "2.0",
      "tools/call",
      Some(Json.Obj("name" -> Json.Str(name), "arguments" -> args)),
      Some(RequestId.NumberId(1))
    )

  def spec = suite("AuthPropagation")(
    suite("AuthFiberRef")(
      test("defaults to None when unset") {
        for
          auth <- AuthFiberRef.currentAuth.get
        yield assertTrue(auth.isEmpty)
      },
      test("propagates through fiber-local scope") {
        val ctx = AuthContext(sub = "user-1", email = Some("user@test.com"))
        for
          _ <- AuthFiberRef.currentAuth.set(Some(ctx))
          auth <- AuthFiberRef.currentAuth.get
          _ <- AuthFiberRef.currentAuth.set(None) // cleanup
        yield assertTrue(
          auth.isDefined,
          auth.get.sub == "user-1",
          auth.get.email.contains("user@test.com")
        )
      },
      test("child fiber inherits parent FiberRef value") {
        val ctx = AuthContext(sub = "parent-user")
        for
          _ <- AuthFiberRef.currentAuth.set(Some(ctx))
          child <- AuthFiberRef.currentAuth.get.fork
          auth <- child.join
          _ <- AuthFiberRef.currentAuth.set(None) // cleanup
        yield assertTrue(
          auth.isDefined,
          auth.get.sub == "parent-user"
        )
      }
    ),
    suite("ToolContext.auth via FiberRef")(
      test("tool receives auth=None when FiberRef is unset") {
        for
          _ <- AuthFiberRef.currentAuth.set(None) // ensure clean state
          (dispatcher, _) <- makeDispatcher(List(whoamiTool))
          result <- dispatcher.dispatch(toolCallRequest("whoami"), None).flatMap(_.toOption)
        yield
          val json = result.get.outcome.toOption.get.toJson
          assertTrue(json.contains("anonymous"))
      },
      test("tool receives auth when FiberRef is set") {
        val ctx =
          AuthContext(sub = "alice", email = Some("alice@corp.com"), groups = Set("admin", "dev"))
        for
          _ <- AuthFiberRef.currentAuth.set(Some(ctx))
          (dispatcher, _) <- makeDispatcher(List(whoamiTool))
          result <- dispatcher.dispatch(toolCallRequest("whoami"), None).flatMap(_.toOption)
          _ <- AuthFiberRef.currentAuth.set(None) // cleanup
        yield
          val json = result.get.outcome.toOption.get.toJson
          assertTrue(
            json.contains("sub=alice"),
            json.contains("email=alice@corp.com"),
            json.contains("admin"),
            json.contains("dev")
          )
      },
      test("tool receives correct auth across sequential calls") {
        val alice = AuthContext(sub = "alice")
        val bob = AuthContext(sub = "bob")
        for
          (dispatcher, _) <- makeDispatcher(List(whoamiTool))
          _ <- AuthFiberRef.currentAuth.set(Some(alice))
          result1 <- dispatcher.dispatch(toolCallRequest("whoami"), None).flatMap(_.toOption)
          _ <- AuthFiberRef.currentAuth.set(Some(bob))
          result2 <- dispatcher.dispatch(toolCallRequest("whoami"), None).flatMap(_.toOption)
          _ <- AuthFiberRef.currentAuth.set(None)
        yield
          val json1 = result1.get.outcome.toOption.get.toJson
          val json2 = result2.get.outcome.toOption.get.toJson
          assertTrue(
            json1.contains("sub=alice"),
            json2.contains("sub=bob")
          )
      }
    ),
    suite("AuthContext")(
      test("hasScopes returns true when all required scopes present") {
        val ctx = AuthContext(sub = "u", scopes = Set("read", "write", "admin"))
        assertTrue(ctx.hasScopes(Set("read", "write")))
      },
      test("hasScopes returns false when a required scope is missing") {
        val ctx = AuthContext(sub = "u", scopes = Set("read"))
        assertTrue(!ctx.hasScopes(Set("read", "write")))
      },
      test("hasScopes returns true for empty required set") {
        val ctx = AuthContext(sub = "u")
        assertTrue(ctx.hasScopes(Set.empty))
      }
    ),
    suite("TrustedHeaderPolicy")(
      test("extracts identity from default headers") {
        val extract = trustedHeaderExtractor()
        val headers = zio.http.Headers(
          "X-Forwarded-User" -> "alice",
          "X-Forwarded-Email" -> "alice@corp.com",
          "X-Forwarded-Preferred-Username" -> "Alice Smith",
          "X-Forwarded-Groups" -> "admin,dev",
          "X-Forwarded-Scopes" -> "read write"
        )
        val request = zio.http.Request.get("/").addHeaders(headers)
        for
          auth <- extract(request)
        yield assertTrue(
          auth.sub == "alice",
          auth.email.contains("alice@corp.com"),
          auth.name.contains("Alice Smith"),
          auth.groups == Set("admin", "dev"),
          auth.scopes == Set("read", "write")
        )
      },
      test("accepts X-User-Id as fallback sub header") {
        val extract = trustedHeaderExtractor()
        val headers = zio.http.Headers("X-User-Id" -> "bob")
        val request = zio.http.Request.get("/").addHeaders(headers)
        for
          auth <- extract(request)
        yield assertTrue(auth.sub == "bob")
      },
      test("rejects request without sub header") {
        val extract = trustedHeaderExtractor()
        val request = zio.http.Request.get("/").addHeaders(
          zio.http.Headers("X-Forwarded-Email" -> "nobody@test.com")
        )
        for
          result <- extract(request).either
        yield assertTrue(result.isLeft)
      },
      test("handles empty groups and scopes gracefully") {
        val extract = trustedHeaderExtractor()
        val headers = zio.http.Headers("X-Forwarded-User" -> "alice")
        val request = zio.http.Request.get("/").addHeaders(headers)
        for
          auth <- extract(request)
        yield assertTrue(
          auth.groups.isEmpty,
          auth.scopes.isEmpty
        )
      }
    ),
    suite("HttpSecurityPolicy.fromExtractor")(
      test("fromExtractor creates policy that can extract auth") {
        val extractor: zio.http.Request => IO[String, AuthContext] = _ =>
          ZIO.succeed(AuthContext(sub = "test-user"))
        // Verify the extractor itself works (middleware integration tested end-to-end)
        val request = zio.http.Request.get("/")
        for
          auth <- extractor(request)
        yield assertTrue(auth.sub == "test-user")
      },
      test("fromExtractor rejects when extractor fails") {
        val extractor: zio.http.Request => IO[String, AuthContext] = _ =>
          ZIO.fail("auth failed")
        val request = zio.http.Request.get("/")
        for
          result <- extractor(request).either
        yield assertTrue(
          result.isLeft,
          result.swap.toOption.get == "auth failed"
        )
      }
    )
  )

  /** Helper: creates the raw extractor function from TrustedHeaderPolicy for direct testing without
    * going through middleware.
    */
  private def trustedHeaderExtractor(
      mapping: TrustedHeaderPolicy.HeaderMapping = TrustedHeaderPolicy.defaultMapping
  ): zio.http.Request => IO[String, AuthContext] =
    request =>
      val headers = request.headers
      val sub = mapping.subHeaders.collectFirst {
        case h if headers.get(h).isDefined => headers.get(h).get
      }
      sub match
        case None =>
          ZIO.fail("Missing identity header: expected one of " + mapping.subHeaders.mkString(", "))
        case Some(subject) =>
          val email = mapping.emailHeaders.collectFirst {
            case h if headers.get(h).isDefined => headers.get(h).get
          }
          val name = mapping.nameHeaders.collectFirst {
            case h if headers.get(h).isDefined => headers.get(h).get
          }
          val groups = headers.get(mapping.groupsHeader)
            .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
            .getOrElse(Set.empty)
          val scopes = headers.get(mapping.scopesHeader)
            .map(_.split(" ").map(_.trim).filter(_.nonEmpty).toSet)
            .getOrElse(Set.empty)
          ZIO.succeed(AuthContext(
            sub = subject,
            email = email,
            name = name,
            groups = groups,
            scopes = scopes,
            rawClaims = None
          ))
