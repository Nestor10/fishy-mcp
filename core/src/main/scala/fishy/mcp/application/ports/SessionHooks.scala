package fishy.mcp.application.ports

import fishy.mcp.domain.model.AuthContext
import zio.*
import zio.stream.ZStream

/** Lifecycle hooks for SSE connections.
  *
  * `onConnect` returns a `ZStream` scoped to the SSE connection. The stream is merged into the SSE
  * output alongside `MessageRouter` messages. When the client disconnects, the `Scope` closes the
  * stream and `onDisconnect` fires.
  *
  * Stream elements must be valid JSON-RPC messages (notifications or responses).
  */
trait SessionHooks:
  def onConnect(
      sessionId: String,
      auth: Option[AuthContext]
  ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]]
  def onDisconnect(sessionId: String): UIO[Unit]

object SessionHooks:

  def onConnect(
      sessionId: String,
      auth: Option[AuthContext]
  ): ZIO[SessionHooks & Scope, Nothing, ZStream[Any, Nothing, String]] =
    ZIO.serviceWithZIO[SessionHooks](_.onConnect(sessionId, auth))

  def onDisconnect(sessionId: String): URIO[SessionHooks, Unit] =
    ZIO.serviceWithZIO[SessionHooks](_.onDisconnect(sessionId))

  val noOp: ULayer[SessionHooks] = ZLayer.succeed(NoOp)

  private object NoOp extends SessionHooks:
    def onConnect(
        sessionId: String,
        auth: Option[AuthContext]
    ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      ZIO.succeed(ZStream.empty)
    def onDisconnect(sessionId: String): UIO[Unit] =
      ZIO.unit
