package fishy.mcp.application.ports

import fishy.mcp.domain.model.*
import zio.*

/** Service for managing and retrieving prompts.
  *
  * R is erased at this boundary.
  */
trait PromptRegistry:

  /** List metadata of all registered prompts. */
  def list: UIO[List[PromptInfo]]

  /** Get a prompt by name, instantiating it with the given arguments. */
  def get(name: String, arguments: Map[String, String]): IO[PromptError, PromptResult]

object PromptRegistry:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list: URIO[PromptRegistry, List[PromptInfo]] =
    ZIO.serviceWithZIO[PromptRegistry](_.list)

  def get(name: String, arguments: Map[String, String]) =
    ZIO.serviceWithZIO[PromptRegistry](_.get(name, arguments))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  def layer[R](prompts: List[Prompt[R]]): ZLayer[R, Nothing, PromptRegistry] =
    ZLayer.fromZIO {
      ZIO.environment[R].map(env => Live(prompts, env): PromptRegistry)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live[R](prompts: List[Prompt[R]], env: ZEnvironment[R])
      extends PromptRegistry:

    private val promptMap: Map[String, Prompt[R]] =
      prompts.map(p => p.name -> p).toMap

    private val promptInfos: List[PromptInfo] =
      prompts.map(p => PromptInfo(p.name, p.description, p.arguments))

    def list: UIO[List[PromptInfo]] =
      ZIO.succeed(promptInfos)

    def get(name: String, arguments: Map[String, String]): IO[PromptError, PromptResult] =
      promptMap.get(name) match
        case None         => ZIO.fail(PromptError.NotFound(name))
        case Some(prompt) => prompt.get(arguments).provideEnvironment(env)
