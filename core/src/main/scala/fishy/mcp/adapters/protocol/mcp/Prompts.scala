package fishy.mcp.adapters.protocol.mcp

import zio.json.*

/** MCP Prompts method types.
  *
  * Wire format for prompts/list and prompts/get requests and responses.
  */

// ---------------------------------------------------------------------------
// prompts/list
// ---------------------------------------------------------------------------

/** Prompt argument definition. */
final case class PromptArgumentDef(
    name: String,
    description: Option[String] = None,
    required: Option[Boolean] = None
)

object PromptArgumentDef:
  given JsonDecoder[PromptArgumentDef] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptArgumentDef] = DeriveJsonEncoder.gen

/** Prompt definition returned by prompts/list. */
final case class PromptDefinition(
    name: String,
    description: Option[String] = None,
    arguments: Option[List[PromptArgumentDef]] = None
)

object PromptDefinition:
  given JsonDecoder[PromptDefinition] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptDefinition] = DeriveJsonEncoder.gen

/** Result of prompts/list method. */
final case class PromptsListResult(
    prompts: List[PromptDefinition]
)

object PromptsListResult:
  given JsonDecoder[PromptsListResult] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptsListResult] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// prompts/get
// ---------------------------------------------------------------------------

/** Parameters for prompts/get method. */
final case class PromptGetParams(
    name: String,
    arguments: Option[Map[String, String]] = None
)

object PromptGetParams:
  given JsonDecoder[PromptGetParams] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptGetParams] = DeriveJsonEncoder.gen

/** Content inside a prompt message (text only for now). */
final case class PromptMessageContent(
    `type`: String,
    text: String
)

object PromptMessageContent:
  given JsonDecoder[PromptMessageContent] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptMessageContent] = DeriveJsonEncoder.gen

  def text(value: String): PromptMessageContent =
    PromptMessageContent("text", value)

/** A message in a prompt result. */
final case class PromptMessageWire(
    role: String,
    content: PromptMessageContent
)

object PromptMessageWire:
  given JsonDecoder[PromptMessageWire] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptMessageWire] = DeriveJsonEncoder.gen

/** Result of prompts/get method. */
final case class GetPromptResult(
    description: Option[String] = None,
    messages: List[PromptMessageWire]
)

object GetPromptResult:
  given JsonDecoder[GetPromptResult] = DeriveJsonDecoder.gen
  given JsonEncoder[GetPromptResult] = DeriveJsonEncoder.gen
