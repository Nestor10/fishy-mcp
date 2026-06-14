package fishy.mcp.adapters.protocol.jsonrpc

import fishy.mcp.adapters.protocol.jsonrpc.RequestIdCodec.given
import fishy.mcp.domain.model.{McpError, ResponsePayload, StreamFrame}
import zio.json.*
import zio.json.ast.Json

/** Encodes domain dispatch values to JSON-RPC wire format.
  *
  * This is the only place that knows how a [[ResponsePayload]] becomes a
  * `Response`/`ErrorResponse`, and how a [[StreamFrame]] becomes a wire
  * notification or response string. Use-cases stay free of wire concerns.
  */
object DispatchResultEncoder:

  /** Serialize a payload to a wire envelope (success or error). */
  def encodePayload(p: ResponsePayload): Either[ErrorResponse, Response] =
    p.outcome match
      case Right(result) => Right(Response.success(p.id, result))
      case Left(err)     => Left(toErrorResponse(p.id, err))

  /** Serialize a payload directly to its wire JSON string. */
  def encodePayloadJson(p: ResponsePayload): String =
    encodePayload(p).fold(_.toJson, _.toJson)

  /** Serialize one streaming frame to its wire JSON string (one SSE data line). */
  def encodeFrameJson(frame: StreamFrame): String =
    frame match
      case StreamFrame.Notification(method, params) =>
        Notification.make(method, Some(params)).toJson
      case StreamFrame.Final(payload) =>
        encodePayloadJson(payload)

  private def toErrorResponse(id: fishy.mcp.domain.model.RequestId, err: McpError): ErrorResponse =
    ErrorResponse("2.0", Error(err.code, err.message, err.data), id)
