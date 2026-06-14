package fishy.mcp.domain.model

import zio.json.ast.Json

/** Per-request context handed to every tool handler: the single, explicit place
  * a handler discovers what it can do.
  *
  * Capabilities are typed interfaces with safe defaults rather than nullable
  * callbacks — server→client requests via [[client]] (`ctx.createMessage`, …),
  * progress via [[progress]], resource-update signals via [[resources]]. `auth`
  * is the snapshot of [[AuthFiberRef]] taken when the executor built this
  * context (auth is the one capability set before any context exists — by the
  * HTTP security middleware — so it travels by fiber-ref and lands here).
  */
final case class ToolContext(
    requestId: String,
    sessionId: Option[String],
    meta: Option[Json],
    auth: Option[AuthContext] = None,
    client: ClientChannel = ClientChannel.unavailable,
    progress: ProgressReporter = ProgressReporter.noop,
    resources: ResourceNotifier = ResourceNotifier.noop
)
