package fishy.mcp.server

import fishy.mcp.application.ports.*
import fishy.mcp.application.usecase.*
import fishy.mcp.bootstrap.TracingLayers
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.mcp.*
import fishy.mcp.adapters.storage.{InMemoryBackend, InMemorySubscriptionRegistry}
import fishy.mcp.dsl.Tool as DslTool
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.stream.ZStream
import zio.test.*

object SessionHooksSpec extends ZIOSpecDefault:

  // -- helpers ----------------------------------------------------------------

  final case class EchoInput(message: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  val echoTool: Tool[Any] =
    DslTool("echo").description("Echoes input").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Echo: ${in.message}"))
    }

  val serverInfo = ServerInfo("test-server", "1.0.0")
  val capabilities = ServerCapabilities(tools = Some(ToolsCapability(listChanged = Some(true))))

  // -- specs ------------------------------------------------------------------

  def spec = suite("SessionHooks")(
    suite("noOp")(
      test("onConnect returns empty stream") {
        ZIO.scoped {
          for
            stream <- SessionHooks.noOp.build.flatMap { env =>
              env.get[SessionHooks].onConnect("s1", None)
            }
            items <- stream.runCollect
          yield assertTrue(items.isEmpty)
        }
      },
      test("onDisconnect is a no-op") {
        ZIO.scoped {
          SessionHooks.noOp.build.flatMap { env =>
            env.get[SessionHooks].onDisconnect("s1")
          }.as(assertTrue(true))
        }
      }
    ),
    suite("custom hooks")(
      test("onConnect stream is merged into SSE output") {
        // Verify that messages from the app stream appear alongside MessageRouter messages.
        ZIO.scoped {
          for
            // Set up a custom SessionHooks that pushes one notification
            appNotification =
              """{"jsonrpc":"2.0","method":"notifications/custom","params":{"hello":true}}"""
            hooks = new SessionHooks:
              def onConnect(sessionId: String, auth: Option[AuthContext])
                  : ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
                ZIO.succeed(ZStream(appNotification))
              def onDisconnect(sessionId: String): UIO[Unit] = ZIO.unit

            // Build InMemory infra so we get real MessageRouter + EventReplay
            env <- (InMemoryBackend.layer ++ ZLayer.succeed(hooks)).build
            router = env.get[MessageRouter]
            eventReplay = env.get[EventReplay]
            sessionHooks = env.get[SessionHooks]

            // Subscribe to the merged SSE stream (simulating what SseHandler does)
            appStream <- sessionHooks.onConnect("s1", None)
            liveStream <- router.subscribe("s1")
            combined = liveStream merge appStream

            // Publish one message through MessageRouter
            routerMsg = """{"jsonrpc":"2.0","result":"from-router","id":1}"""
            fiber <- combined.take(2).runCollect.fork
            _ <- ZIO.yieldNow
            _ <- router.publish("s1", routerMsg)
            messages <- fiber.join
          yield assertTrue(
            messages.size == 2,
            messages.contains(appNotification),
            messages.contains(routerMsg)
          )
        }
      },
      test("onDisconnect fires when scope closes") {
        for
          disconnected <- Ref.make(false)
          hooks = new SessionHooks:
            def onConnect(
                sessionId: String,
                auth: Option[AuthContext]
            ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
              ZIO.succeed(ZStream.empty)
            def onDisconnect(sessionId: String): UIO[Unit] =
              disconnected.set(true)
          _ <- ZIO.scoped {
            ZIO.addFinalizer(hooks.onDisconnect("s1")) *>
              ZIO.unit
          }
          wasDisconnected <- disconnected.get
        yield assertTrue(wasDisconnected)
      },
      test("onConnect receives auth context") {
        val auth = AuthContext(sub = "user1", email = Some("u@test.com"))
        for
          receivedAuth <- Ref.make(Option.empty[AuthContext])
          hooks = new SessionHooks:
            def onConnect(
                sessionId: String,
                auth: Option[AuthContext]
            ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
              receivedAuth.set(auth).as(ZStream.empty)
            def onDisconnect(sessionId: String): UIO[Unit] = ZIO.unit
          _ <- ZIO.scoped(hooks.onConnect("s1", Some(auth)) *> ZIO.unit)
          captured <- receivedAuth.get
        yield assertTrue(captured == Some(auth))
      },
      test("onConnect receives sessionId") {
        for
          receivedId <- Ref.make("")
          hooks = new SessionHooks:
            def onConnect(
                sessionId: String,
                auth: Option[AuthContext]
            ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
              receivedId.set(sessionId).as(ZStream.empty)
            def onDisconnect(sessionId: String): UIO[Unit] = ZIO.unit
          _ <- ZIO.scoped(hooks.onConnect("session-42", None) *> ZIO.unit)
          captured <- receivedId.get
        yield assertTrue(captured == "session-42")
      }
    ),
    suite("built-in hooks")(
      test("listChangedOnConnect emits one list_changed per advertised primitive") {
        val caps = ServerCapabilities(
          tools = Some(ToolsCapability(listChanged = Some(true))),
          resources = Some(ResourcesCapability(listChanged = Some(true)))
        )
        ZIO.scoped {
          for
            stream <- SessionHooks.listChangedOnConnect(caps).onConnect("s1", None)
            items  <- stream.runCollect
          yield assertTrue(
            items.size == 2,
            items.exists(_.contains("notifications/tools/list_changed")),
            items.exists(_.contains("notifications/resources/list_changed")),
            !items.exists(_.contains("prompts"))
          )
        }
      },
      test("listChangedOnConnect with no capabilities emits nothing") {
        ZIO.scoped {
          for
            stream <- SessionHooks.listChangedOnConnect(ServerCapabilities()).onConnect("s1", None)
            items  <- stream.runCollect
          yield assertTrue(items.isEmpty)
        }
      },
      test("combine concatenates onConnect first-then-second and runs both onDisconnect") {
        for
          disconnects <- Ref.make(List.empty[String])
          first = new SessionHooks:
            def onConnect(sessionId: String, auth: Option[AuthContext])
                : ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
              ZIO.succeed(ZStream("a1", "a2"))
            def onDisconnect(sessionId: String): UIO[Unit] = disconnects.update(_ :+ "first")
          second = new SessionHooks:
            def onConnect(sessionId: String, auth: Option[AuthContext])
                : ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
              ZIO.succeed(ZStream("b1"))
            def onDisconnect(sessionId: String): UIO[Unit] = disconnects.update(_ :+ "second")
          combined = SessionHooks.combine(first, second)
          items <- ZIO.scoped(combined.onConnect("s1", None).flatMap(_.runCollect))
          _     <- combined.onDisconnect("s1")
          order <- disconnects.get
        yield assertTrue(
          items.toList == List("a1", "a2", "b1"),
          order == List("first", "second")
        )
      }
    ),
    suite("tool call event tap")(
      test("emits notifications/message to MessageRouter after tool call") {
        ZIO.scoped {
          for
            // Build layers with tool call events enabled
            env <- ZLayer.make[ToolExecutor & ToolRegistry & SessionStore & MessageRouter](
              ToolRegistry.layer(List(echoTool)),
              ToolExecutor.layerWith(publishToolEvents = true),
              ClientRequester.layer,
              NotificationSender.layer,
              InMemorySubscriptionRegistry.layer,
              InMemoryBackend.layer,
              TracingLayers.noop
            ).build
            executor = env.get[ToolExecutor]
            router = env.get[MessageRouter]
            sessions = env.get[SessionStore]

            sessionId <- sessions.create()
            stream <- router.subscribe(sessionId)
            fiber <- stream.take(1).runCollect.fork
            _ <- ZIO.yieldNow

            // Call a tool with a sessionId
            params = Json.Obj(
              "name" -> Json.Str("echo"),
              "arguments" -> Json.Obj("message" -> Json.Str("test"))
            )
            _ <- executor.call(
              fishy.mcp.domain.model.RequestId.NumberId(1),
              Some(params),
              Some(sessionId)
            )
            messages <- fiber.join

            // The emitted message should be a notifications/message
            parsed = messages.headOption.flatMap(_.fromJson[Json].toOption)
            method = parsed.flatMap(_.asObject).flatMap(_.get("method")).flatMap(_.asString)
          yield assertTrue(
            messages.size == 1,
            method == Some("notifications/message")
          )
        }
      },
      test("does not emit when publishToolEvents is false") {
        ZIO.scoped {
          for
            env <- ZLayer.make[ToolExecutor & ToolRegistry & SessionStore & MessageRouter](
              ToolRegistry.layer(List(echoTool)),
              ToolExecutor.layer,
              ClientRequester.layer,
              NotificationSender.layer,
              InMemorySubscriptionRegistry.layer,
              InMemoryBackend.layer,
              TracingLayers.noop
            ).build
            executor = env.get[ToolExecutor]
            router = env.get[MessageRouter]
            sessions = env.get[SessionStore]

            sessionId <- sessions.create()
            stream <- router.subscribe(sessionId)
            fiber <- stream.take(1).timeout(100.millis).runCollect.fork
            _ <- ZIO.yieldNow

            params = Json.Obj(
              "name" -> Json.Str("echo"),
              "arguments" -> Json.Obj("message" -> Json.Str("test"))
            )
            _ <- executor.call(
              fishy.mcp.domain.model.RequestId.NumberId(1),
              Some(params),
              Some(sessionId)
            )
            _ <- TestClock.adjust(200.millis)
            messages <- fiber.join
          yield assertTrue(messages.isEmpty)
        }
      },
      test("does not emit when no sessionId") {
        ZIO.scoped {
          for
            env <- ZLayer.make[ToolExecutor & ToolRegistry & MessageRouter](
              ToolRegistry.layer(List(echoTool)),
              ToolExecutor.layerWith(publishToolEvents = true),
              ClientRequester.layer,
              NotificationSender.layer,
              InMemorySubscriptionRegistry.layer,
              InMemoryBackend.layer,
              TracingLayers.noop
            ).build
            executor = env.get[ToolExecutor]

            // Call without sessionId -- no event should be published (no crash)
            params = Json.Obj(
              "name" -> Json.Str("echo"),
              "arguments" -> Json.Obj("message" -> Json.Str("test"))
            )
            result <- executor.call(
              fishy.mcp.domain.model.RequestId.NumberId(1),
              Some(params),
              None
            )
          yield assertTrue(result.isInstanceOf[DispatchResult.Single])
        }
      }
    )
  )
