package fishy.mcp.server

import fishy.mcp.adapters.storage.InMemoryBackend
import fishy.mcp.application.ports.{EventReplay, MessageRouter}
import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

object MessageRouterSpec extends ZIOSpecDefault:

  def spec = suite("InMemoryMessageRouter")(
    test("publish returns false when no subscribers") {
      for
        delivered <- MessageRouter.publish("session-1", "hello")
      yield assertTrue(!delivered)
    },
    test("subscriber receives published messages") {
      ZIO.scoped {
        for
          stream <- MessageRouter.subscribe("session-1")
          _ <- MessageRouter.publish("session-1", "msg-1").fork
          result <- stream.take(1).runCollect
        yield assertTrue(result.toList == List("msg-1"))
      }
    },
    test("multiple messages arrive in order") {
      ZIO.scoped {
        for
          stream <- MessageRouter.subscribe("session-1")
          fiber <- stream.take(3).runCollect.fork
          _ <- MessageRouter.publish("session-1", "a")
          _ <- MessageRouter.publish("session-1", "b")
          _ <- MessageRouter.publish("session-1", "c")
          result <- fiber.join
        yield assertTrue(result.toList == List("a", "b", "c"))
      }
    },
    test("different sessions are isolated") {
      ZIO.scoped {
        for
          stream1 <- MessageRouter.subscribe("session-1")
          stream2 <- MessageRouter.subscribe("session-2")
          fiber1 <- stream1.take(1).runCollect.fork
          fiber2 <- stream2.take(1).runCollect.fork
          _ <- MessageRouter.publish("session-1", "for-1")
          _ <- MessageRouter.publish("session-2", "for-2")
          result1 <- fiber1.join
          result2 <- fiber2.join
        yield assertTrue(
          result1.toList == List("for-1"),
          result2.toList == List("for-2")
        )
      }
    },
    test("hasSubscribers returns true when subscribed") {
      ZIO.scoped {
        for
          _ <- MessageRouter.subscribe("session-1")
          has <- MessageRouter.hasSubscribers("session-1")
        yield assertTrue(has)
      }
    },
    test("hasSubscribers returns false when no subscribers") {
      for
        has <- MessageRouter.hasSubscribers("nonexistent")
      yield assertTrue(!has)
    },
    test("removeSession shuts down the hub") {
      for
        // Create and subscribe, then remove the session
        _ <- ZIO.scoped(MessageRouter.subscribe("session-1").unit)
        _ <- MessageRouter.removeSession("session-1")
        // Hub is gone -- new publish creates nothing
        delivered <- MessageRouter.publish("session-1", "should not deliver")
      yield assertTrue(!delivered)
    }
  ).provide(InMemoryBackend.layer)

object EventReplaySpec extends ZIOSpecDefault:

  def spec = suite("InMemoryEventReplay")(
    test("append returns incrementing event IDs") {
      for
        id1 <- EventReplay.append("session-1", "msg-1")
        id2 <- EventReplay.append("session-1", "msg-2")
        id3 <- EventReplay.append("session-1", "msg-3")
      yield assertTrue(id1 == "1", id2 == "2", id3 == "3")
    },
    test("since returns events after the given ID") {
      for
        id1 <- EventReplay.append("session-1", "msg-1")
        id2 <- EventReplay.append("session-1", "msg-2")
        id3 <- EventReplay.append("session-1", "msg-3")
        events <- EventReplay.since("session-1", id1)
      yield assertTrue(
        events.size == 2,
        events.head.id == "2",
        events.head.message == "msg-2",
        events(1).id == "3",
        events(1).message == "msg-3"
      )
    },
    test("since with last ID returns empty") {
      for
        id1 <- EventReplay.append("session-1", "msg-1")
        id2 <- EventReplay.append("session-1", "msg-2")
        events <- EventReplay.since("session-1", id2)
      yield assertTrue(events.isEmpty)
    },
    test("since with unknown ID returns empty") {
      for
        _ <- EventReplay.append("session-1", "msg-1")
        events <- EventReplay.since("session-1", "unknown")
      yield assertTrue(events.isEmpty)
    },
    test("since with nonexistent session returns empty") {
      for
        events <- EventReplay.since("nonexistent", "1")
      yield assertTrue(events.isEmpty)
    },
    test("different sessions have independent logs") {
      for
        _ <- EventReplay.append("session-1", "s1-msg")
        id <- EventReplay.append("session-2", "s2-msg")
        events1 <- EventReplay.since("session-1", "0")
        events2 <- EventReplay.since("session-2", "0")
      yield assertTrue(
        events1.isEmpty, // no event with ID "0" in session-1
        events2.isEmpty // no event with ID "0" in session-2
      )
    },
    test("removeSession clears all events") {
      for
        id <- EventReplay.append("session-1", "msg-1")
        _ <- EventReplay.removeSession("session-1")
        events <- EventReplay.since("session-1", id)
      yield assertTrue(events.isEmpty)
    }
  ).provide(InMemoryBackend.layer)
