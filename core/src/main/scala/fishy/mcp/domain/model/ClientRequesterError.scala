package fishy.mcp.domain.model

import zio.Duration
import zio.json.ast.Json

/** Failure modes for [[fishy.mcp.application.usecase.ClientRequester]].
  *
  * Server-to-client requests (sampling/createMessage, roots/list,
  * elicitation/create) can fail in five distinct, recoverable ways. Modeling
  * them as a sealed sum lets callers pattern-match instead of inspecting
  * exception messages.
  */
enum ClientRequesterError:

  /** No active SSE stream attached for this session. */
  case NoActiveConnection(sessionId: String)

  /** No client-request callback was wired into `ToolContext`. Happens for
    * tools called outside an HTTP transport (e.g. stdio), where
    * server-to-client requests are not supported.
    */
  case NoClientCallback

  /** The publish to the session's SSE hub failed (race with disconnect, etc.). */
  case PublishFailed(sessionId: String)

  /** The client did not respond within the configured timeout. */
  case RequestTimeout(method: String, requestId: String, timeout: Duration)

  /** The session was disconnected while the request was in flight. */
  case SessionDisconnected(sessionId: String)

  /** The client returned a JSON-RPC error response. */
  case ClientReturnedError(code: Int, message: String, data: Option[Json] = None)

object ClientRequesterError:

  extension (e: ClientRequesterError)
    /** Human-readable message; suitable for downgrading to a `Throwable` at
      * legacy boundaries (`new RuntimeException(e.message)`).
      */
    def message: String = e match
      case NoActiveConnection(sid) =>
        s"No active SSE connection for session $sid. Client must have an open GET /mcp SSE stream."
      case NoClientCallback =>
        "No client request callback available. Server-to-client requests require an active SSE connection (HTTP transport)."
      case PublishFailed(sid) =>
        s"Failed to publish request to session $sid"
      case RequestTimeout(method, id, timeout) =>
        s"Client did not respond to $method (id=$id) within ${timeout.toMillis}ms"
      case SessionDisconnected(sid) =>
        s"Session $sid disconnected while request was pending"
      case ClientReturnedError(code, msg, _) =>
        s"Client returned error: code=$code, message=$msg"
