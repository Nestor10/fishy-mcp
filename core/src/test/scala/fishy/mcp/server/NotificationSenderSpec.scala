package fishy.mcp.server

import fishy.mcp.adapters.storage.{InMemoryBackend, InMemorySubscriptionRegistry}
import fishy.mcp.application.ports.{MessageRouter, SessionStore}
import fishy.mcp.application.usecase.NotificationSender
import zio.*
import zio.test.*
import zio.test.Assertion.*

object NotificationSenderSpec extends ZIOSpecDefault:

  def spec = suite("NotificationSender")(
    test("toolsListChanged publishes to sessions with subscribers") {
      ZIO.scoped {
        for
          sessionId <- SessionStore.create()
          stream <- MessageRouter.subscribe(sessionId)
          fiber <- stream.take(1).runCollect.fork
          _ <- NotificationSender.toolsListChanged
          result <- fiber.join
          msg = result.head
        yield assertTrue(
          msg.contains("notifications/tools/list_changed"),
          msg.contains("2.0")
        )
      }
    },
    test("resourcesListChanged sends correct method") {
      ZIO.scoped {
        for
          sessionId <- SessionStore.create()
          stream <- MessageRouter.subscribe(sessionId)
          fiber <- stream.take(1).runCollect.fork
          _ <- NotificationSender.resourcesListChanged
          result <- fiber.join
          msg = result.head
        yield assertTrue(
          msg.contains("notifications/resources/list_changed")
        )
      }
    },
    test("promptsListChanged sends correct method") {
      ZIO.scoped {
        for
          sessionId <- SessionStore.create()
          stream <- MessageRouter.subscribe(sessionId)
          fiber <- stream.take(1).runCollect.fork
          _ <- NotificationSender.promptsListChanged
          result <- fiber.join
          msg = result.head
        yield assertTrue(
          msg.contains("notifications/prompts/list_changed")
        )
      }
    },
    test("sendToSession delivers to specific session only") {
      ZIO.scoped {
        for
          sid1 <- SessionStore.create()
          sid2 <- SessionStore.create()
          stream1 <- MessageRouter.subscribe(sid1)
          stream2 <- MessageRouter.subscribe(sid2)
          delivered <- NotificationSender.sendToSession(sid1, "test/notification")
          // Give a moment for message delivery
          result <- stream1.take(1).runCollect.timeout(1.second)
        yield assertTrue(delivered, result.isDefined)
      }
    },
    test("broadcast reaches all sessions with subscribers") {
      ZIO.scoped {
        for
          sid1 <- SessionStore.create()
          sid2 <- SessionStore.create()
          stream1 <- MessageRouter.subscribe(sid1)
          stream2 <- MessageRouter.subscribe(sid2)
          fiber1 <- stream1.take(1).runCollect.fork
          fiber2 <- stream2.take(1).runCollect.fork
          _ <- NotificationSender.broadcast("test/broadcast")
          r1 <- fiber1.join
          r2 <- fiber2.join
        yield assertTrue(r1.size == 1, r2.size == 1)
      }
    }
  ).provide(
    InMemoryBackend.layer,
    InMemorySubscriptionRegistry.layer,
    NotificationSender.layer
  )
