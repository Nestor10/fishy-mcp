package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{MessageRouter, SessionStore, SubscriptionRegistry}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Sends server-initiated notifications to all sessions with active SSE connections. */
trait NotificationSender:

  /** Notify all SSE-connected sessions that the tools list has changed. */
  def toolsListChanged: UIO[Unit]

  /** Notify all SSE-connected sessions that the resources list has changed. */
  def resourcesListChanged: UIO[Unit]

  /** Notify sessions subscribed to a resource URI that the resource content has changed. */
  def resourceUpdated(uri: String): UIO[Unit]

  /** Notify all SSE-connected sessions that the prompts list has changed. */
  def promptsListChanged: UIO[Unit]

  /** Send an arbitrary notification to a specific session. */
  def sendToSession(sessionId: String, method: String, params: Option[Json] = None): UIO[Boolean]

  /** Send an arbitrary notification to all active SSE sessions. */
  def broadcast(method: String, params: Option[Json] = None): UIO[Unit]

object NotificationSender:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def toolsListChanged: URIO[NotificationSender, Unit] =
    ZIO.serviceWithZIO(_.toolsListChanged)

  def resourcesListChanged: URIO[NotificationSender, Unit] =
    ZIO.serviceWithZIO(_.resourcesListChanged)

  def resourceUpdated(uri: String): URIO[NotificationSender, Unit] =
    ZIO.serviceWithZIO(_.resourceUpdated(uri))

  def promptsListChanged: URIO[NotificationSender, Unit] =
    ZIO.serviceWithZIO(_.promptsListChanged)

  def sendToSession(
      sessionId: String,
      method: String,
      params: Option[Json] = None
  ): URIO[NotificationSender, Boolean] =
    ZIO.serviceWithZIO(_.sendToSession(sessionId, method, params))

  def broadcast(method: String, params: Option[Json] = None): URIO[NotificationSender, Unit] =
    ZIO.serviceWithZIO(_.broadcast(method, params))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[MessageRouter & SessionStore & SubscriptionRegistry, NotificationSender] =
    ZLayer.fromFunction(Live(_, _, _))

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  final case class Live(
      messageRouter: MessageRouter,
      sessionStore: SessionStore,
      subscriptionRegistry: SubscriptionRegistry
  ) extends NotificationSender:

    def toolsListChanged: UIO[Unit] =
      broadcast("notifications/tools/list_changed")

    def resourcesListChanged: UIO[Unit] =
      broadcast("notifications/resources/list_changed")

    def resourceUpdated(uri: String): UIO[Unit] =
      val message =
        makeNotification("notifications/resources/updated", Some(Json.Obj("uri" -> Json.Str(uri))))
      subscriptionRegistry.subscribers(uri).flatMap { sessionIds =>
        ZIO.logDebug(s"resourceUpdated($uri): subscribers=$sessionIds") *>
          ZIO.foreachDiscard(sessionIds) { sid =>
            ZIO.logDebug(s"resourceUpdated: pushing to session $sid") *>
              messageRouter.publish(sid, message).ignore
          }
      }

    def promptsListChanged: UIO[Unit] =
      broadcast("notifications/prompts/list_changed")

    def sendToSession(sessionId: String, method: String, params: Option[Json]): UIO[Boolean] =
      messageRouter.publish(sessionId, makeNotification(method, params))

    def broadcast(method: String, params: Option[Json]): UIO[Unit] =
      val message = makeNotification(method, params)
      // Publish to all sessions -- MessageRouter.publish is no-op for sessions without subscribers
      // We iterate known sessions and publish to each. Only sessions with active SSE connections will receive.
      ZIO.logDebug(s"Broadcasting notification: $method") *>
        sessionStore.allSessionIds.flatMap { sessionIds =>
          ZIO.foreachDiscard(sessionIds) { sid =>
            messageRouter.publish(sid, message).ignore
          }
        }

    private def makeNotification(method: String, params: Option[Json]): String =
      val base = Json.Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "method" -> Json.Str(method)
      )
      val withParams = params match
        case Some(p) => Json.Obj(base.fields :+ ("params" -> p)*)
        case None    => base
      withParams.toJson
