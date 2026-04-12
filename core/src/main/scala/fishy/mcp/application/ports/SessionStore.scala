package fishy.mcp.application.ports

import zio.*

/** Session lifecycle: creation, validation, enumeration, init tracking.
  *
  * Init state is stored here so it survives across horizontally-scaled instances (the MCP handshake
  * must complete before other requests).
  */
trait SessionStore:
  def create(): UIO[String]
  def exists(sessionId: String): UIO[Boolean]
  def remove(sessionId: String): UIO[Unit]
  def allSessionIds: UIO[List[String]]
  def markInitialized(sessionId: String): UIO[Unit]
  def isInitialized(sessionId: String): UIO[Boolean]

object SessionStore:

  // ---------------------------------------------------------------------------
  // ZIO Environment accessors
  // ---------------------------------------------------------------------------

  def create(): URIO[SessionStore, String] =
    ZIO.serviceWithZIO(_.create())

  def exists(sessionId: String): URIO[SessionStore, Boolean] =
    ZIO.serviceWithZIO(_.exists(sessionId))

  def remove(sessionId: String): URIO[SessionStore, Unit] =
    ZIO.serviceWithZIO(_.remove(sessionId))

  def allSessionIds: URIO[SessionStore, List[String]] =
    ZIO.serviceWithZIO(_.allSessionIds)

  def markInitialized(sessionId: String): URIO[SessionStore, Unit] =
    ZIO.serviceWithZIO(_.markInitialized(sessionId))

  def isInitialized(sessionId: String): URIO[SessionStore, Boolean] =
    ZIO.serviceWithZIO(_.isInitialized(sessionId))
