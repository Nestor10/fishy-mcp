package fishy.mcp.application.usecase

import fishy.mcp.application.ports.{MessageRouter, ToolRegistry}
import fishy.mcp.adapters.protocol.jsonrpc.Notification
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
        fibers             <- Ref.make(Map.empty[RequestId, Fiber.Runtime[Nothing, Any]])
        count              <- Ref.make(0)
      yield Live(
        toolRegistry,
        clientRequester,
        notificationSender,
        messageRouter,
        tracing,
        new InFlightCalls(maxInFlight, count, fibers),
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
      calls: InFlightCalls,
      publishToolEvents: Boolean
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
                ctx = ToolContext(
                  requestId = id.toString,
                  sessionId = sessionId,
                  meta = callParams._meta,
                  auth = auth,
                  client = sessionId.fold(ClientChannel.unavailable)(channelFor),
                  resources = resourceNotifier
                )
                result <- callParams.progressToken match
                  case None      => callSync(id, callParams.name, args, ctx)
                  case Some(tok) => callStreaming(id, callParams.name, args, tok, ctx)
              yield result

    private def channelFor(sessionId: String): ClientChannel = new ClientChannel:
      def request(method: String, params: Json): IO[ClientRequesterError, Json] =
        clientRequester.sendRequest(sessionId, method, params)

    private val resourceNotifier: ResourceNotifier = new ResourceNotifier:
      def updated(uri: String): UIO[Unit] = notificationSender.resourceUpdated(uri)

    def cancel(requestId: RequestId): UIO[Unit] =
      calls.cancel(requestId) @@ ZIOAspect.annotated("requestId", requestId.toString)

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private def callSync(
        id: RequestId,
        name: String,
        args: Json,
        ctx: ToolContext
    ): UIO[DispatchResult] =
      // Bounded like streaming: a flood of slow (e.g. DB-bound) sync calls can
      // otherwise saturate just as easily. Slot held only for the execution.
      calls.reserve.flatMap {
        case false => ZIO.succeed(DispatchResult.Single(calls.overloaded(id)))
        case true =>
          val run =
            tracing.setAttribute("mcp.tool.name", name) *>
              runToolToPayload(id, name, args, ctx)
                .map(DispatchResult.Single(_))
                .tap(_ => emitToolEvent(ctx.sessionId, name))
          (run @@ span("mcp.tool.call")).ensuring(calls.release)
      }

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
        calls.reserve.flatMap {
          case false =>
            ZIO.succeed(DispatchResult.Single(calls.overloaded(id)))
          case true =>
            for
              queue   <- Queue.unbounded[StreamFrame]
              // The streaming call gets a live progress reporter on its context;
              // the forked tool fiber reports via `ctx.progress`.
              streamingCtx = ctx.copy(progress = makeProgressReporter(token, queue))
              fiber   <- runToolStreaming(id, name, args, streamingCtx, queue).forkDaemon
              _       <- calls.register(id, fiber)
              _       <- ZIO.logDebug("Fiber registered in inFlight")
            yield DispatchResult.Streaming(streamFor(id, queue, fiber))
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
        .map(content => encodeResult(id, ToolCallResult(List(content))))
        // A tool error (bad arguments, validation failure, execution failure) is returned
        // as an `isError` tool *result*, not a JSON-RPC protocol error: MCP clients surface
        // result content to the model (so it can self-correct) but genericize protocol
        // errors. Also logged — a tool failure should never be silent server-side.
        .catchAll { err =>
          ZIO.logWarning(s"tool '$name' failed: ${err.message}") *>
            ZIO.succeed(encodeResult(id, ToolCallResult.error(err.message)))
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
      * first `Final`, then interrupts the tool fiber so it never outlives its
      * consumer (e.g. on client disconnect), unregisters it, releases the
      * budget slot, and shuts down the queue.
      */
    private def streamFor(
        id: RequestId,
        queue: Queue[StreamFrame],
        fiber: Fiber.Runtime[Nothing, Any]
    ): ZStream[Any, Nothing, StreamFrame] =
      ZStream
        .fromQueue(queue)
        .takeUntil:
          case _: StreamFrame.Final => true
          case _                    => false
        .ensuring(
          ZIO.logDebug("Stream finalized") *>
            fiber.interrupt *>
            calls.unregister(id) *>
            calls.release *>
            queue.shutdown
        )

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

    private def emitToolEvent(sessionId: Option[String], toolName: String): UIO[Unit] =
      if !publishToolEvents then ZIO.unit
      else
        sessionId match
          case None => ZIO.unit
          case Some(sid) =>
            val params = Json.Obj(
              "level"  -> Json.Str("info"),
              "logger" -> Json.Str("mcp.tools"),
              "data"   -> Json.Obj("tool" -> Json.Str(toolName))
            )
            messageRouter.publish(sid, Notification.make("notifications/message", Some(params)).toJson).unit

    private def encodeResult[A: JsonEncoder](id: RequestId, value: A): ResponsePayload =
      value.toJsonAST match
        case Right(json) => ResponsePayload.success(id, json)
        case Left(err) =>
          ResponsePayload.failure(
            id,
            McpError.InternalError(s"failed to encode result: $err")
          )

  // ---------------------------------------------------------------------------
  // In-flight call lifecycle (budget + cancellation registry)
  // ---------------------------------------------------------------------------

  /** Owns the lifecycle of in-flight tool calls so the executor stays thin
    * routing. Two concerns, one place:
    *
    *   - a concurrency budget enforced by an atomic counter ([[reserve]] /
    *     [[release]]). `reserve` *rejects* (returns `false`) when the cap is
    *     reached rather than blocking, so a hostile or buggy client cannot park
    *     unbounded fiber/request state. We use a counter `Ref` rather than a
    *     blocking `Semaphore` deliberately: this is a DoS guard, and the
    *     reject-on-full variant is the right primitive (zionomicon ch.9 for the
    *     atomic `Ref.modify`; ch.13 frames the work-limiting trade-off).
    *   - a registry of running fibers for cooperative cancellation
    *     ([[register]] / [[unregister]] / [[cancel]]; zionomicon ch.7–8 fiber
    *     supervision + interruption).
    */
  private final class InFlightCalls(
      maxInFlight: Int,
      count: Ref[Int],
      fibers: Ref[Map[RequestId, Fiber.Runtime[Nothing, Any]]]
  ):

    /** Atomically take a slot, or `false` if the budget is exhausted. */
    val reserve: UIO[Boolean] =
      count.modify(n => if n >= maxInFlight then (false, n) else (true, n + 1))

    /** Return a previously-reserved slot. */
    val release: UIO[Unit] =
      count.update(n => math.max(0, n - 1))

    def register(id: RequestId, fiber: Fiber.Runtime[Nothing, Any]): UIO[Unit] =
      fibers.update(_ + (id -> fiber))

    def unregister(id: RequestId): UIO[Unit] =
      fibers.update(_ - id)

    def cancel(id: RequestId): UIO[Unit] =
      fibers.get.flatMap(_.get(id) match
        case Some(fiber) => ZIO.logDebug("Interrupting in-flight tool fiber") *> fiber.interruptFork.unit
        case None        => ZIO.logDebug("No in-flight tool fiber to cancel")
      )

    /** The reject-on-full overload payload (JSON-RPC custom code `-32099`). */
    def overloaded(id: RequestId): ResponsePayload =
      ResponsePayload.failure(
        id,
        McpError.Custom(
          code = -32099,
          message = s"Server overloaded: $maxInFlight concurrent tool calls already in flight"
        )
      )
