package fishy.mcp.application.ports

import zio.*

/** Append-only event log per session for SSE replay on reconnect.
  *
  * When a client reconnects with Last-Event-ID, events after that cursor are replayed. Bounded per
  * session to limit memory.
  */
trait EventReplay:
  def append(sessionId: String, message: String): UIO[String]
  def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]]
  def removeSession(sessionId: String): UIO[Unit]

object EventReplay:

  /** A stored event with its SSE ID and message payload. */
  case class Event(id: String, message: String)

  // ---------------------------------------------------------------------------
  // ZIO Environment accessors
  // ---------------------------------------------------------------------------

  def append(sessionId: String, message: String): URIO[EventReplay, String] =
    ZIO.serviceWithZIO(_.append(sessionId, message))

  def since(sessionId: String, eventId: String): URIO[EventReplay, List[Event]] =
    ZIO.serviceWithZIO(_.since(sessionId, eventId))

  def removeSession(sessionId: String): URIO[EventReplay, Unit] =
    ZIO.serviceWithZIO(_.removeSession(sessionId))
