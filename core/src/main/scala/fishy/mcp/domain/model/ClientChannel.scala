package fishy.mcp.domain.model

import zio.*
import zio.json.ast.Json

/** A tool handler's capability to issue server→client JSON-RPC requests
  * (`sampling/createMessage`, `roots/list`, `elicitation/create`) and await the
  * client's reply.
  *
  * Carried as a typed field on [[ToolContext]] (`ctx.client`), reached through
  * the ergonomic extension methods in `ClientMessages` (`ctx.createMessage`, …).
  * A named interface rather than a raw callback, so the context stays
  * self-describing and type-safe.
  */
trait ClientChannel:
  def request(method: String, params: Json): IO[ClientRequesterError, Json]

object ClientChannel:

  /** No client is connected: every request fails with `NoClientCallback`. The
    * default on [[ToolContext]], so a handler that asks for sampling outside an
    * active session gets a clean typed failure rather than a null. */
  val unavailable: ClientChannel = new ClientChannel:
    def request(method: String, params: Json): IO[ClientRequesterError, Json] =
      ZIO.fail(ClientRequesterError.NoClientCallback)
