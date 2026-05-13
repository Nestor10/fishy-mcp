package fishy.mcp.bootstrap

import fishy.mcp.bootstrap.config.TracingConfig
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

/** Distributed tracing via OpenTelemetry.
  *
  * When `TracingConfig.enabled` is true, builds a real SDK with OTLP span
  * exporter. Otherwise provides `JOpenTelemetry.noop()` for zero overhead.
  *
  * Layer output: `Tracing & ContextStorage` -- required by instrumented
  * services. The layer takes [[TracingConfig]] as input; loading happens
  * once at startup via [[AppConfig.load]].
  */
object TracingLayers:

  private val ScopeName = "fishy-mcp"

  /** Complete tracing stack: ContextStorage + Tracing. Driven by
    * [[TracingConfig]].
    */
  lazy val live: URLayer[TracingConfig, Tracing & ContextStorage] =
    ZLayer.makeSome[TracingConfig, Tracing & ContextStorage](
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
  private lazy val otelLayer: URLayer[TracingConfig, JOpenTelemetry] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[TracingConfig] { cfg =>
        cfg.otlpEndpoint.filter(_.nonEmpty) match
          case Some(endpoint) => buildSdk(endpoint, cfg.serviceName)
          case None           => ZIO.succeed(JOpenTelemetry.noop(): JOpenTelemetry)
      }
    }

  /** Real OTLP SDK with scoped lifecycle for proper flush on shutdown. */
  private def buildSdk(endpoint: String, serviceName: String): URIO[Scope, JOpenTelemetry] =
    for
      resource <- ZIO.succeed(
                    Resource.builder()
                      .put(AttributeKey.stringKey("service.name"), serviceName)
                      .build()
                  )
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
