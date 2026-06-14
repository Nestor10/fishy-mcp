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
