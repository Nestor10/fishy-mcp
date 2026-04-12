package fishy.mcp.adapters.protocol.mcp

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

@jsonDiscriminator("type")
sealed trait ToolContent

object ToolContent:
  @jsonHint("text")
  final case class Text(text: String) extends ToolContent

  @jsonHint("image")
  final case class Image(data: String, mimeType: String) extends ToolContent

  given JsonDecoder[ToolContent] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolContent] = DeriveJsonEncoder.gen

  def text(value: String): ToolContent = Text(value)
  def image(data: String, mimeType: String): ToolContent = Image(data, mimeType)

final case class ToolCallResult(
    content: List[ToolContent],
    isError: Option[Boolean] = None
)

object ToolCallResult:
  given JsonDecoder[ToolCallResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolCallResult] = DeriveJsonEncoder.gen

  def success(text: String): ToolCallResult =
    ToolCallResult(List(ToolContent.text(text)))

  def error(message: String): ToolCallResult =
    ToolCallResult(List(ToolContent.text(message)), Some(true))
