package fishy.mcp.oauth

import fishy.mcp.adapters.inbound.http.HttpExtraRoutes
import fishy.mcp.adapters.inbound.http.oauth.OAuthRoutes
import fishy.mcp.adapters.outbound.oauth.tenant.SingleTenantResolver
import fishy.mcp.application.ports.oauth.{OAuthConfig, OAuthStorage}
import fishy.mcp.bootstrap.config.{DeploymentConfig, DeploymentProfile}
import fishy.mcp.bootstrap.oauth.OAuthLayers
import fishy.mcp.domain.model.oauth.{AdmissionSpec, Ids, Tenant, TenantIdpConfig}
import zio.*
import zio.http.Client

/** Builds the `HttpExtraRoutes` layer that mounts OAuth's metadata,
  * registration, authorize, callback, token, and revoke endpoints onto an MCP
  * server. Each flavor differs only in how the OAuth ports are sourced; the
  * shared [[mount]] tail runs the production stub-audit, stacks the SDK
  * use-cases, and mounts the routes.
  *
  *   - [[inMemory]] -- reference adapters, dev only (stub upstream IdP).
  *   - [[fromEnv]] -- production-leaning: every port except storage selected
  *     from `OAUTH_*` env; the deployer supplies only `OAuthStorage`.
  *   - [[withCustomLayers]] -- the deployer supplies a fully-wired port stack.
  *   - [[asEnvRequirement]] -- every port deferred to `serveHttp.provide`.
  */
object OAuthFeature:

  private val routes: URLayer[OAuthRoutes.Env, HttpExtraRoutes] =
    HttpExtraRoutes.layer[OAuthRoutes.Env](OAuthRoutes.all)

  /** Run the production stub-audit (derived from the MCP deployment profile) as
    * a build-time side effect. Not auto-wireable -- nothing consumes its `Unit`
    * output -- so [[mount]] threads it with `>+>`. */
  private val auditLayer: URLayer[OAuthLayers.Ports & DeploymentConfig, Unit] =
    ZLayer.fromZIO(
      ZIO
        .serviceWith[DeploymentConfig](_.profile == DeploymentProfile.Production)
        .flatMap(OAuthLayers.audit)
    )

  /** The one shared tail behind every flavor: audit the resolved ports, stack
    * the use-cases on them, and mount the routes. */
  private def mount[In](
      ports: ZLayer[In, Nothing, OAuthLayers.Ports]
  ): URLayer[In & DeploymentConfig, HttpExtraRoutes] =
    (ports >+> auditLayer >+> OAuthLayers.useCases) >>> routes

  /** In-memory reference bundle. Dev only -- restart loses every record, and
    * `MCP_PROFILE=production` refuses to boot (every port is a stub). */
  def inMemory(config: OAuthConfig): URLayer[DeploymentConfig, HttpExtraRoutes] =
    mount(OAuthLayers.inMemoryPorts(config, SingleTenantResolver.layer(defaultTenant(config))))

  /** Production-leaning: select every port except storage from `OAUTH_*` env
    * (real adapter when configured, else the dev stub the audit refuses),
    * provide the upstream OIDC driver's HTTP `Client`, and defer only
    * [[OAuthStorage]] to the `serveHttp.provide` boundary. */
  val fromEnv: URLayer[OAuthStorage & DeploymentConfig, HttpExtraRoutes] =
    mount(
      ZLayer.makeSome[OAuthStorage, OAuthLayers.Ports](
        Client.default.orDie,
        OAuthLayers.fromEnv.orDie
      )
    )

  /** Mount atop a deployer-supplied port stack; the SDK use-cases are stacked
    * internally and stay out of the deployer's API surface. */
  def withCustomLayers(ports: ULayer[OAuthLayers.Ports]): URLayer[DeploymentConfig, HttpExtraRoutes] =
    mount(ports)

  /** Defer every OAuth port to the `serveHttp.provide` boundary. */
  val asEnvRequirement: URLayer[OAuthLayers.Ports & DeploymentConfig, HttpExtraRoutes] =
    mount(ZLayer.environment[OAuthLayers.Ports])

  /** The default tenant used by [[inMemory]]. Hostname from the issuer; OIDC
    * credentials blank -- a deliberate dev footgun, since this bundle ships no
    * real upstream IdP. */
  private def defaultTenant(config: OAuthConfig): Tenant =
    Tenant(
      id = Ids.TenantId("default"),
      hostname = java.net.URI.create(config.issuer).getHost,
      idp = TenantIdpConfig.Oidc(issuer = config.issuer, oidcClientId = "", clientSecret = ""),
      admission = AdmissionSpec("allow-all", Map.empty)
    )
