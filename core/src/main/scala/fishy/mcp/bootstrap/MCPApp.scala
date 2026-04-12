package fishy.mcp.bootstrap

import zio.*

/** ZIO app base with structured JSON logging.
  *
  * Extend instead of ZIOAppDefault when bypassing serveHttp/serveStdio. Log level controlled by
  * LOG_LEVEL env var (default INFO).
  */
trait MCPApp extends ZIOAppDefault:
  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    MCPServer.httpLoggingLayer
