package fishy.mcp.application.ports

import zio.*
import zio.stream.ZStream

/** Per-session message pub/sub for GET SSE delivery.
  *
  * POST handlers publish; GET SSE handlers subscribe. Subscriptions are scoped -- auto-unsubscribe
  * when the Scope closes.
  */
trait MessageRouter:
  def publish(sessionId: String, message: String): UIO[Boolean]
  def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]]
  def hasSubscribers(sessionId: String): UIO[Boolean]
  def removeSession(sessionId: String): UIO[Unit]

object MessageRouter:

  // ---------------------------------------------------------------------------
  // ZIO Environment accessors
  // ---------------------------------------------------------------------------

  def publish(sessionId: String, message: String): URIO[MessageRouter, Boolean] =
    ZIO.serviceWithZIO(_.publish(sessionId, message))

  def subscribe(sessionId: String)
      : ZIO[MessageRouter & Scope, Nothing, ZStream[Any, Nothing, String]] =
    ZIO.serviceWithZIO[MessageRouter](_.subscribe(sessionId))

  def hasSubscribers(sessionId: String): URIO[MessageRouter, Boolean] =
    ZIO.serviceWithZIO(_.hasSubscribers(sessionId))

  def removeSession(sessionId: String): URIO[MessageRouter, Unit] =
    ZIO.serviceWithZIO(_.removeSession(sessionId))
