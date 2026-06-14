package fishy.mcp.adapters.protocol.jsonrpc

import fishy.mcp.domain.model.RequestId
import zio.json.*
import zio.json.ast.Json

/** JSON-RPC 2.0 message types.
  *
  * @see
  *   https://www.jsonrpc.org/specification
  */

object RequestIdCodec:
  given JsonDecoder[RequestId] = JsonDecoder[Json].mapOrFail:
    case Json.Str(s) => Right(RequestId.StringId(s))
    case Json.Num(n) => Right(RequestId.NumberId(n.longValue))
    case Json.Null   => Right(RequestId.Null)
    case other       => Left(s"Invalid request id: $other")

  given JsonEncoder[RequestId] = JsonEncoder[Json].contramap:
    case RequestId.StringId(s) => Json.Str(s)
    case RequestId.NumberId(n) => Json.Num(n)
    case RequestId.Null        => Json.Null

import RequestIdCodec.given

final case class Request(
    jsonrpc: String,
    method: String,
    params: Option[Json],
    id: Option[RequestId]
)

object Request:
  given JsonDecoder[Request] = DeriveJsonDecoder.gen
  given JsonEncoder[Request] = DeriveJsonEncoder.gen

  extension (r: Request)
    def isNotification: Boolean = r.id.isEmpty

/** A JSON-RPC 2.0 notification: a method call with no `id`, expecting no
  * response. The single typed source for outbound server→client notification
  * framing (progress, `list_changed`, logging, …) -- use [[Notification.make]]
  * instead of hand-building `Json.Obj("jsonrpc" -> ...)`. */
final case class Notification(
    jsonrpc: String,
    method: String,
    params: Option[Json] = None
)

object Notification:
  given JsonDecoder[Notification] = DeriveJsonDecoder.gen
  given JsonEncoder[Notification] = DeriveJsonEncoder.gen

  /** Build a `"2.0"` notification. `params` is omitted from the wire when None. */
  def make(method: String, params: Option[Json] = None): Notification =
    Notification("2.0", method, params)

final case class Response(
    jsonrpc: String,
    result: Json,
    id: RequestId
)

object Response:
  given JsonDecoder[Response] = DeriveJsonDecoder.gen
  given JsonEncoder[Response] = DeriveJsonEncoder.gen

  def success(id: RequestId, result: Json): Response =
    Response("2.0", result, id)

final case class Error(
    code: Int,
    message: String,
    data: Option[Json] = None
)

object Error:
  given JsonDecoder[Error] = DeriveJsonDecoder.gen
  given JsonEncoder[Error] = DeriveJsonEncoder.gen

final case class ErrorResponse(
    jsonrpc: String,
    error: Error,
    id: RequestId
)

object ErrorResponse:
  given JsonDecoder[ErrorResponse] = DeriveJsonDecoder.gen
  given JsonEncoder[ErrorResponse] = DeriveJsonEncoder.gen

  def apply(id: RequestId, code: Int, message: String): ErrorResponse =
    ErrorResponse("2.0", Error(code, message), id)

  def parseError(id: RequestId): ErrorResponse =
    apply(id, ErrorCode.ParseError, "Parse error")

  def invalidRequest(id: RequestId): ErrorResponse =
    apply(id, ErrorCode.InvalidRequest, "Invalid request")

  def methodNotFound(id: RequestId, method: String): ErrorResponse =
    apply(id, ErrorCode.MethodNotFound, s"Method not found: $method")

  def invalidParams(id: RequestId, detail: String): ErrorResponse =
    apply(id, ErrorCode.InvalidParams, s"Invalid params: $detail")

  def internalError(id: RequestId, detail: String): ErrorResponse =
    apply(id, ErrorCode.InternalError, s"Internal error: $detail")
