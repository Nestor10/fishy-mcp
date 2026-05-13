package fishy.mcp.domain.model

import zio.*
import zio.stream.ZStream

/** Result of dispatching a JSON-RPC request.
  *
  * Domain-pure: the transport layer reads the variant and renders to its
  * native response shape (HTTP body / SSE / stdio line) via an encoder in
  * `adapters/protocol/jsonrpc/`.
  */
enum DispatchResult:

  /** Notification -- no response body (HTTP 202 / no stdio output). */
  case Empty

  /** A single typed response payload. */
  case Single(payload: ResponsePayload)

  /** A stream of zero or more notifications terminated by exactly one `Final`. */
  case Streaming(events: ZStream[Any, Nothing, StreamFrame])

object DispatchResult:

  /** Collapse to the final payload, dropping intermediate notifications.
    * Used by stdio/batch transports where streaming isn't applicable.
    */
  extension (dr: DispatchResult)
    def toOption: UIO[Option[ResponsePayload]] =
      dr match
        case DispatchResult.Empty       => ZIO.none
        case DispatchResult.Single(p)   => ZIO.some(p)
        case DispatchResult.Streaming(events) =>
          events.runLast.map {
            case Some(StreamFrame.Final(p)) => Some(p)
            case _                          => None
          }
