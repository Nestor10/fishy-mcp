package fishy.mcp.adapters.protocol.oauth

import zio.json.*

/** RFC 9728 Protected Resource Metadata.
  *
  * Advertises the authorization servers the MCP resource trusts. Clients fetch
  * this at `.well-known/oauth-protected-resource` to discover where to register
  * and acquire tokens.
  */
final case class ProtectedResourceMetadata(
    resource: String,
    authorization_servers: List[String],
    scopes_supported: Option[List[String]] = None,
    bearer_methods_supported: List[String] = List("header"),
    resource_documentation: Option[String] = None
)

object ProtectedResourceMetadata:
  given JsonEncoder[ProtectedResourceMetadata] = DeriveJsonEncoder.gen
  given JsonDecoder[ProtectedResourceMetadata] = DeriveJsonDecoder.gen
