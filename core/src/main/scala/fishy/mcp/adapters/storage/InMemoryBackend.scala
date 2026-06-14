package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, PersistenceBackend, SessionStore}
import zio.*

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
        sessions    <- Ref.make(Set.empty[String])
        initialized <- Ref.make(Set.empty[String])
        logs        <- Ref.make(Map.empty[String, SessionLog])
        router      <- HubMessageRouter.make(HubCapacity)
        backend = Live(sessions, initialized, logs, router)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  private final case class Live(
      sessions: Ref[Set[String]],
      initialized: Ref[Set[String]],
      logs: Ref[Map[String, SessionLog]],
      router: HubMessageRouter
  ) extends PersistenceBackend:

    // -- SessionStore ---------------------------------------------------------

    def create(): UIO[String] =
      for
        sessionId <- ZIO.succeed(UUID.randomUUID().toString)
        _ <- sessions.update(_ + sessionId)
      yield sessionId

    def exists(sessionId: String): UIO[Boolean] = sessions.get.map(_.contains(sessionId))
    def remove(sessionId: String): UIO[Unit] = sessions.update(_ - sessionId)
    def allSessionIds: UIO[List[String]] = sessions.get.map(_.toList)
    def markInitialized(sessionId: String): UIO[Unit] = initialized.update(_ + sessionId)
    def isInitialized(sessionId: String): UIO[Boolean] = initialized.get.map(_.contains(sessionId))

    // -- MessageRouter (live SSE fan-out) -- delegated to the shared hub router

    export router.{publish, subscribe, hasSubscribers}

    // -- EventReplay ----------------------------------------------------------

    def append(sessionId: String, message: String): UIO[String] =
      logs.modify { map =>
        val log = map.getOrElse(sessionId, SessionLog(Vector.empty, 1L))
        val eventId = log.nextId.toString
        val event = EventReplay.Event(eventId, message)
        val newEvents =
          if log.events.size >= MaxEventsPerSession then log.events.tail :+ event
          else log.events :+ event
        (eventId, map + (sessionId -> SessionLog(newEvents, log.nextId + 1)))
      }

    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] =
      logs.get.map { map =>
        map.get(sessionId) match
          case None => Nil
          case Some(log) =>
            val idx = log.events.indexWhere(_.id == eventId)
            if idx < 0 then Nil else log.events.drop(idx + 1).toList
      }

    // -- Combined session teardown --------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] =
      router.removeSession(sessionId) *> logs.update(_ - sessionId)
