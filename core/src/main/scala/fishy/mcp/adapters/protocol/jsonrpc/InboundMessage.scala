package fishy.mcp.adapters.protocol.jsonrpc

import fishy.mcp.domain.model.RequestId
import zio.json.*
import zio.json.ast.Json

/** A single inbound JSON-RPC message, classified once at the wire boundary so
  * every transport (HTTP, stdio) routes it the same way instead of each
  * re-deriving the taxonomy:
  *
  *   - [[Dispatch]] -- has a `method`, so it's a request or notification bound
  *     for `McpDispatcher`.
  *   - [[ClientResponse]] -- has an `id` plus `result`/`error` and no `method`,
  *     so it's a client's reply to a server-initiated request, bound for
  *     `ClientRequester`.
  *
  * This replaces the field-sniffing that used to live inside the HTTP handler
  * and the missing response-case in the stdio transport.
  */
enum InboundMessage:
  case Dispatch(request: Request)
  case ClientResponse(id: RequestId, result: Either[Error, Json])

object InboundMessage:

  /** Classify one (non-batch) JSON value. `Left` means the JSON is neither a
    * valid request/notification nor a recognizable response. */
  def parse(json: Json): Either[String, InboundMessage] =
    val obj = json.asObject
    if obj.exists(_.contains("method")) then
      json.as[Request].map(Dispatch(_))
    else
      obj.flatMap(_.get("id")) match
        case None => Left("not a JSON-RPC message: no `method` and no `id`")
        case Some(idJson) =>
          decodeId(idJson) match
            case None => Left(s"invalid JSON-RPC response id: $idJson")
            case Some(id) =>
              obj.flatMap(_.get("error")) match
                case Some(errJson) =>
                  val err = errJson.as[Error].getOrElse(
                    Error(ErrorCode.InternalError, "Malformed error response")
                  )
                  Right(ClientResponse(id, Left(err)))
                case None =>
                  val result = obj.flatMap(_.get("result")).getOrElse(Json.Obj())
                  Right(ClientResponse(id, Right(result)))

  private def decodeId(idJson: Json): Option[RequestId] =
    idJson match
      case Json.Str(s) => Some(RequestId.StringId(s))
      case Json.Num(n) => Some(RequestId.NumberId(n.longValue))
      case _           => None
