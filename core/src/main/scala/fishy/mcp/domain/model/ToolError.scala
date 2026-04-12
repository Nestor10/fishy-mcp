package fishy.mcp.domain.model

/** Domain errors for tool execution. Translated to MCP error responses by the application layer.
  */
sealed trait ToolError:
  def message: String

object ToolError:

  final case class ExecutionFailed(message: String) extends ToolError

  final case class InvalidInput(field: String, reason: String) extends ToolError:
    def message: String = s"Invalid input for '$field': $reason"

  final case class PermissionDenied(resource: String) extends ToolError:
    def message: String = s"Permission denied: $resource"

  final case class NotFound(resource: String) extends ToolError:
    def message: String = s"Not found: $resource"

  def apply(message: String): ToolError = ExecutionFailed(message)
