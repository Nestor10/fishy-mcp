package fishy.mcp.adapters.protocol.jsonrpc

import zio.json.*
import zio.json.ast.Json

/** JSON-RPC 2.0 message types.
  *
  * @see
  *   https://www.jsonrpc.org/specification
  */

/** Request ID: string, number, or null per JSON-RPC spec. */
enum RequestId:
  case StringId(value: String)
  case NumberId(value: Long)
  case Null

object RequestId:
  given JsonDecoder[RequestId] = JsonDecoder[Json].mapOrFail:
    case Json.Str(s) => Right(StringId(s))
    case Json.Num(n) => Right(NumberId(n.longValue))
    case Json.Null   => Right(Null)
    case other       => Left(s"Invalid request id: $other")

  given JsonEncoder[RequestId] = JsonEncoder[Json].contramap:
    case StringId(s) => Json.Str(s)
    case NumberId(n) => Json.Num(n)
    case Null        => Json.Null

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
