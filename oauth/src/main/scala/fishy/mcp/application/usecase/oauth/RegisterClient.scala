package fishy.mcp.application.usecase.oauth

import fishy.mcp.application.ports.oauth.{ClientRegistrationStore, RedirectUriValidator}
import fishy.mcp.domain.model.oauth.{ClientRegistration, OAuthError, OAuthErrorKind}
import fishy.mcp.domain.model.oauth.Ids.ClientId
import zio.*

/** Inputs for [[RegisterClient]] -- pre-parsed from the wire DTO so the
  * application layer doesn't depend on `adapters/protocol`.
  */
final case class RegisterClientInput(
    redirectUris: List[String],
    clientName: Option[String],
    grantTypes: Option[List[String]],
    responseTypes: Option[List[String]],
    scope: Option[String]
)

/** RFC 7591 dynamic client registration.
  *
  * Defaults follow the policy choices documented on the route adapter:
  * public clients only, `authorization_code + refresh_token` grants, `code`
  * response type. PKCE is enforced at the `/token` endpoint.
  */
trait RegisterClient:
  def register(input: RegisterClientInput): IO[OAuthError, ClientRegistration]

object RegisterClient:

  def register(input: RegisterClientInput): ZIO[RegisterClient, OAuthError, ClientRegistration] =
    ZIO.serviceWithZIO[RegisterClient](_.register(input))

  val layer: URLayer[ClientRegistrationStore & RedirectUriValidator, RegisterClient] =
    ZLayer.fromFunction(Live(_, _))

  final case class Live(store: ClientRegistrationStore, validator: RedirectUriValidator)
      extends RegisterClient:

    override def register(input: RegisterClientInput): IO[OAuthError, ClientRegistration] =
      for
        _   <- ZIO.when(input.redirectUris.isEmpty)(
                 ZIO.fail(
                   OAuthError(OAuthErrorKind.InvalidRequest, Some("redirect_uris is required"))
                 )
               )
        _   <- ZIO.foreachDiscard(input.redirectUris)(validator.validate)
        now <- Clock.instant
        reg = ClientRegistration(
                id = ClientId.fresh,
                redirectUris = input.redirectUris,
                clientName = input.clientName,
                grantTypes = input.grantTypes
                  .map(_.toSet)
                  .getOrElse(Set("authorization_code", "refresh_token")),
                responseTypes = input.responseTypes.map(_.toSet).getOrElse(Set("code")),
                tokenEndpointAuthMethod = "none",
                scope = input.scope,
                createdAt = now
              )
        _   <- store.put(reg)
      yield reg
