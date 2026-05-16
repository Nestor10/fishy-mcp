package fishy.mcp.adapters.inbound.http

import zio.*

/** Selects the HTTP security policy from an [[AuthConfig]] value.
  *
  * Configuration is loaded once at startup via `AppConfig.load`; this object
  * just dispatches on the resolved enum.
  */
object ConfigDrivenAuth:

  val layer: URLayer[AuthConfig, HttpSecurityPolicy] =
    ZLayer.fromZIO(ZIO.service[AuthConfig]).flatMap { env =>
      env.get[AuthConfig] match
        case AuthConfig.AllowAll => HttpSecurityPolicy.allowAll
        case AuthConfig.Trusted  => TrustedHeaderPolicy.layer()
        case jwt: AuthConfig.Jwt =>
          JwtSecurityPolicy.layer(JwtSecurityPolicy.Config(
            jwksUri = jwt.jwksUri,
            issuer = jwt.issuer,
            audience = jwt.audience,
            groupsClaim = jwt.groupsClaim,
            scopesClaim = jwt.scopesClaim,
            resourceMetadataUrl = jwt.resourceMetadataUrl,
            realm = jwt.realm
          )).orDie
    }
