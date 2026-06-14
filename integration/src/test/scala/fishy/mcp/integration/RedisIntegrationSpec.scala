package fishy.mcp.integration

import fishy.mcp.adapters.inbound.http.{HttpExtraRoutes, HttpSecurityPolicy, HttpTransport}
import fishy.mcp.domain.model.mcp.*
import fishy.mcp.adapters.storage.{RedisBackend, RedisSubscriptionRegistry}
import fishy.mcp.application.ports.*
import fishy.mcp.application.usecase.*
import fishy.mcp.bootstrap.TracingLayers
import fishy.mcp.domain.model.PromptMessage
import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, ToolContext}
import org.testcontainers.containers.GenericContainer as JGenericContainer
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisSubscription}
import zio.schema.Schema
import zio.schema.derived
import zio.test.*

/** Integration tests that exercise the full MCP lifecycle against a real Redis instance via
  * Testcontainers.
  *
  * Validates that all three Redis-backed adapters (SessionStore, MessageRouter, EventReplay) work
  * correctly with the HTTP transport.
  */
object RedisIntegrationSpec extends ZIOSpecDefault:

  // -- Testcontainer ----------------------------------------------------------

  class RedisContainer
      extends JGenericContainer[RedisContainer](DockerImageName.parse("redis:7-alpine")):
    addExposedPort(6379)

  val redisContainerLayer: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = new RedisContainer()
          c.start()
          c
        }
      )(c => ZIO.attemptBlocking(c.stop()).orDie)
        .map(c => RedisConfig(c.getHost, c.getMappedPort(6379)))
    }

  // -- Minimal test server ----------------------------------------------------

  final case class EchoInput(message: String) derives Schema
  final case class AddInput(a: Int, b: Int) derives Schema

  val echo = Tool("echo")
    .description("Echoes the input message back")
    .handle { (input: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"You said: ${input.message}"))
    }

  val add = Tool("add")
    .description("Adds two numbers together")
    .handle { (input: AddInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"${input.a} + ${input.b} = ${input.a + input.b}"))
    }

  val readme = Resource.text(
    "file:///readme.md",
    "readme",
    "Project README",
    "text/markdown"
  )("# Test README")

  val summarize = Prompt.static("summarize", "Summarize text")(
    List(PromptMessage("user", "Please provide a concise summary."))
  )

  // -- Shared layer -----------------------------------------------------------

  val serverInfo: ServerInfo = ServerInfo("redis-test", "0.1.0")

  val capabilities: ServerCapabilities = ServerCapabilities(
    tools = Some(ToolsCapability(listChanged = Some(true))),
    resources = Some(ResourcesCapability(listChanged = Some(true))),
    prompts = Some(PromptsCapability(listChanged = Some(true)))
  )

  // Tracing exposed in output type because the observability middleware wraps
  // routes in OTel spans — `transport.routes.runZIO(...)` requires it at call
  // time. TracingLayers.noop satisfies it for tests without a real exporter.
  val fullLayer: ZLayer[Any, Throwable, HttpTransport & SessionStore & zio.telemetry.opentelemetry.tracing.Tracing] =
    ZLayer.make[HttpTransport & SessionStore & zio.telemetry.opentelemetry.tracing.Tracing](
      redisContainerLayer,
      SessionHooks.noOp,
      RedisSubscriptionRegistry.layer,
      TracingLayers.noop,
      ZLayer.succeed(CodecSupplier.utf8),
      Redis.singleNode,
      RedisSubscription.singleNode,
      RedisBackend.layer(),
      ToolRegistry.layer(List(echo, add)),
      ResourceRegistry.layer(List(readme)),
      PromptRegistry.layer(List(summarize)),
      NotificationSender.layer,
      ClientRequester.layer,
      ToolExecutor.layer,
      ResourceExecutor.layer,
      PromptExecutor.layer,
      McpDispatcher.layer(serverInfo, capabilities),
      HttpSecurityPolicy.allowAll,
      HttpExtraRoutes.empty,
      HttpTransport.layer
    )

  // -- Helpers ----------------------------------------------------------------

  private def postMcp(
      body: String,
      sessionId: Option[String] = None
  ): ZIO[HttpTransport & zio.telemetry.opentelemetry.tracing.Tracing, Throwable, Response] =
    for
      transport <- ZIO.service[HttpTransport]
      response <- ZIO.scoped {
        val base = Request
          .post(URL.root / "mcp", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
        val req = sessionId.fold(base)(id => base.addHeader("Mcp-Session-Id", id))
        transport.routes.runZIO(req)
      }
    yield response

  private def bodyString(response: Response): ZIO[Any, Throwable, String] =
    response.body.asString

  private def jsonRpcRequest(id: Long, method: String, params: Option[Json] = None): String =
    val paramsField = params.map(p => s""","params":${p.toJson}""").getOrElse("")
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method"$paramsField}"""

  // -- Tests ------------------------------------------------------------------

  def spec = {
    val redisIntegration = suite("Redis Integration")(
      test("full MCP lifecycle with Redis-backed storage") {
        for
          // 1) Initialize -- creates session in Redis
          initResp <- postMcp(jsonRpcRequest(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj(
                "name" -> Json.Str("integration-test"),
                "version" -> Json.Str("1.0")
              )
            ))
          ))
          sessionId = initResp.headers.get("Mcp-Session-Id").get
          initBody <- bodyString(initResp)

          // 1b) notifications/initialized -- required by MCP protocol
          _ <- postMcp(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            Some(sessionId)
          )

          // 2) tools/list
          toolsResp <- postMcp(jsonRpcRequest(2, "tools/list"), Some(sessionId))
          toolsBody <- bodyString(toolsResp)

          // 3) tools/call echo
          echoResp <- postMcp(
            jsonRpcRequest(
              3,
              "tools/call",
              Some(Json.Obj(
                "name" -> Json.Str("echo"),
                "arguments" -> Json.Obj("message" -> Json.Str("hello from Redis!"))
              ))
            ),
            Some(sessionId)
          )
          echoBody <- bodyString(echoResp)

          // 4) tools/call add
          addResp <- postMcp(
            jsonRpcRequest(
              4,
              "tools/call",
              Some(Json.Obj(
                "name" -> Json.Str("add"),
                "arguments" -> Json.Obj("a" -> Json.Num(17), "b" -> Json.Num(25))
              ))
            ),
            Some(sessionId)
          )
          addBody <- bodyString(addResp)

          // 5) resources/list
          resResp <- postMcp(jsonRpcRequest(5, "resources/list"), Some(sessionId))
          resBody <- bodyString(resResp)

          // 6) prompts/list
          promptsResp <- postMcp(jsonRpcRequest(6, "prompts/list"), Some(sessionId))
          promptsBody <- bodyString(promptsResp)
        yield assertTrue(initResp.status == Status.Ok) &&
          assertTrue(sessionId.nonEmpty) &&
          assertTrue(initBody.contains("redis-test")) &&
          assertTrue(initBody.contains("capabilities")) &&
          assertTrue(toolsBody.contains("echo")) &&
          assertTrue(toolsBody.contains("add")) &&
          assertTrue(echoBody.contains("You said: hello from Redis!")) &&
          assertTrue(addBody.contains("17 + 25 = 42")) &&
          assertTrue(resBody.contains("readme")) &&
          assertTrue(promptsBody.contains("summarize"))
      },
      test("session persists in Redis SessionStore") {
        for
          // Initialize to create a session
          initResp <- postMcp(jsonRpcRequest(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj(
                "name" -> Json.Str("session-test"),
                "version" -> Json.Str("1.0")
              )
            ))
          ))
          sessionId = initResp.headers.get("Mcp-Session-Id").get
          _ <- postMcp(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            Some(sessionId)
          )

          // Verify session exists in the Redis-backed store
          store <- ZIO.service[SessionStore]
          exists1 <- store.exists(sessionId)

          // Use the session for another request
          _ <- postMcp(jsonRpcRequest(2, "tools/list"), Some(sessionId))

          // Session should still exist
          exists2 <- store.exists(sessionId)

          // Check allSessionIds includes our session
          allIds <- store.allSessionIds
        yield assertTrue(exists1) &&
          assertTrue(exists2) &&
          assertTrue(allIds.contains(sessionId))
      },
      test("independent sessions are isolated") {
        for
          // Create two separate sessions
          resp1 <- postMcp(jsonRpcRequest(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("client-a"), "version" -> Json.Str("1.0"))
            ))
          ))
          session1 = resp1.headers.get("Mcp-Session-Id").get
          _ <- postMcp(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            Some(session1)
          )

          resp2 <- postMcp(jsonRpcRequest(
            1,
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2025-03-26"),
              "capabilities" -> Json.Obj(),
              "clientInfo" -> Json.Obj("name" -> Json.Str("client-b"), "version" -> Json.Str("1.0"))
            ))
          ))
          session2 = resp2.headers.get("Mcp-Session-Id").get
          _ <- postMcp(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            Some(session2)
          )

          // Both should work independently
          echo1 <- postMcp(
            jsonRpcRequest(
              2,
              "tools/call",
              Some(Json.Obj(
                "name" -> Json.Str("echo"),
                "arguments" -> Json.Obj("message" -> Json.Str("from session 1"))
              ))
            ),
            Some(session1)
          )
          echo1Body <- bodyString(echo1)

          echo2 <- postMcp(
            jsonRpcRequest(
              2,
              "tools/call",
              Some(Json.Obj(
                "name" -> Json.Str("echo"),
                "arguments" -> Json.Obj("message" -> Json.Str("from session 2"))
              ))
            ),
            Some(session2)
          )
          echo2Body <- bodyString(echo2)

          // Verify sessions are distinct
          store <- ZIO.service[SessionStore]
          allIds <- store.allSessionIds
        yield assertTrue(session1 != session2) &&
          assertTrue(echo1Body.contains("from session 1")) &&
          assertTrue(echo2Body.contains("from session 2")) &&
          assertTrue(allIds.contains(session1)) &&
          assertTrue(allIds.contains(session2))
      }
    ).provideShared(fullLayer) @@
      TestAspect.sequential @@
      TestAspect.withLiveClock @@
      TestAspect.timeout(120.seconds)

    val horizontalScaling = suite("horizontal scaling")(
      test("session initialized on server A can be used by server B") {
        // Build a layer that gives us TWO independent HttpTransport apps
        // sharing a single Redis instance. This simulates two MCP server
        // processes in a horizontally-scaled deployment.
        val appLayer: ZLayer[RedisConfig, Throwable, HttpTransport & SessionStore & zio.telemetry.opentelemetry.tracing.Tracing] =
          ZLayer.makeSome[RedisConfig, HttpTransport & SessionStore & zio.telemetry.opentelemetry.tracing.Tracing](
            SessionHooks.noOp,
            RedisSubscriptionRegistry.layer,
            TracingLayers.noop,
            ZLayer.succeed(CodecSupplier.utf8),
            Redis.singleNode,
            RedisSubscription.singleNode,
            RedisBackend.layer(),
            ToolRegistry.layer(List(echo, add)),
            ResourceRegistry.layer(List(readme)),
            PromptRegistry.layer(List(summarize)),
            NotificationSender.layer,
            ClientRequester.layer,
            ToolExecutor.layer,
            ResourceExecutor.layer,
            PromptExecutor.layer,
            McpDispatcher.layer(serverInfo, capabilities),
            HttpSecurityPolicy.allowAll,
            HttpExtraRoutes.empty,
            HttpTransport.layer
          )

        def postTo(
            transport: HttpTransport,
            body: String,
            sessionId: Option[String] = None
        ): ZIO[zio.telemetry.opentelemetry.tracing.Tracing, Throwable, Response] =
          ZIO.scoped {
            val base = Request
              .post(URL.root / "mcp", Body.fromString(body))
              .addHeader(Header.ContentType(MediaType.application.json))
            val req = sessionId.fold(base)(id => base.addHeader("Mcp-Session-Id", id))
            transport.routes.runZIO(req)
          }

        ZIO.scoped {
          for
            envA <- appLayer.build
            envB <- appLayer.build
            transportA = envA.get[HttpTransport]
            transportB = envB.get[HttpTransport]

            // Initialize session via server A
            initResp <- postTo(
              transportA,
              jsonRpcRequest(
                1,
                "initialize",
                Some(Json.Obj(
                  "protocolVersion" -> Json.Str("2025-03-26"),
                  "capabilities" -> Json.Obj(),
                  "clientInfo" -> Json.Obj(
                    "name" -> Json.Str("horizontal-test"),
                    "version" -> Json.Str("1.0")
                  )
                ))
              )
            )
            sessionId = initResp.headers.get("Mcp-Session-Id").get
            initBody <- bodyString(initResp)

            // Send notifications/initialized via server A
            _ <- postTo(
              transportA,
              """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
              Some(sessionId)
            )

            // Verify server A works normally
            toolsRespA <- postTo(
              transportA,
              jsonRpcRequest(2, "tools/list"),
              Some(sessionId)
            )
            toolsBodyA <- bodyString(toolsRespA)

            // Now use server B with the SAME sessionId -- this is the key assertion
            toolsRespB <- postTo(
              transportB,
              jsonRpcRequest(3, "tools/list"),
              Some(sessionId)
            )
            toolsBodyB <- bodyString(toolsRespB)

            // tools/call via server B
            echoRespB <- postTo(
              transportB,
              jsonRpcRequest(
                4,
                "tools/call",
                Some(Json.Obj(
                  "name" -> Json.Str("echo"),
                  "arguments" -> Json.Obj("message" -> Json.Str("hello from server B!"))
                ))
              ),
              Some(sessionId)
            )
            echoBodyB <- bodyString(echoRespB)
          yield assertTrue(initResp.status == Status.Ok) &&
            assertTrue(initBody.contains("redis-test")) &&
            assertTrue(toolsBodyA.contains("echo")) &&
            assertTrue(toolsRespB.status == Status.Ok) &&
            assertTrue(toolsBodyB.contains("echo")) &&
            assertTrue(toolsBodyB.contains("add")) &&
            assertTrue(echoBodyB.contains("hello from server B!"))
        }
      }
    ).provideShared(redisContainerLayer ++ TracingLayers.noop) @@
      TestAspect.sequential @@
      TestAspect.withLiveClock @@
      TestAspect.timeout(120.seconds)

    redisIntegration + horizontalScaling
  }
