package fishy.mcp.adapters.outbound.oauth.policy

import fishy.mcp.application.ports.oauth.RedirectUriValidator
import fishy.mcp.domain.model.oauth.{OAuthError, OAuthErrorKind}
import zio.*

/** RFC 6749 §3.1.2 redirect-URI policy:
  *
  *   - Loopback `http://localhost`, `http://127.0.0.1`, `http://[::1]` — allowed.
  *   - Any `https://` URL — allowed.
  *   - Custom (private-use) schemes — allowed.
  *   - `javascript:` and other dangerous schemes — rejected.
  *
  * URIs that fail to parse are rejected as `invalid_redirect_uri`.
  */
object DefaultRedirectUriValidator extends RedirectUriValidator:

  override def validate(uri: String): IO[OAuthError, Unit] =
    ZIO
      .attempt {
        val parsed = java.net.URI.create(uri)
        val scheme = Option(parsed.getScheme).getOrElse("").toLowerCase
        val host   = Option(parsed.getHost).getOrElse("")
        val loopbackOk =
          scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
        val httpsOk  = scheme == "https"
        val customOk =
          scheme.nonEmpty && scheme != "http" && scheme != "https" && scheme != "javascript"
        loopbackOk || httpsOk || customOk
      }
      .mapError(e =>
        OAuthError(OAuthErrorKind.InvalidRequest, Some(s"redirect_uri parse failed: ${e.getMessage}"))
      )
      .flatMap { ok =>
        if ok then ZIO.unit
        else
          ZIO.fail(
            OAuthError(OAuthErrorKind.InvalidRequest, Some(s"redirect_uri scheme not allowed: $uri"))
          )
      }

  val layer: ULayer[RedirectUriValidator] = ZLayer.succeed(this)
