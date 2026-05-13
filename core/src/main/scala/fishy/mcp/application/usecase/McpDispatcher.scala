package fishy.mcp.application.usecase

import fishy.mcp.domain.model.*
import fishy.mcp.adapters.protocol.jsonrpc.Request
import fishy.mcp.domain.model.mcp.*
import fishy.mcp.application.ports.SessionStore
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

/** JSON-RPC router for MCP.
  *
  * Enforces the initialization gate and delegates to executor services. Init
  * gate is backed by SessionStore for cross-instance consistency. When
  * sessionId is None (sessionless HTTP or stdio), the gate is skipped.
  *
  * Output is domain-shaped ([[DispatchResult]] / [[ResponsePayload]] /
  * [[StreamFrame]]). The transport adapter renders to the wire via
  * `DispatchResultEncoder`.
  */
trait McpDispatcher:

  /** Dispatch a JSON-RPC request within a session context. */
  def dispatch(request: Request, sessionId: Option[String]): UIO[DispatchResult]

object McpDispatcher:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def dispatch(request: Request, sessionId: Option[String] = None) =
    ZIO.serviceWithZIO[McpDispatcher](_.dispatch(request, sessionId))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  def layer(
      serverInfo: ServerInfo,
      capabilities: ServerCapabilities,
      instructions: Option[String] = None
  ): URLayer[
    ToolExecutor & ResourceExecutor & PromptExecutor & SessionStore & ClientRequester & Tracing,
    McpDispatcher
  ] =
    ZLayer.fromZIO {
      for
        store      <- ZIO.service[SessionStore]
        toolExec   <- ZIO.service[ToolExecutor]
        resExec    <- ZIO.service[ResourceExecutor]
        promptExec <- ZIO.service[PromptExecutor]
        clientReq  <- ZIO.service[ClientRequester]
        tracing    <- ZIO.service[Tracing]
      yield Live(
        serverInfo,
        capabilities,
        instructions,
        store,
        toolExec,
        resExec,
        promptExec,
        clientReq,
        tracing
      )
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  /** Methods allowed before initialization completes. */
  private val preInitMethods: Set[String] = Set("initialize", "ping")

  private final case class Live(
      serverInfo: ServerInfo,
      capabilities: ServerCapabilities,
      instructions: Option[String],
      sessionStore: SessionStore,
      toolExecutor: ToolExecutor,
      resourceExecutor: ResourceExecutor,
      promptExecutor: PromptExecutor,
      clientRequester: ClientRequester,
      tracing: Tracing
  ) extends McpDispatcher:

    import tracing.aspects.*

    def dispatch(request: Request, sessionId: Option[String]): UIO[DispatchResult] =
      val id = request.id.getOrElse(RequestId.Null)
      val method = request.method

      val body =
        AuthFiberRef.currentAuth.get.flatMap { auth =>
          ZIO.logAnnotate("method", method) {
            ZIO.logAnnotate("sessionId", sessionId.getOrElse("none")) {
              ZIO.logAnnotate("requestId", id.toString) {
                ZIO.logAnnotate("authSub", auth.map(_.sub).getOrElse("anonymous")) {
                  if method.startsWith("notifications/") then
                    handleNotification(method, sessionId, request.params).as(DispatchResult.Empty)
                  else if preInitMethods.contains(method) then
                    handleMethod(method, id, request.params, sessionId)
                  else
                    sessionId match
                      case None => handleMethod(method, id, request.params, sessionId)
                      case Some(sid) =>
                        sessionStore.isInitialized(sid).flatMap {
                          case true => handleMethod(method, id, request.params, sessionId)
                          case false =>
                            ZIO.succeed(DispatchResult.Single(
                              ResponsePayload.failure(
                                id,
                                McpError.InvalidRequest("Server not initialized. Send initialize first.")
                              )
                            ))
                        }
                }
              }
            }
          }
        }

      (tracing.setAttribute("mcp.method", method) *>
        tracing.setAttribute("mcp.session_id", sessionId.getOrElse("none")) *>
        tracing.setAttribute("mcp.request_id", id.toString) *>
        body) @@ root("mcp.dispatch")

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    private def handleMethod(
        method: String,
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[DispatchResult] =
      method match
        case "initialize"     => handleInitialize(id, params, sessionId).map(DispatchResult.Single(_))
        case "ping"           => handlePing(id).map(DispatchResult.Single(_))
        case "tools/list"     => toolExecutor.list(id).map(DispatchResult.Single(_))
        case "tools/call"     => toolExecutor.call(id, params, sessionId)
        case "resources/list" => resourceExecutor.list(id).map(DispatchResult.Single(_))
        case "resources/read" => resourceExecutor.read(id, params).map(DispatchResult.Single(_))
        case "resources/templates/list" =>
          resourceExecutor.templatesList(id).map(DispatchResult.Single(_))
        case "resources/subscribe" =>
          ZIO.logDebug(s"resources/subscribe called: params=$params session=$sessionId") *>
            resourceExecutor.subscribe(id, params, sessionId).map(DispatchResult.Single(_))
        case "resources/unsubscribe" =>
          ZIO.logDebug(s"resources/unsubscribe called: params=$params session=$sessionId") *>
            resourceExecutor.unsubscribe(id, params, sessionId).map(DispatchResult.Single(_))
        case "prompts/list" => promptExecutor.list(id).map(DispatchResult.Single(_))
        case "prompts/get"  => promptExecutor.get(id, params).map(DispatchResult.Single(_))
        case other =>
          ZIO.succeed(DispatchResult.Single(
            ResponsePayload.failure(id, McpError.MethodNotFound(other))
          ))

    private def handleNotification(
        method: String,
        sessionId: Option[String],
        params: Option[Json]
    ): UIO[Unit] =
      method match
        case "notifications/initialized" =>
          sessionId match
            case Some(sid) => sessionStore.markInitialized(sid)
            case None      => ZIO.unit
        case "notifications/cancelled" =>
          handleCancelled(params)
        case _ => ZIO.unit

    // -------------------------------------------------------------------------
    // Cancellation -- delegates to ToolExecutor
    // -------------------------------------------------------------------------

    private def handleCancelled(params: Option[Json]): UIO[Unit] =
      params match
        case None => ZIO.logDebug("No params on cancel notification")
        case Some(json) =>
          json.as[CancelledParams] match
            case Left(_) => ZIO.logDebug("Failed to parse CancelledParams")
            case Right(cancelParams) =>
              val targetId = cancelParams.requestId match
                case Json.Str(s) => RequestId.StringId(s)
                case Json.Num(n) => RequestId.NumberId(n.longValue)
                case _           => RequestId.Null
              toolExecutor.cancel(targetId)

    // -------------------------------------------------------------------------
    // Handlers (only initialize + ping remain here)
    // -------------------------------------------------------------------------

    private def handleInitialize(
        id: RequestId,
        params: Option[Json],
        sessionId: Option[String]
    ): UIO[ResponsePayload] =
      val storeClientCaps = (params, sessionId) match
        case (Some(json), Some(sid)) =>
          json.as[InitializeParams] match
            case Right(initParams) =>
              clientRequester.registerClientCapabilities(sid, initParams.capabilities)
            case Left(_) => ZIO.unit
        case _ => ZIO.unit

      storeClientCaps.as(
        encodeResult(
          id,
          InitializeResult(
            protocolVersion = "2024-11-05",
            capabilities = capabilities,
            serverInfo = serverInfo,
            instructions = instructions
          )
        )
      )

    private def handlePing(id: RequestId): UIO[ResponsePayload] =
      ZIO.succeed(ResponsePayload.success(id, Json.Obj()))

    private def encodeResult[A: JsonEncoder](id: RequestId, value: A): ResponsePayload =
      value.toJsonAST match
        case Right(json) => ResponsePayload.success(id, json)
        case Left(err) =>
          ResponsePayload.failure(
            id,
            McpError.InternalError(s"failed to encode result: $err")
          )
