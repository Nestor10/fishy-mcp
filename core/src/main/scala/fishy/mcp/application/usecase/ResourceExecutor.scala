package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{ResourceRegistry, SubscriptionRegistry}
import fishy.mcp.domain.model.*
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

/** Handles resource list and read operations.
  *
  * Owns the translation between JSON-RPC params and ResourceRegistry calls, including error mapping
  * to JSON-RPC error responses.
  *
  * Pattern: Trait + Companion + Live in one file.
  */
trait ResourceExecutor:

  def list(id: RequestId): UIO[Either[ErrorResponse, Response]]

  def read(id: RequestId, params: Option[Json]): UIO[Either[ErrorResponse, Response]]

  def templatesList(id: RequestId): UIO[Either[ErrorResponse, Response]]

  def subscribe(
      id: RequestId,
      params: Option[Json],
      sessionId: Option[String]
  ): UIO[Either[ErrorResponse, Response]]

  def unsubscribe(
      id: RequestId,
      params: Option[Json],
      sessionId: Option[String]
  ): UIO[Either[ErrorResponse, Response]]

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

  private final case class Live(
      resourceRegistry: ResourceRegistry,
      subscriptionRegistry: SubscriptionRegistry,
      tracing: Tracing
  ) extends ResourceExecutor:

    import tracing.aspects.*

    def list(id: RequestId): UIO[Either[ErrorResponse, Response]] =
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
        result = ResourcesListResult(definitions)
      yield Right(Response.success(id, result.toJsonAST.toOption.get))

    def read(id: RequestId, params: Option[Json]): UIO[Either[ErrorResponse, Response]] =
      params match
        case None =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Missing params")))
        case Some(json) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(Left(ErrorResponse.invalidParams(id, err)))
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
                    Right(Response.success(id, ResourceReadResult(items).toJsonAST.toOption.get))
                  }
                  .catchAll {
                    case ResourceError.NotFound(uri) =>
                      ZIO.succeed(Left(ErrorResponse(id, -32002, s"Resource not found: $uri")))
                    case err =>
                      ZIO.succeed(Left(ErrorResponse.internalError(id, err.message)))
                  }) @@ span("mcp.resource.read")

    def templatesList(id: RequestId): UIO[Either[ErrorResponse, Response]] =
      val result = ResourceTemplatesListResult(Nil)
      ZIO.succeed(Right(Response.success(id, result.toJsonAST.toOption.get)))

    def subscribe(
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[Either[ErrorResponse, Response]] =
      (params, sessionId) match
        case (None, _) =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Missing params")))
        case (_, None) =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Subscriptions require a session")))
        case (Some(json), Some(sid)) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(Left(ErrorResponse.invalidParams(id, err)))
            case Right(p) =>
              subscriptionRegistry.subscribe(sid, p.uri).as(
                Right(Response.success(id, Json.Obj()))
              )

    def unsubscribe(
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[Either[ErrorResponse, Response]] =
      (params, sessionId) match
        case (None, _) =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Missing params")))
        case (_, None) =>
          ZIO.succeed(Left(ErrorResponse.invalidParams(id, "Subscriptions require a session")))
        case (Some(json), Some(sid)) =>
          json.as[ResourceReadParams] match
            case Left(err) =>
              ZIO.succeed(Left(ErrorResponse.invalidParams(id, err)))
            case Right(p) =>
              subscriptionRegistry.unsubscribe(sid, p.uri).as(
                Right(Response.success(id, Json.Obj()))
              )
