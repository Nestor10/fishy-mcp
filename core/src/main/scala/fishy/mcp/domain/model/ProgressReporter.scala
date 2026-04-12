package fishy.mcp.domain.model

import zio.*

/** Callback for reporting progress during long-running tool execution. */
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

  /** FiberRef holding the current reporter. Defaults to noop. The dispatcher sets this when a
    * progress token is present.
    */
  val current: FiberRef[ProgressReporter] =
    Unsafe.unsafe { implicit u =>
      FiberRef.unsafe.make(noop)
    }

  def report(
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): UIO[Unit] =
    current.get.flatMap(_.report(progress, total, message))
