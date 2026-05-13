package fishy.mcp.adapters.protocol.oauth

import zio.json.*

/** RFC 8414 Authorization Server Metadata.
  *
  * Only the fields MCP clients and spec-compliant validators actually read are
  * modeled. Unknown fields are omitted rather than surfaced as `Option[Json]`.
  */
final case class AuthorizationServerMetadata(
    issuer: String,
    authorization_endpoint: String,
    token_endpoint: String,
    registration_endpoint: Option[String],
    jwks_uri: String,
    response_types_supported: List[String],
    grant_types_supported: List[String],
    token_endpoint_auth_methods_supported: List[String],
    code_challenge_methods_supported: List[String],
    scopes_supported: Option[List[String]] = None,
    revocation_endpoint: Option[String] = None
)

object AuthorizationServerMetadata:
  given JsonEncoder[AuthorizationServerMetadata] = DeriveJsonEncoder.gen
  given JsonDecoder[AuthorizationServerMetadata] = DeriveJsonDecoder.gen
