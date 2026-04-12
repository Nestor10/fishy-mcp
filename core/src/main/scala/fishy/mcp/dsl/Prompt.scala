package fishy.mcp.dsl

import fishy.mcp.domain.model.{
  Prompt as CorePrompt,
  PromptArgument,
  PromptError,
  PromptMessage,
  PromptResult
}
import zio.*

/** User-facing DSL for defining MCP prompts. */
object Prompt:

  /** Create a prompt with typed arguments.
    *
    * The function receives a map of argument values and returns a list of messages.
    */
  def apply[R](
      name: String,
      description: String,
      args: List[PromptArgument] = Nil
  )(f: Map[String, String] => ZIO[R, PromptError, List[PromptMessage]]): CorePrompt[R] =
    val pName = name
    val pDesc = description
    val pArgs = args

    new CorePrompt[R]:
      def name: String = pName
      def description: String = pDesc
      def arguments: List[PromptArgument] = pArgs
      def get(args: Map[String, String]): ZIO[R, PromptError, PromptResult] =
        f(args).map(msgs => PromptResult(Some(pDesc), msgs))

  /** Create a simple prompt with no arguments that returns static messages. */
  def static(
      name: String,
      description: String
  )(messages: List[PromptMessage]): CorePrompt[Any] =
    val pName = name
    val pDesc = description

    new CorePrompt[Any]:
      def name: String = pName
      def description: String = pDesc
      def arguments: List[PromptArgument] = Nil
      def get(args: Map[String, String]): UIO[PromptResult] =
        ZIO.succeed(PromptResult(Some(pDesc), messages))
