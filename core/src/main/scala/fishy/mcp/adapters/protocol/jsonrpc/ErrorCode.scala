package fishy.mcp.adapters.protocol.jsonrpc

/** Standard JSON-RPC 2.0 error codes.
  *
  * These are defined by the JSON-RPC specification and must be used for protocol-level errors.
  * Application errors use different codes.
  *
  * @see
  *   https://www.jsonrpc.org/specification#error_object
  */
object ErrorCode:

  /** Invalid JSON was received. */
  val ParseError: Int = -32700

  /** The JSON sent is not a valid Request object. */
  val InvalidRequest: Int = -32600

  /** The method does not exist or is not available. */
  val MethodNotFound: Int = -32601

  /** Invalid method parameters. */
  val InvalidParams: Int = -32602

  /** Internal JSON-RPC error. */
  val InternalError: Int = -32603

  // Server errors reserved: -32000 to -32099
