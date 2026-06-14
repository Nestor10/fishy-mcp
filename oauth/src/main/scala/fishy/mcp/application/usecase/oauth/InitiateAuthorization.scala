package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.{
  AuthorizationRequestStore,
  ClientRegistrationStore,
  OAuthConfig,
  TenantResolver,
  UpstreamIdP
}
import fishy.mcp.domain.model.oauth.{
  AuthorizationRequest,
  AuthorizeError,
  OAuthError,
  OAuthErrorKind,
  PkceChallenge,
  PkceMethod,
  Tenant,
  TenantIdpConfig
}
import fishy.mcp.domain.model.oauth.Ids.{AuthorizationCode, ClientId}
import zio.*

/** Parsed `/authorize` query parameters. */
final case class InitiateAuthorizationInput(
    responseType: String,
    clientId: String,
    redirectUri: String,
    state: String,
    codeChallenge: String,
    codeChallengeMethod: String,
    scope: List[String],
    host: String
)

/** Result of a successful `/authorize`: the URL the user agent should be sent
  * to so the upstream IdP can authenticate them.
  */
final case class AuthorizationInitiated(upstreamUrl: String)

/** RFC 6749 §4.1.1 + RFC 7636: validate the client/redirect/PKCE inputs,
  * persist a pending [[AuthorizationRequest]] keyed by an opaque
  * `upstreamState`, and return the URL the AS should redirect the user agent
  * to so the upstream IdP can authenticate them.
  *
  * Failures partition into [[AuthorizeError.ClientFault]] (rendered as JSON,
  * pre-validation) and [[AuthorizeError.ClientRedirectFault]] (rendered as a
  * 302 to the client's `redirect_uri`, post-validation) per §4.1.2.1.
  */
trait InitiateAuthorization:
  def initiate(input: InitiateAuthorizationInput): IO[AuthorizeError, AuthorizationInitiated]

object InitiateAuthorization:

  def initiate(
      input: InitiateAuthorizationInput
  ): ZIO[InitiateAuthorization, AuthorizeError, AuthorizationInitiated] =
    ZIO.serviceWithZIO[InitiateAuthorization](_.initiate(input))

  val layer: URLayer[
    OAuthConfig & AuthorizationRequestStore & ClientRegistrationStore
      & TenantResolver & UpstreamIdP,
    InitiateAuthorization
  ] =
    ZLayer.fromFunction(Live(_, _, _, _, _))

  final case class Live(
      config: OAuthConfig,
      authz: AuthorizationRequestStore,
      clients: ClientRegistrationStore,
      tenants: TenantResolver,
      upstream: UpstreamIdP
  ) extends InitiateAuthorization:

    override def initiate(
        input: InitiateAuthorizationInput
    ): IO[AuthorizeError, AuthorizationInitiated] =
      for
        _      <- ZIO.when(input.responseType != "code")(
                    clientFail(
                      OAuthErrorKind.UnsupportedGrantType,
                      s"unsupported response_type: ${input.responseType}"
                    )
                  )
        client <- clients
                    .get(ClientId(input.clientId))
                    .someOrElseZIO(
                      clientFail(OAuthErrorKind.InvalidRequest, "unknown client_id")
                    )
        _      <- ZIO.when(!client.allowsRedirectUri(input.redirectUri))(
                    clientFail(OAuthErrorKind.InvalidRequest, "redirect_uri not registered")
                  )
        // From here on, the client + redirect_uri are validated, so failures
        // are rendered back to the client per RFC 6749 §4.1.2.1.
        _      <- ZIO.when(input.codeChallengeMethod != "S256")(
                    redirectFail(
                      input.redirectUri,
                      input.state,
                      OAuthErrorKind.InvalidRequest,
                      "only S256 PKCE is supported"
                    )
                  )
        challenge <- ZIO
                       .fromEither(PkceChallenge.fromString(input.codeChallenge))
                       .mapError(msg =>
                         AuthorizeError.ClientRedirectFault(
                           input.redirectUri,
                           input.state,
                           OAuthError(OAuthErrorKind.InvalidRequest, Some(msg))
                         )
                       )
        tenant <- tenants
                    .resolve(input.host)
                    .someOrElseZIO(
                      clientFail(OAuthErrorKind.InvalidRequest, "unknown tenant")
                    )
        code          <- SecureTokens.urlSafe()
        upstreamState <- SecureTokens.urlSafe()
        now           <- Clock.instant
        request = AuthorizationRequest(
                    code = AuthorizationCode(code),
                    clientId = client.id,
                    tenantId = tenant.id,
                    redirectUri = input.redirectUri,
                    scope = input.scope,
                    state = input.state,
                    pkceChallenge = challenge,
                    pkceMethod = PkceMethod.S256,
                    identityId = None,
                    upstreamState = upstreamState,
                    createdAt = now,
                    expiresAt = now.plus(config.authorizationCodeTtl)
                  )
        _ <- authz.put(request)
        callbackUri = s"${config.issuer}/oauth/callback/${providerId(tenant.idp)}"
        upstreamUrl <- upstream.authorizationUrl(
                         tenant,
                         upstreamState,
                         input.codeChallenge,
                         input.codeChallengeMethod,
                         callbackUri
                       )
      yield AuthorizationInitiated(upstreamUrl)

  private def clientFail(kind: OAuthErrorKind, msg: String): IO[AuthorizeError, Nothing] =
    ZIO.fail(AuthorizeError.ClientFault(OAuthError(kind, Some(msg))))

  private def redirectFail(
      redirectUri: String,
      state: String,
      kind: OAuthErrorKind,
      msg: String
  ): IO[AuthorizeError, Nothing] =
    ZIO.fail(
      AuthorizeError.ClientRedirectFault(redirectUri, state, OAuthError(kind, Some(msg)))
    )

  private def providerId(cfg: TenantIdpConfig): String = cfg match
    case TenantIdpConfig.Google(_, _, _)  => "google"
    case TenantIdpConfig.Oidc(_, _, _, _) => "oidc"
