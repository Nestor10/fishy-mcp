package fishy.mcp.adapters.inbound.http.oauth

import fishy.mcp.adapters.protocol.oauth.{
  AuthorizationServerMetadata,
  JwksDocument,
  ProtectedResourceMetadata
}
import fishy.mcp.application.ports.oauth.{OAuthConfig, SigningKeySource}
import zio.*
import zio.http.*
import zio.json.*

/** Public, unauthenticated routes for OAuth discovery:
  *
  *   - `GET /.well-known/oauth-authorization-server` (RFC 8414)
  *   - `GET /.well-known/oauth-protected-resource` (RFC 9728)
  *   - `GET /.well-known/jwks.json`
  *
  * These are mounted alongside the MCP routes but never behind the auth
  * middleware -- clients fetch them *before* having a token.
  */
object OAuthMetadataRoutes:

  def routes: URIO[OAuthConfig & SigningKeySource, Routes[Any, Response]] =
    for
      config <- ZIO.service[OAuthConfig]
      signer <- ZIO.service[SigningKeySource]
    yield build(config, signer)

  private def build(config: OAuthConfig, signer: SigningKeySource): Routes[Any, Response] =
    val asMetadata = Method.GET / ".well-known" / "oauth-authorization-server" -> handler {
      Response.json(authServerMetadata(config).toJson)
    }

    val resourceMetadata = Method.GET / ".well-known" / "oauth-protected-resource" -> handler {
      Response.json(protectedResourceMetadata(config).toJson)
    }

    val jwks = Method.GET / ".well-known" / "jwks.json" -> handler {
      signer.publishedKeys.map(keys => Response.json(JwksDocument(keys).toJson))
    }

    Routes(asMetadata, resourceMetadata, jwks)

  private def authServerMetadata(c: OAuthConfig): AuthorizationServerMetadata =
    AuthorizationServerMetadata(
      issuer = c.issuer,
      authorization_endpoint = c.issuer + "/authorize",
      token_endpoint = c.issuer + "/token",
      registration_endpoint = Some(c.issuer + "/register"),
      jwks_uri = c.issuer + "/.well-known/jwks.json",
      response_types_supported = List("code"),
      grant_types_supported = List("authorization_code", "refresh_token"),
      token_endpoint_auth_methods_supported = List("none"),
      code_challenge_methods_supported = List("S256"),
      scopes_supported = Some(c.scopesSupported),
      revocation_endpoint = Some(c.issuer + "/revoke")
    )

  private def protectedResourceMetadata(c: OAuthConfig): ProtectedResourceMetadata =
    ProtectedResourceMetadata(
      resource = c.resource,
      authorization_servers = List(c.issuer),
      scopes_supported = Some(c.scopesSupported),
      bearer_methods_supported = List("header")
    )
