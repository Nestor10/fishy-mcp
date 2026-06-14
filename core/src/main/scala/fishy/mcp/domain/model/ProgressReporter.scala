package fishy.mcp.domain.model

import zio.*

/** A tool handler's capability to report progress during long-running work.
  *
  * Carried as a typed field on [[ToolContext]] (`ctx.progress`). For a streaming
  * tool call the executor binds a reporter that emits `notifications/progress`;
  * for a sync call it is [[noop]].
  */
trait ProgressReporter:
  def report(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): UIO[Unit]

object ProgressReporter:

  val noop: ProgressReporter = new ProgressReporter:
    def report(progress: Double, total: Option[Double], message: Option[String]): UIO[Unit] =
      ZIO.unit
