package fishy.mcp.bootstrap

import fishy.mcp.adapters.inbound.http.AuthConfig
import fishy.mcp.adapters.storage.BackendConfig
import fishy.mcp.application.ports.oauth.OAuthConfig
import fishy.mcp.bootstrap.config.{
  DeploymentConfig,
  DeploymentProfile,
  HttpServerConfig,
  LoggingConfig,
  TracingConfig
}
import zio.*

/** Aggregate of every config the SDK and its bundled adapters know how to
  * read. The single read point is [[AppConfig.load]] -- one effectful call
  * at startup, then everything downstream consumes resolved case classes
  * via `ZIO.service[...]`.
  *
  * `oauth` is gated on `OAUTH_ENABLED`:
  *   - `OAUTH_ENABLED=false` (default) -- `oauth` resolves to `None`. Any
  *     stray `OAUTH_*` env vars are ignored.
  *   - `OAUTH_ENABLED=true` -- every required `OAUTH_*` var is mandatory; a
  *     missing one fails startup with a clear error. This eliminates the
  *     silent-disable trap (e.g. typo'd `OAUTH_ISSUR=...` no longer pretends
  *     OAuth is off).
  */
final case class AppConfig(
    server: HttpServerConfig,
    log: LoggingConfig,
    tracing: TracingConfig,
    backend: BackendConfig,
    auth: AuthConfig,
    oauth: Option[OAuthConfig],
    deployment: DeploymentConfig
)

object AppConfig:

  /** OAuth slot: gated by `OAUTH_ENABLED`. When the gate is true, missing
    * required `OAUTH_*` vars become a hard `Config.Error.MissingData`; when
    * false, the inner descriptor is not even read.
    */
  private val oauthSlot: Config[Option[OAuthConfig]] = (
    Config.boolean("OAUTH_ENABLED").withDefault(false) zip
    OAuthConfig.config.optional
  ).mapOrFail {
    case (true, Some(cfg)) => Right(Some(cfg))
    case (true, None) =>
      Left(Config.Error.MissingData(
        Chunk("OAUTH_*"),
        "OAUTH_ENABLED=true requires OAUTH_ISSUER and OAUTH_RESOURCE; the rest default. " +
          "Set every required var, or unset OAUTH_ENABLED to disable OAuth."
      ))
    case (false, _) => Right(None)
  }

  val config: Config[AppConfig] = (
    HttpServerConfig.config zip
    LoggingConfig.config zip
    TracingConfig.config zip
    BackendConfig.config zip
    AuthConfig.config zip
    oauthSlot zip
    DeploymentConfig.config
  ).map(AppConfig.apply)

  /** Single read point. Reads the env once, then runs the safety audit
    * ([[audit]]) which fails fast in production on dev-only adapter
    * defaults.
    */
  val load: IO[Config.Error, AppConfig] =
    ZIO.config(config).tap(audit)

  /** Production safety gate.
    *
    *   - `MCP_PROFILE=production` + `BackendConfig.InMemory` -> fails.
    *     `MCP_PROFILE=production` + `AuthConfig.AllowAll` -> fails.
    *   - In any profile, the selected backend and auth mode are logged at
    *     INFO; dev-only choices log at WARN so operators see them in stdout
    *     during a deploy.
    *
    * Intentionally only audits config-driven knobs; the OAuth wiring stubs
    * (`NullUpstreamIdP`, `AllowAllAdmission`) are wired by code at layer
    * construction time and are not visible from a config-level check.
    */
  def audit(cfg: AppConfig): IO[Config.Error, Unit] =
    val isProd = cfg.deployment.profile == DeploymentProfile.Production

    val backendCheck = cfg.backend match
      case BackendConfig.InMemory =>
        if isProd then
          ZIO.fail(Config.Error.InvalidData(
            Chunk("MCP_STATELESS"),
            "Production deployments must set REDIS_URL or MCP_STATELESS=true; the in-memory backend is dev-only and loses every session on restart."
          ))
        else
          ZIO.logWarning(
            "Using InMemoryBackend -- sessions, refresh tokens and client registrations will be lost on restart. Set REDIS_URL or MCP_STATELESS=true for production."
          )
      case BackendConfig.Stateless =>
        ZIO.logInfo("Selected backend: Stateless (no session state)")
      case BackendConfig.Redis(url) =>
        ZIO.logInfo(s"Selected backend: Redis ($url)")

    val authCheck = cfg.auth match
      case AuthConfig.AllowAll =>
        if isProd then
          ZIO.fail(Config.Error.InvalidData(
            Chunk("AUTH_MODE"),
            "Production deployments must set AUTH_MODE=jwt or AUTH_MODE=trusted; allow-all is dev-only and exposes the MCP surface to anonymous clients."
          ))
        else
          ZIO.logWarning(
            "Using AUTH_MODE=allow_all -- no authentication. Set AUTH_MODE=jwt or trusted for production."
          )
      case AuthConfig.Trusted =>
        ZIO.logInfo("Selected auth: Trusted (upstream proxy headers)")
      case AuthConfig.Jwt(_, iss, aud, _, _, _, _) =>
        ZIO.logInfo(s"Selected auth: JWT (issuer=$iss, audience=$aud)")

    val profileLog =
      ZIO.logInfo(s"Deployment profile: ${cfg.deployment.profile}")

    profileLog *> backendCheck *> authCheck

  /** One-shot read from env exposed as a layer that spreads each sub-config
    * for downstream consumption. Use this when wiring `MCPServer.buildLayers`
    * directly via `ZLayer.make` (e.g. the SSE example).
    *
    * The single `ZIO.config` call is what makes this layer-friendly without
    * violating CLAUDE.md's "no per-component config layers" rule -- it's the
    * one bootstrap-level read point, not a hidden side effect inside a
    * component.
    */
  val layer
      : ZLayer[Any, Config.Error, AppConfig & HttpServerConfig & LoggingConfig & TracingConfig & BackendConfig & AuthConfig & DeploymentConfig] =
    ZLayer.fromZIO(load).flatMap { env =>
      val cfg = env.get[AppConfig]
      ZLayer.succeed(cfg) ++
        ZLayer.succeed(cfg.server) ++
        ZLayer.succeed(cfg.log) ++
        ZLayer.succeed(cfg.tracing) ++
        ZLayer.succeed(cfg.backend) ++
        ZLayer.succeed(cfg.auth) ++
        ZLayer.succeed(cfg.deployment)
    }

  /** Defaults suitable for in-process tests and local examples: in-memory
    * backend, allow-all auth, no tracing, Dev profile. Compose into your test
    * harness to satisfy `MCPServer.buildLayers`'s config inputs.
    */
  val testDefaults: ULayer[BackendConfig & AuthConfig & TracingConfig & DeploymentConfig] =
    ZLayer.succeed(BackendConfig.InMemory) ++
      ZLayer.succeed(AuthConfig.AllowAll) ++
      ZLayer.succeed(TracingConfig(otlpEndpoint = None, serviceName = "test")) ++
      ZLayer.succeed(DeploymentConfig(DeploymentProfile.Dev))
