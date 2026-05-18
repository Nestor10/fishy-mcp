package fishy.mcp.adapters.inbound.http

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

/** HTTP server observability: OTel server-side spans + access logs.
  *
  * Wrap your top-level route tree with [[HttpObservability.middleware]] so that
  * every request (MCP transport, OAuth endpoints, well-known metadata, health,
  * any [[HttpExtraRoutes]]) emits both:
  *
  *   - A `SERVER`-kind OTel span named `HTTP {METHOD}` with the standard
  *     `http.method` / `http.target` / `http.status_code` / `http.user_agent`
  *     attributes. W3C `traceparent` is extracted from incoming headers so the
  *     span parents into the caller's trace.
  *   - A request log line via zio-http's `HandlerAspect.requestLogging` —
  *     method, URL, status, duration, user-agent. Level is `Warning` for 5xx,
  *     `Info` for everything else.
  *
  * When the OTel SDK is in noop mode (no `OTEL_EXPORTER_OTLP_ENDPOINT`
  * configured), the span attributes are still set but go nowhere — zero
  * runtime cost. Access logs always fire and respect `LOG_LEVEL`.
  */
object HttpObservability:

  /** Combined tracing + access-log middleware. Apply with `@@`. */
  def middleware: Middleware[Tracing] = tracing ++ accessLogAsMiddleware

  // Tracing must be applied as Middleware (not HandlerAspect) so it can wrap
  // the whole handler effect in a span scope, not just intercept request/response.
  private def tracing: Middleware[Tracing] =
    new Middleware[Tracing]:
      override def apply[Env1 <: Tracing, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
        routes.transform { (handler: Handler[Env1, Response, Request, Response]) =>
          Handler.fromFunctionZIO[Request] { request =>
            val spanName = s"HTTP ${request.method.name}"
            val attrs = Attributes
              .builder()
              .put(AttributeKey.stringKey("http.method"), request.method.name)
              .put(AttributeKey.stringKey("http.target"), request.url.path.toString)
              .put(
                AttributeKey.stringKey("http.user_agent"),
                request.headers.get(Header.UserAgent).map(_.renderedValue).getOrElse("")
              )
              .build()
            ZIO.serviceWithZIO[Tracing] { t =>
              t.extractSpan(
                propagator = TraceContextPropagator.default,
                carrier = headerCarrier(request),
                spanName = spanName,
                spanKind = SpanKind.SERVER,
                attributes = attrs
              ) {
                ZIO.scoped {
                  handler
                    .runZIO(request)
                    .tap(resp => t.setAttribute("http.status_code", resp.status.code.toLong))
                    .tapErrorCause { cause =>
                      cause.failureOption match
                        case Some(resp: Response) =>
                          t.setAttribute("http.status_code", resp.status.code.toLong)
                        case _ =>
                          t.setAttribute("error", true)
                    }
                }
              }
            }
          }
        }

  private def accessLogAsMiddleware: Middleware[Any] =
    new Middleware[Any]:
      override def apply[Env1, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
        routes @@ HandlerAspect.requestLogging(
          level = status =>
            if status.code >= 500 then LogLevel.Warning
            else LogLevel.Debug,
          // Authorization deliberately excluded — bearer tokens land on disk.
          loggedRequestHeaders = Set(Header.UserAgent)
        )

  /** Adapter from zio-http `Headers` to OTel `IncomingContextCarrier` for
    * traceparent extraction. The `kernel` is unused; lookup goes straight to
    * the captured request's headers (cleaner than threading a mutable map).
    */
  private def headerCarrier(request: Request): IncomingContextCarrier[Unit] =
    new IncomingContextCarrier[Unit]:
      override val kernel: Unit = ()
      override def getAllKeys(carrier: Unit): Iterable[String] =
        request.headers.iterator.map(_.headerName).toList
      override def getByKey(carrier: Unit, key: String): Option[String] =
        request.headers.get(key)
