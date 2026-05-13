package fishy.mcp.bootstrap.config

import zio.*

/** Inbound HTTP transport configuration.
  *
  *   - `PORT` -- TCP port to bind. Default `8080`.
  *
  * Bind address is intentionally not configured here -- `zio-http`
  * binds `0.0.0.0` by default; override only via host networking config.
  */
final case class HttpServerConfig(port: Int)

object HttpServerConfig:
  val config: Config[HttpServerConfig] =
    Config.int("PORT").withDefault(8080).map(HttpServerConfig.apply)
