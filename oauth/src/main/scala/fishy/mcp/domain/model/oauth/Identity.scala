package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.{IdentityId, TenantId}
import java.time.Instant

/** Upstream identity provider kind -- extensible without touching the protocol layer. */
enum UpstreamProvider:
  case Google
  case GenericOidc(issuer: String)

/** Stable reference to the upstream subject a local `Identity` corresponds to.
  *
  * `sub` is the raw upstream `sub` claim; never exposed to external callers.
  */
final case class UpstreamRef(provider: UpstreamProvider, sub: String)

/** Everything we learn about a user from a successful upstream exchange. */
final case class UpstreamIdentity(
    ref: UpstreamRef,
    email: String,
    emailVerified: Boolean,
    name: Option[String],
    rawClaims: Map[String, String]
)

enum IdentityStatus:
  case Pending, Active, Admin, Disabled

  def isAllowed: Boolean = this match
    case Active | Admin     => true
    case Pending | Disabled => false

/** Persistent record of a locally-known identity.
  *
  * `id` is the opaque value we embed as the JWT `sub`; never the upstream sub.
  */
final case class Identity(
    id: IdentityId,
    tenantId: TenantId,
    upstream: UpstreamRef,
    email: String,
    status: IdentityStatus,
    createdAt: Instant
)
