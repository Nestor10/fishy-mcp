package fishy.mcp.domain.model

/** JSON-RPC request identifier: string, number, or null per the JSON-RPC 2.0
  * spec. Lives in domain because it's a pure value carried through dispatch
  * results and use-case signatures. The wire codec (JsonEncoder/JsonDecoder)
  * belongs to `adapters/protocol/jsonrpc/`.
  */
enum RequestId:
  case StringId(value: String)
  case NumberId(value: Long)
  case Null
