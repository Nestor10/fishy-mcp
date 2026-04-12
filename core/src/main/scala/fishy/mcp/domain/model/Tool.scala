package fishy.mcp.domain.model

import zio.*
import zio.json.ast.Json

/** A tool that can be invoked via MCP. */
trait Tool[-R]:
  def name: String
  def description: String
  def inputJsonSchema: Json
  def requiredScopes: Set[String] = Set.empty
  def execute(input: Json, ctx: ToolContext): ZIO[R, ToolError, Content]

/** Tool metadata without the environment type parameter. */
final case class ToolInfo(
    name: String,
    description: String,
    inputJsonSchema: Json,
    requiredScopes: Set[String] = Set.empty
)
