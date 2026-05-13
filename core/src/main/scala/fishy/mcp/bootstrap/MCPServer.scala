package fishy.mcp.bootstrap

import fishy.mcp.adapters.inbound.http.HttpTransport
import fishy.mcp.adapters.inbound.http.{
  AuthConfig,
  HttpExtraRoutes,
  HttpSecurityPolicy,
  JwtSecurityPolicy,
  TrustedHeaderPolicy
}
import fishy.mcp.adapters.inbound.stdio.StdioTransport
import fishy.mcp.domain.model.mcp.{
  PromptsCapability,
  ResourcesCapability,
  ServerCapabilities,
  ServerInfo,
  ToolsCapability
}
import fishy.mcp.adapters.storage.{BackendConfig, ConfigDrivenLayers, InMemorySubscriptionRegistry}
import fishy.mcp.application.ports.{
  EventReplay,
  MessageRouter,
  PromptRegistry,
  ResourceRegistry,
  SessionHooks,
  SessionStore,
  SubscriptionRegistry,
  ToolRegistry
}
import fishy.mcp.application.ports.oauth.OAuthConfig
import fishy.mcp.application.usecase.{
  ClientRequester,
  McpDispatcher,
  NotificationSender,
  PromptExecutor,
  ResourceExecutor,
  ToolExecutor
}
import fishy.mcp.bootstrap.config.{DeploymentConfig, TracingConfig}
import fishy.mcp.bootstrap.http.HttpSecurityLayers
import fishy.mcp.bootstrap.oauth.{OAuthFeature, OAuthLayers}
import fishy.mcp.domain.model.{Prompt, Resource, Tool}
import scala.annotation.targetName
import scala.util.NotGiven
import zio.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

/** Top-level builder for an MCP server.
  *
  * A `MCPServer[R]` carries the user-declared tools/resources/prompts plus
  * the wiring choices for HTTP security, OAuth, session hooks, and tool-call
  * eventing. It is converted to a runnable program by [[serveHttp]] or
  * [[serveStdio]], or to a layer graph by [[buildLayers]] for advanced
  * wiring.
  *
  * Feature-specific layer factories live in:
  *
  *   - [[fishy.mcp.bootstrap.LoggingLayers]] -- console JSON loggers.
  *   - [[fishy.mcp.bootstrap.http.HttpSecurityLayers]] -- auth policies.
  *   - [[fishy.mcp.bootstrap.oauth.OAuthFeature]] -- OAuth route mounting.
  *   - [[fishy.mcp.bootstrap.oauth.OAuthLayers]] -- OAuth port aggregation.
  *
  * Configuration is read once at the entry point via [[AppConfig.load]];
  * downstream layers consume resolved case classes via `ZIO.service[...]`.
  */
final case class MCPServer[R] private (
    name: String,
    version: String,
    tools: List[Tool[R]],
    resources: List[Resource[R]],
    prompts: List[Prompt[R]],
    httpSecurityPolicyLayer: URLayer[AuthConfig, HttpSecurityPolicy],
    sessionHooksLayer: ULayer[SessionHooks],
    httpExtraRoutesLayer: URLayer[R & DeploymentConfig, HttpExtraRoutes],
    publishToolCallEvents: Boolean,
    instructions: Option[String] = None
):

  // ---------------------------------------------------------------------------
  // Tool / resource / prompt registration
  // ---------------------------------------------------------------------------

  @targetName("withAnyTools")
  def withTools(newTools: Tool[Any]*): MCPServer[R] =
    copy(tools = tools ++ newTools.toList.asInstanceOf[List[Tool[R]]])

  def withTools[R1](newTools: Tool[R1]*)(using NotGiven[R1 =:= Nothing]): MCPServer[R & R1] =
    val existing: List[Tool[R & R1]] = tools
    val added: List[Tool[R & R1]] = newTools.toList
    MCPServer(name, version, existing ++ added, resources, prompts,
      httpSecurityPolicyLayer, sessionHooksLayer, httpExtraRoutesLayer, publishToolCallEvents)

  @targetName("withAnyResources")
  def withResources(newResources: Resource[Any]*): MCPServer[R] =
    copy(resources = resources ++ newResources.toList.asInstanceOf[List[Resource[R]]])

  def withResources[R1](newResources: Resource[R1]*)(using NotGiven[R1 =:= Nothing]): MCPServer[R & R1] =
    val existing: List[Resource[R & R1]] = resources
    val added: List[Resource[R & R1]] = newResources.toList
    MCPServer(name, version, tools, existing ++ added, prompts,
      httpSecurityPolicyLayer, sessionHooksLayer, httpExtraRoutesLayer, publishToolCallEvents)

  @targetName("withAnyPrompts")
  def withPrompts(newPrompts: Prompt[Any]*): MCPServer[R] =
    copy(prompts = prompts ++ newPrompts.toList.asInstanceOf[List[Prompt[R]]])

  def withPrompts[R1](newPrompts: Prompt[R1]*)(using NotGiven[R1 =:= Nothing]): MCPServer[R & R1] =
    val existing: List[Prompt[R & R1]] = prompts
    val added: List[Prompt[R & R1]] = newPrompts.toList
    MCPServer(name, version, tools, resources, existing ++ added,
      httpSecurityPolicyLayer, sessionHooksLayer, httpExtraRoutesLayer, publishToolCallEvents)

  // ---------------------------------------------------------------------------
  // Identity
  // ---------------------------------------------------------------------------

  def withName(newName: String): MCPServer[R] = copy(name = newName)
  def withVersion(newVersion: String): MCPServer[R] = copy(version = newVersion)
  def withInstructions(text: String): MCPServer[R] = copy(instructions = Some(text))

  // ---------------------------------------------------------------------------
  // HTTP security -- delegates to bootstrap.http.HttpSecurityLayers
  // ---------------------------------------------------------------------------

  def withHttpSecurityPolicy(layer: URLayer[AuthConfig, HttpSecurityPolicy]): MCPServer[R] =
    copy(httpSecurityPolicyLayer = layer)

  def withJwtAuth(config: JwtSecurityPolicy.Config): MCPServer[R] =
    copy(httpSecurityPolicyLayer = HttpSecurityLayers.jwt(config))

  def withTrustedHeaders(
      mapping: TrustedHeaderPolicy.HeaderMapping = TrustedHeaderPolicy.defaultMapping
  ): MCPServer[R] =
    copy(httpSecurityPolicyLayer = HttpSecurityLayers.trusted(mapping))

  /** Select auth policy from environment variables (`AUTH_MODE` + `JWT_*`). */
  def withConfigDrivenAuth(): MCPServer[R] =
    copy(httpSecurityPolicyLayer = HttpSecurityLayers.configDriven)

  // ---------------------------------------------------------------------------
  // Session hooks + tool eventing
  // ---------------------------------------------------------------------------

  def withSessionHooks(hooks: SessionHooks): MCPServer[R] =
    copy(sessionHooksLayer = ZLayer.succeed(hooks))

  def withSessionHooks(layer: ULayer[SessionHooks]): MCPServer[R] =
    copy(sessionHooksLayer = layer)

  def withToolCallEvents: MCPServer[R] =
    copy(publishToolCallEvents = true)

  // ---------------------------------------------------------------------------
  // OAuth -- delegates to bootstrap.oauth.OAuthFeature
  // ---------------------------------------------------------------------------

  /** Built-in OAuth 2.1 AS with in-memory reference adapters. Dev only. */
  def withOAuth(config: OAuthConfig): MCPServer[R] =
    copy(httpExtraRoutesLayer = OAuthFeature.inMemory(config))

  /** Built-in OAuth 2.1 AS with deployer-supplied port stack.
    *
    * The deployer supplies only `OAuthLayers.Ports`; the SDK use-cases are
    * stacked internally. `config` is unused by the routes themselves -- it's
    * accepted here only to keep the call shape parallel with `withOAuth(config)`.
    */
  def withOAuth(config: OAuthConfig, ports: ULayer[OAuthLayers.Ports]): MCPServer[R] =
    copy(httpExtraRoutesLayer = OAuthFeature.withCustomLayers(ports))

  /** Mount OAuth endpoints, defer port wiring to the `serveHttp.provide`
    * boundary. The resulting `MCPServer[R & OAuthLayers.Ports]` requires the
    * deployer to provide one layer per port (or one bundled layer like
    * `PostgresOAuthStorage.layer`); use-cases are wired by the SDK.
    */
  def withCustomOAuth: MCPServer[R & OAuthLayers.Ports] =
    type R2 = R & OAuthLayers.Ports
    MCPServer[R2](
      name, version,
      tools.asInstanceOf[List[Tool[R2]]],
      resources.asInstanceOf[List[Resource[R2]]],
      prompts.asInstanceOf[List[Prompt[R2]]],
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      OAuthFeature.asEnvRequirement,
      publishToolCallEvents,
      instructions
    )

  // ---------------------------------------------------------------------------
  // Layer composition
  // ---------------------------------------------------------------------------

  def serverInfo: ServerInfo = ServerInfo(name, version)

  /** Compose every layer the SDK provides into a single graph rooted at `R`.
    *
    * Inputs: `R` (from user code) plus `BackendConfig & AuthConfig & TracingConfig`
    * (sourced from `AppConfig.load` at the entry point).
    */
  def buildLayers: ZLayer[
    R & BackendConfig & AuthConfig & TracingConfig & DeploymentConfig,
    Nothing,
    ToolRegistry & ResourceRegistry & PromptRegistry & McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender & ClientRequester & HttpSecurityPolicy & SessionHooks & SubscriptionRegistry & ServerCapabilities & HttpExtraRoutes & Tracing & ContextStorage
  ] =
    val capabilities = ServerCapabilities(
      tools = if tools.nonEmpty then Some(ToolsCapability(listChanged = Some(true))) else None,
      resources =
        if resources.nonEmpty then Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true)))
        else None,
      prompts = if prompts.nonEmpty then Some(PromptsCapability(listChanged = Some(true))) else None
    )

    // Registry layers capture R at construction time, erasing it from the output.
    val registries: ZLayer[R, Nothing, ToolRegistry & ResourceRegistry & PromptRegistry] =
      ToolRegistry.layer(tools) ++ ResourceRegistry.layer(resources) ++ PromptRegistry.layer(prompts)

    // Remaining services are R-free and macro-wireable. HttpExtraRoutes is composed
    // separately because its layer may require R (e.g. withCustomOAuth defers OAuth
    // port implementations to the application boundary).
    val services = ZLayer.makeSome[
      ToolRegistry & ResourceRegistry & PromptRegistry & BackendConfig & AuthConfig & TracingConfig,
      McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender & ClientRequester & HttpSecurityPolicy & SessionHooks & SubscriptionRegistry & ServerCapabilities & Tracing & ContextStorage
    ](
      ToolExecutor.layerWith(publishToolCallEvents),
      ResourceExecutor.layer,
      PromptExecutor.layer,
      McpDispatcher.layer(serverInfo, capabilities, instructions),
      ZLayer.succeed(capabilities),
      ConfigDrivenLayers.live,
      InMemorySubscriptionRegistry.layer,
      NotificationSender.layer,
      ClientRequester.layer,
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      TracingLayers.live
    )

    val core = registries >>> (services ++ ZLayer.environment[ToolRegistry & ResourceRegistry & PromptRegistry])
    core ++ httpExtraRoutesLayer

  // ---------------------------------------------------------------------------
  // Entry points
  // ---------------------------------------------------------------------------

  def serveStdio: ZIO[R, Throwable, Unit] =
    AppConfig.load.flatMap { cfg =>
      val configs = ZLayer.succeed(cfg.backend) ++ ZLayer.succeed(cfg.auth) ++
        ZLayer.succeed(cfg.tracing) ++ ZLayer.succeed(cfg.deployment)
      val layers = (configs >+> buildLayers) >>> StdioTransport.layer
      ZIO.scoped {
        LoggingLayers.stderrJson(cfg.log).build *>
          (ZIO.logInfo(s"Starting $name v$version on stdio") *>
            ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
            StdioTransport.run.provideSomeLayer[R](layers))
            .catchAllDefect { throwable =>
              ZIO.logErrorCause("FATAL: Unexpected defect in stdio server", Cause.die(throwable)) *>
                ZIO.die(throwable)
            }
      }
    }

  def serveHttp: ZIO[R, Throwable, Nothing] =
    AppConfig.load.flatMap { cfg =>
      val configs = ZLayer.succeed(cfg.backend) ++ ZLayer.succeed(cfg.auth) ++
        ZLayer.succeed(cfg.tracing) ++ ZLayer.succeed(cfg.deployment)
      val layers = (configs >+> buildLayers) >>> HttpTransport.layer
      ZIO.scoped {
        LoggingLayers.stdoutJson(cfg.log).build *>
          (ZIO.logInfo(s"Starting $name v$version on HTTP port ${cfg.server.port}") *>
            ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
            HttpTransport.serve(cfg.server.port).provideSomeLayer[R](layers))
            .catchAllDefect { throwable =>
              ZIO.logErrorCause("FATAL: Unexpected defect in HTTP server", Cause.die(throwable)) *>
                ZIO.die(throwable)
            }
      }
    }

object MCPServer:

  /** Default version stamped into the `serverInfo` payload when the user
    * doesn't call `withVersion(...)`. Kept in sync with `version.sbt` by
    * convention -- when bumping the SDK release, update both. (A `BuildInfo`
    * plugin would automate this; deliberately not adding one yet to keep
    * sbt plugin surface small.)
    */
  val DefaultVersion: String = "0.0.2-SNAPSHOT"

  def apply(): MCPServer[Any] =
    MCPServer(
      name = "fishy-mcp",
      version = DefaultVersion,
      tools = Nil,
      resources = Nil,
      prompts = Nil,
      httpSecurityPolicyLayer = HttpSecurityLayers.allowAll,
      sessionHooksLayer = SessionHooks.noOp,
      httpExtraRoutesLayer = HttpExtraRoutes.empty,
      publishToolCallEvents = false,
      instructions = None
    )

  @targetName("withAnyToolsFromCompanion")
  def withTools(tools: Tool[Any]*): MCPServer[Any] =
    MCPServer().withTools(tools*)

  def withTools[R](tools: Tool[R]*)(using NotGiven[R =:= Nothing]): MCPServer[R] =
    MCPServer().withTools(tools*)

  def withName(name: String): MCPServer[Any] =
    MCPServer().withName(name)
