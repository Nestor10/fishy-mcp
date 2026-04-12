package fishy.mcp

import fishy.mcp.domain.model.ToolError

/** fishy-mcp: A Scala SDK for building MCP servers with ZIO.
  *
  * Import this package to get started:
  * {{{
  * import fishy.mcp.*
  *
  * final case class GreetInput(name: String) derives zio.schema.Schema
  *
  * val greet = Tool("greet").description("Say hello").handle { (in: GreetInput, _: ToolContext) =>
  *   ZIO.succeed(Content.Text(s"Hello, ${in.name}!"))
  * }
  *
  * MCPServer
  *   .withTools(greet)
  *   .serveStdio
  * }}}
  */

// Re-export user-facing types for clean imports
export dsl.Tool
export dsl.MCPServer
export domain.model.ToolError
export domain.model.ToolContext
export domain.model.Content
export domain.model.AuthContext
export application.ports.SessionHooks
export adapters.inbound.http.JwtSecurityPolicy
export adapters.inbound.http.TrustedHeaderPolicy
export adapters.inbound.http.HttpSecurityPolicy
export adapters.protocol.mcp.ClientMessages.*
export adapters.protocol.mcp.CreateMessageParams
export adapters.protocol.mcp.CreateMessageResult
export adapters.protocol.mcp.SamplingMessage
export adapters.protocol.mcp.SamplingContent
export adapters.protocol.mcp.ModelPreferences
export adapters.protocol.mcp.ListRootsResult
export adapters.protocol.mcp.Root
export adapters.protocol.mcp.ElicitationParams
export adapters.protocol.mcp.ElicitationResult
export adapters.protocol.mcp.ElicitationAction
