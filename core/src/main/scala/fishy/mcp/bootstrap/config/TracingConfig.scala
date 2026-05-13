package fishy.mcp.bootstrap.config

import zio.*

/** OpenTelemetry tracing configuration.
  *
  *   - `OTEL_EXPORTER_OTLP_ENDPOINT` -- OTLP gRPC endpoint URL. When unset
  *     or empty, the SDK is replaced with a no-op (zero overhead).
  *   - `OTEL_SERVICE_NAME` -- the `service.name` resource attribute.
  *     Default `fishy-mcp`.
  */
final case class TracingConfig(
    otlpEndpoint: Option[String],
    serviceName: String
):
  /** True when a real OTLP endpoint is configured. */
  def enabled: Boolean = otlpEndpoint.exists(_.nonEmpty)

object TracingConfig:

  val config: Config[TracingConfig] = (
    Config.string("OTEL_EXPORTER_OTLP_ENDPOINT").optional zip
    Config.string("OTEL_SERVICE_NAME").withDefault("fishy-mcp")
  ).map(TracingConfig.apply)
