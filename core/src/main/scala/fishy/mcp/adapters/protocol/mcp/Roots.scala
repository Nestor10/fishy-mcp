package fishy.mcp.adapters.protocol.mcp

import zio.json.*

/** Wire types for roots/list (server -> client request).
  *
  * The server asks the client for its filesystem roots.
  *
  * @see
  *   https://modelcontextprotocol.io/specification/2025-06-18/client/roots
  */

// ---------------------------------------------------------------------------
// roots/list result
// ---------------------------------------------------------------------------

/** A filesystem root exposed by the client. */
final case class Root(
    uri: String,
    name: Option[String] = None
)

object Root:
  given JsonDecoder[Root] = DeriveJsonDecoder.gen
  given JsonEncoder[Root] = DeriveJsonEncoder.gen

/** Result of roots/list method. */
final case class ListRootsResult(
    roots: List[Root]
)

object ListRootsResult:
  given JsonDecoder[ListRootsResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ListRootsResult] = DeriveJsonEncoder.gen
