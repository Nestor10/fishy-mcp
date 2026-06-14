package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.protocol.oauth.{
  ClientRegistrationRequest,
  ClientRegistrationResponse,
  OAuthErrorBody
}
import fishy.mcp.application.usecase.oauth.{RegisterClient, RegisterClientInput}
import fishy.mcp.domain.model.oauth.{ClientRegistration, OAuthError, OAuthErrorKind}
import zio.*
import zio.http.*
import zio.json.*

/** RFC 7591 dynamic client registration -- thin transport adapter.
  *
  * Parsing and rendering only. All policy and persistence lives in
  * [[RegisterClient]].
  *
  * The endpoint is open (no bearer required) because clients need a way to
  * register before they can acquire a token. Rate limiting is expected to be
  * applied by the deploying host.
  */
object OAuthRegistrationRoutes:

  type Env = RegisterClient

  val routes: URIO[Env, Routes[Any, Response]] =
    ZIO.service[RegisterClient].map(build)

  private def build(useCase: RegisterClient): Routes[Any, Response] =
    val register = Method.POST / "register" -> handler { (request: Request) =>
      (for
        body <- request.body.asString.orElseFail(badRequest("invalid_request", "unreadable body"))
        dto  <- ZIO.fromEither(body.fromJson[ClientRegistrationRequest])
                  .mapError(msg => badRequest("invalid_client_metadata", msg))
        reg  <- useCase
                  .register(toInput(dto))
                  .mapError(renderError)
      yield successResponse(reg)).merge
    }

    Routes(register)

  private def toInput(dto: ClientRegistrationRequest): RegisterClientInput =
    RegisterClientInput(
      redirectUris = dto.redirect_uris,
      clientName = dto.client_name,
      grantTypes = dto.grant_types,
      responseTypes = dto.response_types,
      scope = dto.scope
    )

  private def successResponse(reg: ClientRegistration): Response =
    Response
      .json(ClientRegistrationResponse(
        client_id = reg.id.value,
        client_id_issued_at = reg.createdAt.getEpochSecond,
        redirect_uris = reg.redirectUris,
        grant_types = reg.grantTypes.toList,
        response_types = reg.responseTypes.toList,
        token_endpoint_auth_method = reg.tokenEndpointAuthMethod,
        client_name = reg.clientName,
        scope = reg.scope
      ).toJson)
      .status(Status.Created)

  /** RFC 7591 §3.2.2 maps redirect-URI failures to the narrower
    * `invalid_redirect_uri` code; all other domain errors keep their RFC code.
    */
  private def renderError(err: OAuthError): Response =
    val code = err.kind match
      case OAuthErrorKind.InvalidRequest => "invalid_redirect_uri"
      case _                              => err.code
    Response.json(OAuthErrorBody(code, err.description).toJson).status(Status.BadRequest)

  private def badRequest(code: String, description: String): Response =
    Response.json(OAuthErrorBody(code, Some(description)).toJson).status(Status.BadRequest)
