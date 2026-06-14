package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.RefreshTokenStore
import fishy.mcp.domain.model.oauth.Ids.RefreshTokenId
import zio.*

/** RFC 7009 §2 token revocation.
  *
  * Per §2.2 the endpoint must always succeed (even for unknown tokens), so
  * this use-case has no error channel. Only refresh-token revocation has a
  * server-side effect; access tokens are stateless JWTs.
  */
trait RevokeRefreshToken:
  def revoke(rawToken: Option[String]): UIO[Unit]

object RevokeRefreshToken:

  def revoke(rawToken: Option[String]): URIO[RevokeRefreshToken, Unit] =
    ZIO.serviceWithZIO[RevokeRefreshToken](_.revoke(rawToken))

  val layer: URLayer[RefreshTokenStore, RevokeRefreshToken] =
    ZLayer.fromFunction(Live(_))

  final case class Live(refreshStore: RefreshTokenStore) extends RevokeRefreshToken:
    override def revoke(rawToken: Option[String]): UIO[Unit] =
      rawToken match
        case Some(t) => refreshStore.consume(RefreshTokenId(t)).unit
        case None    => ZIO.unit
