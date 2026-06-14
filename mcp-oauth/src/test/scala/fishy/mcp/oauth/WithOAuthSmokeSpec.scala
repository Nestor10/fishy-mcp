package fishy.mcp.oauth

import fishy.mcp.bootstrap.MCPServer
import fishy.mcp.adapters.inbound.http.HttpExtraRoutes
import fishy.mcp.bootstrap.config.{DeploymentConfig, DeploymentProfile}
import zio.*
import zio.test.*

/** Locks the MCP↔OAuth glue: the `withOAuth` builder extension is wired onto
  * `MCPServer`, and the in-memory feature builds a mountable `HttpExtraRoutes`.
  */
object WithOAuthSmokeSpec extends ZIOSpecDefault:

  private val config = OAuthConfig(issuer = "https://idp.example", resource = "https://api.example")

  def spec = suite("fishy-mcp-oauth glue")(
    test("withOAuth extension returns a configured MCPServer") {
      val server = MCPServer.withName("svc").withOAuth(config)
      assertTrue(server.name == "svc")
    },
    test("withCustomOAuth widens the environment by OAuthLayers.Ports") {
      // Type-level check: compiles iff the extension surfaces the port bundle.
      val server: MCPServer[OAuthLayers.Ports] = MCPServer.withName("svc").withCustomOAuth
      assertTrue(server.name == "svc")
    },
    test("inMemory feature builds an HttpExtraRoutes layer") {
      ZIO.scoped {
        OAuthFeature
          .inMemory(config)
          .build
          .provideSomeLayer[Scope](ZLayer.succeed(DeploymentConfig(DeploymentProfile.Dev)))
          .map(env => assertTrue(env.get[HttpExtraRoutes] ne null))
      }
    }
  )
