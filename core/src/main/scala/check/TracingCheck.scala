package check

import io.opentelemetry.api.{OpenTelemetry => JOpenTelemetry}
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object TracingCheck:
  val tracingLayer: URLayer[JOpenTelemetry & ContextStorage, Tracing] =
    OpenTelemetry.tracing("test-scope")

  val contextLayer: ULayer[ContextStorage] = OpenTelemetry.contextZIO

  val fullLayer: URLayer[JOpenTelemetry, Tracing] =
    OpenTelemetry.contextZIO >>> OpenTelemetry.tracing("test-scope")

  val noopOtel: ULayer[JOpenTelemetry] =
    ZLayer.succeed(JOpenTelemetry.noop())

  def useTracing(tracing: Tracing): Task[Unit] =
    ZIO.unit @@ tracing.aspects.span("test-span")

  def setAttr(tracing: Tracing): UIO[Unit] =
    tracing.setAttribute("key", "value")
