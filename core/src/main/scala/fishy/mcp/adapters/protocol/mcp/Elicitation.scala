package fishy.mcp.adapters.protocol.mcp

import zio.json.*
import zio.json.ast.Json

/** Wire types for elicitation/create (server -> client request).
  *
  * The server asks the client for human input, optionally with a schema describing the expected
  * response shape.
  *
  * @see
  *   https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation
  */

// ---------------------------------------------------------------------------
// elicitation/create params + result
// ---------------------------------------------------------------------------

final case class ElicitationParams(
    message: String,
    requestedSchema: Option[Json] = None
)

object ElicitationParams:
  given JsonDecoder[ElicitationParams] = DeriveJsonDecoder.gen
  given JsonEncoder[ElicitationParams] = DeriveJsonEncoder.gen

/** Result of elicitation/create. */
final case class ElicitationResult(
    action: String,
    content: Option[Json] = None
)

object ElicitationResult:
  given JsonDecoder[ElicitationResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ElicitationResult] = DeriveJsonEncoder.gen

object ElicitationAction:
  val Accepted: String = "accepted"
  val Declined: String = "declined"
  val Cancelled: String = "cancelled"
