package fishy.mcp.adapters.outbound.oauth.admission

import fishy.mcp.domain.model.oauth.*
import fishy.mcp.domain.model.oauth.Ids.TenantId
import zio.test.*

object AdmissionPoliciesSpec extends ZIOSpecDefault:

  private val tenant = Tenant(
    id = TenantId("t"),
    hostname = "example.com",
    idp = TenantIdpConfig.Oidc("https://issuer", "cid", "sec"),
    admission = AdmissionSpec("allow-all", Map.empty)
  )

  private def upstream(email: String, verified: Boolean = true): UpstreamIdentity =
    UpstreamIdentity(
      ref = UpstreamRef(UpstreamProvider.Google, "sub-1"),
      email = email,
      emailVerified = verified,
      name = None,
      rawClaims = Map.empty
    )

  override def spec = suite("AdmissionPolicies")(
    test("AllowAll admits verified users as Active") {
      assertTrue(AllowAllAdmission.evaluate(tenant, upstream("a@b.com")) == IdentityStatus.Active)
    },
    test("AllowAll defers unverified users to Pending") {
      assertTrue(AllowAllAdmission.evaluate(tenant, upstream("a@b.com", verified = false)) == IdentityStatus.Pending)
    },
    test("EmailDomainAllowlist admits only allow-listed domains") {
      val p = EmailDomainAllowlistAdmission(Set("allowed.com"))
      assertTrue(
        p.evaluate(tenant, upstream("x@allowed.com")) == IdentityStatus.Active,
        p.evaluate(tenant, upstream("x@other.com")) == IdentityStatus.Pending
      )
    },
    test("EmailDomainAllowlist strips a leading @ in configured domains") {
      val p = EmailDomainAllowlistAdmission(Set("@allowed.com"))
      assertTrue(p.evaluate(tenant, upstream("x@allowed.com")) == IdentityStatus.Active)
    },
    test("ManualApproval keeps everyone Pending") {
      assertTrue(ManualApprovalAdmission.evaluate(tenant, upstream("a@b.com")) == IdentityStatus.Pending)
    }
  )
