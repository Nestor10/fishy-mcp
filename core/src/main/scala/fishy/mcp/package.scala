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
export domain.model.mcp.CreateMessageParams
export domain.model.mcp.CreateMessageResult
export domain.model.mcp.SamplingMessage
export domain.model.mcp.SamplingContent
export domain.model.mcp.ModelPreferences
export domain.model.mcp.ListRootsResult
export domain.model.mcp.Root
export domain.model.mcp.ElicitationParams
export domain.model.mcp.ElicitationResult
export domain.model.mcp.ElicitationAction
