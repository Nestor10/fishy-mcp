package fishy.mcp.adapters.protocol.mcp

import zio.json.*
import zio.json.ast.Json

/** Wire types for MCP progress tracking and cancellation.
  *
  * Progress: server emits `notifications/progress` with token, progress, optional total.
  * Cancellation: client sends `notifications/cancelled` with the request ID to cancel.
  */

// ---------------------------------------------------------------------------
// notifications/progress (server -> client, sent as SSE events)
// ---------------------------------------------------------------------------

/** Progress notification payload. Sent as a JSON-RPC notification with method
  * `notifications/progress`.
  */
final case class ProgressNotification(
    progressToken: Json,
    progress: Double,
    total: Option[Double] = None,
    message: Option[String] = None
)

object ProgressNotification:
  given JsonEncoder[ProgressNotification] = DeriveJsonEncoder.gen
  given JsonDecoder[ProgressNotification] = DeriveJsonDecoder.gen

// ---------------------------------------------------------------------------
// notifications/cancelled (client -> server)
// ---------------------------------------------------------------------------

/** Cancellation notification params. Client sends this to cancel an in-flight request. The
  * `requestId` identifies which request to cancel.
  */
final case class CancelledParams(
    requestId: Json,
    reason: Option[String] = None
)

object CancelledParams:
  given JsonDecoder[CancelledParams] = DeriveJsonDecoder.gen
  given JsonEncoder[CancelledParams] = DeriveJsonEncoder.gen
