package fishy.mcp.domain.model

import fishy.mcp.adapters.protocol.jsonrpc.{ErrorResponse, Response}
import zio.*
import zio.stream.ZStream

/** Result of dispatching a JSON-RPC request.
  *
  * Transport layer uses the variant to pick HTTP response format (JSON / SSE / 202).
  */
enum DispatchResult:

  /** Notification -- no response body (HTTP 202). */
  case Empty

  case Single(value: Either[ErrorResponse, Response])

  /** SSE event stream: JSON-RPC notifications followed by the final response. */
  case Streaming(events: ZStream[Any, Nothing, String])

object DispatchResult:

  /** Collapse to Option for stdio/batch where SSE is not applicable. For Streaming, drains and
    * returns the last message.
    */
  extension (dr: DispatchResult)
    def toOption: UIO[Option[Either[ErrorResponse, Response]]] =
      dr match
        case DispatchResult.Empty     => ZIO.none
        case DispatchResult.Single(v) => ZIO.some(v)
        case DispatchResult.Streaming(events) =>
          events.runLast.map {
            case Some(json) =>
              import zio.json.*
              json.fromJson[Response] match
                case Right(r) => Some(Right(r))
                case Left(_) =>
                  json.fromJson[ErrorResponse] match
                    case Right(e) => Some(Left(e))
                    case Left(_)  => None
            case None => None
          }
