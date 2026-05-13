package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.ClientId
import java.time.Instant

/** Dynamically-registered OAuth client (RFC 7591 subset).
  *
  * Only the fields the AS actually enforces are modeled. Additional RFC 7591
  * metadata can live in `metadata` without widening this type.
  */
final case class ClientRegistration(
    id: ClientId,
    redirectUris: List[String],
    clientName: Option[String],
    grantTypes: Set[String], // e.g. "authorization_code", "refresh_token"
    responseTypes: Set[String], // e.g. "code"
    tokenEndpointAuthMethod: String, // "none" for public clients (PKCE), "client_secret_basic", ...
    scope: Option[String],
    createdAt: Instant,
    metadata: Map[String, String] = Map.empty
):

  def allowsRedirectUri(uri: String): Boolean =
    redirectUris.contains(uri)

  def allowsGrant(grantType: String): Boolean =
    grantTypes.contains(grantType)
