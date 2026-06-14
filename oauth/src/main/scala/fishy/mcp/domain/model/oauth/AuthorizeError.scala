package fishy.mcp.domain.model.oauth

/** RFC 6749 §4.1.2.1 says an authorization error MUST be reported back to the
  * client via the `redirect_uri` only after the AS has positively validated
  * the client_id and redirect_uri pairing. Earlier failures must be shown to
  * the resource owner directly (a JSON 400 in our case).
  *
  * This sum carries that distinction up out of HTTP into a typed failure so
  * the route adapter just maps each variant to the right `Response` shape.
  */
enum AuthorizeError:
  /** Client/redirect_uri not yet validated -- render as a JSON OAuth error. */
  case ClientFault(error: OAuthError)

  /** Client/redirect_uri validated -- redirect back to the client with
    * `?error=...&state=...` (RFC 6749 §4.1.2.1).
    */
  case ClientRedirectFault(redirectUri: String, state: String, error: OAuthError)
