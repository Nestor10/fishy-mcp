package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.{
  AdmissionPolicy,
  AuthorizationRequestStore,
  OAuthConfig,
  TenantResolver,
  UpstreamIdP,
  UserDirectory
}
import fishy.mcp.domain.model.oauth.{
  AuthorizeError,
  Identity,
  IdentityStatus,
  OAuthError,
  OAuthErrorKind,
  Tenant,
  TenantIdpConfig,
  UpstreamIdentity
}
import zio.*

/** Parsed `/oauth/callback/:provider` parameters. */
final case class CompleteUpstreamCallbackInput(
    provider: String,
    state: String,
    code: Option[String],
    upstreamError: Option[String],
    upstreamErrorDescription: Option[String],
    host: String
)

/** Result of a successful callback: the URL the user agent should be sent to,
  * which is the client's `redirect_uri` carrying `?code=...&state=...`.
  */
final case class CallbackCompleted(redirectUrl: String)

/** RFC 6749 §4.1.2: complete the upstream half of an authorization-code flow.
  *
  *   1. Recover the pending [[fishy.mcp.domain.model.oauth.AuthorizationRequest]] by `upstream_state`.
  *   2. Bail with the upstream error if the IdP signaled one.
  *   3. Exchange the upstream code for an upstream identity.
  *   4. Apply the per-tenant [[AdmissionPolicy]].
  *   5. Find-or-create the local [[Identity]].
  *   6. Bind `identityId` onto the pending request so `/token` can redeem it.
  *
  * Failure mapping:
  *   - missing/unknown `state` -> [[AuthorizeError.ClientFault]] (no client redirect_uri available).
  *   - any post-state failure -> [[AuthorizeError.ClientRedirectFault]] using
  *     the pending request's `redirect_uri` per RFC 6749 §4.1.2.1.
  */
trait CompleteUpstreamCallback:
  def complete(input: CompleteUpstreamCallbackInput): IO[AuthorizeError, CallbackCompleted]

object CompleteUpstreamCallback:

  def complete(
      input: CompleteUpstreamCallbackInput
  ): ZIO[CompleteUpstreamCallback, AuthorizeError, CallbackCompleted] =
    ZIO.serviceWithZIO[CompleteUpstreamCallback](_.complete(input))

  val layer: URLayer[
    OAuthConfig & AdmissionPolicy & AuthorizationRequestStore & TenantResolver
      & UpstreamIdP & UserDirectory,
    CompleteUpstreamCallback
  ] =
    ZLayer.fromFunction(Live(_, _, _, _, _, _))

  final case class Live(
      config: OAuthConfig,
      admit: AdmissionPolicy,
      authz: AuthorizationRequestStore,
      tenants: TenantResolver,
      upstream: UpstreamIdP,
      users: UserDirectory
  ) extends CompleteUpstreamCallback:

    override def complete(
        input: CompleteUpstreamCallbackInput
    ): IO[AuthorizeError, CallbackCompleted] =
      for
        pending <- authz
                     .getByUpstreamState(input.state)
                     .someOrElseZIO(clientFail(OAuthErrorKind.InvalidRequest, "unknown state"))
        _       <- input.upstreamError match
                     case Some(err) =>
                       ZIO.fail(
                         AuthorizeError.ClientRedirectFault(
                           pending.redirectUri,
                           pending.state,
                           // Decode upstream error into our taxonomy; fall back to
                           // Other(...) for codes we don't classify.
                           OAuthError(
                             classifyUpstreamError(err),
                             input.upstreamErrorDescription
                           )
                         )
                       )
                     case None => ZIO.unit
        code    <- ZIO
                     .fromOption(input.code.filter(_.nonEmpty))
                     .orElseFail(
                       AuthorizeError.ClientRedirectFault(
                         pending.redirectUri,
                         pending.state,
                         OAuthError(OAuthErrorKind.InvalidRequest, Some("missing code"))
                       )
                     )
        tenant  <- tenants
                     .resolve(input.host)
                     .someOrElseZIO(clientFail(OAuthErrorKind.InvalidRequest, "unknown tenant"))
        _       <- ZIO.when(providerId(tenant.idp) != input.provider)(
                     redirectFail(
                       pending.redirectUri,
                       pending.state,
                       OAuthErrorKind.InvalidRequest,
                       s"provider mismatch: ${input.provider}"
                     )
                   )
        callbackUri = s"${config.issuer}/oauth/callback/${input.provider}"
        upstreamId <- upstream
                        .exchangeCode(tenant, code, callbackUri)
                        .mapError(e =>
                          AuthorizeError.ClientRedirectFault(
                            pending.redirectUri,
                            pending.state,
                            OAuthError(OAuthErrorKind.AccessDenied, Some(e.message))
                          )
                        )
        status   = admit.evaluate(tenant, upstreamId)
        _       <- ZIO.when(!status.isAllowed)(
                     ZIO.fail(
                       AuthorizeError.ClientRedirectFault(
                         pending.redirectUri,
                         pending.state,
                         OAuthError(identityStatusKind(status), Some(statusReason(status)))
                       )
                     )
                   )
        identity <- findOrCreate(tenant, upstreamId, status)
        _        <- authz.update(pending.copy(identityId = Some(identity.id)))
      yield CallbackCompleted(
        redirectUrl = buildClientRedirect(pending.redirectUri, pending.code.value, pending.state)
      )

    private def findOrCreate(
        tenant: Tenant,
        upstreamId: UpstreamIdentity,
        status: IdentityStatus
    ): UIO[Identity] =
      users.findByUpstream(tenant.id, upstreamId.ref).flatMap {
        case Some(existing) => ZIO.succeed(existing)
        case None           => users.create(tenant.id, upstreamId.ref, upstreamId.email, status)
      }

  private def clientFail(
      kind: OAuthErrorKind,
      msg: String
  ): IO[AuthorizeError, Nothing] =
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

  private def identityStatusKind(s: IdentityStatus): OAuthErrorKind = s match
    case IdentityStatus.Pending  => OAuthErrorKind.IdentityPending
    case IdentityStatus.Disabled => OAuthErrorKind.IdentityDisabled
    case _                       => OAuthErrorKind.AccessDenied

  private def statusReason(s: IdentityStatus): String = s match
    case IdentityStatus.Pending  => "identity_pending_admission"
    case IdentityStatus.Disabled => "identity_disabled"
    case _                       => "access_denied"

  /** Map an upstream `?error=` value into our taxonomy. Anything we don't
    * recognize is preserved via [[OAuthErrorKind.Other]] so the message is
    * still meaningful when rendered.
    */
  private def classifyUpstreamError(code: String): OAuthErrorKind =
    summon[zio.json.JsonDecoder[OAuthErrorKind]]
      .decodeJson(s""""$code"""")
      .getOrElse(OAuthErrorKind.AccessDenied)

  private def buildClientRedirect(base: String, code: String, state: String): String =
    val sep = if base.contains("?") then "&" else "?"
    val enc = (s: String) =>
      java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
    s"$base${sep}code=${enc(code)}&state=${enc(state)}"
