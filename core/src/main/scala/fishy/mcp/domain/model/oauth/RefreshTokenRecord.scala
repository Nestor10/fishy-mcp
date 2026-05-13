package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.{IdentityId, RefreshTokenId}
import java.time.Instant

/** Persisted refresh token.
  *
  * `id` is the opaque handle returned to the client (after base64 encoding).
  * Refresh tokens rotate on use: each redemption issues a fresh id and marks
  * the previous as consumed.
  */
final case class RefreshTokenRecord(
    id: RefreshTokenId,
    identityId: IdentityId,
    scope: List[String],
    createdAt: Instant,
    expiresAt: Instant,
    consumedAt: Option[Instant] = None
):
  def isActive(now: Instant): Boolean =
    consumedAt.isEmpty && now.isBefore(expiresAt)
