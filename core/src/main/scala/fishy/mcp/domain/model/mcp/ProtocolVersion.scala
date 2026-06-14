package fishy.mcp.domain.model.mcp

/** MCP protocol versions this SDK can speak, newest first.
  *
  * On `initialize` the server echoes the client's requested version when it's
  * one of these, otherwise it answers with [[latest]] and lets the client decide
  * whether to proceed — per the MCP version-negotiation handshake. (Previously
  * the server hard-coded a single version and ignored the request.)
  */
object ProtocolVersion:

  /** Supported wire versions, newest first. */
  val supported: List[String] = List("2025-06-18", "2025-03-26", "2024-11-05")

  /** The newest supported version — the fallback when the client asks for one we
    * don't recognize. */
  val latest: String = supported.head

  /** Echo `requested` if supported, else fall back to [[latest]]. */
  def negotiate(requested: String): String =
    if supported.contains(requested) then requested else latest
