package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.outbound.oauth.policy.DefaultRedirectUriValidator
import fishy.mcp.adapters.storage.oauth.InMemoryClientRegistrationStore
import fishy.mcp.application.usecase.oauth.RegisterClient
import zio.*
import zio.http.*
import zio.test.*

/** Covers happy-path registration, redirect-uri validation, and malformed JSON. */
object OAuthRegistrationRoutesSpec extends ZIOSpecDefault:

  private def post(body: String) =
    for
      routes <- OAuthRegistrationRoutes.routes
      req     = Request
                  .post("/register", Body.fromString(body))
                  .addHeader(Header.ContentType(MediaType.application.json))
      resp   <- ZIO.scoped(routes.runZIO(req))
      out    <- resp.body.asString
    yield (resp, out)

  override def spec = suite("OAuthRegistrationRoutes")(
    test("issues a client_id for a valid request") {
      for r <- post("""{"redirect_uris":["https://app.example.com/cb"],"client_name":"demo"}""")
      yield assertTrue(
        r._1.status == Status.Created,
        r._2.contains("\"client_id\":\""),
        r._2.contains("\"token_endpoint_auth_method\":\"none\""),
        r._2.contains("\"grant_types\":["),
        r._2.contains("authorization_code")
      )
    },
    test("rejects empty redirect_uris") {
      for r <- post("""{"redirect_uris":[]}""")
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"invalid_redirect_uri\"")
      )
    },
    test("rejects non-http(s) redirect_uri schemes like javascript:") {
      for r <- post("""{"redirect_uris":["javascript:alert(1)"]}""")
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"invalid_redirect_uri\"")
      )
    },
    test("accepts http loopback redirect URIs") {
      for r <- post("""{"redirect_uris":["http://localhost:9999/cb"]}""")
      yield assertTrue(r._1.status == Status.Created)
    },
    test("rejects malformed JSON") {
      for r <- post("not-json")
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"invalid_client_metadata\"")
      )
    }
  ).provide(
    InMemoryClientRegistrationStore.layer,
    DefaultRedirectUriValidator.layer,
    RegisterClient.layer
  )
