package fishy.mcp.bootstrap

import fishy.mcp.bootstrap.config.LoggingConfig
import zio.*

/** ZIO app base with structured JSON logging.
  *
  * Extend instead of ZIOAppDefault when bypassing `serveHttp` / `serveStdio`.
  * Log level is read from `LOG_LEVEL` (default INFO) via [[AppConfig.load]].
  */
trait MCPApp extends ZIOAppDefault:
  override val bootstrap: ZLayer[Any, Config.Error, Unit] =
    ZLayer.fromZIO(ZIO.config(LoggingConfig.config)).flatMap { env =>
      LoggingLayers.stdoutJson(env.get[LoggingConfig])
    }
