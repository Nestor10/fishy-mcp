package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, PersistenceBackend, SessionStore}
import zio.*
import zio.stream.ZStream

import java.util.UUID

/** Stateless persistence backend. No session tracking, no event replay.
  *
  * All session checks pass unconditionally. Message routing uses ephemeral local Hubs for SSE
  * within a single connection lifetime. Event replay is a no-op (no reconnection support).
  *
  * Use this for horizontally-scaled deployments that don't need session state. Set
  * `MCP_STATELESS=true` to activate via `ConfigDrivenLayers`.
  */
object StatelessBackend:

  private val HubCapacity = 256

  val layer: ULayer[SessionStore & MessageRouter & EventReplay] =
    ZLayer.fromZIOEnvironment {
      for
        hubs <- Ref.make(Map.empty[String, Hub[String]])
        backend = Live(hubs)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  private final case class Live(
      hubs: Ref[Map[String, Hub[String]]]
  ) extends PersistenceBackend:

    // -- SessionStore ---------------------------------------------------------

    def create(): UIO[String] =
      ZIO.succeed(UUID.randomUUID().toString)

    def exists(sessionId: String): UIO[Boolean] =
      ZIO.succeed(true)

    def remove(sessionId: String): UIO[Unit] =
      ZIO.unit

    def allSessionIds: UIO[List[String]] =
      ZIO.succeed(Nil)

    def markInitialized(sessionId: String): UIO[Unit] =
      ZIO.unit

    def isInitialized(sessionId: String): UIO[Boolean] =
      ZIO.succeed(true)

    // -- MessageRouter --------------------------------------------------------

    def publish(sessionId: String, message: String): UIO[Boolean] =
      for
        hubOpt <- hubs.get.map(_.get(sessionId))
        delivered <- hubOpt match
          case Some(hub) => hub.publish(message)
          case None      => ZIO.succeed(false)
      yield delivered

    def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      for
        hub <- getOrCreateHub(sessionId)
        dequeue <- hub.subscribe
      yield ZStream.fromQueue(dequeue)

    def hasSubscribers(sessionId: String): UIO[Boolean] =
      hubs.get.map(_.contains(sessionId))

    // -- EventReplay ----------------------------------------------------------

    def append(sessionId: String, message: String): UIO[String] =
      ZIO.succeed("0")

    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] =
      ZIO.succeed(Nil)

    // -- Combined session teardown --------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] =
      hubs.modify { map =>
        val hubOpt = map.get(sessionId)
        (hubOpt, map - sessionId)
      }.flatMap {
        case Some(hub) => hub.shutdown
        case None      => ZIO.unit
      }

    // -- Internals ------------------------------------------------------------

    private def getOrCreateHub(sessionId: String): UIO[Hub[String]] =
      hubs.get.map(_.get(sessionId)).flatMap {
        case Some(hub) => ZIO.succeed(hub)
        case None =>
          Hub.bounded[String](HubCapacity).flatMap { newHub =>
            hubs.modify { map =>
              map.get(sessionId) match
                case Some(existing) => (existing, map)
                case None           => (newHub, map + (sessionId -> newHub))
            }
          }
      }
