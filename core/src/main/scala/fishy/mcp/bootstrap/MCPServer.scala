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
import fishy.mcp.adapters.storage.{BackendConfig, ConfigDrivenLayers}
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
  *
  * Feature modules (e.g. `fishy-mcp-oauth`) mount their own HTTP endpoints via
  * the public [[withHttpExtraRoutes]] seam.
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
  // Extra HTTP routes -- generic mount seam for feature modules
  // ---------------------------------------------------------------------------

  /** Replace the extra-HTTP-routes layer, widening the environment by `R1`.
    *
    * This is the public seam feature modules build on. `fishy-mcp-oauth`, for
    * example, ships a `withOAuth` extension that mounts the OAuth authorization
    * server's routes through here. Extra routes are mounted *before* the
    * security policy, so discovery/metadata endpoints stay unauthenticated.
    *
    * Use `R1 = Any` to mount routes that add no new environment requirement;
    * pass a concrete `R1` (e.g. a port bundle) to defer that wiring to the
    * `serveHttp.provide` boundary.
    */
  def withHttpExtraRoutes[R1](
      layer: URLayer[R & R1 & DeploymentConfig, HttpExtraRoutes]
  ): MCPServer[R & R1] =
    MCPServer[R & R1](
      name,
      version,
      tools.asInstanceOf[List[Tool[R & R1]]],
      resources.asInstanceOf[List[Resource[R & R1]]],
      prompts.asInstanceOf[List[Prompt[R & R1]]],
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      layer,
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
    ToolRegistry & ResourceRegistry & PromptRegistry & McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender & ClientRequester & HttpSecurityPolicy & SessionHooks & SubscriptionRegistry & HttpExtraRoutes & Tracing & ContextStorage
  ] =
    val capabilities = ServerCapabilities(
      tools = if tools.nonEmpty then Some(ToolsCapability(listChanged = Some(true))) else None,
      resources =
        if resources.nonEmpty then Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true)))
        else None,
      prompts = if prompts.nonEmpty then Some(PromptsCapability(listChanged = Some(true))) else None
    )

    // Two values of the same service type can't be auto-wired separately, so the
    // SDK's built-in list_changed-on-connect hook is combined with the user's
    // here before wiring.
    val hooksLayer: ULayer[SessionHooks] =
      sessionHooksLayer.flatMap { env =>
        ZLayer.succeed(
          SessionHooks.combine(SessionHooks.listChangedOnConnect(capabilities), env.get[SessionHooks])
        )
      }

    // One macro-wired graph. The registry and extra-routes layers require `R`
    // (the user's tool environment), which is supplied as part of the input.
    ZLayer.makeSome[
      R & BackendConfig & AuthConfig & TracingConfig & DeploymentConfig,
      ToolRegistry & ResourceRegistry & PromptRegistry & McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender & ClientRequester & HttpSecurityPolicy & SessionHooks & SubscriptionRegistry & HttpExtraRoutes & Tracing & ContextStorage
    ](
      ToolRegistry.layer(tools),
      ResourceRegistry.layer(resources),
      PromptRegistry.layer(prompts),
      ToolExecutor.layerWith(publishToolCallEvents),
      ResourceExecutor.layer,
      PromptExecutor.layer,
      McpDispatcher.layer(serverInfo, capabilities, instructions),
      ConfigDrivenLayers.live,
      NotificationSender.layer,
      ClientRequester.layer,
      httpSecurityPolicyLayer,
      hooksLayer,
      TracingLayers.live,
      httpExtraRoutesLayer
    )

  // ---------------------------------------------------------------------------
  // Entry points
  // ---------------------------------------------------------------------------

  /** The resolved sub-configs, spread as one layer for the entry points. */
  private def configLayer(
      cfg: AppConfig
  ): ULayer[BackendConfig & AuthConfig & TracingConfig & DeploymentConfig] =
    ZLayer.succeed(cfg.backend) ++ ZLayer.succeed(cfg.auth) ++
      ZLayer.succeed(cfg.tracing) ++ ZLayer.succeed(cfg.deployment)

  def serveStdio: ZIO[R, Throwable, Unit] =
    AppConfig.load.flatMap { cfg =>
      ZIO.scoped {
        LoggingLayers.stderrJson(cfg.log).build *>
          (ZIO.logInfo(s"Starting $name v$version on stdio") *>
            ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
            StdioTransport.run)
            .provideSome[R](configLayer(cfg), buildLayers, StdioTransport.layer)
            .catchAllDefect { throwable =>
              ZIO.logErrorCause("FATAL: Unexpected defect in stdio server", Cause.die(throwable)) *>
                ZIO.die(throwable)
            }
      }
    }

  def serveHttp: ZIO[R, Throwable, Nothing] =
    AppConfig.load.flatMap { cfg =>
      // Logger setup is handled by MCPApp.bootstrap (or the user's own
      // ZIOAppDefault.bootstrap); registering again here would double every line.
      ZIO.scoped {
        (ZIO.logInfo(s"Starting $name v$version on HTTP port ${cfg.server.port}") *>
          ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
          HttpTransport.serve(cfg.server.port))
          .provideSome[R](configLayer(cfg), buildLayers, HttpTransport.layer)
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

  @targetName("withAnyResourcesFromCompanion")
  def withResources(resources: Resource[Any]*): MCPServer[Any] =
    MCPServer().withResources(resources*)

  def withResources[R](resources: Resource[R]*)(using NotGiven[R =:= Nothing]): MCPServer[R] =
    MCPServer().withResources(resources*)

  @targetName("withAnyPromptsFromCompanion")
  def withPrompts(prompts: Prompt[Any]*): MCPServer[Any] =
    MCPServer().withPrompts(prompts*)

  def withPrompts[R](prompts: Prompt[R]*)(using NotGiven[R =:= Nothing]): MCPServer[R] =
    MCPServer().withPrompts(prompts*)

  def withName(name: String): MCPServer[Any] =
    MCPServer().withName(name)
