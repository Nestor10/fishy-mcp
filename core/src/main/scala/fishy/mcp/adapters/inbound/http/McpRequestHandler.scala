package fishy.mcp.adapters.inbound.http

import fishy.mcp.application.ports.{MessageRouter, SessionStore}
import fishy.mcp.application.usecase.{ClientRequester, McpDispatcher}
import fishy.mcp.domain.model.{DispatchResult, McpError, RequestId, ResponsePayload}
import fishy.mcp.adapters.protocol.jsonrpc.{
  DispatchResultEncoder,
  Error as JsonRpcError,
  ErrorCode,
  ErrorResponse,
  InboundMessage,
  Request as McpRequest,
  Response as McpResponse
}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Handles POST /mcp: JSON-RPC single and batch dispatch, session creation,
  * client response correlation, and direct response.
  *
  * Wire encoding lives in [[DispatchResultEncoder]]; this handler handles
  * HTTP shaping only.
  */
private[http] final case class McpRequestHandler(
    dispatcher: McpDispatcher,
    sessionStore: SessionStore,
    clientRequester: ClientRequester
):

  private val McpSessionIdHeader = "Mcp-Session-Id"

  def handle(request: Request): ZIO[Any, Throwable, Response] =
    val sessionId = request.headers.get(McpSessionIdHeader)
    ZIO.logAnnotate("transport", "POST") {
      ZIO.logAnnotate("sessionId", sessionId.getOrElse("none")) {
        ZIO.logSpan("handleMcpRequest") {
          for
            body     <- request.body.asString
            _        <- ZIO.logDebug(s"body length=${body.length}")
            response <- processRequest(body, sessionId)
            _        <- ZIO.logDebug(s"response status=${response.status.code}")
          yield response
        }
      }
    }

  // ---------------------------------------------------------------------------
  // Request parsing
  // ---------------------------------------------------------------------------

  private def processRequest(
      body: String,
      sessionId: Option[String]
  ): ZIO[Any, Throwable, Response] =
    Json.decoder.decodeJson(body) match
      case Left(_) =>
        ZIO.succeed(jsonResponse(parseError, Status.BadRequest))
      case Right(Json.Arr(elements)) =>
        processBatch(elements, sessionId)
      case Right(json) =>
        InboundMessage.parse(json) match
          case Left(_) =>
            ZIO.succeed(jsonResponse(invalidRequestError, Status.BadRequest))
          case Right(InboundMessage.Dispatch(request)) =>
            processSingle(request, sessionId)
          case Right(InboundMessage.ClientResponse(id, outcome)) =>
            handleClientResponse(id, outcome)

  // ---------------------------------------------------------------------------
  // Single request
  // ---------------------------------------------------------------------------

  private def processSingle(
      mcpRequest: McpRequest,
      sessionId: Option[String]
  ): ZIO[Any, Throwable, Response] =
    val requestId = mcpRequest.id.map(_.toString).getOrElse("notification")
    ZIO.logAnnotate("method", mcpRequest.method) {
      ZIO.logAnnotate("requestId", requestId) {
        ZIO.logSpan("processSingle") {
          for
            _            <- ZIO.logDebug("dispatching")
            result       <- dispatcher.dispatch(mcpRequest, sessionId)
            _            <- ZIO.logDebug(s"dispatch returned ${result.getClass.getSimpleName}")
            newSessionId <- handleSession(mcpRequest.method, sessionId)
            response     <- buildResponse(result)
            _            <- ZIO.logDebug(s"response status=${response.status.code}")
            withSession = newSessionId match
              case Some(sid) => response.addHeader(Header.Custom(McpSessionIdHeader, sid))
              case None      => response
          yield withSession
        }
      }
    }

  // ---------------------------------------------------------------------------
  // Batch request
  // ---------------------------------------------------------------------------

  private def processBatch(
      elements: Chunk[Json],
      sessionId: Option[String]
  ): ZIO[Any, Throwable, Response] =
    if elements.isEmpty then
      ZIO.succeed(jsonResponse(invalidRequestError, Status.BadRequest))
    else
      val requests = elements.map(_.toJson.fromJson[McpRequest])
      if requests.collect { case Right(r) => r }.exists(_.method == "initialize") then
        ZIO.succeed(jsonResponse(
          ErrorResponse(
            RequestId.Null,
            ErrorCode.InvalidRequest,
            "initialize must not be sent inside a batch"
          ).toJson,
          Status.BadRequest
        ))
      else
        for
          results <- ZIO.foreach(elements) { elem =>
            elem.toJson.fromJson[McpRequest] match
              case Left(_) =>
                ZIO.succeed(Some(asJson(invalidRequestError)))
              case Right(req) =>
                dispatcher.dispatch(req, sessionId).flatMap(_.toOption).map {
                  case Some(payload) =>
                    Some(asJson(DispatchResultEncoder.encodePayloadJson(payload)))
                  case None => None
                }
          }
          responses = results.flatten
        yield
          if responses.isEmpty then Response.status(Status.Accepted)
          else Response.json(Json.Arr(responses*).toJson)

  // ---------------------------------------------------------------------------
  // Response building -- POST always returns directly
  // ---------------------------------------------------------------------------

  private def buildResponse(result: DispatchResult): ZIO[Any, Nothing, Response] =
    ZIO.logSpan("buildResponse") {
      result match
        case DispatchResult.Empty =>
          ZIO.logDebug("empty result").as(Response.status(Status.Accepted))
        case DispatchResult.Single(payload) =>
          val body   = DispatchResultEncoder.encodePayloadJson(payload)
          val status = if payload.outcome.isLeft then Status.BadRequest else Status.Ok
          ZIO.logDebug(s"returning ${if payload.outcome.isLeft then "error" else "success"}")
            .as(Response.json(body).status(status))
        case DispatchResult.Streaming(events) =>
          ZIO.logDebug("returning SSE stream") *>
            ZIO.succeed {
              val sseStream = events.map { frame =>
                ServerSentEvent(data = DispatchResultEncoder.encodeFrameJson(frame))
              }
              Response.fromServerSentEvents(sseStream)
            }
    }

  // ---------------------------------------------------------------------------
  // Client response handling (for server-to-client requests)
  // ---------------------------------------------------------------------------

  private def handleClientResponse(
      id: RequestId,
      outcome: Either[JsonRpcError, Json]
  ): ZIO[Any, Nothing, Response] =
    val requestId = id match
      case RequestId.StringId(s) => s
      case RequestId.NumberId(n) => n.toString
      case RequestId.Null        => ""

    if requestId.isEmpty then
      ZIO.succeed(jsonResponse(invalidRequestError, Status.BadRequest))
    else
      ZIO.logDebug(s"Client response received for request id=$requestId") *>
        clientRequester.completeRequest(requestId, outcome).map { completed =>
          if completed then Response.status(Status.Accepted)
          else
            jsonResponse(
              ErrorResponse(
                RequestId.StringId(requestId),
                ErrorCode.InvalidRequest,
                "No pending request with this ID"
              ).toJson,
              Status.BadRequest
            )
        }

  // ---------------------------------------------------------------------------
  // Session management
  // ---------------------------------------------------------------------------

  private def handleSession(
      method: String,
      existingSessionId: Option[String]
  ): UIO[Option[String]] =
    method match
      case "initialize" => sessionStore.create().map(Some(_))
      case _            => ZIO.succeed(existingSessionId)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def jsonResponse(body: String, status: Status): Response =
    Response.json(body).status(status)

  /** Parse a serialized JSON-RPC response/error body back to its AST so it can
    * be embedded in a batch array. The serialization just happened immediately
    * upstream so this can't realistically fail.
    */
  private def asJson(body: String): Json =
    Json.decoder.decodeJson(body).getOrElse(Json.Obj())

  private val parseError: String =
    ErrorResponse.parseError(RequestId.Null).toJson

  private val invalidRequestError: String =
    ErrorResponse.invalidRequest(RequestId.Null).toJson
