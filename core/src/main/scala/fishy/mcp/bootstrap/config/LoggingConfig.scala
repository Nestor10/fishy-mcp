package fishy.mcp.bootstrap.config

import zio.*

/** Logging configuration.
  *
  *   - `LOG_LEVEL` -- one of `trace|debug|info|warning|error|fatal`.
  *     Default `info`.
  */
final case class LoggingConfig(level: LogLevel)

object LoggingConfig:

  val config: Config[LoggingConfig] =
    Config.logLevel("LOG_LEVEL").withDefault(LogLevel.Info).map(LoggingConfig.apply)
