package fishy.mcp.adapters.inbound.http

import zio.*

/** Selects HTTP security policy from environment variables.
  *
  * `AUTH_MODE=jwt` -- JWT Bearer token validation via JWKS. Requires: `JWKS_URI`, `JWT_ISSUER`,
  * `JWT_AUDIENCE`. Optional: `JWT_GROUPS_CLAIM` (default `"groups"`), `JWT_SCOPES_CLAIM` (default
  * `"scp"`). `AUTH_MODE=trusted` -- trusted upstream proxy headers. `AUTH_MODE` unset -- no
  * authentication (`allowAll`).
  *
  * Setting an unknown `AUTH_MODE` value is a startup error.
  */
object ConfigDrivenAuth:

  val layer: ULayer[HttpSecurityPolicy] =
    ZLayer.fromZIO {
      for
        mode <- System.env("AUTH_MODE")
        jwks <- System.env("JWKS_URI")
        issuer <- System.env("JWT_ISSUER")
        aud <- System.env("JWT_AUDIENCE")
        groups <- System.env("JWT_GROUPS_CLAIM")
        scopes <- System.env("JWT_SCOPES_CLAIM")
      yield (mode.map(_.toLowerCase), jwks, issuer, aud, groups, scopes)
    }.flatMap { env =>
      val (mode, jwks, issuer, aud, groups, scopes) =
        env.get[(
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Option[String]
        )]
      mode match
        case Some("jwt") =>
          (jwks, issuer, aud) match
            case (Some(j), Some(i), Some(a)) =>
              JwtSecurityPolicy.layer(JwtSecurityPolicy.Config(
                jwksUri = j,
                issuer = i,
                audience = a,
                groupsClaim = groups.getOrElse("groups"),
                scopesClaim = scopes.getOrElse("scp")
              ))
            case _ =>
              val missing = List(
                if jwks.isEmpty then Some("JWKS_URI") else None,
                if issuer.isEmpty then Some("JWT_ISSUER") else None,
                if aud.isEmpty then Some("JWT_AUDIENCE") else None
              ).flatten.mkString(", ")
              ZLayer.fromZIO(ZIO.die(
                new IllegalArgumentException(s"AUTH_MODE=jwt requires: $missing")
              ))
        case Some("trusted") =>
          TrustedHeaderPolicy.layer()
        case Some(other) =>
          ZLayer.fromZIO(ZIO.die(
            new IllegalArgumentException(
              s"Unknown AUTH_MODE: $other. Expected 'jwt', 'trusted', or unset."
            )
          ))
        case None =>
          HttpSecurityPolicy.allowAll
    }.orDie
