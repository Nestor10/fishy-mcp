package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.OAuthError
import zio.*

/** Per-tenant policy for which `redirect_uri` values a registered client may use.
  *
  * Encoded as a port (mirroring [[AdmissionPolicy]]) so deployments can plug in
  * stricter policies (https-only, allowlists, etc.) without touching transport.
  *
  * The reference implementation in `adapters/outbound/oauth/policy/` follows
  * RFC 6749 §3.1.2: loopback http, https, and custom schemes; rejects `javascript:`.
  */
trait RedirectUriValidator:
  def validate(uri: String): IO[OAuthError, Unit]

object RedirectUriValidator:
  def validate(uri: String): ZIO[RedirectUriValidator, OAuthError, Unit] =
    ZIO.serviceWithZIO[RedirectUriValidator](_.validate(uri))
