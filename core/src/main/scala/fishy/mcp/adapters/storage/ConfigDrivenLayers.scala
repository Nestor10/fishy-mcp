package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, SessionStore, SubscriptionRegistry}
import zio.*
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisSubscription}

/** Selects the persistence backend stack from a [[BackendConfig]] value.
  *
  * Configuration is loaded once at startup via `AppConfig.load`; this object
  * just dispatches on the resolved enum.
  */
object ConfigDrivenLayers:

  /** Builds the full session-state stack — including the resource
    * [[SubscriptionRegistry]] — from a `BackendConfig` already in the
    * environment. Subscriptions are selected alongside the rest so Redis mode is
    * genuinely multi-instance; in-memory and stateless share the in-memory
    * registry.
    */
  val live
      : URLayer[BackendConfig, SessionStore & MessageRouter & EventReplay & SubscriptionRegistry] =
    ZLayer.fromZIO(ZIO.service[BackendConfig]).flatMap { env =>
      env.get[BackendConfig] match
        case BackendConfig.Stateless  => StatelessBackend.layer ++ InMemorySubscriptionRegistry.layer
        case BackendConfig.InMemory   => InMemoryBackend.layer ++ InMemorySubscriptionRegistry.layer
        case BackendConfig.Redis(url) => redisLayers(url).orDie
    }

  private def redisLayers(url: String)
      : ZLayer[Any, Throwable, SessionStore & MessageRouter & EventReplay & SubscriptionRegistry] =
    val codec = ZLayer.succeed(CodecSupplier.utf8)
    val redisConfig = ZLayer.succeed(parseRedisUrl(url))
    val redis = (codec ++ redisConfig) >>> Redis.singleNode
    val redisSub = (codec ++ redisConfig) >>> RedisSubscription.singleNode
    // Share one Redis connection across the backend and the subscription registry.
    (redis ++ redisSub) >>> (RedisBackend.layer() ++ RedisSubscriptionRegistry.layer)

  private def parseRedisUrl(url: String): RedisConfig =
    val cleaned = url.stripPrefix("redis://").stripPrefix("rediss://")
    val parts = cleaned.split(':')
    val host = parts.headOption.getOrElse("localhost")
    val port = parts.lift(1).flatMap(_.toIntOption).getOrElse(6379)
    RedisConfig(host, port)
