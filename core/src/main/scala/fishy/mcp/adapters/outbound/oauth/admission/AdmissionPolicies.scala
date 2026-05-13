package fishy.mcp.adapters.outbound.oauth.admission

import fishy.mcp.application.ports.oauth.{AdmissionPolicy, StubMarker}
import fishy.mcp.domain.model.oauth.{IdentityStatus, Tenant, UpstreamIdentity}

/** Admit any verified upstream user as `Active`. Homelab default.
  *
  * Mixed in [[StubMarker]] so the OAuth audit refuses to start the server in
  * production when this is the resolved `AdmissionPolicy`.
  */
object AllowAllAdmission extends AdmissionPolicy with StubMarker:
  override val stubId: String = "AllowAllAdmission"

  override def evaluate(tenant: Tenant, upstream: UpstreamIdentity): IdentityStatus =
    if upstream.emailVerified then IdentityStatus.Active else IdentityStatus.Pending

/** Admit when the email domain matches one of the configured suffixes. */
final case class EmailDomainAllowlistAdmission(domains: Set[String]) extends AdmissionPolicy:
  private val normalized = domains.map(_.toLowerCase.stripPrefix("@"))

  override def evaluate(tenant: Tenant, upstream: UpstreamIdentity): IdentityStatus =
    val domain = upstream.email.split('@').lastOption.map(_.toLowerCase)
    if upstream.emailVerified && domain.exists(normalized.contains) then IdentityStatus.Active
    else IdentityStatus.Pending

/** Every new identity starts `Pending` until an admin approves it. */
object ManualApprovalAdmission extends AdmissionPolicy:
  override def evaluate(tenant: Tenant, upstream: UpstreamIdentity): IdentityStatus =
    IdentityStatus.Pending
