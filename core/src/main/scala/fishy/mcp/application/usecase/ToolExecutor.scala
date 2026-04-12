package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{MessageRouter, ToolRegistry}
import fishy.mcp.domain.model.*
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.telemetry.opentelemetry.tracing.Tracing

/** Executes tool calls with progress streaming and cancellation. */
trait ToolExecutor:

  /** List available tools. */
  def list(id: RequestId): UIO[Either[ErrorResponse, Response]]

  /** Execute a tool call, parsing params from JSON-RPC. Returns Single or Streaming. */
  def call(id: RequestId, params: Option[Json], sessionId: Option[String]): UIO[DispatchResult]

  /** Cancel an in-flight tool execution by request ID. */
  def cancel(requestId: RequestId): UIO[Unit]

object ToolExecutor:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list(id: RequestId) =
    ZIO.serviceWithZIO[ToolExecutor](_.list(id))

  def call(id: RequestId, params: Option[Json], sessionId: Option[String]) =
    ZIO.serviceWithZIO[ToolExecutor](_.call(id, params, sessionId))

  def cancel(requestId: RequestId) =
    ZIO.serviceWithZIO[ToolExecutor](_.cancel(requestId))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[
    ToolRegistry & ClientRequester & NotificationSender & MessageRouter & Tracing,
    ToolExecutor
  ] =
    layerWith(publishToolEvents = false)

  def layerWith(publishToolEvents: Boolean): URLayer[
    ToolRegistry & ClientRequester & NotificationSender & MessageRouter & Tracing,
    ToolExecutor
  ] =
    ZLayer {
      for
        toolRegistry <- ZIO.service[ToolRegistry]
        clientRequester <- ZIO.service[ClientRequester]
        notificationSender <- ZIO.service[NotificationSender]
        messageRouter <- ZIO.service[MessageRouter]
        tracing <- ZIO.service[Tracing]
        inFlight <- Ref.make(Map.empty[RequestId, Fiber.Runtime[Nothing, Any]])
      yield Live(
        toolRegistry,
        clientRequester,
        notificationSender,
        messageRouter,
        tracing,
        inFlight,
        publishToolEvents
      )
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live(
      toolRegistry: ToolRegistry,
      clientRequester: ClientRequester,
      notificationSender: NotificationSender,
      messageRouter: MessageRouter,
      tracing: Tracing,
      inFlight: Ref[Map[RequestId, Fiber.Runtime[Nothing, Any]]],
      publishToolEvents: Boolean
  ) extends ToolExecutor:

    import tracing.aspects.*

    def list(id: RequestId): UIO[Either[ErrorResponse, Response]] =
      for
        tools <- toolRegistry.list
        definitions = tools.map(t =>
          ToolDefinition(
            name = t.name,
            description = t.description,
            inputSchema = t.inputJsonSchema
          )
        )
        result = ToolsListResult(definitions)
      yield Right(Response.success(id, result.toJsonAST.toOption.get))

    def call(id: RequestId, params: Option[Json], sessionId: Option[String]): UIO[DispatchResult] =
      params match
        case None =>
          ZIO.succeed(DispatchResult.Single(Left(ErrorResponse.invalidParams(
            id,
            "Missing params"
          ))))
        case Some(json) =>
          json.as[ToolCallParams] match
            case Left(err) =>
              ZIO.succeed(DispatchResult.Single(Left(ErrorResponse.invalidParams(id, err))))
            case Right(callParams) =>
              val args = callParams.arguments.getOrElse(Json.Obj())
              for
                auth <- AuthFiberRef.currentAuth.get
                callback = sessionId.map { sid => (method: String, params: Json) =>
                  clientRequester.sendRequest(sid, method, params)
                }
                ctx = ToolContext(
                  requestId = id.toString,
                  sessionId = sessionId,
                  meta = callParams._meta,
                  auth = auth,
                  sendClientRequest = callback,
                  notifyResourceUpdated = uri => notificationSender.resourceUpdated(uri)
                )
                result <- callParams.progressToken match
                  case None      => callSync(id, callParams.name, args, ctx)
                  case Some(tok) => callStreaming(id, callParams.name, args, tok, ctx)
              yield result

    def cancel(requestId: RequestId): UIO[Unit] =
      (for
        map <- inFlight.get
        _ <- ZIO.logDebug("Looking up in-flight fiber") @@ ZIOAspect.annotated(
          "inFlightKeys",
          map.keys.mkString(",")
        )
        _ <- map.get(requestId) match
          case Some(fiber) => ZIO.logDebug("Interrupting fiber") *> fiber.interruptFork.unit
          case None        => ZIO.logDebug("No in-flight fiber found")
      yield ()) @@ ZIOAspect.annotated("requestId", requestId.toString)

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private def callSync(
        id: RequestId,
        name: String,
        args: Json,
        ctx: ToolContext
    ): UIO[DispatchResult] =
      (tracing.setAttribute("mcp.tool.name", name) *>
        toolRegistry
          .call(name, args, ctx)
          .map(content =>
            DispatchResult.Single(Right(Response.success(
              id,
              contentToResult(content).toJsonAST.toOption.get
            )))
          )
          .catchAll {
            case err: ToolError.InvalidInput =>
              ZIO.succeed(DispatchResult.Single(Left(ErrorResponse.invalidParams(id, err.message))))
            case err =>
              ZIO.succeed(DispatchResult.Single(Left(ErrorResponse.internalError(id, err.message))))
          }
          .tap(_ => emitToolEvent(ctx.sessionId, name))) @@ span("mcp.tool.call")

    /** Streaming tool call -- progress token present, returns Streaming.
      *
      * Uses a sentinel value (empty string) to signal end-of-stream rather than queue.shutdown,
      * which discards pending elements.
      */
    private def callStreaming(
        id: RequestId,
        name: String,
        args: Json,
        token: Json,
        ctx: ToolContext
    ): UIO[DispatchResult] =
      (tracing.setAttribute("mcp.tool.name", name) *>
        tracing.setAttribute("mcp.tool.streaming", true) *>
        (for
          queue <- Queue.unbounded[String]
          _ <- ZIO.logDebug("Queue created")
          reporter = makeProgressReporter(token, queue)
          fiber <- ProgressReporter.current.locally(reporter)(for
            _ <- ZIO.logDebug("Tool execution starting")
            result <- toolRegistry
              .call(name, args, ctx)
              .map(content =>
                Right(
                  Response.success(id, contentToResult(content).toJsonAST.toOption.get)
                ): Either[ErrorResponse, Response]
              )
              .catchAll {
                case err: ToolError.InvalidInput =>
                  ZIO.succeed(Left(ErrorResponse.invalidParams(id, err.message)))
                case err => ZIO.succeed(Left(ErrorResponse.internalError(id, err.message)))
              }
            _ <- ZIO.logDebug("Tool execution completed")
            finalJson = result match
              case Right(r) => r.toJson
              case Left(e)  => e.toJson
            _ <- queue.offer(finalJson)
            _ <- ZIO.logDebug("Final response offered")
            _ <- queue.offer("") // sentinel: end of stream
            _ <- ZIO.logDebug("Sentinel offered")
          yield ()).catchAllCause { cause =>
            ZIO.logDebug("Caught cause, offering sentinel") @@
              ZIOAspect.annotated("interrupted", cause.isInterrupted.toString) *>
              queue.offer("").ignore
          }.forkDaemon
          _ <- inFlight.update(_ + (id -> fiber))
          _ <- ZIO.logDebug("Fiber registered in inFlight")
          stream = ZStream.fromQueue(queue)
            .takeWhile(_.nonEmpty)
            .ensuring(ZIO.logDebug("Stream finalized") *> inFlight.update(_ - id) *> queue.shutdown)
        yield DispatchResult.Streaming(stream))) @@
        span("mcp.tool.call") @@
        ZIOAspect.annotated("requestId", id.toString) @@
        ZIOAspect.annotated("tool", name) @@
        ZIOAspect.annotated("progressToken", token.toString)

    private def makeProgressReporter(token: Json, queue: Queue[String]): ProgressReporter =
      new ProgressReporter:
        def report(progress: Double, total: Option[Double], message: Option[String]): UIO[Unit] =
          val notification = ProgressNotification(token, progress, total, message)
          val jsonRpc = Json.Obj(
            "jsonrpc" -> Json.Str("2.0"),
            "method" -> Json.Str("notifications/progress"),
            "params" -> notification.toJsonAST.toOption.get
          )
          queue.offer(jsonRpc.toJson).unit

    private def contentToResult(content: Content): ToolCallResult =
      content match
        case Content.Text(value)       => ToolCallResult.success(value)
        case Content.Image(data, mime) => ToolCallResult(List(ToolContent.image(data, mime)))
        case Content.Blob(data, mime)  => ToolCallResult(List(ToolContent.image(data, mime)))

    private def emitToolEvent(sessionId: Option[String], toolName: String): UIO[Unit] =
      if !publishToolEvents then ZIO.unit
      else
        sessionId match
          case None => ZIO.unit
          case Some(sid) =>
            val notification = Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "method" -> Json.Str("notifications/message"),
              "params" -> Json.Obj(
                "level" -> Json.Str("info"),
                "logger" -> Json.Str("mcp.tools"),
                "data" -> Json.Obj("tool" -> Json.Str(toolName))
              )
            )
            messageRouter.publish(sid, notification.toJson).unit
