package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.{AuthorizationCode, ClientId, IdentityId, TenantId}
import java.time.Instant

/** Short-lived pending authorization request.
  *
  * Created at `/authorize`, completed at the upstream `/oauth/callback/:provider`
  * handler, redeemed at `/token`. Lifetime must be <= 60s per spec.
  */
final case class AuthorizationRequest(
    code: AuthorizationCode,
    clientId: ClientId,
    tenantId: TenantId,
    redirectUri: String,
    scope: List[String],
    state: String,
    pkceChallenge: PkceChallenge,
    pkceMethod: PkceMethod,
    identityId: Option[IdentityId], // populated on upstream-callback success
    upstreamState: String, // opaque value sent to the IdP so we can correlate
    createdAt: Instant,
    expiresAt: Instant
):

  def isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
