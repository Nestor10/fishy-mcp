package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.RefreshTokenStore
import fishy.mcp.domain.model.oauth.{OAuthError, OAuthErrorKind}
import fishy.mcp.domain.model.oauth.Ids.RefreshTokenId
import zio.*

/** RFC 6749 §6 `refresh_token` grant: atomically consume the stored refresh
  * record and reissue a fresh access + refresh pair via [[TokenIssuer]].
  *
  * Rotation is enforced because [[RefreshTokenStore.consume]] is a
  * fetch-and-delete (a replayed refresh token is rejected as `invalid_grant`).
  */
trait RefreshTokens:
  def refresh(rawToken: String): IO[OAuthError, TokenGrant]

object RefreshTokens:

  def refresh(rawToken: String): ZIO[RefreshTokens, OAuthError, TokenGrant] =
    ZIO.serviceWithZIO[RefreshTokens](_.refresh(rawToken))

  val layer: URLayer[RefreshTokenStore & TokenIssuer, RefreshTokens] =
    ZLayer.fromFunction(Live(_, _))

  final case class Live(refreshStore: RefreshTokenStore, issuer: TokenIssuer)
      extends RefreshTokens:

    override def refresh(rawToken: String): IO[OAuthError, TokenGrant] =
      for
        now    <- Clock.instant
        record <- refreshStore
                    .consume(RefreshTokenId(rawToken))
                    .someOrFail(
                      OAuthError(OAuthErrorKind.InvalidGrant, Some("refresh token not recognized"))
                    )
        _      <- ZIO.when(!record.isActive(now))(
                    ZIO.fail(
                      OAuthError(
                        OAuthErrorKind.InvalidGrant,
                        Some("refresh token expired or consumed")
                      )
                    )
                  )
        grant  <- issuer.issue(record.identityId, record.scope)
      yield grant
