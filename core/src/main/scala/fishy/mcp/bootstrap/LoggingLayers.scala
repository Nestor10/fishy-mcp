package fishy.mcp.bootstrap

import fishy.mcp.bootstrap.config.LoggingConfig
import zio.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleJsonLogger, consoleErrJsonLogger}

/** Include log annotations (e.g. zio-http's `method`/`url`/`status_code`/
  * `duration_ms` set by `HandlerAspect.requestLogging`) in every line. */
private val format: LogFormat = LogFormat.default + LogFormat.space + LogFormat.allAnnotations

/** Structured-JSON console loggers driven by [[LoggingConfig]].
  *
  * Two flavors with the same payload format -- they differ only in the
  * destination stream:
  *
  *   - [[stdoutJson]] -- writes to stdout. Use with the HTTP transport,
  *     where stdout is a normal log channel.
  *   - [[stderrJson]] -- writes to stderr. Use with the stdio transport,
  *     because stdout is reserved for the MCP protocol channel.
  *
  * The level filter is sourced from `LOG_LEVEL` via [[LoggingConfig]].
  */
object LoggingLayers:

  def stdoutJson(cfg: LoggingConfig): ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> consoleJsonLogger(ConsoleLoggerConfig(
      format,
      LogFilter.LogLevelByNameConfig(cfg.level)
    ))

  def stderrJson(cfg: LoggingConfig): ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> consoleErrJsonLogger(ConsoleLoggerConfig(
      format,
      LogFilter.LogLevelByNameConfig(cfg.level)
    ))
