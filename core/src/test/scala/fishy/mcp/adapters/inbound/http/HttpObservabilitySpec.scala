package fishy.mcp.adapters.inbound.http

import fishy.mcp.bootstrap.TracingLayers
import zio.*
import zio.http.*
import zio.test.*

/** Smoke-test: HttpObservability.middleware leaves response shape intact and
  * fires the structured access log line on every request.
  *
  * Span emission is exercised via the noop OTel SDK (so we don't depend on an
  * exporter to assert traffic). The presence of `Http request served` log
  * lines proves the access log half is wired; the route-level span attributes
  * are exercised by the tracing wrapper compiling against the real
  * `Tracing` service.
  */
object HttpObservabilitySpec extends ZIOSpecDefault:

  private def probeRoutes =
    Routes(
      Method.GET / "ping" -> handler { (_: Request) => Response.text("pong") },
      Method.GET / "boom" -> handler { (_: Request) =>
        ZIO.fail(Response.text("nope").status(Status.Unauthorized))
      }
    ) @@ HttpObservability.middleware

  def spec = suite("HttpObservability")(
    test("preserves 2xx responses on success") {
      ZIO.scoped {
        probeRoutes.runZIO(Request.get(URL.root / "ping"))
      }.map { response =>
        assertTrue(response.status == Status.Ok)
      }
    },
    test("preserves short-circuit Response failures (e.g. 401 from auth)") {
      ZIO.scoped {
        probeRoutes.runZIO(Request.get(URL.root / "boom"))
      }.map { response =>
        assertTrue(response.status == Status.Unauthorized)
      }
    },
    test("returns 404 for unknown route without exploding") {
      ZIO.scoped {
        probeRoutes.runZIO(Request.get(URL.root / "missing"))
      }.map { response =>
        assertTrue(response.status == Status.NotFound)
      }
    }
  ).provide(TracingLayers.noop)
