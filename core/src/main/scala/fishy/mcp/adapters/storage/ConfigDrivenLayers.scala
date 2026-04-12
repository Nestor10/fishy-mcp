package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, SessionStore}
import zio.*
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisSubscription}

/** Selects persistence backend from environment variables.
  *
  * `MCP_STATELESS=true` -- stateless backend (horizontal scaling, no session state). `REDIS_URL` --
  * Redis backend (horizontal scaling with session state). Neither -- in-memory backend (single
  * instance).
  *
  * Setting both `MCP_STATELESS=true` and `REDIS_URL` is a configuration error and will fail at
  * startup.
  */
object ConfigDrivenLayers:

  val live: ZLayer[Any, Nothing, SessionStore & MessageRouter & EventReplay] =
    ZLayer.fromZIO {
      for
        stateless <- System.env("MCP_STATELESS")
        redisUrl <- System.env("REDIS_URL")
      yield (stateless, redisUrl)
    }.flatMap { env =>
      val (stateless, redisUrl) = env.get[(Option[String], Option[String])]
      val isStateless = stateless.exists(_.equalsIgnoreCase("true"))
      (isStateless, redisUrl) match
        case (true, Some(_)) =>
          ZLayer.fromZIO(ZIO.die(
            new IllegalArgumentException("MCP_STATELESS=true and REDIS_URL are mutually exclusive")
          ))
        case (true, None) =>
          StatelessBackend.layer
        case (false, Some(url)) =>
          redisLayers(url)
        case (false, None) =>
          InMemoryBackend.layer
    }.orDie

  private def redisLayers(url: String)
      : ZLayer[Any, Throwable, SessionStore & MessageRouter & EventReplay] =
    val codec = ZLayer.succeed(CodecSupplier.utf8)
    val redisConfig = ZLayer.succeed(parseRedisUrl(url))
    val redis = (codec ++ redisConfig) >>> Redis.singleNode
    val redisSub = (codec ++ redisConfig) >>> RedisSubscription.singleNode
    (redis ++ redisSub) >>> RedisBackend.layer()

  private def parseRedisUrl(url: String): RedisConfig =
    val cleaned = url.stripPrefix("redis://").stripPrefix("rediss://")
    val parts = cleaned.split(':')
    val host = parts.headOption.getOrElse("localhost")
    val port = parts.lift(1).flatMap(_.toIntOption).getOrElse(6379)
    RedisConfig(host, port)
