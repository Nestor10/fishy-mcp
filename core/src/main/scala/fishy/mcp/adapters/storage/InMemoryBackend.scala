package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, PersistenceBackend, SessionStore}
import zio.*
import zio.stream.ZStream

import java.util.UUID

/** In-memory persistence. State lives in Refs, lost on restart. */
object InMemoryBackend:

  private val HubCapacity = 256
  private val MaxEventsPerSession = 1000

  private case class SessionLog(
      events: Vector[EventReplay.Event],
      nextId: Long
  )

  val layer: ULayer[SessionStore & MessageRouter & EventReplay] =
    ZLayer.fromZIOEnvironment {
      for
        sessions <- Ref.make(Set.empty[String])
        initialized <- Ref.make(Set.empty[String])
        hubs <- Ref.make(Map.empty[String, Hub[String]])
        logs <- Ref.make(Map.empty[String, SessionLog])
        backend = Live(sessions, initialized, hubs, logs)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  private final case class Live(
      sessions: Ref[Set[String]],
      initialized: Ref[Set[String]],
      hubs: Ref[Map[String, Hub[String]]],
      logs: Ref[Map[String, SessionLog]]
  ) extends PersistenceBackend:

    // -- SessionStore ---------------------------------------------------------

    def create(): UIO[String] =
      for
        sessionId <- ZIO.succeed(UUID.randomUUID().toString)
        _ <- sessions.update(_ + sessionId)
      yield sessionId

    def exists(sessionId: String): UIO[Boolean] =
      sessions.get.map(_.contains(sessionId))

    def remove(sessionId: String): UIO[Unit] =
      sessions.update(_ - sessionId)

    def allSessionIds: UIO[List[String]] =
      sessions.get.map(_.toList)

    def markInitialized(sessionId: String): UIO[Unit] =
      initialized.update(_ + sessionId)

    def isInitialized(sessionId: String): UIO[Boolean] =
      initialized.get.map(_.contains(sessionId))

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
      logs.modify { map =>
        val log = map.getOrElse(sessionId, SessionLog(Vector.empty, 1L))
        val eventId = log.nextId.toString
        val event = EventReplay.Event(eventId, message)
        val newEvents =
          if log.events.size >= MaxEventsPerSession then log.events.tail :+ event
          else log.events :+ event
        val newLog = SessionLog(newEvents, log.nextId + 1)
        (eventId, map + (sessionId -> newLog))
      }

    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] =
      logs.get.map { map =>
        map.get(sessionId) match
          case None => Nil
          case Some(log) =>
            val idx = log.events.indexWhere(_.id == eventId)
            if idx < 0 then Nil
            else log.events.drop(idx + 1).toList
      }

    // -- Combined session teardown --------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] =
      val removeHub = hubs.modify { map =>
        val hubOpt = map.get(sessionId)
        (hubOpt, map - sessionId)
      }.flatMap {
        case Some(hub) => hub.shutdown
        case None      => ZIO.unit
      }
      val removeLog = logs.update(_ - sessionId)
      removeHub *> removeLog

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
