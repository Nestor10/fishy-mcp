package fishy.mcp.application.ports

import fishy.mcp.domain.model.AuthContext
import fishy.mcp.domain.model.mcp.ServerCapabilities
import fishy.mcp.adapters.protocol.jsonrpc.Notification
import zio.*
import zio.json.*
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

  /** Sequentially combine two hooks: `onConnect` streams concatenate (first
    * then second), `onDisconnect` runs both. Lets the SDK stack a built-in hook
    * (e.g. [[listChangedOnConnect]]) underneath a user-supplied one. */
  def combine(first: SessionHooks, second: SessionHooks): SessionHooks =
    new SessionHooks:
      def onConnect(
          sessionId: String,
          auth: Option[AuthContext]
      ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
        for
          s1 <- first.onConnect(sessionId, auth)
          s2 <- second.onConnect(sessionId, auth)
        yield s1 ++ s2
      def onDisconnect(sessionId: String): UIO[Unit] =
        first.onDisconnect(sessionId) *> second.onDisconnect(sessionId)

  /** A hook that emits one `list_changed` notification per advertised primitive
    * on connect, so a freshly-connected client populates its tool / resource /
    * prompt lists without waiting for a later change event. This was previously
    * synthesized inside the HTTP transport; it is a session-connect concern, so
    * it lives here as a hook and the transport stays protocol-agnostic. */
  def listChangedOnConnect(capabilities: ServerCapabilities): SessionHooks =
    val notifications: List[String] = List(
      capabilities.tools.map(_ => listChangedNotification("tools")),
      capabilities.resources.map(_ => listChangedNotification("resources")),
      capabilities.prompts.map(_ => listChangedNotification("prompts"))
    ).flatten
    new SessionHooks:
      def onConnect(
          sessionId: String,
          auth: Option[AuthContext]
      ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
        ZIO.succeed(ZStream.fromIterable(notifications))
      def onDisconnect(sessionId: String): UIO[Unit] = ZIO.unit

  private def listChangedNotification(primitive: String): String =
    Notification.make(s"notifications/$primitive/list_changed").toJson

  private object NoOp extends SessionHooks:
    def onConnect(
        sessionId: String,
        auth: Option[AuthContext]
    ): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      ZIO.succeed(ZStream.empty)
    def onDisconnect(sessionId: String): UIO[Unit] =
      ZIO.unit
