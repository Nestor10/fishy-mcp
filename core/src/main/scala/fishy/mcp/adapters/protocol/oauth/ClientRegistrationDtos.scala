package fishy.mcp.adapters.protocol.oauth

import zio.json.*

/** RFC 7591 dynamic client registration request.
  *
  * Only the fields we actually validate or echo are modeled. Extra fields are
  * ignored; clients can round-trip their own `metadata` via the catch-all
  * map in [[ClientRegistration]] if they need to.
  */
final case class ClientRegistrationRequest(
    redirect_uris: List[String],
    client_name: Option[String] = None,
    grant_types: Option[List[String]] = None,
    response_types: Option[List[String]] = None,
    token_endpoint_auth_method: Option[String] = None,
    scope: Option[String] = None
) derives JsonDecoder

/** RFC 7591 registration response.
  *
  * We don't issue client secrets -- all dynamically-registered clients are
  * public (PKCE-only). `client_id_issued_at` is seconds since epoch per spec.
  */
final case class ClientRegistrationResponse(
    client_id: String,
    client_id_issued_at: Long,
    redirect_uris: List[String],
    grant_types: List[String],
    response_types: List[String],
    token_endpoint_auth_method: String,
    client_name: Option[String] = None,
    scope: Option[String] = None
) derives JsonEncoder

/** RFC 6749 token response (authorization_code or refresh_token grant). */
final case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Long,
    refresh_token: Option[String] = None,
    scope: Option[String] = None
) derives JsonEncoder

/** RFC 6749 error response for /token and /register. */
final case class OAuthErrorBody(
    error: String,
    error_description: Option[String] = None
) derives JsonEncoder
