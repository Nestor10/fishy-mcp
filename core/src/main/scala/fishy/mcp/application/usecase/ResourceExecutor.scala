package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{ResourceRegistry, SubscriptionRegistry}
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

/** Handles resource list and read operations.
  *
  * Owns the translation between JSON-RPC params and ResourceRegistry calls,
  * mapping registry errors to typed [[McpError]]s. Returns domain
  * [[ResponsePayload]] -- the transport adapter handles wire serialization.
  *
  * Pattern: Trait + Companion + Live in one file.
  */
trait ResourceExecutor:

  def list(id: RequestId): UIO[ResponsePayload]
  def read(id: RequestId, params: Option[Json]): UIO[ResponsePayload]
  def templatesList(id: RequestId): UIO[ResponsePayload]
  def subscribe(id: RequestId, params: Option[Json], sessionId: Option[String]): UIO[ResponsePayload]
  def unsubscribe(id: RequestId, params: Option[Json], sessionId: Option[String]): UIO[ResponsePayload]

object ResourceExecutor:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list(id: RequestId) =
    ZIO.serviceWithZIO[ResourceExecutor](_.list(id))

  def read(id: RequestId, params: Option[Json]) =
    ZIO.serviceWithZIO[ResourceExecutor](_.read(id, params))

  def templatesList(id: RequestId) =
    ZIO.serviceWithZIO[ResourceExecutor](_.templatesList(id))

  def subscribe(id: RequestId, params: Option[Json], sessionId: Option[String]) =
    ZIO.serviceWithZIO[ResourceExecutor](_.subscribe(id, params, sessionId))

  def unsubscribe(id: RequestId, params: Option[Json], sessionId: Option[String]) =
    ZIO.serviceWithZIO[ResourceExecutor](_.unsubscribe(id, params, sessionId))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[ResourceRegistry & SubscriptionRegistry & Tracing, ResourceExecutor] =
    ZLayer.fromZIO {
      for
        registry <- ZIO.service[ResourceRegistry]
        subscriptions <- ZIO.service[SubscriptionRegistry]
        tracing <- ZIO.service[Tracing]
      yield Live(registry, subscriptions, tracing)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  /** MCP-specific extension code for "resource not found" -- not in the
    * standard JSON-RPC range. Spec uses -32002.
    */
  private val ResourceNotFoundCode = -32002

  private final case class Live(
      resourceRegistry: ResourceRegistry,
      subscriptionRegistry: SubscriptionRegistry,
      tracing: Tracing
  ) extends ResourceExecutor:

    import tracing.aspects.*

    def list(id: RequestId): UIO[ResponsePayload] =
      for
        resources <- resourceRegistry.list
        definitions = resources.map(r =>
          ResourceDefinition(
            uri = r.uri,
            name = r.name,
            description = Some(r.description).filter(_.nonEmpty),
            mimeType = r.mimeType
          )
        )
      yield encodeResult(id, ResourcesListResult(definitions))

    def read(id: RequestId, params: Option[Json]): UIO[ResponsePayload] =
      params match
        case None =>
          ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams("Missing params")))
        case Some(json) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(err)))
            case Right(readParams) =>
              (tracing.setAttribute("mcp.resource.uri", readParams.uri) *>
                resourceRegistry
                  .read(readParams.uri)
                  .map { contents =>
                    val items = contents.map {
                      case ResourceContent.Text(uri, mime, text) =>
                        ResourceContentItem(uri, mime, text = Some(text))
                      case ResourceContent.Blob(uri, mime, blob) =>
                        ResourceContentItem(uri, mime, blob = Some(blob))
                    }
                    encodeResult(id, ResourceReadResult(items))
                  }
                  .catchAll {
                    case ResourceError.NotFound(uri) =>
                      ZIO.succeed(ResponsePayload.failure(
                        id,
                        McpError.Custom(ResourceNotFoundCode, s"Resource not found: $uri")
                      ))
                    case err =>
                      ZIO.succeed(ResponsePayload.failure(id, McpError.InternalError(err.message)))
                  }) @@ span("mcp.resource.read")

    def templatesList(id: RequestId): UIO[ResponsePayload] =
      ZIO.succeed(encodeResult(id, ResourceTemplatesListResult(Nil)))

    def subscribe(
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[ResponsePayload] =
      (params, sessionId) match
        case (None, _) =>
          ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams("Missing params")))
        case (_, None) =>
          ZIO.succeed(ResponsePayload.failure(
            id,
            McpError.InvalidParams("Subscriptions require a session")
          ))
        case (Some(json), Some(sid)) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(err)))
            case Right(p) =>
              subscriptionRegistry
                .subscribe(sid, p.uri)
                .as(ResponsePayload.success(id, Json.Obj()))

    def unsubscribe(
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[ResponsePayload] =
      (params, sessionId) match
        case (None, _) =>
          ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams("Missing params")))
        case (_, None) =>
          ZIO.succeed(ResponsePayload.failure(
            id,
            McpError.InvalidParams("Subscriptions require a session")
          ))
        case (Some(json), Some(sid)) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(err)))
            case Right(p) =>
              subscriptionRegistry
                .unsubscribe(sid, p.uri)
                .as(ResponsePayload.success(id, Json.Obj()))

    private def encodeResult[A: JsonEncoder](id: RequestId, value: A): ResponsePayload =
      value.toJsonAST match
        case Right(json) => ResponsePayload.success(id, json)
        case Left(err) =>
          ResponsePayload.failure(
            id,
            McpError.InternalError(s"failed to encode result: $err")
          )
