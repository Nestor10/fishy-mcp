package fishy.mcp.adapters.protocol.mcp

import zio.json.*
import zio.json.ast.Json

/** MCP capability negotiation types for the initialize handshake. */

// ---------------------------------------------------------------------------
// Client -> Server (Initialize Request)
// ---------------------------------------------------------------------------

final case class ClientInfo(
    name: String,
    version: String
)

object ClientInfo:
  given JsonDecoder[ClientInfo] = DeriveJsonDecoder.gen
  given JsonEncoder[ClientInfo] = DeriveJsonEncoder.gen

final case class RootsCapability(
    listChanged: Option[Boolean] = None
)

object RootsCapability:
  given JsonDecoder[RootsCapability] = DeriveJsonDecoder.gen
  given JsonEncoder[RootsCapability] = DeriveJsonEncoder.gen

final case class ClientCapabilities(
    experimental: Option[Map[String, Json]] = None,
    roots: Option[RootsCapability] = None,
    sampling: Option[Json] = None,
    elicitation: Option[Json] = None
)

object ClientCapabilities:
  given JsonDecoder[ClientCapabilities] = DeriveJsonDecoder.gen
  given JsonEncoder[ClientCapabilities] = DeriveJsonEncoder.gen

final case class InitializeParams(
    protocolVersion: String,
    capabilities: ClientCapabilities,
    clientInfo: ClientInfo
)

object InitializeParams:
  given JsonDecoder[InitializeParams] = DeriveJsonDecoder.gen
  given JsonEncoder[InitializeParams] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// Server -> Client (Initialize Response)
// ---------------------------------------------------------------------------

final case class ServerInfo(
    name: String,
    version: String
)

object ServerInfo:
  given JsonDecoder[ServerInfo] = DeriveJsonDecoder.gen
  given JsonEncoder[ServerInfo] = DeriveJsonEncoder.gen

final case class ToolsCapability(
    listChanged: Option[Boolean] = None
)

object ToolsCapability:
  given JsonDecoder[ToolsCapability] = DeriveJsonDecoder.gen
  given JsonEncoder[ToolsCapability] = DeriveJsonEncoder.gen

final case class ResourcesCapability(
    subscribe: Option[Boolean] = None,
    listChanged: Option[Boolean] = None
)

object ResourcesCapability:
  given JsonDecoder[ResourcesCapability] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourcesCapability] = DeriveJsonEncoder.gen

final case class PromptsCapability(
    listChanged: Option[Boolean] = None
)

object PromptsCapability:
  given JsonDecoder[PromptsCapability] = DeriveJsonDecoder.gen
  given JsonEncoder[PromptsCapability] = DeriveJsonEncoder.gen

final case class ServerCapabilities(
    tools: Option[ToolsCapability] = None,
    resources: Option[ResourcesCapability] = None,
    prompts: Option[PromptsCapability] = None,
    logging: Option[Json] = None,
    experimental: Option[Map[String, Json]] = None
)

object ServerCapabilities:
  given JsonDecoder[ServerCapabilities] = DeriveJsonDecoder.gen
  given JsonEncoder[ServerCapabilities] = DeriveJsonEncoder.gen

  /** Capabilities for a tools-only server. */
  def toolsOnly: ServerCapabilities =
    ServerCapabilities(tools = Some(ToolsCapability(listChanged = Some(true))))

final case class InitializeResult(
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: ServerInfo,
    instructions: Option[String] = None
)

object InitializeResult:
  given JsonDecoder[InitializeResult] = DeriveJsonDecoder.gen
  given JsonEncoder[InitializeResult] = DeriveJsonEncoder.gen
