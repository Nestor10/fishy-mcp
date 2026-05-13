package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.protocol.oauth.OAuthErrorBody
import fishy.mcp.application.usecase.oauth.{
  CompleteUpstreamCallback,
  CompleteUpstreamCallbackInput,
  InitiateAuthorization,
  InitiateAuthorizationInput
}
import fishy.mcp.domain.model.oauth.AuthorizeError
import zio.*
import zio.http.*
import zio.json.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Thin transport adapter for `/authorize` (RFC 6749 §4.1.1 + PKCE) and
  * `/oauth/callback/:provider`.
  *
  * Each handler does one job: parse the HTTP request into the use-case's
  * input DTO, call the use-case, render the typed [[AuthorizeError]] outcome.
  * All policy and persistence lives in [[InitiateAuthorization]] and
  * [[CompleteUpstreamCallback]].
  */
object OAuthAuthorizeRoutes:

  type Env = InitiateAuthorization & CompleteUpstreamCallback

  val routes: URIO[Env, Routes[Any, Response]] =
    for
      initiate <- ZIO.service[InitiateAuthorization]
      complete <- ZIO.service[CompleteUpstreamCallback]
    yield build(initiate, complete)

  private def build(
      initiate: InitiateAuthorization,
      complete: CompleteUpstreamCallback
  ): Routes[Any, Response] =

    val authorize = Method.GET / "authorize" -> handler { (request: Request) =>
      handleAuthorize(request, initiate).merge
    }

    val callback =
      Method.GET / "oauth" / "callback" / string("provider") -> handler {
        (provider: String, request: Request) =>
          handleCallback(provider, request, complete).merge
      }

    Routes(authorize, callback)

  // ---------------------------------------------------------------------------
  // /authorize
  // ---------------------------------------------------------------------------

  private def handleAuthorize(
      request: Request,
      initiate: InitiateAuthorization
  ): IO[Response, Response] =
    val q = request.url.queryParams
    for
      input   <- parseAuthorizeInput(request, q)
      outcome <- initiate.initiate(input).mapError(renderAuthorizeError)
    yield Response.redirect(URL.decode(outcome.upstreamUrl).getOrElse(URL.root))

  private def parseAuthorizeInput(
      request: Request,
      q: QueryParams
  ): IO[Response, InitiateAuthorizationInput] =
    for
      responseType <- requiredParam(q, "response_type")
      clientIdRaw  <- requiredParam(q, "client_id")
      redirectUri  <- requiredParam(q, "redirect_uri")
      state        <- requiredParam(q, "state")
      challengeRaw <- requiredParam(q, "code_challenge")
      methodRaw    <- paramOrDefault(q, "code_challenge_method", "S256")
    yield InitiateAuthorizationInput(
      responseType = responseType,
      clientId = clientIdRaw,
      redirectUri = redirectUri,
      state = state,
      codeChallenge = challengeRaw,
      codeChallengeMethod = methodRaw,
      scope = q.queryParam("scope").map(_.split(" ").filter(_.nonEmpty).toList).getOrElse(Nil),
      host = request.headers.get(Header.Host.name).getOrElse("")
    )

  // ---------------------------------------------------------------------------
  // /oauth/callback/:provider
  // ---------------------------------------------------------------------------

  private def handleCallback(
      provider: String,
      request: Request,
      complete: CompleteUpstreamCallback
  ): IO[Response, Response] =
    val q = request.url.queryParams
    val input = CompleteUpstreamCallbackInput(
      provider = provider,
      state = q.queryParam("state").getOrElse(""),
      code = q.queryParam("code").filter(_.nonEmpty),
      upstreamError = q.queryParam("error").filter(_.nonEmpty),
      upstreamErrorDescription = q.queryParam("error_description").filter(_.nonEmpty),
      host = request.headers.get(Header.Host.name).getOrElse("")
    )
    complete
      .complete(input)
      .mapBoth(
        renderAuthorizeError,
        out => Response.redirect(URL.decode(out.redirectUrl).getOrElse(URL.root))
      )
      .merge

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private def renderAuthorizeError(err: AuthorizeError): Response = err match
    case AuthorizeError.ClientFault(e) =>
      plainError(e.code, e.description.getOrElse(""))
    case AuthorizeError.ClientRedirectFault(redirectUri, state, e) =>
      redirectError(redirectUri, state, e.code, e.description.getOrElse(""))

  private def requiredParam(q: QueryParams, key: String): IO[Response, String] =
    ZIO
      .fromOption(q.queryParam(key).filter(_.nonEmpty))
      .orElseFail(plainError("invalid_request", s"missing parameter: $key"))

  private def paramOrDefault(q: QueryParams, key: String, default: String): UIO[String] =
    ZIO.succeed(q.queryParam(key).filter(_.nonEmpty).getOrElse(default))

  private def enc(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def plainError(code: String, description: String): Response =
    Response.json(OAuthErrorBody(code, Some(description)).toJson).status(Status.BadRequest)

  private def redirectError(
      redirectUri: String,
      state: String,
      code: String,
      desc: String
  ): Response =
    val sep = if redirectUri.contains("?") then "&" else "?"
    val url = s"$redirectUri${sep}error=${enc(code)}&error_description=${enc(desc)}&state=${enc(state)}"
    Response.redirect(URL.decode(url).getOrElse(URL.root))
