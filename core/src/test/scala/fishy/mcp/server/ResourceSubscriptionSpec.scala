package fishy.mcp.server

import fishy.mcp.adapters.storage.{InMemoryBackend, InMemorySubscriptionRegistry}
import fishy.mcp.application.ports.{MessageRouter, SessionStore, SubscriptionRegistry}
import fishy.mcp.application.usecase.{McpDispatcher, NotificationSender}
import fishy.mcp.domain.model.*
import fishy.mcp.dsl.Resource
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object ResourceSubscriptionSpec extends ZIOSpecDefault:

  val serverInfo = ServerInfo("test-server", "1.0.0")

  val statusResource: fishy.mcp.domain.model.Resource[Any] =
    Resource.textEffect[Any]("server:///status", "status", "Server status")(
      ZIO.succeed("running")
    )

  def makeDispatcher(
      resources: List[fishy.mcp.domain.model.Resource[Any]] = Nil
  ): UIO[(McpDispatcher, SessionStore)] =
    val capabilities = ServerCapabilities(
      resources = if resources.nonEmpty then
        Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true)))
      else None
    )
    TestLayers.makeDispatcher(
      resources = resources,
      serverInfo = serverInfo,
      capabilities = capabilities
    )

  def request(
      method: String,
      params: Option[Json] = None,
      id: RequestId = RequestId.NumberId(1)
  ): Request =
    Request("2.0", method, params, Some(id))

  def spec = suite("Resource Subscriptions")(
    suite("resources/subscribe")(
      test("returns success for valid subscribe") {
        for
          (dispatcher, store) <- makeDispatcher(resources = List(statusResource))
          sid <- store.create()
          _ <- store.markInitialized(sid)
          result <- dispatcher.dispatch(
            request("resources/subscribe", Some(Json.Obj("uri" -> Json.Str("server:///status")))),
            Some(sid)
          ).flatMap(_.toOption)
        yield assertTrue(result.get.isRight)
      },
      test("returns error when params missing") {
        for
          (dispatcher, store) <- makeDispatcher(resources = List(statusResource))
          sid <- store.create()
          _ <- store.markInitialized(sid)
          result <- dispatcher.dispatch(
            request("resources/subscribe", None),
            Some(sid)
          ).flatMap(_.toOption)
        yield assertTrue(result.get.isLeft)
      },
      test("returns error when no session") {
        for
          (dispatcher, _) <- makeDispatcher(resources = List(statusResource))
          result <- dispatcher.dispatch(
            request("resources/subscribe", Some(Json.Obj("uri" -> Json.Str("server:///status")))),
            None
          ).flatMap(_.toOption)
        yield assertTrue(
          result.get.isLeft,
          result.get.left.toOption.get.error.message.contains("session")
        )
      }
    ),
    suite("resources/unsubscribe")(
      test("returns success for valid unsubscribe") {
        for
          (dispatcher, store) <- makeDispatcher(resources = List(statusResource))
          sid <- store.create()
          _ <- store.markInitialized(sid)
          _ <- dispatcher.dispatch(
            request("resources/subscribe", Some(Json.Obj("uri" -> Json.Str("server:///status")))),
            Some(sid)
          )
          result <- dispatcher.dispatch(
            request(
              "resources/unsubscribe",
              Some(Json.Obj("uri" -> Json.Str("server:///status"))),
              RequestId.NumberId(2)
            ),
            Some(sid)
          ).flatMap(_.toOption)
        yield assertTrue(result.get.isRight)
      }
    ),
    suite("capabilities")(
      test("advertises subscribe capability") {
        for
          (dispatcher, _) <- makeDispatcher(resources = List(statusResource))
          result <- dispatcher.dispatch(
            request(
              "initialize",
              Some(
                InitializeParams("2024-11-05", ClientCapabilities(), ClientInfo("test", "1.0.0"))
                  .toJsonAST.toOption.get
              )
            ),
            None
          ).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("\"subscribe\":true"),
            json.contains("\"listChanged\":true")
          )
      }
    ),
    suite("InMemorySubscriptionRegistry")(
      test("subscribe and query subscribers") {
        ZIO.scoped {
          for
            registry <- InMemorySubscriptionRegistry.layer.build.map(_.get[SubscriptionRegistry])
            _ <- registry.subscribe("session-1", "file:///a.md")
            _ <- registry.subscribe("session-2", "file:///a.md")
            _ <- registry.subscribe("session-1", "file:///b.md")
            subsA <- registry.subscribers("file:///a.md")
            subsB <- registry.subscribers("file:///b.md")
            subsC <- registry.subscribers("file:///c.md")
          yield assertTrue(
            subsA == Set("session-1", "session-2"),
            subsB == Set("session-1"),
            subsC.isEmpty
          )
        }
      },
      test("unsubscribe removes subscription") {
        ZIO.scoped {
          for
            registry <- InMemorySubscriptionRegistry.layer.build.map(_.get[SubscriptionRegistry])
            _ <- registry.subscribe("session-1", "file:///a.md")
            _ <- registry.subscribe("session-1", "file:///b.md")
            _ <- registry.unsubscribe("session-1", "file:///a.md")
            subsA <- registry.subscribers("file:///a.md")
            subsB <- registry.subscribers("file:///b.md")
          yield assertTrue(
            subsA.isEmpty,
            subsB == Set("session-1")
          )
        }
      },
      test("removeSession cleans up all subscriptions") {
        ZIO.scoped {
          for
            registry <- InMemorySubscriptionRegistry.layer.build.map(_.get[SubscriptionRegistry])
            _ <- registry.subscribe("session-1", "file:///a.md")
            _ <- registry.subscribe("session-1", "file:///b.md")
            _ <- registry.subscribe("session-2", "file:///a.md")
            _ <- registry.removeSession("session-1")
            subsA <- registry.subscribers("file:///a.md")
            subsB <- registry.subscribers("file:///b.md")
          yield assertTrue(
            subsA == Set("session-2"),
            subsB.isEmpty
          )
        }
      }
    ),
    suite("resourceUpdated notification")(
      test("sends notification only to subscribed sessions") {
        ZIO.scoped {
          for
            sid1 <- SessionStore.create()
            sid2 <- SessionStore.create()
            _ <- SubscriptionRegistry.subscribe(sid1, "server:///status")
            stream1 <- MessageRouter.subscribe(sid1)
            stream2 <- MessageRouter.subscribe(sid2)
            fiber1 <- stream1.take(1).runCollect.timeout(2.seconds).fork
            fiber2 <- stream2.take(1).runCollect.timeout(2.seconds).fork
            _ <- NotificationSender.resourceUpdated("server:///status")
            r1 <- fiber1.join
            r2 <- fiber2.join
          yield assertTrue(
            r1.isDefined && r1.get.size == 1,
            r1.get.head.contains("notifications/resources/updated"),
            r1.get.head.contains("server:///status"),
            r2.forall(_.isEmpty)
          )
        }
      } @@ TestAspect.withLiveClock
    ).provide(
      InMemoryBackend.layer,
      InMemorySubscriptionRegistry.layer,
      NotificationSender.layer
    )
  )
