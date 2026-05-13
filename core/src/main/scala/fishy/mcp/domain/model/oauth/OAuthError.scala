package fishy.mcp.domain.model.oauth

import zio.json.JsonDecoder

/** RFC 6749 §5.2 / RFC 8628 §3.5 error kinds, plus app-specific identity-lifecycle states.
  *
  * Modeled after zio-cli's `AccessTokenResponse.Error.Kind`: a typed sum with an `Other(code)`
  * escape hatch for codes received from upstream servers that we don't classify.
  */
enum OAuthErrorKind:
  case InvalidRequest
  case InvalidClient
  case InvalidGrant
  case UnauthorizedClient
  case UnsupportedGrantType
  case InvalidScope
  case AccessDenied
  case ServerError
  case TemporarilyUnavailable
  // Identity-lifecycle states. The wire `error` collapses to `access_denied` per RFC,
  // but the kind is preserved so renderers can pick a friendlier message.
  case IdentityPending
  case IdentityDisabled
  // Codes received from upstream IdPs that we don't recognize.
  case Other(code: String)

object OAuthErrorKind:
  /** Render to the wire `error` token (RFC 6749 §5.2). */
  def code(kind: OAuthErrorKind): String = kind match
    case InvalidRequest         => "invalid_request"
    case InvalidClient          => "invalid_client"
    case InvalidGrant           => "invalid_grant"
    case UnauthorizedClient     => "unauthorized_client"
    case UnsupportedGrantType   => "unsupported_grant_type"
    case InvalidScope           => "invalid_scope"
    case AccessDenied           => "access_denied"
    case ServerError            => "server_error"
    case TemporarilyUnavailable => "temporarily_unavailable"
    case IdentityPending        => "access_denied"
    case IdentityDisabled       => "access_denied"
    case Other(c)               => c

  given JsonDecoder[OAuthErrorKind] =
    JsonDecoder.string.map {
      case "invalid_request"         => InvalidRequest
      case "invalid_client"          => InvalidClient
      case "invalid_grant"           => InvalidGrant
      case "unauthorized_client"     => UnauthorizedClient
      case "unsupported_grant_type"  => UnsupportedGrantType
      case "invalid_scope"           => InvalidScope
      case "access_denied"           => AccessDenied
      case "server_error"            => ServerError
      case "temporarily_unavailable" => TemporarilyUnavailable
      case other                     => Other(other)
    }

/** RFC 6749 §5.2 error envelope: a typed kind plus optional human description and info URI.
  *
  * These are *protocol* errors surfaced to clients. Internal persistence or configuration
  * failures should be logged as defects, not mapped here.
  */
final case class OAuthError(
    kind: OAuthErrorKind,
    description: Option[String] = None,
    errorUri: Option[String] = None
):
  def code: String = OAuthErrorKind.code(kind)
