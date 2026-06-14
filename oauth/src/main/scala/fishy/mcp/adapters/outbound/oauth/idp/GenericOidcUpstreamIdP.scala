package fishy.mcp.adapters.outbound.oauth.idp

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.jwk.{JWKMatcher, JWKSelector, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.SignedJWT
import fishy.mcp.application.ports.oauth.UpstreamIdP
import fishy.mcp.domain.model.oauth.{Tenant, TenantIdpConfig, UpstreamIdentity, UpstreamProvider, UpstreamRef}
import zio.*
import zio.http.*
import zio.json.*

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Generic OIDC upstream driver: discovers endpoints via
  * `${issuer}/.well-known/openid-configuration`, exchanges codes at the
  * discovered token endpoint, and validates ID tokens against the discovered
  * JWKS (signature + `iss` + `aud` + `exp`).
  *
  * Works for any OIDC-compliant IdP (Google, Auth0, Keycloak, a local
  * mock-oauth2-server). Per-tenant client credentials come from `Tenant.idp`;
  * this driver owns only deployment-wide behavior (scopes, discovery-doc cache).
  *
  * Production-safe — it is not a `StubMarker`, so the OAuth audit accepts it.
  * Requires a `zio.http.Client` for discovery and the token exchange.
  */
object GenericOidcUpstreamIdP:

  final case class Config(issuer: String, scopes: List[String])

  object Config:
    val config: zio.Config[Config] =
      zio.Config.string("OAUTH_UPSTREAM_ISSUER")
        .zip(zio.Config.chunkOf("OAUTH_UPSTREAM_SCOPES", zio.Config.string)
          .withDefault(Chunk("openid", "email", "profile")))
        .map { case (issuer, scopes) => Config(issuer, scopes.toList) }

  def layer(cfg: Config): ZLayer[Client, Nothing, UpstreamIdP] =
    ZLayer.fromZIO(
      for
        client       <- ZIO.service[Client]
        discoveryRef <- Ref.Synchronized.make[Option[(DiscoveryDoc, JWKSource[SecurityContext])]](None)
      yield Live(cfg, client, discoveryRef)
    )

  private final case class DiscoveryDoc(
      issuer: String,
      authorization_endpoint: String,
      token_endpoint: String,
      jwks_uri: String
  )
  private given JsonDecoder[DiscoveryDoc] = DeriveJsonDecoder.gen[DiscoveryDoc]

  private final case class TokenResponse(id_token: String)
  private given JsonDecoder[TokenResponse] = DeriveJsonDecoder.gen[TokenResponse]

  private final case class Live(
      cfg: Config,
      client: Client,
      discoveryRef: Ref.Synchronized[Option[(DiscoveryDoc, JWKSource[SecurityContext])]]
  ) extends UpstreamIdP:

    override def authorizationUrl(
        tenant: Tenant,
        state: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        redirectUri: String
    ): UIO[String] =
      ensureDiscovery.map { case (doc, _) =>
        // PKCE intentionally omitted on the upstream leg: server-to-IdP is a
        // confidential client (we hold client_secret), and UpstreamIdP.exchangeCode
        // does not plumb a code_verifier through, so sending code_challenge here
        // would make IdPs (e.g. Google) require a verifier we can't supply. The
        // downstream client -> server leg still uses PKCE.
        val params = Map(
          "client_id"     -> upstreamClientId(tenant),
          "response_type" -> "code",
          "scope"         -> cfg.scopes.mkString(" "),
          "state"         -> state,
          "redirect_uri"  -> redirectUri
        )
        val _ = (codeChallenge, codeChallengeMethod)
        s"${doc.authorization_endpoint}?${urlEncode(params)}"
      }.orDie

    override def exchangeCode(
        tenant: Tenant,
        code: String,
        redirectUri: String
    ): IO[UpstreamIdP.ExchangeError, UpstreamIdentity] =
      for
        pair      <- ensureDiscovery.mapError(t =>
                       UpstreamIdP.ExchangeError.Upstream(s"discovery: ${t.getMessage}")
                     )
        (doc, jwks) = pair
        body = urlEncode(Map(
                 "client_id"     -> upstreamClientId(tenant),
                 "client_secret" -> upstreamClientSecret(tenant),
                 "code"          -> code,
                 "redirect_uri"  -> redirectUri,
                 "grant_type"    -> "authorization_code"
               ))
        respText <- httpPostForm(doc.token_endpoint, body).mapError(t =>
                      UpstreamIdP.ExchangeError.Upstream(s"token endpoint: ${t.getMessage}")
                    )
        tokenResp <- ZIO.fromEither(respText.fromJson[TokenResponse])
                       .mapError(msg => UpstreamIdP.ExchangeError.Upstream(s"token response: $msg"))
        identity  <- verifyIdToken(tokenResp.id_token, doc, jwks, upstreamClientId(tenant))
      yield identity

    private def verifyIdToken(
        idToken: String,
        doc: DiscoveryDoc,
        jwks: JWKSource[SecurityContext],
        expectedAudience: String
    ): IO[UpstreamIdP.ExchangeError, UpstreamIdentity] =
      ZIO.attemptBlocking {
        val jwt = SignedJWT.parse(idToken)
        val kid = Option(jwt.getHeader.getKeyID).getOrElse(
          throw new RuntimeException("id_token header missing kid")
        )
        val matcher = new JWKSelector(new JWKMatcher.Builder().keyID(kid).build())
        val keys = jwks.get(matcher, null.asInstanceOf[SecurityContext])
        if keys.isEmpty then throw new RuntimeException(s"no JWKS key matches kid=$kid")
        val rsaKey = keys.get(0) match
          case k: RSAKey => k
          case other     => throw new RuntimeException(s"id_token signing key not RSA: ${other.getKeyType}")
        val verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey)
        if !jwt.verify(verifier) then throw new RuntimeException("id_token signature invalid")

        val claims = jwt.getJWTClaimsSet
        if claims.getIssuer != doc.issuer then
          throw new RuntimeException(s"id_token iss mismatch: got ${claims.getIssuer}, want ${doc.issuer}")
        val audiences = Option(claims.getAudience).map(_.toArray.toList).getOrElse(Nil)
        if !audiences.contains(expectedAudience) then
          throw new RuntimeException(s"id_token aud mismatch: got $audiences, want $expectedAudience")
        val now = Instant.now()
        val exp = Option(claims.getExpirationTime).map(_.toInstant).getOrElse(
          throw new RuntimeException("id_token missing exp")
        )
        if !now.isBefore(exp) then throw new RuntimeException(s"id_token expired at $exp")

        val sub = Option(claims.getSubject).getOrElse(
          throw new MissingClaimException("sub")
        )
        val email = Option(claims.getStringClaim("email")).getOrElse(
          throw new MissingClaimException("email")
        )
        val emailVerified =
          Option(claims.getBooleanClaim("email_verified")).map(_.booleanValue).getOrElse(false)
        val name = Option(claims.getStringClaim("name"))
        val raw: Map[String, String] =
          scala.jdk.CollectionConverters.MapHasAsScala(claims.toJSONObject).asScala.iterator
            .flatMap { case (k, v) => Option(v).map(value => k.toString -> value.toString) }
            .toMap

        UpstreamIdentity(
          ref = UpstreamRef(UpstreamProvider.GenericOidc(cfg.issuer), sub),
          email = email,
          emailVerified = emailVerified,
          name = name,
          rawClaims = raw
        )
      }.mapError {
        case e: MissingClaimException => UpstreamIdP.ExchangeError.MissingClaim(e.getMessage)
        case t                        => UpstreamIdP.ExchangeError.Unauthorized(s"id_token: ${t.getMessage}")
      }

    private def ensureDiscovery: Task[(DiscoveryDoc, JWKSource[SecurityContext])] =
      discoveryRef.updateAndGetZIO {
        case some @ Some(_) => ZIO.succeed(some)
        case None =>
          for
            doc  <- fetchDiscovery
            jwks <- ZIO.attempt(JWKSourceBuilder.create[SecurityContext](URI.create(doc.jwks_uri).toURL).build())
          yield Some((doc, jwks))
      }.map(_.get)

    private def fetchDiscovery: Task[DiscoveryDoc] =
      val discoveryUrl = s"${cfg.issuer.stripSuffix("/")}/.well-known/openid-configuration"
      for
        url <- ZIO.fromEither(URL.decode(discoveryUrl))
                 .mapError(msg => new RuntimeException(s"Invalid discovery URL: $msg"))
        out <- ZIO.scoped {
                 client.request(Request.get(url)).flatMap { resp =>
                   resp.body.asString.flatMap { txt =>
                     if resp.status.isSuccess then ZIO.succeed(txt)
                     else ZIO.fail(new RuntimeException(s"discovery: HTTP ${resp.status.code}: $txt"))
                   }
                 }
               }
        doc <- ZIO.fromEither(out.fromJson[DiscoveryDoc])
                 .mapError(msg => new RuntimeException(s"parse discovery doc: $msg"))
      yield doc

    private def httpPostForm(targetUrl: String, formBody: String): Task[String] =
      for
        url <- ZIO.fromEither(URL.decode(targetUrl))
                 .mapError(msg => new RuntimeException(s"Invalid URL: $msg"))
        out <- ZIO.scoped {
                 val req = Request
                   .post(url, Body.fromString(formBody))
                   .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
                 client.request(req).flatMap { resp =>
                   resp.body.asString.flatMap { txt =>
                     if resp.status.isSuccess then ZIO.succeed(txt)
                     else ZIO.fail(new RuntimeException(s"HTTP ${resp.status.code}: $txt"))
                   }
                 }
               }
      yield out

    private def upstreamClientId(tenant: Tenant): String = tenant.idp match
      case TenantIdpConfig.Oidc(_, id, _, _) => id
      case TenantIdpConfig.Google(id, _, _)  => id

    private def upstreamClientSecret(tenant: Tenant): String = tenant.idp match
      case TenantIdpConfig.Oidc(_, _, sec, _) => sec
      case TenantIdpConfig.Google(_, sec, _)  => sec

  private final class MissingClaimException(claim: String)
      extends RuntimeException(s"ID token missing claim: $claim")

  private def urlEncode(params: Map[String, String]): String =
    params.iterator.map { case (k, v) =>
      s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
    }.mkString("&")
