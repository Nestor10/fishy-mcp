package fishy.mcp.application.usecase

import fishy.mcp.application.ports.PromptRegistry
import fishy.mcp.domain.model.*
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

/** Handles prompt list and get operations.
  *
  * Owns the translation between JSON-RPC params and PromptRegistry calls, including argument
  * extraction and error mapping.
  *
  * Pattern: Trait + Companion + Live in one file.
  */
trait PromptExecutor:

  def list(id: RequestId): UIO[Either[ErrorResponse, Response]]

  def get(id: RequestId, params: Option[Json]): UIO[Either[ErrorResponse, Response]]

object PromptExecutor:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list(id: RequestId) =
    ZIO.serviceWithZIO[PromptExecutor](_.list(id))

  def get(id: RequestId, params: Option[Json]) =
    ZIO.serviceWithZIO[PromptExecutor](_.get(id, params))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[PromptRegistry & Tracing, PromptExecutor] =
    ZLayer.fromZIO {
      for
        registry <- ZIO.service[PromptRegistry]
        tracing <- ZIO.service[Tracing]
      yield Live(registry, tracing)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live(
      promptRegistry: PromptRegistry,
      tracing: Tracing
  ) extends PromptExecutor:

    import tracing.aspects.*

    def list(id: RequestId): UIO[Either[ErrorResponse, Response]] =
      for
        prompts <- promptRegistry.list
        definitions = prompts.map { p =>
          PromptDefinition(
            name = p.name,
            description = Some(p.description).filter(_.nonEmpty),
            arguments = if p.arguments.isEmpty then None
            else
              Some(p.arguments.map(a =>
                PromptArgumentDef(
                  name = a.name,
                  description = Some(a.description).filter(_.nonEmpty),
                  required = if a.required then Some(true) else None
                )
              ))
          )
        }
        result = PromptsListResult(definitions)
      yield Right(Response.success(id, result.toJsonAST.toOption.get))

    def get(id: RequestId, params: Option[Json]): UIO[Either[ErrorResponse, Response]] =
      params match
        case None =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Missing params")))
        case Some(json) =>
          json.as[PromptGetParams] match
            case Left(err) =>
              ZIO.succeed(Left(ErrorResponse.invalidParams(id, err)))
            case Right(getParams) =>
              val args = getParams.arguments.getOrElse(Map.empty)
              (tracing.setAttribute("mcp.prompt.name", getParams.name) *>
                promptRegistry
                  .get(getParams.name, args)
                  .map { result =>
                    val wireMessages = result.messages.map(m =>
                      PromptMessageWire(m.role, PromptMessageContent.text(m.text))
                    )
                    val wireResult = GetPromptResult(result.description, wireMessages)
                    Right(Response.success(id, wireResult.toJsonAST.toOption.get))
                  }
                  .catchAll {
                    case PromptError.NotFound(name) =>
                      ZIO.succeed(Left(ErrorResponse.invalidParams(id, s"Prompt not found: $name")))
                    case PromptError.InvalidArguments(msg) =>
                      ZIO.succeed(Left(ErrorResponse.invalidParams(id, msg)))
                    case err =>
                      ZIO.succeed(Left(ErrorResponse.internalError(id, err.message)))
                  }) @@ span("mcp.prompt.get")
