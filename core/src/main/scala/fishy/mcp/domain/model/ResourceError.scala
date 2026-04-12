package fishy.mcp.domain.model

sealed trait ResourceError:
  def message: String

object ResourceError:
  final case class NotFound(uri: String) extends ResourceError:
    def message: String = s"Resource not found: $uri"

  final case class ReadFailed(message: String) extends ResourceError
