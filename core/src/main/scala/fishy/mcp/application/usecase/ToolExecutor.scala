package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{MessageRouter, ToolRegistry}
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.telemetry.opentelemetry.tracing.Tracing

/** Executes tool calls with progress streaming and cancellation. */
trait ToolExecutor:

  /** List available tools. */
  def list(id: RequestId): UIO[ResponsePayload]

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

  /** Default ceiling on concurrent in-flight tool calls. Anything beyond this
    * is rejected with a JSON-RPC custom error so a buggy or hostile client
    * can't accumulate fiber state without bound. Tune via [[layerWith]].
    */
  val DefaultMaxInFlight: Int = 64

  val layer: URLayer[
    ToolRegistry & ClientRequester & NotificationSender & MessageRouter & Tracing,
    ToolExecutor
  ] =
    layerWith(publishToolEvents = false, maxInFlight = DefaultMaxInFlight)

  def layerWith(
      publishToolEvents: Boolean,
      maxInFlight: Int = DefaultMaxInFlight
  ): URLayer[
    ToolRegistry & ClientRequester & NotificationSender & MessageRouter & Tracing,
    ToolExecutor
  ] =
    ZLayer {
      for
        toolRegistry       <- ZIO.service[ToolRegistry]
        clientRequester    <- ZIO.service[ClientRequester]
        notificationSender <- ZIO.service[NotificationSender]
        messageRouter      <- ZIO.service[MessageRouter]
        tracing            <- ZIO.service[Tracing]
        inFlight           <- Ref.make(Map.empty[RequestId, Fiber.Runtime[Nothing, Any]])
        inFlightCount      <- Ref.make(0)
      yield Live(
        toolRegistry,
        clientRequester,
        notificationSender,
        messageRouter,
        tracing,
        inFlight,
        inFlightCount,
        publishToolEvents,
        maxInFlight
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
      inFlightCount: Ref[Int],
      publishToolEvents: Boolean,
      maxInFlight: Int
  ) extends ToolExecutor:

    import tracing.aspects.*

    def list(id: RequestId): UIO[ResponsePayload] =
      for
        tools <- toolRegistry.list
        definitions = tools.map(t =>
          ToolDefinition(
            name = t.name,
            description = t.description,
            inputSchema = t.inputJsonSchema
          )
        )
      yield encodeResult(id, ToolsListResult(definitions))

    def call(id: RequestId, params: Option[Json], sessionId: Option[String]): UIO[DispatchResult] =
      params match
        case None =>
          ZIO.succeed(DispatchResult.Single(
            ResponsePayload.failure(id, McpError.InvalidParams("Missing params"))
          ))
        case Some(json) =>
          json.as[ToolCallParams] match
            case Left(err) =>
              ZIO.succeed(DispatchResult.Single(
                ResponsePayload.failure(id, McpError.InvalidParams(err))
              ))
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
        runToolToPayload(id, name, args, ctx)
          .map(DispatchResult.Single(_))
          .tap(_ => emitToolEvent(ctx.sessionId, name))) @@ span("mcp.tool.call")

    /** Streaming tool call -- progress token present, returns Streaming.
      *
      * The queue carries domain [[StreamFrame]]s; the stream terminates after
      * the first `Final`. The transport adapter encodes each frame to its wire
      * representation.
      *
      * Concurrency is bounded by `maxInFlight`. If the budget is exhausted,
      * the call is rejected up front with a JSON-RPC custom error rather than
      * accumulating fiber state.
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
        reserveSlot(id).flatMap {
          case false =>
            ZIO.succeed(DispatchResult.Single(ResponsePayload.failure(
              id,
              McpError.Custom(
                code = -32099,
                message = s"Server overloaded: $maxInFlight concurrent tool calls already in flight"
              )
            )))
          case true =>
            for
              queue   <- Queue.unbounded[StreamFrame]
              reporter = makeProgressReporter(token, queue)
              fiber   <- ProgressReporter.current.locally(reporter)(runToolStreaming(id, name, args, ctx, queue)).forkDaemon
              _       <- inFlight.update(_ + (id -> fiber))
              _       <- ZIO.logDebug("Fiber registered in inFlight")
            yield DispatchResult.Streaming(streamFor(id, queue))
        }) @@
        span("mcp.tool.call") @@
        ZIOAspect.annotated("requestId", id.toString) @@
        ZIOAspect.annotated("tool", name) @@
        ZIOAspect.annotated("progressToken", token.toString)

    /** Run a tool, mapping success/typed-error to a [[ResponsePayload]].
      * Used by both sync and streaming paths.
      */
    private def runToolToPayload(
        id: RequestId,
        name: String,
        args: Json,
        ctx: ToolContext
    ): UIO[ResponsePayload] =
      toolRegistry
        .call(name, args, ctx)
        .map(content => encodeResult(id, contentToResult(content)))
        .catchAll {
          case err: ToolError.InvalidInput =>
            ZIO.succeed(ResponsePayload.failure(id, McpError.InvalidParams(err.message)))
          case err =>
            ZIO.succeed(ResponsePayload.failure(id, McpError.InternalError(err.message)))
        }

    /** Streaming variant: run the tool, push the final payload to the queue,
      * surface interruption as a synthetic Final so the consumer's stream
      * always terminates cleanly.
      */
    private def runToolStreaming(
        id: RequestId,
        name: String,
        args: Json,
        ctx: ToolContext,
        queue: Queue[StreamFrame]
    ): UIO[Unit] =
      (for
        _       <- ZIO.logDebug("Tool execution starting")
        payload <- runToolToPayload(id, name, args, ctx)
        _       <- ZIO.logDebug("Tool execution completed")
        _       <- queue.offer(StreamFrame.Final(payload))
        _       <- ZIO.logDebug("Final frame offered")
      yield ()).catchAllCause { cause =>
        ZIO.logDebug("Caught cause, offering interrupted final") @@
          ZIOAspect.annotated("interrupted", cause.isInterrupted.toString) *>
          queue
            .offer(StreamFrame.Final(
              ResponsePayload.failure(id, McpError.InternalError("tool execution interrupted"))
            ))
            .ignore
      }

    /** Build the SSE-emitted stream for a streaming call: drains until the
      * first `Final`, then unregisters, releases the budget slot, and shuts
      * down the queue.
      */
    private def streamFor(id: RequestId, queue: Queue[StreamFrame]): ZStream[Any, Nothing, StreamFrame] =
      ZStream
        .fromQueue(queue)
        .takeUntil:
          case _: StreamFrame.Final => true
          case _                    => false
        .ensuring(
          ZIO.logDebug("Stream finalized") *>
            inFlight.update(_ - id) *>
            inFlightCount.update(_ - 1) *>
            queue.shutdown
        )

    /** Atomically reserve one budget slot. Returns true if a slot was
      * available (and consumed) or false if the cap is reached. The slot is
      * released by [[streamFor]]'s `.ensuring` block when the stream
      * finalizes. We use a separate counter `Ref` rather than checking the
      * map size to avoid the placeholder-fiber gymnastics.
      */
    private def reserveSlot(id: RequestId): UIO[Boolean] =
      inFlightCount.modify { n =>
        if n >= maxInFlight then (false, n)
        else (true, n + 1)
      }

    private def makeProgressReporter(token: Json, queue: Queue[StreamFrame]): ProgressReporter =
      new ProgressReporter:
        def report(progress: Double, total: Option[Double], message: Option[String]): UIO[Unit] =
          val notification = ProgressNotification(token, progress, total, message)
          notification.toJsonAST match
            case Right(params) =>
              queue.offer(StreamFrame.Notification("notifications/progress", params)).unit
            case Left(_) =>
              // ProgressNotification is a fixed shape; encoding can't realistically fail.
              // Drop the notification rather than failing the whole stream.
              ZIO.unit

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

    private def encodeResult[A: JsonEncoder](id: RequestId, value: A): ResponsePayload =
      value.toJsonAST match
        case Right(json) => ResponsePayload.success(id, json)
        case Left(err) =>
          ResponsePayload.failure(
            id,
            McpError.InternalError(s"failed to encode result: $err")
          )
