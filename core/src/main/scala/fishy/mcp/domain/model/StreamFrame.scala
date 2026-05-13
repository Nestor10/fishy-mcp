package fishy.mcp.domain.model

import zio.json.ast.Json

/** One frame on a streaming dispatch result.
  *
  * Use-cases emit a sequence of `Notification`s followed by exactly one
  * `Final`. The transport adapter encodes each frame to a JSON-RPC notification
  * or response on the wire.
  */
enum StreamFrame:
  case Notification(method: String, params: Json)
  case Final(payload: ResponsePayload)
