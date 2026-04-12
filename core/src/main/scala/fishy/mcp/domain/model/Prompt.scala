package fishy.mcp.domain.model

import zio.*

/** A prompt template that can be retrieved via MCP. */
trait Prompt[-R]:
  def name: String
  def description: String
  def arguments: List[PromptArgument]
  def get(args: Map[String, String]): ZIO[R, PromptError, PromptResult]

/** Prompt metadata without the environment type parameter. */
final case class PromptInfo(name: String, description: String, arguments: List[PromptArgument])

final case class PromptArgument(
    name: String,
    description: String,
    required: Boolean = false
)

final case class PromptMessage(role: String, text: String)

final case class PromptResult(
    description: Option[String],
    messages: List[PromptMessage]
)
