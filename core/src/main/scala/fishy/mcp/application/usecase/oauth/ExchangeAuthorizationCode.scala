package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.{AuthorizationRequestStore, ClientRegistrationStore}
import fishy.mcp.domain.model.oauth.{OAuthError, OAuthErrorKind, Pkce, PkceVerifier}
import fishy.mcp.domain.model.oauth.Ids.AuthorizationCode
import zio.*

/** Inputs for the RFC 6749 §4.1.3 `authorization_code` grant. */
final case class AuthorizationCodeExchangeInput(
    code: String,
    clientId: String,
    redirectUri: String,
    codeVerifier: String
)

/** Atomically consume a pending [[fishy.mcp.domain.model.oauth.AuthorizationRequest]],
  * validate the client/redirect/PKCE binding, and mint a fresh access + refresh
  * token pair via [[TokenIssuer]].
  *
  * Failure modes (RFC 6749 §5.2):
  *   - `invalid_grant` -- code missing/expired, mismatched client_id /
  *     redirect_uri, never-completed upstream callback, or PKCE failure.
  *   - `invalid_client` -- the bound client has since been deleted.
  */
trait ExchangeAuthorizationCode:
  def exchange(input: AuthorizationCodeExchangeInput): IO[OAuthError, TokenGrant]

object ExchangeAuthorizationCode:

  def exchange(
      input: AuthorizationCodeExchangeInput
  ): ZIO[ExchangeAuthorizationCode, OAuthError, TokenGrant] =
    ZIO.serviceWithZIO[ExchangeAuthorizationCode](_.exchange(input))

  val layer: URLayer[
    AuthorizationRequestStore & ClientRegistrationStore & TokenIssuer,
    ExchangeAuthorizationCode
  ] =
    ZLayer.fromFunction(Live(_, _, _))

  final case class Live(
      authz: AuthorizationRequestStore,
      clients: ClientRegistrationStore,
      issuer: TokenIssuer
  ) extends ExchangeAuthorizationCode:

    override def exchange(
        input: AuthorizationCodeExchangeInput
    ): IO[OAuthError, TokenGrant] =
      for
        pending      <- authz
                          .consume(AuthorizationCode(input.code))
                          .someOrFail(invalidGrant("code not found or already used"))
        now          <- Clock.instant
        _            <- ZIO.when(pending.isExpired(now))(
                          ZIO.fail(invalidGrant("authorization code expired"))
                        )
        _            <- ZIO.when(pending.clientId.value != input.clientId)(
                          ZIO.fail(invalidGrant("client_id mismatch"))
                        )
        _            <- ZIO.when(pending.redirectUri != input.redirectUri)(
                          ZIO.fail(invalidGrant("redirect_uri mismatch"))
                        )
        _            <- clients
                          .get(pending.clientId)
                          .someOrFail(
                            OAuthError(OAuthErrorKind.InvalidClient, Some("unknown client"))
                          )
        identityId   <- ZIO
                          .fromOption(pending.identityId)
                          .orElseFail(
                            invalidGrant("authorization code never completed upstream callback")
                          )
        pkceVerifier <- ZIO
                          .fromEither(PkceVerifier.fromString(input.codeVerifier))
                          .mapError(msg => invalidGrant(msg))
        _            <- ZIO.when(
                          !Pkce.verify(pkceVerifier, pending.pkceChallenge, pending.pkceMethod)
                        )(ZIO.fail(invalidGrant("PKCE verification failed")))
        grant        <- issuer.issue(identityId, pending.scope)
      yield grant

  private def invalidGrant(msg: String): OAuthError =
    OAuthError(OAuthErrorKind.InvalidGrant, Some(msg))
