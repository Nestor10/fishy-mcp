package fishy.mcp.domain.model

import zio.json.ast.Json

/** JSON-RPC 2.0 + MCP error sum type, expressed as domain values rather than
  * wire codes. The transport adapter maps each variant to its `code` / `message`
  * / optional `data` on the way out.
  *
  * Modeled after the same pattern used for [[fishy.mcp.domain.model.oauth.OAuthErrorKind]]:
  * a typed enum with a `Custom` escape hatch for codes outside the standard set.
  */
enum McpError:
  case ParseError(detail: String)
  case InvalidRequest(detail: String)
  case MethodNotFound(method: String)
  case InvalidParams(detail: String)
  case InternalError(detail: String)
  case Custom(code: Int, message: String, data: Option[Json] = None)

object McpError:

  extension (e: McpError)

    /** RFC-defined wire code (RFC 7159 §5.1 + JSON-RPC 2.0 §5.1). */
    def code: Int = e match
      case ParseError(_)           => -32700
      case InvalidRequest(_)       => -32600
      case MethodNotFound(_)       => -32601
      case InvalidParams(_)        => -32602
      case InternalError(_)        => -32603
      case Custom(c, _, _)         => c

    /** Human-readable message rendered into the wire `error.message` field. */
    def message: String = e match
      case ParseError(d)           => s"Parse error: $d"
      case InvalidRequest(d)       => s"Invalid request: $d"
      case MethodNotFound(m)       => s"Method not found: $m"
      case InvalidParams(d)        => s"Invalid params: $d"
      case InternalError(d)        => s"Internal error: $d"
      case Custom(_, m, _)         => m

    /** Optional structured payload (`error.data` per JSON-RPC). */
    def data: Option[Json] = e match
      case Custom(_, _, d) => d
      case _               => None
