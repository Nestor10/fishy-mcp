package fishy.mcp.bootstrap.oauth

import fishy.mcp.application.ports.oauth.{AdmissionPolicy, SigningKeySource, StubMarker, UpstreamIdP}
import zio.*
import zio.http.Client
import zio.test.*

/** `OAuthLayers.fromEnv` selects each non-storage port from `OAUTH_*` env: the
  * real adapter when configured, else the dev stub (a `StubMarker`, which the
  * production audit refuses). These lock that selection. */
object OAuthFromEnvSpec extends ZIOSpecDefault:

  private val baseEnv = Map("OAUTH_ISSUER" -> "https://svc.test", "OAUTH_RESOURCE" -> "https://svc.test")

  /** Build the non-storage ports under a fixed env, returning the resolved
    * environment so we can inspect which adapter each port resolved to. */
  private def ports(env: Map[String, String]): Task[ZEnvironment[OAuthLayers.NonStoragePorts]] =
    ZIO.scoped {
      ZLayer
        .make[OAuthLayers.NonStoragePorts](Client.default.orDie, OAuthLayers.fromEnv.orDie)
        .build
    }.withConfigProvider(ConfigProvider.fromMap(env))

  def spec = suite("OAuthLayers.fromEnv")(
    test("with no upstream / admission / signing env, all three resolve to stubs") {
      ports(baseEnv).map { env =>
        assertTrue(
          env.get[UpstreamIdP].isInstanceOf[StubMarker],
          env.get[AdmissionPolicy].isInstanceOf[StubMarker],
          env.get[SigningKeySource].isInstanceOf[StubMarker]
        )
      }
    },
    test("OAUTH_UPSTREAM_ISSUER selects the generic OIDC driver (not a stub)") {
      ports(baseEnv ++ Map(
        "OAUTH_UPSTREAM_ISSUER"        -> "https://idp.test",
        "OAUTH_UPSTREAM_CLIENT_ID"     -> "client-id",
        "OAUTH_UPSTREAM_CLIENT_SECRET" -> "client-secret"
      )).map(env => assertTrue(!env.get[UpstreamIdP].isInstanceOf[StubMarker]))
    },
    test("OAUTH_ADMISSION_EMAIL_DOMAINS selects the email allowlist (not a stub)") {
      ports(baseEnv ++ Map("OAUTH_ADMISSION_EMAIL_DOMAINS" -> "example.com,test.org"))
        .map(env => assertTrue(!env.get[AdmissionPolicy].isInstanceOf[StubMarker]))
    }
  )
