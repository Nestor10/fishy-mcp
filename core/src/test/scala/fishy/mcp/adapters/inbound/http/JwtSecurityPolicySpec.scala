package fishy.mcp.adapters.inbound.http

import zio.*
import zio.http.*
import zio.test.*

/** Asserts that JwtSecurityPolicy emits a `WWW-Authenticate` challenge on 401, per RFC 6750 §3,
  * RFC 9728 §5.1, and the MCP 2025-06-18 authorization profile.
  *
  * The protocol cite (modelcontextprotocol.io/specification/2025-06-18/basic/authorization):
  * "MCP servers MUST use the HTTP header WWW-Authenticate when returning a 401 Unauthorized to
  * indicate the location of the resource server metadata URL". Without this, spec-compliant MCP
  * clients have no canonical signal to start the OAuth flow and hang on `initialize`.
  */
object JwtSecurityPolicySpec extends ZIOSpecDefault:

  private val resourceMetadataUrl =
    "https://example.test/.well-known/oauth-protected-resource"

  private val baseConfig = JwtSecurityPolicy.Config(
    jwksUri = "http://127.0.0.1:1/jwks.json",
    issuer = "https://example.test",
    audience = "test-aud",
    resourceMetadataUrl = Some(resourceMetadataUrl),
    realm = Some("mcp")
  )

  // Runs a single Request through the JwtSecurityPolicy middleware applied to a trivial
  // protected route. Returns the resulting Response.
  private def runThroughPolicy(config: JwtSecurityPolicy.Config, request: Request): Task[Response] =
    ZIO.scoped {
      JwtSecurityPolicy.layer(config).build.flatMap { env =>
        val policy = env.get[HttpSecurityPolicy]
        val protectedRoutes =
          Routes(
            Method.POST / "mcp" -> handler { (_: Request) => Response.ok }
          ) @@ policy.middleware
        protectedRoutes.runZIO(request)
      }
    }

  def spec = suite("JwtSecurityPolicy 401 challenge")(
    test("returns 401 with WWW-Authenticate when Authorization header is absent") {
      for response <- runThroughPolicy(
          baseConfig,
          Request.post(URL.root / "mcp", Body.empty)
        )
      yield {
        val challenge = response.headers.get("WWW-Authenticate").getOrElse("")
        assertTrue(
          response.status == Status.Unauthorized,
          challenge.startsWith("Bearer "),
          challenge.contains(s"""resource_metadata="$resourceMetadataUrl""""),
          challenge.contains("""realm="mcp""""),
          // RFC 6750 §3: missing-credentials response SHOULD NOT include an error parameter.
          !challenge.contains("error=")
        )
      }
    },
    test("returns 401 with WWW-Authenticate error=invalid_token when Bearer is malformed") {
      for response <- runThroughPolicy(
          baseConfig,
          Request
            .post(URL.root / "mcp", Body.empty)
            .addHeader("Authorization", "Bearer not-a-real-jwt")
        )
      yield {
        val challenge = response.headers.get("WWW-Authenticate").getOrElse("")
        assertTrue(
          response.status == Status.Unauthorized,
          challenge.startsWith("Bearer "),
          challenge.contains("""error="invalid_token""""),
          challenge.contains("error_description="),
          challenge.contains(s"""resource_metadata="$resourceMetadataUrl"""")
        )
      }
    },
    test("omits resource_metadata and realm when neither is configured") {
      for response <- runThroughPolicy(
          baseConfig.copy(resourceMetadataUrl = None, realm = None),
          Request.post(URL.root / "mcp", Body.empty)
        )
      yield {
        val challenge = response.headers.get("WWW-Authenticate").getOrElse("")
        assertTrue(
          response.status == Status.Unauthorized,
          // Bare Bearer challenge — RFC 6750 §3 minimum.
          challenge == "Bearer",
          !challenge.contains("resource_metadata"),
          !challenge.contains("realm")
        )
      }
    }
  )
