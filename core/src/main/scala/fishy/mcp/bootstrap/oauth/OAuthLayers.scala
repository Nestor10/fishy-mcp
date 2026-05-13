package fishy.mcp.bootstrap.oauth

import fishy.mcp.adapters.outbound.oauth.admission.AllowAllAdmission
import fishy.mcp.adapters.outbound.oauth.idp.NullUpstreamIdP
import fishy.mcp.adapters.outbound.oauth.keys.RsaSigningKeySource
import fishy.mcp.adapters.outbound.oauth.policy.DefaultRedirectUriValidator
import fishy.mcp.adapters.storage.oauth.InMemoryOAuthStorage
import fishy.mcp.application.ports.oauth.{
  AdmissionPolicy,
  OAuthConfig,
  OAuthStorage,
  RedirectUriValidator,
  SigningKeySource,
  StubMarker,
  TenantResolver,
  UpstreamIdP
}
import fishy.mcp.bootstrap.config.{DeploymentConfig, DeploymentProfile}
import fishy.mcp.application.usecase.oauth.{
  CompleteUpstreamCallback,
  ExchangeAuthorizationCode,
  InitiateAuthorization,
  RefreshTokens,
  RegisterClient,
  RevokeRefreshToken,
  TokenIssuer
}
import zio.*

/** Layer aggregations for the built-in OAuth Authorization Server.
  *
  * The SDK's stable surface is [[useCases]] -- the application logic that
  * production deployments compose with their own port implementations
  * (Postgres / Redis / their IdP / their admission policy / etc.).
  *
  * [[inMemoryPorts]] and [[inMemory]] are reference bundles for development.
  * They keep state in-process, generate signing keys per-JVM, and stub out
  * the upstream IdP -- never use them in production.
  */
object OAuthLayers:

  /** Every port the OAuth feature reads from. Production deployments build
    * a `ULayer[Ports]` from their own adapters and stack [[useCases]] on top.
    *
    * The four state-bearing ports are grouped behind [[OAuthStorage]] so a
    * deployment usually plugs in a single storage layer (Postgres, Redis,
    * etc.) instead of wiring four separate ones.
    */
  type Ports =
    OAuthConfig & TenantResolver & AdmissionPolicy & OAuthStorage
      & SigningKeySource & UpstreamIdP & RedirectUriValidator

  /** Every use-case the HTTP routes consume. Built atop [[Ports]]. */
  type UseCases =
    RegisterClient & TokenIssuer & InitiateAuthorization
      & CompleteUpstreamCallback & RevokeRefreshToken & RefreshTokens
      & ExchangeAuthorizationCode

  type OAuthEnv = Ports & UseCases

  /** Production safety audit: refuses to boot when any resolved OAuth port
    * is a [[StubMarker]] (the SDK's placeholder adapters) and
    * `MCP_PROFILE=production`. In dev, a stub just logs `WARN`.
    *
    * Mounted automatically by [[fishy.mcp.bootstrap.oauth.OAuthFeature]] so
    * any of the three OAuth-mounting paths (`withOAuth(cfg)`,
    * `withOAuth(cfg, ports)`, `withCustomOAuth`) get the check.
    */
  val audit: URIO[Ports & DeploymentConfig, Unit] =
    for
      profile   <- ZIO.serviceWith[DeploymentConfig](_.profile)
      upstream  <- ZIO.service[UpstreamIdP]
      admission <- ZIO.service[AdmissionPolicy]
      signer    <- ZIO.service[SigningKeySource]
      stubs = List[Any](upstream, admission, signer).collect { case s: StubMarker => s.stubId }
      _ <- (profile, stubs) match
             case (DeploymentProfile.Production, ids) if ids.nonEmpty =>
               ZIO.die(new IllegalStateException(
                 s"Production deployment refuses to start with OAuth stub adapter(s): ${ids.mkString(", ")}. " +
                   "Replace each with a real implementation (real upstream IdP, deployment-specific admission, " +
                   "persistent signing key) or unset MCP_PROFILE=production."
               ))
             case (_, ids) if ids.nonEmpty =>
               ZIO.logWarning(
                 s"OAuth stub adapter(s) wired: ${ids.mkString(", ")}. " +
                   "Replace before production -- MCP_PROFILE=production will refuse to boot."
               )
             case _ => ZIO.unit
    yield ()

  /** SDK application layer: wires the OAuth use-cases on top of any
    * [[Ports]] you provide. Stable, production-safe, opinion-free about how
    * you store data or talk to your IdP.
    */
  val useCases: URLayer[Ports, UseCases] =
    ZLayer.makeSome[Ports, UseCases](
      RegisterClient.layer,
      TokenIssuer.layer,
      InitiateAuthorization.layer,
      CompleteUpstreamCallback.layer,
      RevokeRefreshToken.layer,
      RefreshTokens.layer,
      ExchangeAuthorizationCode.layer
    )

  /** Reference port implementations, all in-process. Dev only.
    *
    *   - `InMemory*Store` lose all state on restart.
    *   - `RsaSigningKeySource.generated` mints fresh keys per JVM; tokens
    *     issued by one instance will not validate on another.
    *   - `NullUpstreamIdP` always fails authorization (no real IdP wired).
    *   - `AllowAllAdmission` admits every verified upstream identity.
    */
  def inMemoryPorts(
      config: OAuthConfig,
      tenantResolver: ULayer[TenantResolver]
  ): ULayer[Ports] =
    ZLayer.make[Ports](
      ZLayer.succeed(config),
      tenantResolver,
      InMemoryOAuthStorage.layer,
      ZLayer.succeed[AdmissionPolicy](AllowAllAdmission),
      RsaSigningKeySource.generated,
      NullUpstreamIdP.layer,
      DefaultRedirectUriValidator.layer
    )

  /** Convenience: in-memory ports + SDK use-cases. Dev only.
    *
    * Equivalent to `inMemoryPorts(config, tenantResolver) >+> useCases`.
    */
  def inMemory(config: OAuthConfig, tenantResolver: ULayer[TenantResolver]): ULayer[OAuthEnv] =
    inMemoryPorts(config, tenantResolver) >+> useCases
