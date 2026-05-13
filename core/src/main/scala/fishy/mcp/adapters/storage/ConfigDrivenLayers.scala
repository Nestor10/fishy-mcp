package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, SessionStore}
import zio.*
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisSubscription}

/** Selects the persistence backend stack from a [[BackendConfig]] value.
  *
  * Configuration is loaded once at startup via `AppConfig.load`; this object
  * just dispatches on the resolved enum.
  */
object ConfigDrivenLayers:

  /** Builds a backend stack from a `BackendConfig` already in the environment. */
  val live: URLayer[BackendConfig, SessionStore & MessageRouter & EventReplay] =
    ZLayer.fromZIO(ZIO.service[BackendConfig]).flatMap { env =>
      env.get[BackendConfig] match
        case BackendConfig.Stateless    => StatelessBackend.layer
        case BackendConfig.InMemory     => InMemoryBackend.layer
        case BackendConfig.Redis(url)   => redisLayers(url).orDie
    }

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
