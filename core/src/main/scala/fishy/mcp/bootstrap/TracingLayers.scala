package fishy.mcp.bootstrap

import io.opentelemetry.api.{OpenTelemetry => JOpenTelemetry}
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

/** Opt-in distributed tracing via OpenTelemetry.
  *
  * When `OTEL_EXPORTER_OTLP_ENDPOINT` is set, creates a real SDK with OTLP span exporter. Otherwise
  * provides `JOpenTelemetry.noop()` for zero overhead.
  *
  * Layer output: `Tracing & ContextStorage` -- required by instrumented services.
  */
object TracingLayers:

  private val ScopeName = "fishy-mcp"

  /** Complete tracing stack: ContextStorage + Tracing. Reads OTEL_EXPORTER_OTLP_ENDPOINT to decide
    * real vs noop.
    */
  lazy val live: ZLayer[Any, Nothing, Tracing & ContextStorage] =
    ZLayer.make[Tracing & ContextStorage](
      otelLayer,
      OpenTelemetry.contextZIO,
      OpenTelemetry.tracing(ScopeName)
    )

  /** Noop tracing for tests. */
  lazy val noop: ULayer[Tracing & ContextStorage] =
    ZLayer.make[Tracing & ContextStorage](
      ZLayer.succeed(JOpenTelemetry.noop()),
      OpenTelemetry.contextZIO,
      OpenTelemetry.tracing(ScopeName)
    )

  /** JOpenTelemetry layer: real SDK when OTLP endpoint configured, noop otherwise. */
  private lazy val otelLayer: ZLayer[Any, Nothing, JOpenTelemetry] =
    sys.env.get("OTEL_EXPORTER_OTLP_ENDPOINT") match
      case Some(endpoint) if endpoint.nonEmpty => sdkLayer(endpoint)
      case _                                   => ZLayer.succeed(JOpenTelemetry.noop())

  /** Real OTLP SDK with scoped lifecycle for proper flush on shutdown. */
  private def sdkLayer(endpoint: String): ZLayer[Any, Nothing, JOpenTelemetry] =
    ZLayer.scoped {
      for
        serviceName <- ZIO.succeed(sys.env.getOrElse("OTEL_SERVICE_NAME", "fishy-mcp"))
        resource = Resource.builder()
          .put(AttributeKey.stringKey("service.name"), serviceName)
          .build()
        spanExporter <- ZIO.fromAutoCloseable(
          ZIO.succeed(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build())
        )
        spanProcessor <- ZIO.fromAutoCloseable(
          ZIO.succeed(BatchSpanProcessor.builder(spanExporter).build())
        )
        tracerProvider <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkTracerProvider.builder()
              .setResource(resource)
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setTracerProvider(tracerProvider)
              .build()
          )
        )
        _ <- ZIO.logInfo(s"OpenTelemetry tracing enabled, exporting to $endpoint")
      yield sdk: JOpenTelemetry
    }
