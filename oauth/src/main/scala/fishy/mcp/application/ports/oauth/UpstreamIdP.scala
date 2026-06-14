package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.{Tenant, UpstreamIdentity}
import zio.*

/** Pluggable upstream identity provider driver (OIDC code flow).
  *
  * Built-ins will include `GoogleOIDC` and `GenericOIDC` (discovery via
  * `.well-known/openid-configuration`). Instances are scoped to a `Tenant`
  * because client credentials differ per-tenant.
  */
trait UpstreamIdP:

  /** Build the authorization URL to redirect the user to. `state` must be
    * propagated back on callback; it is opaque to the driver.
    */
  def authorizationUrl(
      tenant: Tenant,
      state: String,
      codeChallenge: String,
      codeChallengeMethod: String,
      redirectUri: String
  ): UIO[String]

  /** Exchange an upstream authorization code for user claims. */
  def exchangeCode(
      tenant: Tenant,
      code: String,
      redirectUri: String
  ): IO[UpstreamIdP.ExchangeError, UpstreamIdentity]

object UpstreamIdP:

  enum ExchangeError(val message: String):
    case BadRequest(override val message: String)    extends ExchangeError(message)
    case Unauthorized(override val message: String)  extends ExchangeError(message)
    case Upstream(override val message: String)      extends ExchangeError(message)
    case MissingClaim(override val message: String)  extends ExchangeError(message)
