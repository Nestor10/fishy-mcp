package fishy.mcp.bootstrap.oauth

import fishy.mcp.adapters.outbound.oauth.admission.{AllowAllAdmission, EmailDomainAllowlistAdmission}
import fishy.mcp.adapters.outbound.oauth.idp.{GenericOidcUpstreamIdP, NullUpstreamIdP}
import fishy.mcp.adapters.outbound.oauth.keys.{DiskRsaSigningKeySource, RsaSigningKeySource}
import fishy.mcp.adapters.outbound.oauth.policy.DefaultRedirectUriValidator
import fishy.mcp.adapters.outbound.oauth.tenant.SingleTenantResolver
import fishy.mcp.adapters.storage.oauth.InMemoryOAuthStorage
import fishy.mcp.domain.model.oauth.{AdmissionSpec, Ids, Tenant, TenantIdpConfig}
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
import zio.http.Client

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

  /** Production safety audit: refuses to boot when any resolved OAuth port is a
    * [[StubMarker]] (the SDK's placeholder adapters) and `isProduction` is true.
    * Otherwise a wired stub just logs `WARN`.
    *
    * Framework-agnostic by design -- callers pass their own notion of
    * "production". The MCP glue (`fishy-mcp-oauth`) derives it from the
    * deployment profile and mounts this automatically on every OAuth path.
    */
  def audit(isProduction: Boolean): URIO[Ports, Unit] =
    for
      upstream  <- ZIO.service[UpstreamIdP]
      admission <- ZIO.service[AdmissionPolicy]
      signer    <- ZIO.service[SigningKeySource]
      stubs = List[Any](upstream, admission, signer).collect { case s: StubMarker => s.stubId }
      _ <- (isProduction, stubs) match
             case (true, ids) if ids.nonEmpty =>
               ZIO.die(new IllegalStateException(
                 s"Production deployment refuses to start with OAuth stub adapter(s): ${ids.mkString(", ")}. " +
                   "Replace each with a real implementation (real upstream IdP, deployment-specific admission, " +
                   "persistent signing key) or disable production mode."
               ))
             case (_, ids) if ids.nonEmpty =>
               ZIO.logWarning(
                 s"OAuth stub adapter(s) wired: ${ids.mkString(", ")}. " +
                   "Replace before production -- production mode will refuse to boot."
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

  // ---------------------------------------------------------------------------
  // Env-driven port selection (storage excluded)
  // ---------------------------------------------------------------------------

  /** Every port except [[OAuthStorage]] -- the one a deployer always supplies
    * (the in-memory store ships for dev; bring Postgres/etc. for production). */
  type NonStoragePorts =
    OAuthConfig & TenantResolver & AdmissionPolicy
      & SigningKeySource & UpstreamIdP & RedirectUriValidator

  /** Select every non-storage port from `OAUTH_*` env, each resolving to its
    * real adapter when configured, else the dev stub (which [[audit]] refuses
    * in production). Mirrors the MCP `ConfigDrivenLayers` backend selector.
    *
    * Needs a `zio.http.Client` for the upstream OIDC driver. Compose with your
    * own [[OAuthStorage]] to get full [[Ports]]:
    * {{{ storageLayer ++ (Client.default >>> OAuthLayers.fromEnv) }}}
    * or let the `fishy-mcp-oauth` glue's `withOAuthFromEnv` wire it for you.
    *
    *   - upstream IdP : `OAUTH_UPSTREAM_ISSUER` set -> GenericOidcUpstreamIdP, else NullUpstreamIdP
    *   - signing key  : `OAUTH_SIGNING_KEY_PATH` set -> DiskRsaSigningKeySource, else generated
    *   - admission    : `OAUTH_ADMISSION_EMAIL_DOMAINS` set -> email allowlist, else AllowAll
    */
  val fromEnv: ZLayer[Client, Config.Error, NonStoragePorts] =
    ZLayer.fromZIO(ZIO.config(envSetup)).flatMap { resolved =>
      val s = resolved.get[EnvSetup]

      val upstream: URLayer[Client, UpstreamIdP] =
        s.upstream.fold(NullUpstreamIdP.layer)((cfg, _) => GenericOidcUpstreamIdP.layer(cfg))

      val signing: ULayer[SigningKeySource] =
        s.signing.fold(RsaSigningKeySource.generated)(cfg => DiskRsaSigningKeySource.layer(cfg).orDie)

      val admission: ULayer[AdmissionPolicy] =
        s.admissionDomains.fold(ZLayer.succeed[AdmissionPolicy](AllowAllAdmission))(domains =>
          ZLayer.succeed[AdmissionPolicy](EmailDomainAllowlistAdmission(domains))
        )

      ZLayer.makeSome[Client, NonStoragePorts](
        ZLayer.succeed(s.base),
        SingleTenantResolver.layer(tenantFor(s)),
        admission,
        signing,
        upstream,
        DefaultRedirectUriValidator.layer
      )
    }

  private final case class UpstreamCreds(clientId: String, clientSecret: String)

  private final case class EnvSetup(
      base: OAuthConfig,
      upstream: Option[(GenericOidcUpstreamIdP.Config, UpstreamCreds)],
      signing: Option[DiskRsaSigningKeySource.Config],
      admissionDomains: Option[Set[String]]
  )

  private val envSetup: Config[EnvSetup] =
    val creds = Config.string("OAUTH_UPSTREAM_CLIENT_ID")
      .zip(Config.string("OAUTH_UPSTREAM_CLIENT_SECRET"))
      .map(UpstreamCreds.apply)
    OAuthConfig.config
      .zip((GenericOidcUpstreamIdP.Config.config zip creds).optional)
      .zip(DiskRsaSigningKeySource.Config.config.optional)
      .zip(Config.chunkOf("OAUTH_ADMISSION_EMAIL_DOMAINS", Config.string).map(_.toSet).optional)
      .map { case (base, upstream, signing, domains) => EnvSetup(base, upstream, signing, domains) }

  private def tenantFor(s: EnvSetup): Tenant =
    val hostname =
      scala.util.Try(java.net.URI(s.base.issuer).getHost).toOption.flatMap(Option(_)).getOrElse("localhost")
    val idp = s.upstream match
      case Some((cfg, creds)) =>
        TenantIdpConfig.Oidc(cfg.issuer, creds.clientId, creds.clientSecret, cfg.scopes)
      case None =>
        TenantIdpConfig.Oidc("https://stub.invalid", "dev-stub", "dev-stub")
    val admission = s.admissionDomains match
      case Some(d) => AdmissionSpec("email-allowlist", Map("domains" -> d.mkString(",")))
      case None    => AdmissionSpec("allow-all", Map.empty)
    Tenant(Ids.TenantId("default"), hostname, idp, admission)
