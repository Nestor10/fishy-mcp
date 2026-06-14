package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.application.ports.oauth.{OAuthConfig, SigningKeySource}
import fishy.mcp.application.usecase.oauth.{
  CompleteUpstreamCallback,
  ExchangeAuthorizationCode,
  InitiateAuthorization,
  RefreshTokens,
  RegisterClient,
  RevokeRefreshToken
}
import zio.*
import zio.http.*

/** Aggregates every OAuth route into a single `Routes` value.
  *
  * Composed at the `HttpExtraRoutes` seam so the transport module stays
  * OAuth-agnostic.
  *
  * Routes only depend on use-cases now -- the underlying ports
  * (`AuthorizationRequestStore`, `RefreshTokenStore`, etc.) are internal
  * collaborators of the use-cases and not part of this surface.
  * `OAuthConfig` and `SigningKeySource` remain because the metadata routes
  * read them directly.
  */
object OAuthRoutes:

  type Env = OAuthConfig & SigningKeySource
    & RegisterClient & InitiateAuthorization & CompleteUpstreamCallback
    & ExchangeAuthorizationCode & RefreshTokens & RevokeRefreshToken

  val all: URIO[Env, Routes[Any, Response]] =
    for
      meta     <- OAuthMetadataRoutes.routes
      register <- OAuthRegistrationRoutes.routes
      authz    <- OAuthAuthorizeRoutes.routes
      token    <- OAuthTokenRoutes.routes
    yield meta ++ register ++ authz ++ token
