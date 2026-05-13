package fishy.mcp.server

import fishy.mcp.adapters.storage.{BackendConfig, ConfigDrivenLayers, StatelessBackend}
import fishy.mcp.application.ports.{EventReplay, MessageRouter, SessionStore}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object StatelessBackendSpec extends ZIOSpecDefault:

  def spec = suite("StatelessBackend")(
    sessionStoreSuite,
    messageRouterSuite,
    eventReplaySuite,
    configDrivenSuite
  )

  private val sessionStoreSuite = suite("SessionStore")(
    test("create returns a session ID") {
      for
        id <- SessionStore.create()
      yield assertTrue(id.nonEmpty)
    },
    test("exists always returns true") {
      for
        result <- SessionStore.exists("any-session-id")
      yield assertTrue(result)
    },
    test("exists returns true for nonexistent sessions") {
      for
        result <- SessionStore.exists("never-created")
      yield assertTrue(result)
    },
    test("isInitialized always returns true") {
      for
        result <- SessionStore.isInitialized("any-session-id")
      yield assertTrue(result)
    },
    test("remove is a no-op") {
      for
        _ <- SessionStore.remove("any-session-id")
        result <- SessionStore.exists("any-session-id")
      yield assertTrue(result)
    },
    test("allSessionIds returns empty") {
      for
        ids <- SessionStore.allSessionIds
      yield assertTrue(ids.isEmpty)
    },
    test("markInitialized is a no-op") {
      for
        _ <- SessionStore.markInitialized("any-session-id")
        result <- SessionStore.isInitialized("any-session-id")
      yield assertTrue(result)
    }
  ).provide(StatelessBackend.layer)

  private val messageRouterSuite = suite("MessageRouter")(
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
    test("hasSubscribers returns true when subscribed") {
      ZIO.scoped {
        for
          _ <- MessageRouter.subscribe("session-1")
          has <- MessageRouter.hasSubscribers("session-1")
        yield assertTrue(has)
      }
    },
    test("removeSession shuts down the hub") {
      for
        _ <- ZIO.scoped(MessageRouter.subscribe("session-1").unit)
        _ <- MessageRouter.removeSession("session-1")
        delivered <- MessageRouter.publish("session-1", "should not deliver")
      yield assertTrue(!delivered)
    }
  ).provide(StatelessBackend.layer)

  private val eventReplaySuite = suite("EventReplay")(
    test("append returns constant ID") {
      for
        id <- EventReplay.append("session-1", "msg")
      yield assertTrue(id == "0")
    },
    test("since always returns empty") {
      for
        events <- EventReplay.since("session-1", "0")
      yield assertTrue(events.isEmpty)
    }
  ).provide(StatelessBackend.layer)

  private val configDrivenSuite = suite("BackendConfig conflict detection")(
    test("fails when both MCP_STATELESS=true and REDIS_URL are set") {
      for
        _      <- TestSystem.putEnv("MCP_STATELESS", "true")
        _      <- TestSystem.putEnv("REDIS_URL", "redis://localhost:6379")
        result <- ZIO.config(BackendConfig.config).exit
      yield assert(result)(fails(isSubtype[Config.Error.InvalidData](anything)))
    }
  )
