package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.TenantId

/** Per-tenant configuration for the upstream IdP.
  *
  * Concrete upstream clients read this to build authorization URLs and
  * exchange codes. Client secret is kept out of logs by construction: callers
  * should never stringify this value.
  */
enum TenantIdpConfig:
  case Google(googleClientId: String, clientSecret: String, hostedDomain: Option[String] = None)
  case Oidc(
      issuer: String,
      oidcClientId: String,
      clientSecret: String,
      scopes: List[String] = List("openid", "email", "profile")
  )

  def clientId: String = this match
    case Google(id, _, _)  => id
    case Oidc(_, id, _, _) => id

/** Admission rule name + raw args -- the concrete evaluator lives in an adapter. */
final case class AdmissionSpec(kind: String, args: Map[String, String])

final case class Tenant(
    id: TenantId,
    hostname: String,
    idp: TenantIdpConfig,
    admission: AdmissionSpec
)
