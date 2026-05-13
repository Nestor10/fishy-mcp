package fishy.mcp.bootstrap.http

import fishy.mcp.adapters.inbound.http.{
  AuthConfig,
  ConfigDrivenAuth,
  HttpSecurityPolicy,
  JwtSecurityPolicy,
  TrustedHeaderPolicy
}
import zio.*

/** Named layer factories for `HttpSecurityPolicy`.
  *
  * Every factory has the same shape: `URLayer[AuthConfig, HttpSecurityPolicy]`.
  * The `AuthConfig` input is supplied by [[fishy.mcp.bootstrap.AppConfig]] at
  * the entry point, so layers below this seam are `URLayer`s with no
  * `Config.Error` channel.
  *
  * Use directly when wiring a custom `MCPServer`, or via the convenience
  * builders (`withJwtAuth`, `withTrustedHeaders`, `withConfigDrivenAuth`).
  */
object HttpSecurityLayers:

  /** No authentication. Default for tests and dev. */
  val allowAll: URLayer[AuthConfig, HttpSecurityPolicy] =
    HttpSecurityPolicy.allowAll

  /** JWT Bearer validation via JWKS. JWKS is fetched lazily by Nimbus on the
    * first request (5min cache). Layer-construction failures are downgraded
    * to defects via `.orDie`.
    */
  def jwt(cfg: JwtSecurityPolicy.Config): URLayer[AuthConfig, HttpSecurityPolicy] =
    JwtSecurityPolicy.layer(cfg).orDie

  /** Identity from upstream proxy headers. Deployer is responsible for
    * proving the proxy is trusted.
    */
  def trusted(
      mapping: TrustedHeaderPolicy.HeaderMapping = TrustedHeaderPolicy.defaultMapping
  ): URLayer[AuthConfig, HttpSecurityPolicy] =
    TrustedHeaderPolicy.layer(mapping)

  /** Dispatch on the [[AuthConfig]] in the environment. The deployer sets
    * `AUTH_MODE` and the relevant `JWT_*` vars; `AppConfig.audit` enforces
    * production strictness.
    */
  val configDriven: URLayer[AuthConfig, HttpSecurityPolicy] =
    ConfigDrivenAuth.layer
