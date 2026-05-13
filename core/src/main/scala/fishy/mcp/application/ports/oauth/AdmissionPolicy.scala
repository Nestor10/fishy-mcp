package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.{IdentityStatus, Tenant, UpstreamIdentity}

/** Lifts a freshly-authenticated upstream user into an `IdentityStatus`.
  *
  * Pure by design -- admission decisions are deterministic given the same
  * tenant + upstream identity. Composition helpers let host apps stack rules
  * (e.g. "admin if email in admin list, otherwise active if domain allowlisted,
  * otherwise pending").
  */
trait AdmissionPolicy:
  def evaluate(tenant: Tenant, upstream: UpstreamIdentity): IdentityStatus

object AdmissionPolicy:

  /** Take the first policy that returns something other than `Pending`. */
  def any(policies: AdmissionPolicy*): AdmissionPolicy =
    (tenant, upstream) =>
      policies.iterator
        .map(_.evaluate(tenant, upstream))
        .find(_ != IdentityStatus.Pending)
        .getOrElse(IdentityStatus.Pending)

  /** Intersect: if any policy returns `Pending` or `Disabled`, that wins. */
  def all(policies: AdmissionPolicy*): AdmissionPolicy =
    (tenant, upstream) =>
      policies.foldLeft[IdentityStatus](IdentityStatus.Active) { (acc, p) =>
        (acc, p.evaluate(tenant, upstream)) match
          case (_, IdentityStatus.Disabled) => IdentityStatus.Disabled
          case (IdentityStatus.Disabled, _) => IdentityStatus.Disabled
          case (_, IdentityStatus.Pending)  => IdentityStatus.Pending
          case (IdentityStatus.Pending, _)  => IdentityStatus.Pending
          case (IdentityStatus.Admin, b)    => b // prefer the more restrictive of Admin/Active
          case (a, _)                       => a
      }
