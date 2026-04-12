package fishy.mcp.application.ports

import fishy.mcp.domain.model.*
import zio.*
import zio.json.ast.Json

/** Service for managing and executing tools.
  *
  * R is erased at this boundary. The layer captures the environment at construction time and
  * provides it internally when executing tools.
  */
trait ToolRegistry:

  /** List metadata of all registered tools. */
  def list: UIO[List[ToolInfo]]

  /** Execute a tool by name with given arguments. */
  def call(name: String, arguments: Json, ctx: ToolContext): IO[ToolError, Content]

object ToolRegistry:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list: URIO[ToolRegistry, List[ToolInfo]] =
    ZIO.serviceWithZIO[ToolRegistry](_.list)

  def call(name: String, arguments: Json, ctx: ToolContext) =
    ZIO.serviceWithZIO[ToolRegistry](_.call(name, arguments, ctx))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  /** Create a layer from a list of tools. R is captured from the environment. */
  def layer[R](tools: List[Tool[R]]): ZLayer[R, Nothing, ToolRegistry] =
    ZLayer.fromZIO {
      ZIO.environment[R].map(env => Live(tools, env): ToolRegistry)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live[R](tools: List[Tool[R]], env: ZEnvironment[R]) extends ToolRegistry:

    private val toolMap: Map[String, Tool[R]] =
      tools.map(t => t.name -> t).toMap

    private val toolInfos: List[ToolInfo] =
      tools.map(t => ToolInfo(t.name, t.description, t.inputJsonSchema, t.requiredScopes))

    def list: UIO[List[ToolInfo]] =
      ZIO.succeed(toolInfos)

    def call(name: String, arguments: Json, ctx: ToolContext): IO[ToolError, Content] =
      toolMap.get(name) match
        case None => ZIO.fail(ToolError.NotFound(s"Tool: $name"))
        case Some(tool) =>
          val scopes = tool.requiredScopes
          val check =
            if scopes.isEmpty then ZIO.unit
            else
              ctx.auth match
                case None => ZIO.unit // no auth configured -- skip scope enforcement
                case Some(auth) if !auth.hasScopes(scopes) =>
                  val missing = scopes -- auth.scopes
                  ZIO.fail(ToolError.PermissionDenied(s"Missing scopes: ${missing.mkString(", ")}"))
                case _ => ZIO.unit
          check *> tool.execute(arguments, ctx).provideEnvironment(env)
