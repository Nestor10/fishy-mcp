package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, PersistenceBackend, SessionStore}
import zio.*

import java.util.UUID

/** Stateless persistence backend. No session tracking, no event replay.
  *
  * All session checks pass unconditionally. Live SSE routing uses ephemeral
  * local Hubs (via the shared [[HubMessageRouter]]) within a single connection
  * lifetime. Event replay is a no-op (no reconnection support).
  *
  * Use this for horizontally-scaled deployments that don't need session state.
  * Set `MCP_STATELESS=true` to activate via [[ConfigDrivenLayers]].
  *
  * Note the degenerate `SessionStore`: `isInitialized` is always true (the init
  * gate is a no-op) and `allSessionIds` is always empty (so server-initiated
  * broadcast reaches only same-instance subscribers). That is the documented
  * trade for not carrying session state.
  */
object StatelessBackend:

  private val HubCapacity = 256

  val layer: ULayer[SessionStore & MessageRouter & EventReplay] =
    ZLayer.fromZIOEnvironment {
      for
        router <- HubMessageRouter.make(HubCapacity)
        backend = Live(router)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  private final case class Live(router: HubMessageRouter) extends PersistenceBackend:

    // -- SessionStore (degenerate) --------------------------------------------

    def create(): UIO[String] = ZIO.succeed(UUID.randomUUID().toString)
    def exists(sessionId: String): UIO[Boolean] = ZIO.succeed(true)
    def remove(sessionId: String): UIO[Unit] = ZIO.unit
    def allSessionIds: UIO[List[String]] = ZIO.succeed(Nil)
    def markInitialized(sessionId: String): UIO[Unit] = ZIO.unit
    def isInitialized(sessionId: String): UIO[Boolean] = ZIO.succeed(true)

    // -- MessageRouter (live SSE fan-out) -- delegated to the shared hub router

    export router.{publish, subscribe, hasSubscribers}

    // -- EventReplay (no-op) --------------------------------------------------

    def append(sessionId: String, message: String): UIO[String] = ZIO.succeed("0")
    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] = ZIO.succeed(Nil)

    // -- Session teardown -----------------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] = router.removeSession(sessionId)
