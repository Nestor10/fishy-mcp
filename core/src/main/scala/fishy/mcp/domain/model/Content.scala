package fishy.mcp.domain.model

/** Content produced by tool execution. */
sealed trait Content

object Content:
  final case class Text(value: String) extends Content
  final case class Image(data: String, mimeType: String) extends Content
  final case class Blob(data: String, mimeType: String) extends Content

  given Conversion[String, Content] = Content.Text(_)
