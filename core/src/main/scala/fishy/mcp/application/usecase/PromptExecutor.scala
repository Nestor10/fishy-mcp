package fishy.mcp.application.usecase

import fishy.mcp.application.ports.PromptRegistry
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

/** Handles prompt list and get operations.
  *
  * Owns the translation between JSON-RPC params and PromptRegistry calls,
  * mapping registry errors to typed [[McpError]]s. Returns domain
  * [[ResponsePayload]] -- the transport adapter handles wire serialization.
  *
  * Pattern: Trait + Companion + Live in one file.
  */
trait PromptExecutor:
  def list(id: RequestId): UIO[ResponsePayload]
  def get(id: RequestId, params: Option[Json]): UIO[ResponsePayload]

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
        tracing  <- ZIO.service[Tracing]
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

    def list(id: RequestId): UIO[ResponsePayload] =
      for
        prompts <- promptRegistry.list
        definitions = prompts.map { p =>
          PromptDefinition(
            name = p.name,
            description = Some(p.description).filter(_.nonEmpty),
            arguments =
              if p.arguments.isEmpty then None
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
      yield encodeResult(id, PromptsListResult(definitions))

    def get(id: RequestId, params: Option[Json]): UIO[ResponsePayload] =
      params match
        case None =>
          ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams("Missing params")))
        case Some(json) =>
          json.as[PromptGetParams] match
            case Left(err) =>
              ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(err)))
            case Right(getParams) =>
              val args = getParams.arguments.getOrElse(Map.empty)
              (tracing.setAttribute("mcp.prompt.name", getParams.name) *>
                promptRegistry
                  .get(getParams.name, args)
                  .map { result =>
                    val wireMessages = result.messages.map(m =>
                      PromptMessageWire(m.role, PromptMessageContent.text(m.text))
                    )
                    encodeResult(id, GetPromptResult(result.description, wireMessages))
                  }
                  .catchAll {
                    case PromptError.NotFound(name) =>
                      ZIO.succeed(ResponsePayload.failure(
                        id,
                        McpError.InvalidParams(s"Prompt not found: $name")
                      ))
                    case PromptError.InvalidArguments(msg) =>
                      ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(msg)))
                    case err =>
                      ZIO.succeed(ResponsePayload.failure(id, McpError.InternalError(err.message)))
                  }) @@ span("mcp.prompt.get")

    private def encodeResult[A: JsonEncoder](id: RequestId, value: A): ResponsePayload =
      value.toJsonAST match
        case Right(json) => ResponsePayload.success(id, json)
        case Left(err) =>
          ResponsePayload.failure(
            id,
            McpError.InternalError(s"failed to encode result: $err")
          )
