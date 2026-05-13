package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.outbound.oauth.keys.RsaSigningKeySource
import fishy.mcp.application.ports.oauth.OAuthConfig
import zio.*
import zio.http.*
import zio.test.*

/** Smoke test for the public OAuth discovery surface. */
object OAuthMetadataRoutesSpec extends ZIOSpecDefault:

  private val config = OAuthConfig(
    issuer = "https://example.test",
    resource = "https://example.test/mcp",
    scopesSupported = List("mcp:use")
  )

  private val layers =
    ZLayer.succeed(config) ++ RsaSigningKeySource.generated

  private def call(path: String): ZIO[OAuthConfig & fishy.mcp.application.ports.oauth.SigningKeySource, Throwable, (Response, String)] =
    for
      routes   <- OAuthMetadataRoutes.routes
      request   = Request.get(URL.decode("/" + path).toOption.get)
      response <- ZIO.scoped(routes.runZIO(request))
      body     <- response.body.asString
    yield (response, body)

  override def spec = suite("OAuthMetadataRoutes")(
    test("serves RFC 8414 authorization-server metadata") {
      for r <- call(".well-known/oauth-authorization-server")
      yield assertTrue(
        r._1.status == Status.Ok,
        r._2.contains("\"issuer\":\"https://example.test\""),
        r._2.contains("\"code_challenge_methods_supported\":[\"S256\"]"),
        r._2.contains("\"token_endpoint\":\"https://example.test/token\"")
      )
    },
    test("serves RFC 9728 protected-resource metadata") {
      for r <- call(".well-known/oauth-protected-resource")
      yield assertTrue(
        r._1.status == Status.Ok,
        r._2.contains("\"resource\":\"https://example.test/mcp\""),
        r._2.contains("\"authorization_servers\":[\"https://example.test\"]")
      )
    },
    test("serves a JWKS document with at least one RSA signing key") {
      for r <- call(".well-known/jwks.json")
      yield assertTrue(
        r._1.status == Status.Ok,
        r._2.contains("\"kty\":\"RSA\""),
        r._2.contains("\"use\":\"sig\""),
        r._2.contains("\"alg\":\"RS256\"")
      )
    }
  ).provide(layers)
