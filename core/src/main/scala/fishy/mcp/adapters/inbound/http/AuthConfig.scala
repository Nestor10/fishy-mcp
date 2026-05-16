package fishy.mcp.adapters.inbound.http

import zio.*

/** Inbound-HTTP security mode.
  *
  *   - `AUTH_MODE` unset or empty -- [[AllowAll]]. No authentication.
  *   - `AUTH_MODE=trusted` -- [[Trusted]]. Read identity from upstream-proxy
  *     headers (deployer is responsible for proving the proxy is trusted).
  *   - `AUTH_MODE=jwt` -- [[Jwt]]. Validate Bearer tokens via JWKS. Requires
  *     `JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`. Optional
  *     `JWT_GROUPS_CLAIM` (default `groups`), `JWT_SCOPES_CLAIM`
  *     (default `scp`), `JWT_RESOURCE_METADATA_URL` (advertised in the
  *     `WWW-Authenticate` 401 challenge — required by the MCP 2025-06-18
  *     authorization profile for client OAuth bootstrap), `JWT_REALM`.
  *
  * Unknown `AUTH_MODE` values or `jwt` with missing required vars fail at
  * startup.
  */
enum AuthConfig:
  case AllowAll
  case Trusted
  case Jwt(
      jwksUri: String,
      issuer: String,
      audience: String,
      groupsClaim: String,
      scopesClaim: String,
      resourceMetadataUrl: Option[String],
      realm: Option[String]
  )

object AuthConfig:

  val config: Config[AuthConfig] = (
    Config.string("AUTH_MODE").withDefault("") zip
    Config.string("JWKS_URI").optional zip
    Config.string("JWT_ISSUER").optional zip
    Config.string("JWT_AUDIENCE").optional zip
    Config.string("JWT_GROUPS_CLAIM").withDefault("groups") zip
    Config.string("JWT_SCOPES_CLAIM").withDefault("scp") zip
    Config.string("JWT_RESOURCE_METADATA_URL").optional zip
    Config.string("JWT_REALM").optional
  ).mapOrFail {
    case (mode, jwks, iss, aud, groups, scopes, resourceMetadata, realm) =>
      mode.toLowerCase match
        case "" | "allow_all" =>
          Right(AuthConfig.AllowAll)
        case "trusted" =>
          Right(AuthConfig.Trusted)
        case "jwt" =>
          (jwks, iss, aud) match
            case (Some(j), Some(i), Some(a)) =>
              Right(AuthConfig.Jwt(j, i, a, groups, scopes, resourceMetadata, realm))
            case _ =>
              val missing = List(
                Option.when(jwks.isEmpty)("JWKS_URI"),
                Option.when(iss.isEmpty)("JWT_ISSUER"),
                Option.when(aud.isEmpty)("JWT_AUDIENCE")
              ).flatten.mkString(", ")
              Left(Config.Error.MissingData(
                Chunk("AUTH_MODE"),
                s"AUTH_MODE=jwt requires: $missing"
              ))
        case other =>
          Left(Config.Error.InvalidData(
            Chunk("AUTH_MODE"),
            s"Unknown AUTH_MODE: $other. Expected 'jwt', 'trusted', or unset."
          ))
  }
