package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.SubscriptionRegistry
import zio.*
import zio.redis.Redis

/** Redis-backed resource-subscription registry.
  *
  * The in-memory variant only sees subscriptions recorded on the *same* JVM, so
  * under a multi-instance Redis deployment a `resources/updated` raised on
  * instance B would never find a subscriber that registered on instance A. This
  * keeps the subscription set in Redis so `subscribers(uri)` is global.
  *
  * Key layout:
  *   - `mcp:subs:{uri}`         -- SET of session IDs subscribed to a resource URI
  *   - `mcp:session-subs:{sid}` -- SET of URIs a session subscribed to (for teardown)
  *
  * Every Redis call retries transient blips then surfaces a persistent failure
  * as a defect, matching [[RedisBackend]].
  */
object RedisSubscriptionRegistry:

  private val retrySchedule: Schedule[Any, Throwable, Any] =
    Schedule.exponential(100.millis) && Schedule.recurs(4)

  private def uriKey(uri: String): String = s"mcp:subs:$uri"
  private def sessionKey(sid: String): String = s"mcp:session-subs:$sid"

  val layer: URLayer[Redis, SubscriptionRegistry] =
    ZLayer.fromFunction((redis: Redis) => Live(redis))

  private final case class Live(redis: Redis) extends SubscriptionRegistry:

    private def resilient[A](z: ZIO[Any, Throwable, A]): UIO[A] =
      z.retry(retrySchedule).orDie

    def subscribe(sessionId: String, uri: String): UIO[Unit] =
      resilient(
        redis.sAdd(uriKey(uri), sessionId) *> redis.sAdd(sessionKey(sessionId), uri).unit
      )

    def unsubscribe(sessionId: String, uri: String): UIO[Unit] =
      resilient(
        redis.sRem(uriKey(uri), sessionId) *> redis.sRem(sessionKey(sessionId), uri).unit
      )

    def subscribers(uri: String): UIO[Set[String]] =
      resilient(redis.sMembers(uriKey(uri)).returning[String]).map(_.toSet)

    def removeSession(sessionId: String): UIO[Unit] =
      resilient(
        for
          uris <- redis.sMembers(sessionKey(sessionId)).returning[String]
          _    <- ZIO.foreachDiscard(uris)(uri => redis.sRem(uriKey(uri), sessionId))
          _    <- redis.del(sessionKey(sessionId))
        yield ()
      )
