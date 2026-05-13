package fishy.mcp.adapters.storage

import zio.*

/** Persistence backend selection.
  *
  *   - `MCP_STATELESS=true` and no `REDIS_URL` -- no session state
  *     ([[BackendConfig.Stateless]]). Suitable for horizontal scaling without
  *     stickiness.
  *   - `REDIS_URL` set, `MCP_STATELESS` unset/false -- Redis-backed
  *     ([[BackendConfig.Redis]]). Suitable for horizontal scaling with
  *     session affinity managed by Redis Pub/Sub.
  *   - Neither set -- in-memory ([[BackendConfig.InMemory]]). Single
  *     instance only; restart loses every session.
  *
  * Setting both `MCP_STATELESS=true` and `REDIS_URL` is a startup error.
  */
enum BackendConfig:
  case InMemory
  case Stateless
  case Redis(url: String)

object BackendConfig:

  val config: Config[BackendConfig] = (
    Config.boolean("MCP_STATELESS").withDefault(false) zip
    Config.string("REDIS_URL").optional
  ).mapOrFail {
    case (true, Some(_)) =>
      Left(Config.Error.InvalidData(
        Chunk("MCP_STATELESS"),
        "MCP_STATELESS=true and REDIS_URL are mutually exclusive"
      ))
    case (true, None)        => Right(BackendConfig.Stateless)
    case (false, Some(url))  => Right(BackendConfig.Redis(url))
    case (false, None)       => Right(BackendConfig.InMemory)
  }
