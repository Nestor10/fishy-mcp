package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.protocol.oauth.{OAuthErrorBody, TokenResponse}
import fishy.mcp.application.usecase.oauth.{
  AuthorizationCodeExchangeInput,
  ExchangeAuthorizationCode,
  RefreshTokens,
  RevokeRefreshToken,
  TokenGrant
}
import fishy.mcp.domain.model.oauth.{OAuthError, OAuthErrorKind}
import zio.*
import zio.http.*
import zio.json.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** `/token` (RFC 6749 §4.1.3 + refresh) and `/revoke` (RFC 7009).
  *
  * Thin transport adapter: parses the form body, dispatches to the
  * appropriate use-case ([[ExchangeAuthorizationCode]] or [[RefreshTokens]]),
  * and renders the result. All grant logic lives in those use-cases.
  *
  * Access tokens are self-contained JWTs so the MCP resource server never
  * needs to call back into the AS on the hot path.
  */
object OAuthTokenRoutes:

  type Env = ExchangeAuthorizationCode & RefreshTokens & RevokeRefreshToken

  val routes: URIO[Env, Routes[Any, Response]] =
    for
      codeFlow    <- ZIO.service[ExchangeAuthorizationCode]
      refreshFlow <- ZIO.service[RefreshTokens]
      revokeFlow  <- ZIO.service[RevokeRefreshToken]
    yield build(codeFlow, refreshFlow, revokeFlow)

  private def build(
      codeFlow: ExchangeAuthorizationCode,
      refreshFlow: RefreshTokens,
      revokeFlow: RevokeRefreshToken
  ): Routes[Any, Response] =

    val token = Method.POST / "token" -> handler { (request: Request) =>
      (for
        body  <- request.body.asString.orElseFail(badRequest("invalid_request", "unreadable body"))
        form   = parseForm(body)
        grant <- required(form, "grant_type")
        resp  <- grant match
                   case "authorization_code" =>
                     handleAuthorizationCode(form, codeFlow)
                   case "refresh_token" =>
                     handleRefreshToken(form, refreshFlow)
                   case other =>
                     ZIO.fail(badRequest("unsupported_grant_type", other))
      yield resp).merge
    }

    val revoke = Method.POST / "revoke" -> handler { (request: Request) =>
      (for
        body <- request.body.asString.orElseFail(badRequest("invalid_request", "unreadable body"))
        form  = parseForm(body)
        _    <- revokeFlow.revoke(form.get("token"))
      yield Response.status(Status.Ok)).merge
    }

    Routes(token, revoke)

  private def handleAuthorizationCode(
      form: Map[String, String],
      codeFlow: ExchangeAuthorizationCode
  ): IO[Response, Response] =
    for
      code        <- required(form, "code")
      clientIdStr <- required(form, "client_id")
      redirectUri <- required(form, "redirect_uri")
      verifier    <- required(form, "code_verifier")
      grant       <- codeFlow
                       .exchange(AuthorizationCodeExchangeInput(code, clientIdStr, redirectUri, verifier))
                       .mapError(renderOAuthError)
    yield successResponse(grant)

  private def handleRefreshToken(
      form: Map[String, String],
      refreshFlow: RefreshTokens
  ): IO[Response, Response] =
    for
      raw   <- required(form, "refresh_token")
      grant <- refreshFlow.refresh(raw).mapError(renderOAuthError)
    yield successResponse(grant)

  private def successResponse(grant: TokenGrant): Response =
    val scopeStr = grant.scope.mkString(" ")
    Response.json(TokenResponse(
      access_token = grant.accessToken,
      token_type = "Bearer",
      expires_in = grant.accessTokenExpiresIn.toSeconds,
      refresh_token = Some(grant.refreshToken),
      scope = if grant.scope.nonEmpty then Some(scopeStr) else None
    ).toJson).addHeader(Header.CacheControl.NoStore)

  private def renderOAuthError(err: OAuthError): Response =
    val status = err.kind match
      case OAuthErrorKind.ServerError => Status.InternalServerError
      case _                          => Status.BadRequest
    Response.json(OAuthErrorBody(err.code, err.description).toJson).status(status)

  private def parseForm(body: String): Map[String, String] =
    if body.isEmpty then Map.empty
    else
      body.split('&').iterator.flatMap { pair =>
        pair.split("=", 2) match
          case Array(k, v) => Some(decode(k) -> decode(v))
          case Array(k)    => Some(decode(k) -> "")
          case _           => None
      }.toMap

  private def decode(s: String): String =
    URLDecoder.decode(s, StandardCharsets.UTF_8)

  private def required(form: Map[String, String], key: String): IO[Response, String] =
    ZIO.fromOption(form.get(key).filter(_.nonEmpty))
      .orElseFail(badRequest("invalid_request", s"missing parameter: $key"))

  private def badRequest(code: String, description: String): Response =
    Response.json(OAuthErrorBody(code, Some(description)).toJson).status(Status.BadRequest)
