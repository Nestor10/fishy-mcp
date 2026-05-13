package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.{OAuthConfig, RefreshTokenStore, SigningKeySource}
import fishy.mcp.domain.model.oauth.{OAuthError, OAuthErrorKind, RefreshTokenRecord}
import fishy.mcp.domain.model.oauth.Ids.{IdentityId, RefreshTokenId}
import zio.*
import zio.json.*

/** A successfully-issued token pair (access JWT + opaque refresh).
  *
  * Adapter-agnostic; the HTTP route maps this to the `TokenResponse` wire DTO.
  */
final case class TokenGrant(
    accessToken: String,
    refreshToken: String,
    accessTokenExpiresIn: Duration,
    scope: List[String]
)

/** RFC 7519 access-token claim set. Encoded via zio-json -- no hand-rolled
  * string concatenation. `kid` is *not* a claim; the signer reads it from its
  * own active key.
  */
private[oauth] final case class JwtClaims(
    iss: String,
    sub: String,
    aud: String,
    iat: Long,
    exp: Long,
    scope: String
) derives JsonEncoder

/** Mint an access JWT plus a fresh rotating refresh token, persisting the
  * refresh record so a subsequent `refresh_token` grant can consume it.
  *
  * Used by both the `authorization_code` and `refresh_token` grant flows.
  */
trait TokenIssuer:
  def issue(identityId: IdentityId, scope: List[String]): IO[OAuthError, TokenGrant]

object TokenIssuer:

  def issue(identityId: IdentityId, scope: List[String]): ZIO[TokenIssuer, OAuthError, TokenGrant] =
    ZIO.serviceWithZIO[TokenIssuer](_.issue(identityId, scope))

  val layer: URLayer[OAuthConfig & SigningKeySource & RefreshTokenStore, TokenIssuer] =
    ZLayer.fromFunction(Live(_, _, _))

  final case class Live(
      config: OAuthConfig,
      signer: SigningKeySource,
      refreshStore: RefreshTokenStore
  ) extends TokenIssuer:

    override def issue(
        identityId: IdentityId,
        scope: List[String]
    ): IO[OAuthError, TokenGrant] =
      for
        now         <- Clock.instant
        accessExp    = now.plus(config.accessTokenTtl)
        scopeStr     = scope.mkString(" ")
        claims       = JwtClaims(
                         iss = config.issuer,
                         sub = identityId.value,
                         aud = config.audience,
                         iat = now.getEpochSecond,
                         exp = accessExp.getEpochSecond,
                         scope = scopeStr
                       )
        access      <- signer
                         .sign(claims.toJson)
                         .mapError(e => OAuthError(OAuthErrorKind.ServerError, Some(e.message)))
        refreshRaw  <- SecureTokens.urlSafe()
        refreshExp   = now.plus(config.refreshTokenTtl)
        _           <- refreshStore.put(RefreshTokenRecord(
                         id = RefreshTokenId(refreshRaw),
                         identityId = identityId,
                         scope = scope,
                         createdAt = now,
                         expiresAt = refreshExp
                       ))
      yield TokenGrant(
        accessToken = access,
        refreshToken = refreshRaw,
        accessTokenExpiresIn = config.accessTokenTtl,
        scope = scope
      )
