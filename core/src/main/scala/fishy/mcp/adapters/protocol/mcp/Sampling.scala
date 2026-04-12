package fishy.mcp.adapters.protocol.mcp

import zio.json.*
import zio.json.ast.Json

/** Wire types for sampling/createMessage (server -> client request).
  *
  * The server asks the client's LLM to generate a message.
  *
  * @see
  *   https://modelcontextprotocol.io/specification/2025-03-26/client/sampling
  */

// ---------------------------------------------------------------------------
// Content types shared with sampling messages
// ---------------------------------------------------------------------------

/** Content inside a sampling message (text or image). */
@jsonDiscriminator("type")
sealed trait SamplingContent

object SamplingContent:
  @jsonHint("text")
  final case class Text(text: String) extends SamplingContent

  @jsonHint("image")
  final case class Image(data: String, mimeType: String) extends SamplingContent

  given JsonDecoder[SamplingContent] = DeriveJsonDecoder.gen
  given JsonEncoder[SamplingContent] = DeriveJsonEncoder.gen

/** A message in a sampling conversation. */
final case class SamplingMessage(
    role: String,
    content: SamplingContent
)

object SamplingMessage:
  given JsonDecoder[SamplingMessage] = DeriveJsonDecoder.gen
  given JsonEncoder[SamplingMessage] = DeriveJsonEncoder.gen

  def user(text: String): SamplingMessage =
    SamplingMessage("user", SamplingContent.Text(text))

  def assistant(text: String): SamplingMessage =
    SamplingMessage("assistant", SamplingContent.Text(text))

// ---------------------------------------------------------------------------
// Model preferences
// ---------------------------------------------------------------------------

final case class ModelHint(name: Option[String] = None)

object ModelHint:
  given JsonDecoder[ModelHint] = DeriveJsonDecoder.gen
  given JsonEncoder[ModelHint] = DeriveJsonEncoder.gen

final case class ModelPreferences(
    hints: Option[List[ModelHint]] = None,
    costPriority: Option[Double] = None,
    speedPriority: Option[Double] = None,
    intelligencePriority: Option[Double] = None
)

object ModelPreferences:
  given JsonDecoder[ModelPreferences] = DeriveJsonDecoder.gen
  given JsonEncoder[ModelPreferences] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// sampling/createMessage params + result
// ---------------------------------------------------------------------------

final case class CreateMessageParams(
    messages: List[SamplingMessage],
    maxTokens: Int,
    modelPreferences: Option[ModelPreferences] = None,
    systemPrompt: Option[String] = None,
    includeContext: Option[String] = None,
    temperature: Option[Double] = None,
    stopSequences: Option[List[String]] = None,
    metadata: Option[Json] = None
)

object CreateMessageParams:
  given JsonDecoder[CreateMessageParams] = DeriveJsonDecoder.gen
  given JsonEncoder[CreateMessageParams] = DeriveJsonEncoder.gen

final case class CreateMessageResult(
    role: String,
    content: SamplingContent,
    model: String,
    stopReason: Option[String] = None
)

object CreateMessageResult:
  given JsonDecoder[CreateMessageResult] = DeriveJsonDecoder.gen
  given JsonEncoder[CreateMessageResult] = DeriveJsonEncoder.gen
