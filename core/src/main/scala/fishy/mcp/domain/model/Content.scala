package fishy.mcp.domain.model

import zio.json.*

/** MCP message content: the single polymorphic union a tool handler returns and
  * a sampling message carries, and the wire shape of both.
  *
  * Authoring surface and wire form are one type (a "domain twin"), so there is
  * no lossy translation between an authoring content type and a separate wire
  * content type — the previous `Content`/`ToolContent`/`SamplingContent` triple
  * collapsed to this. Wire: `{"type":"text","text":…}`, `{"type":"image",…}`,
  * `{"type":"audio",…}`.
  */
@jsonDiscriminator("type")
sealed trait Content

object Content:
  @jsonHint("text") final case class Text(text: String) extends Content
  @jsonHint("image") final case class Image(data: String, mimeType: String) extends Content
  @jsonHint("audio") final case class Audio(data: String, mimeType: String) extends Content

  given JsonDecoder[Content] = DeriveJsonDecoder.gen
  given JsonEncoder[Content] = DeriveJsonEncoder.gen

  /** Ergonomic: a bare `String` handler result becomes text content. */
  given Conversion[String, Content] = Text(_)
