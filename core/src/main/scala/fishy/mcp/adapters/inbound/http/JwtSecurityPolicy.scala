package fishy.mcp.adapters.inbound.http

import fishy.mcp.domain.model.{AuthContext, AuthFiberRef}
import zio.*
import zio.http.*
import zio.json.ast.Json

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import java.net.URI
import java.util as ju
import scala.jdk.CollectionConverters.*

/** JWT Bearer token validation with JWKS signature verification.
  *
  * Validates the `Authorization: Bearer <token>` header against a remote JWKS endpoint. Verifies
  * signature, expiry, issuer, and audience. On success, sets `AuthFiberRef` with an `AuthContext`
  * extracted from standard OIDC claims.
  *
  * Uses nimbus-jose-jwt under the hood. JWKS keys are fetched lazily on first request and cached
  * automatically (5 min default TTL).
  */
object JwtSecurityPolicy:

  final case class Config(
      jwksUri: String,
      issuer: String,
      audience: String,
      algorithms: Set[String] = Set("RS256"),
      scopesClaim: String = "scp",
      groupsClaim: String = "groups"
  )

  def layer(config: Config): TaskLayer[HttpSecurityPolicy] =
    ZLayer.fromZIO(buildPolicy(config))

  private def buildPolicy(config: Config): Task[HttpSecurityPolicy] =
    ZIO.attempt {
      val jwksUrl = URI.create(config.jwksUri).toURL
      val keySource = JWKSourceBuilder.create[SecurityContext](jwksUrl).build()
      val algorithms = config.algorithms.map(JWSAlgorithm.parse).asJava
      val keySelector =
        new JWSVerificationKeySelector[SecurityContext](algorithms, keySource)

      val processor = new DefaultJWTProcessor[SecurityContext]()
      processor.setJWSKeySelector(keySelector)

      val exactMatch = new JWTClaimsSet.Builder()
        .issuer(config.issuer)
        .audience(config.audience)
        .build()
      val required =
        new ju.HashSet(ju.Arrays.asList("sub", "iss", "aud", "exp", "iat"))
      processor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier[SecurityContext](exactMatch, required)
      )

      makePolicy(processor, config)
    }

  private def makePolicy(
      processor: DefaultJWTProcessor[SecurityContext],
      config: Config
  ): HttpSecurityPolicy =
    new HttpSecurityPolicy:
      def middleware: HandlerAspect[Any, Unit] =
        HandlerAspect.interceptIncomingHandler(
          Handler.fromFunctionZIO[Request] { request =>
            validate(request, processor, config).foldZIO(
              error => ZIO.fail(Response.text(error).status(Status.Unauthorized)),
              auth => AuthFiberRef.currentAuth.set(Some(auth)).as((request, ()))
            )
          }
        )

  private def validate(
      request: Request,
      processor: DefaultJWTProcessor[SecurityContext],
      config: Config
  ): IO[String, AuthContext] =
    request.headers.get("Authorization").filter(_.startsWith("Bearer ")) match
      case None =>
        ZIO.fail("Missing or invalid Authorization: Bearer header")
      case Some(header) =>
        val token = header.substring(7)
        ZIO.attemptBlocking(processor.process(token, null))
          .mapError(e => s"JWT validation failed: ${e.getMessage}")
          .map(claims => extractClaims(claims, config))

  private def extractClaims(claims: JWTClaimsSet, config: Config): AuthContext =
    val sub = claims.getSubject
    val email = safeStringClaim(claims, "email")
    val name = safeStringClaim(claims, "preferred_username")
      .orElse(safeStringClaim(claims, "name"))

    val groups = safeStringListClaim(claims, config.groupsClaim)
      .map(_.asScala.toSet)
      .getOrElse(Set.empty)

    val scopes = safeStringClaim(claims, config.scopesClaim)
      .map(_.split(" ").filter(_.nonEmpty).toSet)
      .getOrElse(Set.empty)

    val rawClaims = Json.decoder.decodeJson(claims.toString).toOption

    AuthContext(
      sub = sub,
      email = email,
      name = name,
      groups = groups,
      scopes = scopes,
      rawClaims = rawClaims
    )

  private def safeStringClaim(claims: JWTClaimsSet, name: String): Option[String] =
    try Option(claims.getStringClaim(name))
    catch case _: java.text.ParseException => None

  private def safeStringListClaim(claims: JWTClaimsSet, name: String): Option[ju.List[String]] =
    try Option(claims.getStringListClaim(name))
    catch case _: java.text.ParseException => None
