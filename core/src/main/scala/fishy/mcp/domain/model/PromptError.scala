package fishy.mcp.domain.model

sealed trait PromptError:
  def message: String

object PromptError:
  final case class NotFound(name: String) extends PromptError:
    def message: String = s"Prompt not found: $name"

  final case class InvalidArguments(message: String) extends PromptError

  final case class ExecutionFailed(message: String) extends PromptError
