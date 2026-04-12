package fishy.mcp.adapters.inbound.http

import fishy.mcp.domain.model.AuthContext
import zio.*

/** Identity extraction from trusted upstream proxy headers.
  *
  * Intended for deployments behind an authenticating reverse proxy (Istio, Envoy, nginx with OIDC,
  * kagent) that sets identity headers after validating the caller. The SDK trusts these headers --
  * no cryptographic verification is performed.
  *
  * Default header mapping:
  *   - `X-Forwarded-User` or `X-User-Id` -> `sub`
  *   - `X-Forwarded-Email` or `X-User-Email` -> `email`
  *   - `X-Forwarded-Preferred-Username` -> `name`
  *   - `X-Forwarded-Groups` (comma-separated) -> `groups`
  *   - `X-Forwarded-Scopes` (space-separated) -> `scopes`
  */
object TrustedHeaderPolicy:

  final case class HeaderMapping(
      subHeaders: List[String] = List("X-Forwarded-User", "X-User-Id"),
      emailHeaders: List[String] = List("X-Forwarded-Email", "X-User-Email"),
      nameHeaders: List[String] = List("X-Forwarded-Preferred-Username"),
      groupsHeader: String = "X-Forwarded-Groups",
      scopesHeader: String = "X-Forwarded-Scopes"
  )

  val defaultMapping: HeaderMapping = HeaderMapping()

  /** Build a security policy that extracts identity from trusted headers.
    *
    * Requests without at least a `sub` header are rejected with 401.
    */
  def layer(mapping: HeaderMapping = defaultMapping): ULayer[HttpSecurityPolicy] =
    HttpSecurityPolicy.fromExtractor { request =>
      val headers = request.headers

      val sub = mapping.subHeaders.collectFirst {
        case h if headers.get(h).isDefined => headers.get(h).get
      }

      sub match
        case None =>
          ZIO.fail("Missing identity header: expected one of " + mapping.subHeaders.mkString(", "))
        case Some(subject) =>
          val email = mapping.emailHeaders.collectFirst {
            case h if headers.get(h).isDefined => headers.get(h).get
          }
          val name = mapping.nameHeaders.collectFirst {
            case h if headers.get(h).isDefined => headers.get(h).get
          }
          val groups = headers.get(mapping.groupsHeader)
            .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
            .getOrElse(Set.empty)
          val scopes = headers.get(mapping.scopesHeader)
            .map(_.split(" ").map(_.trim).filter(_.nonEmpty).toSet)
            .getOrElse(Set.empty)

          ZIO.succeed(AuthContext(
            sub = subject,
            email = email,
            name = name,
            groups = groups,
            scopes = scopes,
            rawClaims = None
          ))
    }

  /** Convenience: layer with default header mapping. */
  val defaultLayer: ULayer[HttpSecurityPolicy] = layer()
