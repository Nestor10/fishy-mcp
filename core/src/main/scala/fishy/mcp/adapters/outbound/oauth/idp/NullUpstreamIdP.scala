package fishy.mcp.adapters.outbound.oauth.idp

import fishy.mcp.application.ports.oauth.{StubMarker, UpstreamIdP}
import fishy.mcp.domain.model.oauth.{Tenant, UpstreamIdentity}
import zio.*

/** Placeholder [[UpstreamIdP]] that fails every exchange.
  *
  * Used as the default in the in-memory layer group so `withOAuth(config)`
  * continues to compile and metadata routes remain functional. Replace with
  * a real adapter (Google OIDC, generic OIDC, etc.) to run the full
  * authorization-code flow.
  *
  * Mixed in [[StubMarker]] so the OAuth audit refuses to start the server in
  * production when this is the resolved `UpstreamIdP`.
  */
object NullUpstreamIdP:

  val layer: ULayer[UpstreamIdP] =
    ZLayer.succeed[UpstreamIdP](new UpstreamIdP with StubMarker:
      override val stubId: String = "NullUpstreamIdP"

      override def authorizationUrl(
          tenant: Tenant,
          state: String,
          codeChallenge: String,
          codeChallengeMethod: String,
          redirectUri: String
      ): UIO[String] =
        ZIO.succeed(
          s"$redirectUri?error=unsupported_provider&error_description=no+upstream+IdP+configured&state=$state"
        )

      override def exchangeCode(
          tenant: Tenant,
          code: String,
          redirectUri: String
      ): IO[UpstreamIdP.ExchangeError, UpstreamIdentity] =
        ZIO.fail(UpstreamIdP.ExchangeError.Upstream("no upstream IdP is configured"))
    )
