package fishy.mcp.domain.model.mcp

import fishy.mcp.domain.model.Content
import zio.json.*
import zio.json.ast.Json

/** Wire format for tools/list and tools/call. */

// ---------------------------------------------------------------------------
// tools/list
// ---------------------------------------------------------------------------

final case class ToolDefinition(
    name: String,
    description: String,
    inputSchema: Json
)

object ToolDefinition:
  given JsonDecoder[ToolDefinition] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolDefinition] = DeriveJsonEncoder.gen

final case class ToolsListResult(
    tools: List[ToolDefinition]
)

object ToolsListResult:
  given JsonDecoder[ToolsListResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolsListResult] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// tools/call
// ---------------------------------------------------------------------------

final case class ToolCallParams(
    name: String,
    arguments: Option[Json] = None,
    _meta: Option[Json] = None
):
  def progressToken: Option[Json] =
    _meta.flatMap(m => m.asObject.flatMap(_.get("progressToken")))

object ToolCallParams:
  given JsonDecoder[ToolCallParams] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolCallParams] = DeriveJsonEncoder.gen

final case class ToolCallResult(
    content: List[Content],
    isError: Option[Boolean] = None
)

object ToolCallResult:
  given JsonDecoder[ToolCallResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolCallResult] = DeriveJsonEncoder.gen

  def success(text: String): ToolCallResult =
    ToolCallResult(List(Content.Text(text)))

  def error(message: String): ToolCallResult =
    ToolCallResult(List(Content.Text(message)), Some(true))
