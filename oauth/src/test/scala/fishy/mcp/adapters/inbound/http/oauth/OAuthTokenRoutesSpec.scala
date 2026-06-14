package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.outbound.oauth.keys.RsaSigningKeySource
import fishy.mcp.adapters.storage.oauth.{
  InMemoryAuthorizationRequestStore,
  InMemoryClientRegistrationStore,
  InMemoryRefreshTokenStore
}
import fishy.mcp.application.ports.oauth.{
  AuthorizationRequestStore,
  ClientRegistrationStore,
  OAuthConfig
}
import fishy.mcp.domain.model.oauth.{
  AdmissionSpec,
  AuthorizationRequest,
  ClientRegistration,
  Identity,
  IdentityStatus,
  PkceChallenge,
  PkceMethod,
  PkceVerifier,
  Tenant,
  TenantIdpConfig,
  UpstreamProvider,
  UpstreamRef
}
import fishy.mcp.domain.model.oauth.Ids.{AuthorizationCode, ClientId, TenantId}
import zio.*
import zio.http.*
import zio.test.*

/** End-to-end token endpoint coverage: authorization_code + refresh_token grants. */
object OAuthTokenRoutesSpec extends ZIOSpecDefault:

  private val config = OAuthConfig(
    issuer = "https://as.example.test",
    resource = "https://as.example.test",
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 1.hour,
    scopesSupported = List("mcp:use")
  )

  private val tenant = Tenant(
    id = TenantId("t1"),
    hostname = "as.example.test",
    idp = TenantIdpConfig.Oidc("https://upstream", "id", "secret"),
    admission = AdmissionSpec("allow-all", Map.empty)
  )

  // RFC 7636 appendix B example pair.
  private val verifierStr  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
  private val challengeStr = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  private def formBody(pairs: (String, String)*): String =
    pairs.map { case (k, v) =>
      s"$k=${java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)}"
    }.mkString("&")

  private def seedClient: URIO[ClientRegistrationStore, ClientId] =
    for
      store <- ZIO.service[ClientRegistrationStore]
      id     = ClientId("client-1")
      now   <- Clock.instant
      _     <- store.put(ClientRegistration(
        id = id,
        redirectUris = List("https://app.example/cb"),
        clientName = Some("demo"),
        grantTypes = Set("authorization_code", "refresh_token"),
        responseTypes = Set("code"),
        tokenEndpointAuthMethod = "none",
        scope = Some("mcp:use"),
        createdAt = now
      ))
    yield id

  private def seedAuthzRequest(
      clientId: ClientId,
      identityPresent: Boolean
  ): URIO[AuthorizationRequestStore, (AuthorizationCode, String)] =
    for
      store  <- ZIO.service[AuthorizationRequestStore]
      now    <- Clock.instant
      code    = AuthorizationCode("authcode-xyz")
      chall   = PkceChallenge.fromString(challengeStr).toOption.get
      upRef   = UpstreamRef(UpstreamProvider.GenericOidc("https://upstream"), "sub-42")
      ident   = Identity(
        id = fishy.mcp.domain.model.oauth.Ids.IdentityId("id-42"),
        tenantId = tenant.id,
        upstream = upRef,
        email = "user@example.com",
        status = IdentityStatus.Active,
        createdAt = now
      )
      _ <- store.put(AuthorizationRequest(
        code = code,
        clientId = clientId,
        tenantId = tenant.id,
        redirectUri = "https://app.example/cb",
        scope = List("mcp:use"),
        state = "st1",
        pkceChallenge = chall,
        pkceMethod = PkceMethod.S256,
        identityId = if identityPresent then Some(ident.id) else None,
        upstreamState = "up-st",
        createdAt = now,
        expiresAt = now.plus(60.seconds)
      ))
    yield (code, ident.id.value)

  private def post(form: String) =
    for
      routes <- OAuthTokenRoutes.routes
      req     = Request
        .post("/token", Body.fromString(form))
        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
      resp   <- ZIO.scoped(routes.runZIO(req))
      body   <- resp.body.asString
    yield (resp, body)

  override def spec = suite("OAuthTokenRoutes")(
    test("authorization_code grant mints access + refresh tokens") {
      for
        clientId <- seedClient
        seeded   <- seedAuthzRequest(clientId, identityPresent = true)
        (code, identityId) = seeded
        r <- post(formBody(
          "grant_type"    -> "authorization_code",
          "client_id"     -> clientId.value,
          "code"          -> code.value,
          "redirect_uri"  -> "https://app.example/cb",
          "code_verifier" -> verifierStr
        ))
      yield assertTrue(
        r._1.status == Status.Ok,
        r._2.contains("\"token_type\":\"Bearer\""),
        r._2.contains("\"access_token\":\""),
        r._2.contains("\"refresh_token\":\""),
        r._2.contains("\"scope\":\"mcp:use\"")
      )
    },
    test("authorization_code with wrong PKCE verifier fails invalid_grant") {
      for
        clientId <- seedClient
        seeded   <- seedAuthzRequest(clientId, identityPresent = true)
        (code, _) = seeded
        r <- post(formBody(
          "grant_type"    -> "authorization_code",
          "client_id"     -> clientId.value,
          "code"          -> code.value,
          "redirect_uri"  -> "https://app.example/cb",
          "code_verifier" -> ("x" * 50)
        ))
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"invalid_grant\"")
      )
    },
    test("authorization_code fails if upstream callback never completed") {
      for
        clientId <- seedClient
        seeded   <- seedAuthzRequest(clientId, identityPresent = false)
        (code, _) = seeded
        r <- post(formBody(
          "grant_type"    -> "authorization_code",
          "client_id"     -> clientId.value,
          "code"          -> code.value,
          "redirect_uri"  -> "https://app.example/cb",
          "code_verifier" -> verifierStr
        ))
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"invalid_grant\"")
      )
    },
    test("unsupported grant type is rejected") {
      for r <- post(formBody("grant_type" -> "password"))
      yield assertTrue(
        r._1.status == Status.BadRequest,
        r._2.contains("\"error\":\"unsupported_grant_type\"")
      )
    },
    test("revoke always returns 200") {
      for
        routes <- OAuthTokenRoutes.routes
        req     = Request
          .post("/revoke", Body.fromString("token=whatever"))
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
        resp   <- ZIO.scoped(routes.runZIO(req))
      yield assertTrue(resp.status == Status.Ok)
    }
  ).provide(
    ZLayer.succeed(config),
    InMemoryAuthorizationRequestStore.layer,
    InMemoryClientRegistrationStore.layer,
    InMemoryRefreshTokenStore.layer,
    RsaSigningKeySource.generated,
    fishy.mcp.application.usecase.oauth.TokenIssuer.layer,
    fishy.mcp.application.usecase.oauth.RefreshTokens.layer,
    fishy.mcp.application.usecase.oauth.ExchangeAuthorizationCode.layer,
    fishy.mcp.application.usecase.oauth.RevokeRefreshToken.layer
  )
