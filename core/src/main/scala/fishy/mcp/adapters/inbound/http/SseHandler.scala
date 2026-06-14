package fishy.mcp.adapters.inbound.http

import fishy.mcp.application.ports.{
  EventReplay,
  MessageRouter,
  SessionHooks,
  SessionStore,
  SubscriptionRegistry
}
import fishy.mcp.domain.model.{AuthContext, AuthFiberRef}
import zio.*
import zio.http.*
import zio.stream.ZStream

/** Handles GET /mcp: session validation, event replay on reconnect, and long-lived SSE stream for
  * server push.
  *
  * Connect-time notifications (e.g. the `list_changed` events that prompt a client to fetch its
  * tool / resource / prompt lists) are supplied by `sessionHooks.onConnect`, not by this handler --
  * see `SessionHooks.listChangedOnConnect`. The handler stays protocol-agnostic: it merges whatever
  * the hooks emit with live `MessageRouter` messages.
  */
private[http] final case class SseHandler(
    sessionStore: SessionStore,
    messageRouter: MessageRouter,
    eventReplay: EventReplay,
    sessionHooks: SessionHooks,
    subscriptionRegistry: SubscriptionRegistry
):

  private val McpSessionIdHeader = "Mcp-Session-Id"
  private val LastEventIdHeader = "Last-Event-ID"

  def handle(request: Request): ZIO[Any, Throwable, Response] =
    request.headers.get(McpSessionIdHeader) match
      case None =>
        ZIO.logDebug("SSE rejected: missing session header").as(
          Response.text("Missing Mcp-Session-Id").status(Status.BadRequest)
        )
      case Some(sessionId) =>
        AuthFiberRef.currentAuth.get.flatMap { auth =>
          ZIO.logAnnotate("transport", "SSE") {
            ZIO.logAnnotate("sessionId", sessionId) {
              ZIO.logAnnotate("authSub", auth.map(_.sub).getOrElse("anonymous")) {
                ZIO.logSpan("handleSseRequest") {
                  sessionStore.exists(sessionId).flatMap {
                    case false =>
                      ZIO.logDebug("SSE rejected: session not found").as(
                        Response.text("Invalid session").status(Status.NotFound)
                      )
                    case true =>
                      ZIO.logDebug("session valid, building SSE stream") *>
                        buildSseResponse(sessionId, request.headers.get(LastEventIdHeader), auth)
                  }
                }
              }
            }
          }
        }

  /** Build SSE response: replay missed events, merge app stream, then stream live messages. */
  private def buildSseResponse(
      sessionId: String,
      lastEventId: Option[String],
      auth: Option[AuthContext]
  ): UIO[Response] =
    ZIO.succeed {
      val sseStream: ZStream[Any, Nothing, String] = ZStream.unwrapScoped {
        for
          replayEvents <- lastEventId match
            case Some(eventId) => eventReplay.since(sessionId, eventId)
            case None          => ZIO.succeed(Nil)
          replayStream = ZStream.fromIterable(replayEvents).map { event =>
            event.id + "\n" + event.message
          }
          appStream <- sessionHooks.onConnect(sessionId, auth)
          _ <- ZIO.addFinalizer(
            sessionHooks.onDisconnect(sessionId) *> subscriptionRegistry.removeSession(sessionId)
          )
          liveStream <- messageRouter.subscribe(sessionId)
          combined = liveStream merge appStream
          liveSSE = combined.mapZIO { message =>
            eventReplay.append(sessionId, message).map { eventId =>
              eventId + "\n" + message
            }
          }
        yield replayStream ++ liveSSE
      }
      val events = sseStream.map { raw =>
        val idx = raw.indexOf('\n')
        val eventId = raw.substring(0, idx)
        val data = raw.substring(idx + 1)
        ServerSentEvent(data = data, id = Some(eventId))
      }
      Response.fromServerSentEvents(events)
    }
