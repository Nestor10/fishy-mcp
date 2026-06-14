package fishy.mcp.bootstrap.oauth

import fishy.mcp.application.ports.oauth.OAuthConfig
import fishy.mcp.adapters.outbound.oauth.tenant.SingleTenantResolver
import fishy.mcp.domain.model.oauth.{AdmissionSpec, Ids, Tenant, TenantIdpConfig}
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Layer-level stub gate, now framework-agnostic: `audit(isProduction)` refuses
  * to start when any wired port is a `StubMarker` and `isProduction` is true.
  */
object OAuthLayersAuditSpec extends ZIOSpecDefault:

  private val sampleConfig = OAuthConfig(
    issuer = "https://test",
    resource = "https://test",
    accessTokenTtl = 15.minutes,
    refreshTokenTtl = 30.days,
    authorizationCodeTtl = 60.seconds,
    scopesSupported = List("mcp:use"),
    audience = "test"
  )

  private val sampleTenant = SingleTenantResolver.layer(Tenant(
    id = Ids.TenantId("t"),
    hostname = "test",
    idp = TenantIdpConfig.Oidc("https://test", "", ""),
    admission = AdmissionSpec("allow-all", Map.empty)
  ))

  private val inMemoryPorts = OAuthLayers.inMemoryPorts(sampleConfig, sampleTenant)

  def spec = suite("OAuthLayers.audit (layer-level stub gate)")(
    test("non-production + in-memory stubs logs warning, succeeds") {
      OAuthLayers
        .audit(isProduction = false)
        .provide(inMemoryPorts)
        .as(assertCompletes)
    },
    test("production + in-memory stubs dies with refusal") {
      OAuthLayers
        .audit(isProduction = true)
        .provide(inMemoryPorts)
        .exit
        .map(exit => assert(exit)(dies(hasMessage(containsString("OAuth stub adapter")))))
    }
  )
