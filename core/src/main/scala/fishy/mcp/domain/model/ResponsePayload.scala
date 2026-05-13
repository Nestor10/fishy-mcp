package fishy.mcp.domain.model

import zio.json.ast.Json

/** A dispatched request's outcome -- success or typed failure -- in domain
  * terms. The transport adapter encodes this into a JSON-RPC `Response` or
  * `ErrorResponse` on the way out.
  *
  * `result` carries the success value as a `Json` AST. zio-json's `Json` is a
  * pure ADT (not coupled to JSON-RPC wire format), so it's domain-safe.
  */
final case class ResponsePayload(id: RequestId, outcome: Either[McpError, Json])

object ResponsePayload:

  def success(id: RequestId, result: Json): ResponsePayload =
    ResponsePayload(id, Right(result))

  def failure(id: RequestId, error: McpError): ResponsePayload =
    ResponsePayload(id, Left(error))
