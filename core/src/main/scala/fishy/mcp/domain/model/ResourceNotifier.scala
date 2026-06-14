package fishy.mcp.domain.model

import zio.*

/** A tool handler's capability to signal that a resource's content changed, so
  * the server pushes `notifications/resources/updated` to subscribed sessions.
  *
  * Carried as a typed field on [[ToolContext]] (`ctx.resources`).
  */
trait ResourceNotifier:
  def updated(uri: String): UIO[Unit]

object ResourceNotifier:

  /** Default: drop the signal (no transport bound). */
  val noop: ResourceNotifier = new ResourceNotifier:
    def updated(uri: String): UIO[Unit] = ZIO.unit
