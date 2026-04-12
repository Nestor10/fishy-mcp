package fishy.mcp.bootstrap

import fishy.mcp.adapters.inbound.http.HttpTransport
import fishy.mcp.adapters.inbound.http.HttpSecurityPolicy
import fishy.mcp.adapters.inbound.http.{ConfigDrivenAuth, JwtSecurityPolicy, TrustedHeaderPolicy}
import fishy.mcp.adapters.inbound.stdio.StdioTransport
import fishy.mcp.adapters.protocol.mcp.{
  PromptsCapability,
  ResourcesCapability,
  ServerCapabilities,
  ServerInfo,
  ToolsCapability
}
import fishy.mcp.adapters.storage.{ConfigDrivenLayers, InMemorySubscriptionRegistry}
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
  McpDispatcher,
  ToolExecutor,
  ResourceExecutor,
  PromptExecutor,
  NotificationSender,
  ClientRequester
}
import fishy.mcp.domain.model.{Prompt, Resource, Tool}
import scala.annotation.targetName
import zio.*
import zio.logging.{
  ConsoleLoggerConfig,
  LogFilter,
  LogFormat,
  consoleJsonLogger,
  consoleErrJsonLogger
}
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import scala.util.NotGiven

import java.io.IOException

final case class MCPServer[R] private (
    name: String,
    version: String,
    tools: List[Tool[R]],
    resources: List[Resource[R]],
    prompts: List[Prompt[R]],
    httpSecurityPolicyLayer: ULayer[HttpSecurityPolicy],
    sessionHooksLayer: ULayer[SessionHooks],
    publishToolCallEvents: Boolean,
    instructions: Option[String] = None
):

  /** Add tools to the server. */
  @targetName("withAnyTools")
  def withTools(newTools: Tool[Any]*): MCPServer[R] =
    val existing: List[Tool[R]] = tools
    val added: List[Tool[R]] = newTools.toList
    copy(tools = existing ++ added)

  def withTools[R1](newTools: Tool[R1]*)(using NotGiven[R1 =:= Nothing]): MCPServer[R & R1] =
    val existing: List[Tool[R & R1]] = tools
    val added: List[Tool[R & R1]] = newTools.toList
    MCPServer(
      name,
      version,
      existing ++ added,
      resources,
      prompts,
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      publishToolCallEvents
    )

  @targetName("withAnyResources")
  def withResources(newResources: Resource[Any]*): MCPServer[R] =
    val existing: List[Resource[R]] = resources
    val added: List[Resource[R]] = newResources.toList
    copy(resources = existing ++ added)

  def withResources[R1](newResources: Resource[R1]*)(using
      NotGiven[R1 =:= Nothing]
  ): MCPServer[R & R1] =
    val existing: List[Resource[R & R1]] = resources
    val added: List[Resource[R & R1]] = newResources.toList
    MCPServer(
      name,
      version,
      tools,
      existing ++ added,
      prompts,
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      publishToolCallEvents
    )

  @targetName("withAnyPrompts")
  def withPrompts(newPrompts: Prompt[Any]*): MCPServer[R] =
    val existing: List[Prompt[R]] = prompts
    val added: List[Prompt[R]] = newPrompts.toList
    copy(prompts = existing ++ added)

  def withPrompts[R1](newPrompts: Prompt[R1]*)(using NotGiven[R1 =:= Nothing]): MCPServer[R & R1] =
    val existing: List[Prompt[R & R1]] = prompts
    val added: List[Prompt[R & R1]] = newPrompts.toList
    MCPServer(
      name,
      version,
      tools,
      resources,
      existing ++ added,
      httpSecurityPolicyLayer,
      sessionHooksLayer,
      publishToolCallEvents
    )

  def withName(newName: String): MCPServer[R] =
    copy(name = newName)

  def withVersion(newVersion: String): MCPServer[R] =
    copy(version = newVersion)

  def withHttpSecurityPolicy(layer: ULayer[HttpSecurityPolicy]): MCPServer[R] =
    copy(httpSecurityPolicyLayer = layer)

  /** Use JWT Bearer token validation with JWKS signature verification. */
  def withJwtAuth(config: JwtSecurityPolicy.Config): MCPServer[R] =
    copy(httpSecurityPolicyLayer = JwtSecurityPolicy.layer(config).orDie)

  /** Use trusted upstream proxy headers for identity extraction. */
  def withTrustedHeaders(mapping: TrustedHeaderPolicy.HeaderMapping =
    TrustedHeaderPolicy.defaultMapping): MCPServer[R] =
    copy(httpSecurityPolicyLayer = TrustedHeaderPolicy.layer(mapping))

  /** Select auth policy from environment variables.
    *
    * `AUTH_MODE=jwt` -- JWT Bearer token validation via JWKS. Requires: `JWKS_URI`, `JWT_ISSUER`,
    * `JWT_AUDIENCE`. Optional: `JWT_GROUPS_CLAIM` (default `"groups"`), `JWT_SCOPES_CLAIM` (default
    * `"scp"`). `AUTH_MODE=trusted` -- trusted upstream proxy headers (explicit opt-in). `AUTH_MODE`
    * unset -- `allowAll` (no authentication).
    */
  def withConfigDrivenAuth(): MCPServer[R] =
    copy(httpSecurityPolicyLayer = ConfigDrivenAuth.layer)

  /** Wire SSE lifecycle hooks. The hooks' `onConnect` stream is merged into the SSE output. */
  def withSessionHooks(hooks: SessionHooks): MCPServer[R] =
    copy(sessionHooksLayer = ZLayer.succeed(hooks))

  /** Wire SSE lifecycle hooks from a layer. */
  def withSessionHooks(layer: ULayer[SessionHooks]): MCPServer[R] =
    copy(sessionHooksLayer = layer)

  /** Enable publishing `notifications/message` events to the SSE stream after each tool call. */
  def withToolCallEvents: MCPServer[R] =
    copy(publishToolCallEvents = true)

  /** Set server instructions sent to the LLM in the initialize response. */
  def withInstructions(text: String): MCPServer[R] =
    copy(instructions = Some(text))

  def buildLayers: ZLayer[
    R,
    Nothing,
    ToolRegistry & ResourceRegistry & PromptRegistry & McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender & ClientRequester & HttpSecurityPolicy & SessionHooks & SubscriptionRegistry & ServerCapabilities & Tracing & ContextStorage
  ] =
    val capabilities = ServerCapabilities(
      tools = if tools.nonEmpty then Some(ToolsCapability(listChanged = Some(true))) else None,
      resources = if resources.nonEmpty then
        Some(ResourcesCapability(subscribe = Some(true), listChanged = Some(true)))
      else None,
      prompts = if prompts.nonEmpty then Some(PromptsCapability(listChanged = Some(true))) else None
    )

    // Registry layers capture R at construction time, erasing it from the output
    val registries: ZLayer[R, Nothing, ToolRegistry & ResourceRegistry & PromptRegistry] =
      ToolRegistry.layer(tools) ++ ResourceRegistry.layer(resources) ++ PromptRegistry.layer(
        prompts
      )

    // All remaining services are R-free and can be auto-wired
    val services = ZLayer.makeSome[
      ToolRegistry & ResourceRegistry & PromptRegistry,
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

    registries >>> (services ++ ZLayer.environment[
      ToolRegistry & ResourceRegistry & PromptRegistry
    ])

  def serverInfo: ServerInfo =
    ServerInfo(name, version)

  def serveStdio: ZIO[R, IOException, Unit] =
    val layers = buildLayers >>> StdioTransport.layer
    ZIO.scoped {
      MCPServer.stdioLoggingLayer.build *>
        (ZIO.logInfo(s"Starting $name v$version on stdio") *>
          ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
          StdioTransport.run.provideLayer(layers))
          .catchAllDefect { throwable =>
            ZIO.logErrorCause("FATAL: Unexpected defect in stdio server", Cause.die(throwable)) *>
              ZIO.die(throwable)
          }
    }

  def serveHttp: ZIO[R, Throwable, Nothing] =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val layers = buildLayers >>> HttpTransport.layer
    ZIO.scoped {
      MCPServer.httpLoggingLayer.build *>
        (ZIO.logInfo(s"Starting $name v$version on HTTP port $port") *>
          ZIO.logInfo(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
          HttpTransport.serve(port).provideLayer(layers))
          .catchAllDefect { throwable =>
            ZIO.logErrorCause("FATAL: Unexpected defect in HTTP server", Cause.die(throwable)) *>
              ZIO.die(throwable)
          }
    }

object MCPServer:

  private val logFilter: LogFilter.LogLevelByNameConfig =
    val level = sys.env.get("LOG_LEVEL")
      .flatMap(s => LogLevel.levels.find(_.label.equalsIgnoreCase(s)))
      .getOrElse(LogLevel.Info)
    LogFilter.LogLevelByNameConfig(level)

  /** Structured JSON to stdout -- for HTTP transport. */
  val httpLoggingLayer: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> consoleJsonLogger(ConsoleLoggerConfig(
      LogFormat.default,
      logFilter
    ))

  /** Structured JSON to stderr -- for stdio transport (stdout is MCP protocol channel). */
  val stdioLoggingLayer: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> consoleErrJsonLogger(ConsoleLoggerConfig(
      LogFormat.default,
      logFilter
    ))

  def apply(): MCPServer[Any] =
    MCPServer(
      name = "fishy-mcp",
      version = "0.1.0",
      tools = Nil,
      resources = Nil,
      prompts = Nil,
      httpSecurityPolicyLayer = HttpSecurityPolicy.allowAll,
      sessionHooksLayer = SessionHooks.noOp,
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
