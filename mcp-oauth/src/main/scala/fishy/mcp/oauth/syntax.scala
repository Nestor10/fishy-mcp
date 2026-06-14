package fishy.mcp.oauth

import fishy.mcp.bootstrap.MCPServer
import fishy.mcp.bootstrap.oauth.OAuthLayers
import fishy.mcp.application.ports.oauth.{OAuthConfig, OAuthStorage}
import zio.*

/** MCP builder sugar for mounting the OAuth 2.1 authorization server.
  *
  * `import fishy.mcp.oauth.*` brings these into scope alongside [[OAuthConfig]]
  * and [[OAuthLayers]]:
  *
  * {{{
  * import fishy.mcp.*
  * import fishy.mcp.oauth.*
  *
  * MCPServer
  *   .withName("svc").withVersion("0.1.0")
  *   .withTools(myTool)
  *   .withOAuth(OAuthConfig(issuer = ..., resource = ...))
  *   .serveHttp
  * }}}
  *
  * The authorization server itself lives in the MCP-agnostic `fishy-oauth`
  * module; this is the thin binding that mounts its routes at core's
  * `HttpExtraRoutes` seam.
  */
extension [R](server: MCPServer[R])

  /** Built-in OAuth 2.1 AS with in-memory reference adapters. Dev only --
    * restart loses every record, and `MCP_PROFILE=production` refuses to boot
    * because every bundled port is a stub. */
  def withOAuth(config: OAuthConfig): MCPServer[R] =
    server.withHttpExtraRoutes[Any](OAuthFeature.inMemory(config))

  /** Built-in OAuth 2.1 AS atop a deployer-supplied port stack
    * ([[OAuthLayers.Ports]]); the SDK use-cases are wired internally. */
  def withOAuth(config: OAuthConfig, ports: ULayer[OAuthLayers.Ports]): MCPServer[R] =
    val _ = config
    server.withHttpExtraRoutes[Any](OAuthFeature.withCustomLayers(ports))

  /** Mount OAuth endpoints, deferring port wiring to the `serveHttp.provide`
    * boundary. The resulting `MCPServer[R & OAuthLayers.Ports]` requires the
    * deployer to provide one layer per OAuth port (or a bundled storage
    * layer). */
  def withCustomOAuth: MCPServer[R & OAuthLayers.Ports] =
    server.withHttpExtraRoutes[OAuthLayers.Ports](OAuthFeature.asEnvRequirement)

  /** Mount OAuth with env-driven port selection: every port except storage is
    * chosen from `OAUTH_*` env (real adapter when configured, else the dev stub
    * the production audit refuses), and the upstream OIDC `Client` is wired
    * automatically. The deployer supplies only an [[OAuthStorage]] at the
    * `serveHttp.provide` boundary — `InMemoryOAuthStorage.layer` for dev, their
    * own for production. */
  def withOAuthFromEnv: MCPServer[R & OAuthStorage] =
    server.withHttpExtraRoutes[OAuthStorage](OAuthFeature.fromEnv)
