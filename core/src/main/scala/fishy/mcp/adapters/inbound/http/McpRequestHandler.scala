package fishy.mcp.adapters.inbound.http

import fishy.mcp.application.ports.{MessageRouter, SessionStore}
import fishy.mcp.application.usecase.{ClientRequester, McpDispatcher}
import fishy.mcp.domain.model.DispatchResult
import fishy.mcp.adapters.protocol.jsonrpc.{
  Error as JsonRpcError,
  ErrorCode,
  ErrorResponse,
  Request as McpRequest,
  RequestId,
  Response as McpResponse
}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Handles POST /mcp: JSON-RPC single and batch dispatch, session creation, client response
  * correlation, and direct response.
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
            body <- request.body.asString
            _ <- ZIO.logDebug(s"body length=${body.length}")
            response <- processRequest(body, sessionId)
            _ <- ZIO.logDebug(s"response status=${response.status.code}")
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
        val errorResponse = ErrorResponse.parseError(RequestId.Null)
        ZIO.succeed(Response.json(errorResponse.toJson).status(Status.BadRequest))
      case Right(Json.Arr(elements)) =>
        processBatch(elements, sessionId)
      case Right(json) =>
        // Detect if this is a client response (has result/error, no method)
        val obj = json.asObject
        val hasMethod = obj.exists(_.contains("method"))
        val hasResult = obj.exists(_.contains("result"))
        val hasError = obj.exists(_.contains("error"))
        if !hasMethod && (hasResult || hasError) then
          handleClientResponse(json)
        else
          body.fromJson[McpRequest] match
            case Left(_) =>
              ZIO.succeed(Response.json(ErrorResponse.invalidRequest(RequestId.Null).toJson).status(
                Status.BadRequest
              ))
            case Right(mcpRequest) =>
              processSingle(mcpRequest, sessionId)

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
            _ <- ZIO.logDebug("dispatching")
            result <- dispatcher.dispatch(mcpRequest, sessionId)
            _ <- ZIO.logDebug(s"dispatch returned ${result.getClass.getSimpleName}")
            newSessionId <- handleSession(mcpRequest.method, sessionId)
            response <- buildResponse(result)
            _ <- ZIO.logDebug(s"response status=${response.status.code}")
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
      ZIO.succeed(
        Response.json(ErrorResponse.invalidRequest(RequestId.Null).toJson).status(Status.BadRequest)
      )
    else
      val requests = elements.map(_.toJson.fromJson[McpRequest])
      if requests.collect { case Right(r) => r }.exists(_.method == "initialize") then
        ZIO.succeed(Response.json(
          ErrorResponse(
            RequestId.Null,
            ErrorCode.InvalidRequest,
            "initialize must not be sent inside a batch"
          ).toJson
        ).status(Status.BadRequest))
      else
        for
          results <- ZIO.foreach(elements) { elem =>
            elem.toJson.fromJson[McpRequest] match
              case Left(_) =>
                val err = ErrorResponse.invalidRequest(RequestId.Null)
                ZIO.succeed(Some(err.toJsonAST.toOption.get))
              case Right(req) =>
                dispatcher.dispatch(req, sessionId).flatMap(_.toOption).map {
                  case Some(Right(success)) => Some(success.toJsonAST.toOption.get)
                  case Some(Left(error))    => Some(error.toJsonAST.toOption.get)
                  case None                 => None
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
        case DispatchResult.Single(Right(success)) =>
          ZIO.logDebug("returning success").as(Response.json(success.toJson))
        case DispatchResult.Single(Left(error)) =>
          ZIO.logDebug("returning error").as(Response.json(error.toJson).status(Status.BadRequest))
        case DispatchResult.Streaming(events) =>
          ZIO.logDebug("returning SSE stream") *>
            ZIO.succeed {
              val sseStream = events.map(json => ServerSentEvent(data = json))
              Response.fromServerSentEvents(sseStream)
            }
    }

  // ---------------------------------------------------------------------------
  // Client response handling (for server-to-client requests)
  // ---------------------------------------------------------------------------

  private def handleClientResponse(json: Json): ZIO[Any, Nothing, Response] =
    val obj = json.asObject.get
    val requestId = obj.get("id") match
      case Some(Json.Str(s)) => s
      case Some(Json.Num(n)) => n.longValue.toString
      case _                 => ""

    if requestId.isEmpty then
      ZIO.succeed(Response.json(
        ErrorResponse.invalidRequest(RequestId.Null).toJson
      ).status(Status.BadRequest))
    else
      val result: Either[JsonRpcError, Json] = obj.get("error") match
        case Some(errorJson) =>
          errorJson.as[JsonRpcError] match
            case Right(err) => Left(err)
            case Left(_)    => Left(JsonRpcError(-32603, "Malformed error response"))
        case None =>
          Right(obj.get("result").getOrElse(Json.Obj()))

      ZIO.logDebug(s"Client response received for request id=$requestId") *>
        clientRequester.completeRequest(requestId, result).map { completed =>
          if completed then Response.status(Status.Accepted)
          else
            Response.json(
              ErrorResponse(
                RequestId.StringId(requestId),
                ErrorCode.InvalidRequest,
                "No pending request with this ID"
              ).toJson
            ).status(Status.BadRequest)
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
