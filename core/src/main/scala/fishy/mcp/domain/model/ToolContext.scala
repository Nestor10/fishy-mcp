package fishy.mcp.domain.model

import zio.*
import zio.json.ast.Json

final case class ToolContext(
    requestId: String,
    sessionId: Option[String],
    meta: Option[Json],
    auth: Option[AuthContext] = None,
    sendClientRequest: Option[(String, Json) => IO[ClientRequesterError, Json]] = None,
    notifyResourceUpdated: String => UIO[Unit] = _ => ZIO.unit
)
