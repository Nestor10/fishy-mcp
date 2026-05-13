package fishy.mcp.bootstrap.oauth

import fishy.mcp.adapters.inbound.http.HttpExtraRoutes
import fishy.mcp.adapters.inbound.http.oauth.OAuthRoutes
import fishy.mcp.adapters.outbound.oauth.tenant.SingleTenantResolver
import fishy.mcp.application.ports.oauth.OAuthConfig
import fishy.mcp.bootstrap.config.DeploymentConfig
import fishy.mcp.domain.model.oauth.{AdmissionSpec, Ids, Tenant, TenantIdpConfig}
import zio.*

/** Builds the `HttpExtraRoutes` layer that mounts OAuth's metadata,
  * registration, authorize, callback, token, and revoke endpoints.
  *
  * Three flavors:
  *
  *   - [[inMemory]] -- the SDK's reference adapters. Dev only. Wires every
  *     OAuth port from [[OAuthLayers.inMemoryPorts]] using a single-tenant
  *     resolver derived from `config.issuer`. The default OIDC client_id /
  *     client_secret are blank strings -- intentionally unusable, since this
  *     bundle ships no real upstream IdP.
  *   - [[withCustomLayers]] -- production. The deployer supplies a fully
  *     wired `ULayer[OAuthLayers.OAuthEnv]` (Postgres storage, real IdP,
  *     etc.); this just mounts the routes on top.
  *   - [[asEnvRequirement]] -- defers every OAuth port to the application
  *     boundary so the deployer wires them via the usual `serveHttp.provide`
  *     call. Useful when the deployer wants per-port composition.
  *
  * All three return a layer that goes into `MCPServer.httpExtraRoutesLayer`.
  */
object OAuthFeature:

  /** Layer that runs [[OAuthLayers.audit]] as a side effect at build time.
    * Composed into every OAuth-mounting flow so the production stub gate
    * fires regardless of which `with*OAuth` method the deployer used.
    */
  private val auditLayer: URLayer[OAuthLayers.Ports & DeploymentConfig, Unit] =
    ZLayer.fromZIO(OAuthLayers.audit)

  /** In-memory reference bundle. Dev only -- restart loses every record.
    *
    * Builds a single-tenant resolver from the configured issuer with the
    * `oidcClientId` / `clientSecret` left blank. Deployments that want a
    * real upstream IdP must use [[withCustomLayers]] or [[asEnvRequirement]].
    *
    * The audit will refuse to boot under `MCP_PROFILE=production` because
    * every port in this bundle is a [[fishy.mcp.application.ports.oauth.StubMarker]].
    */
  def inMemory(config: OAuthConfig): URLayer[DeploymentConfig, HttpExtraRoutes] =
    val tenantLayer = SingleTenantResolver.layer(defaultTenant(config))
    val ports       = OAuthLayers.inMemory(config, tenantLayer)
    (ports >+> auditLayer >+> OAuthLayers.useCases) >>>
      HttpExtraRoutes.layer[OAuthRoutes.Env](OAuthRoutes.all)

  /** Mount OAuth routes atop a deployer-supplied port stack.
    *
    * The deployer supplies only [[OAuthLayers.Ports]] -- the use-cases get
    * stacked automatically via [[OAuthLayers.useCases]]. SDK internals
    * (`ExchangeAuthorizationCode`, `RefreshTokens`, `TokenIssuer`, etc.) are
    * not part of the deployer's API surface. Audit fires automatically.
    */
  def withCustomLayers(ports: ULayer[OAuthLayers.Ports]): URLayer[DeploymentConfig, HttpExtraRoutes] =
    (ports >+> auditLayer >+> OAuthLayers.useCases) >>>
      HttpExtraRoutes.layer[OAuthRoutes.Env](OAuthRoutes.all)

  /** Mount OAuth routes while requiring the deployer to provide every OAuth
    * port at the `serveHttp.provide` boundary. The resulting layer surfaces
    * [[OAuthLayers.Ports]] as the environment requirement -- use-cases are
    * stacked internally and stay out of the deployer's R type. Audit fires
    * automatically.
    */
  val asEnvRequirement: URLayer[OAuthLayers.Ports & DeploymentConfig, HttpExtraRoutes] =
    auditLayer >+> OAuthLayers.useCases >>>
      HttpExtraRoutes.layer[OAuthRoutes.Env](OAuthRoutes.all)

  /** The default tenant used by [[inMemory]]. Hostname comes from the
    * configured issuer; OIDC client credentials are blank, which is a
    * deliberate footgun for dev: the bundle ships no real IdP.
    */
  private def defaultTenant(config: OAuthConfig): Tenant =
    Tenant(
      id = Ids.TenantId("default"),
      hostname = java.net.URI.create(config.issuer).getHost,
      idp = TenantIdpConfig.Oidc(
        issuer = config.issuer,
        oidcClientId = "",
        clientSecret = ""
      ),
      admission = AdmissionSpec("allow-all", Map.empty)
    )
