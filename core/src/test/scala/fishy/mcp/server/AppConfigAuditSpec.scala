package fishy.mcp.server

import fishy.mcp.adapters.inbound.http.AuthConfig
import fishy.mcp.adapters.storage.BackendConfig
import fishy.mcp.bootstrap.AppConfig
import fishy.mcp.bootstrap.config.{
  DeploymentConfig,
  DeploymentProfile,
  HttpServerConfig,
  LoggingConfig,
  TracingConfig
}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object AppConfigGateSpec extends ZIOSpecDefault:

  /** Run the AppConfig descriptor against an explicit env Map. Bypasses the
    * real process env so tests are deterministic.
    */
  private def loadWith(env: Map[String, String]): IO[Config.Error, AppConfig] =
    ZIO.config(AppConfig.config).withConfigProvider(ConfigProvider.fromMap(env))

  def spec = suite("AppConfig OAUTH_ENABLED gate")(
    test("OAUTH_ENABLED unset -> oauth = None even with stray OAUTH_ISSUER") {
      for
        cfg <- loadWith(Map("OAUTH_ISSUER" -> "https://typo"))
      yield assertTrue(cfg.oauth.isEmpty)
    },
    test("OAUTH_ENABLED=true with missing OAUTH_ISSUER fails loudly") {
      loadWith(Map("OAUTH_ENABLED" -> "true")).exit
        .map(exit => assert(exit)(fails(isSubtype[Config.Error.MissingData](anything))))
    },
    test("OAUTH_ENABLED=true with all required vars succeeds") {
      for
        cfg <- loadWith(Map(
                 "OAUTH_ENABLED"  -> "true",
                 "OAUTH_ISSUER"   -> "https://idp",
                 "OAUTH_RESOURCE" -> "https://api"
               ))
      yield assertTrue(
        cfg.oauth.isDefined,
        cfg.oauth.get.issuer == "https://idp",
        cfg.oauth.get.resource == "https://api"
      )
    },
    test("OAUTH_ENABLED=false ignores OAUTH_* even when all set") {
      for
        cfg <- loadWith(Map(
                 "OAUTH_ENABLED"  -> "false",
                 "OAUTH_ISSUER"   -> "https://idp",
                 "OAUTH_RESOURCE" -> "https://api"
               ))
      yield assertTrue(cfg.oauth.isEmpty)
    }
  )

object OAuthLayersStubAuditSpec extends ZIOSpecDefault:

  import fishy.mcp.application.ports.oauth.OAuthConfig
  import fishy.mcp.adapters.outbound.oauth.tenant.SingleTenantResolver
  import fishy.mcp.domain.model.oauth.{AdmissionSpec, Ids, Tenant, TenantIdpConfig}
  import fishy.mcp.bootstrap.oauth.OAuthLayers
  import zio.LogLevel

  private val sampleConfig = OAuthConfig(
    issuer = "https://test", resource = "https://test",
    accessTokenTtl = 15.minutes, refreshTokenTtl = 30.days,
    authorizationCodeTtl = 60.seconds, scopesSupported = List("mcp:use"),
    audience = "test"
  )

  private val sampleTenant = SingleTenantResolver.layer(Tenant(
    id = Ids.TenantId("t"), hostname = "test",
    idp = TenantIdpConfig.Oidc("https://test", "", ""),
    admission = AdmissionSpec("allow-all", Map.empty)
  ))

  private val inMemoryPorts = OAuthLayers.inMemoryPorts(sampleConfig, sampleTenant)

  def spec = suite("OAuthLayers.audit (layer-level stub gate)")(
    test("dev profile + in-memory stubs logs warning, succeeds") {
      OAuthLayers.audit
        .provide(inMemoryPorts, ZLayer.succeed(DeploymentConfig(DeploymentProfile.Dev)))
        .as(assertCompletes)
    },
    test("production profile + in-memory stubs dies with refusal") {
      OAuthLayers.audit
        .provide(inMemoryPorts, ZLayer.succeed(DeploymentConfig(DeploymentProfile.Production)))
        .exit
        .map(exit => assert(exit)(dies(hasMessage(containsString("OAuth stub adapter")))))
    }
  )

/** Locks the production safety gate: in `production` mode, dev-only adapter
  * defaults must refuse to boot.
  */
object AppConfigAuditSpec extends ZIOSpecDefault:

  private def cfg(
      profile: DeploymentProfile,
      backend: BackendConfig = BackendConfig.Redis("redis://localhost:6379"),
      auth: AuthConfig = AuthConfig.Trusted
  ): AppConfig =
    AppConfig(
      server     = HttpServerConfig(8080),
      log        = LoggingConfig(LogLevel.Info),
      tracing    = TracingConfig(otlpEndpoint = None, serviceName = "test"),
      backend    = backend,
      auth       = auth,
      oauth      = None,
      deployment = DeploymentConfig(profile)
    )

  def spec = suite("AppConfig.audit")(
    test("dev profile + InMemory backend logs warning, succeeds") {
      AppConfig.audit(cfg(DeploymentProfile.Dev, backend = BackendConfig.InMemory))
        .as(assertCompletes)
    },
    test("dev profile + AllowAll auth logs warning, succeeds") {
      AppConfig.audit(cfg(DeploymentProfile.Dev, auth = AuthConfig.AllowAll))
        .as(assertCompletes)
    },
    test("production profile + InMemory backend fails") {
      AppConfig
        .audit(cfg(DeploymentProfile.Production, backend = BackendConfig.InMemory))
        .exit
        .map(exit => assert(exit)(fails(isSubtype[Config.Error.InvalidData](anything))))
    },
    test("production profile + AllowAll auth fails") {
      AppConfig
        .audit(cfg(DeploymentProfile.Production, auth = AuthConfig.AllowAll))
        .exit
        .map(exit => assert(exit)(fails(isSubtype[Config.Error.InvalidData](anything))))
    },
    test("production profile + Redis backend + JWT auth succeeds") {
      AppConfig
        .audit(cfg(
          DeploymentProfile.Production,
          backend = BackendConfig.Redis("redis://prod:6379"),
          auth = AuthConfig.Jwt("https://idp/jwks", "https://idp", "mcp", "groups", "scp", None, None)
        ))
        .as(assertCompletes)
    },
    test("production profile + Stateless backend + Trusted auth succeeds") {
      AppConfig
        .audit(cfg(
          DeploymentProfile.Production,
          backend = BackendConfig.Stateless,
          auth = AuthConfig.Trusted
        ))
        .as(assertCompletes)
    }
  )
