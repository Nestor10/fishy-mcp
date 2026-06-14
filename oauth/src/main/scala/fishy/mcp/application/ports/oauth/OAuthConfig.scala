package fishy.mcp.application.ports.oauth

import zio.*

/** Public configuration for the built-in OAuth AS.
  *
  *   - `issuer` is the external base URL (must match `iss` in issued JWTs).
  *   - `resource` is the MCP resource identifier advertised in RFC 9728
  *     protected-resource metadata (typically the same as `issuer`).
  *   - `scopesSupported` drives the advertised scope list in discovery docs.
  */
final case class OAuthConfig(
    issuer: String,
    resource: String,
    accessTokenTtl: Duration = 15.minutes,
    refreshTokenTtl: Duration = 30.days,
    authorizationCodeTtl: Duration = 60.seconds,
    scopesSupported: List[String] = List("mcp:use"),
    audience: String = "mcp"
)

object OAuthConfig:

  /** 12-factor config descriptor.
    *
    *   - `OAUTH_ISSUER` (required) -- public base URL; goes into `iss`.
    *   - `OAUTH_RESOURCE` (required) -- RFC 9728 resource identifier (often
    *     the same as issuer).
    *   - `OAUTH_ACCESS_TOKEN_TTL` -- ZIO duration, default `15m`.
    *   - `OAUTH_REFRESH_TOKEN_TTL` -- default `30d`.
    *   - `OAUTH_AUTHZ_CODE_TTL` -- default `60s`.
    *   - `OAUTH_SCOPES_SUPPORTED` -- comma-separated, default `mcp:use`.
    *   - `OAUTH_AUDIENCE` -- default `mcp`.
    *
    * Issuer and resource are intentionally required: defaulting them would be
    * a silent security footgun (tokens minted with a bogus `iss`/`aud`).
    */
  val config: Config[OAuthConfig] =
    val issuer     = Config.string("OAUTH_ISSUER")
    val resource   = Config.string("OAUTH_RESOURCE")
    val accessTtl  = Config.duration("OAUTH_ACCESS_TOKEN_TTL").withDefault(15.minutes)
    val refreshTtl = Config.duration("OAUTH_REFRESH_TOKEN_TTL").withDefault(30.days)
    val codeTtl    = Config.duration("OAUTH_AUTHZ_CODE_TTL").withDefault(60.seconds)
    val scopes     = Config.chunkOf("OAUTH_SCOPES_SUPPORTED", Config.string)
      .withDefault(Chunk("mcp:use"))
    val audience   = Config.string("OAUTH_AUDIENCE").withDefault("mcp")
    (issuer zip resource zip accessTtl zip refreshTtl zip codeTtl zip scopes zip audience)
      .map { case (iss, res, at, rt, ct, sc, aud) =>
        OAuthConfig(iss, res, at, rt, ct, sc.toList, aud)
      }
