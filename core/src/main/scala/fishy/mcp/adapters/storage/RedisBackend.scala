package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.{EventReplay, MessageRouter, PersistenceBackend, SessionStore}
import zio.*
import zio.redis.{Redis, RedisSubscription}
import zio.stream.ZStream

import java.util.UUID

/** Redis-backed persistence.
  *
  * Key layout:
  *   - `mcp:session:{id}` -- creation timestamp (with TTL)
  *   - `mcp:sessions` -- Redis SET of active session IDs
  *   - `mcp:msg:{sessionId}` -- Pub/Sub channel
  *   - `mcp:events:{sessionId}` -- Redis Stream
  *
  * Message routing uses Redis Pub/Sub with a local Hub relay per session for multi-instance
  * deployments.
  */
object RedisBackend:

  val DefaultTtl: Duration = 24.hours
  val DefaultMaxLen: Long = 1000L
  private val HubCapacity = 256

  private def sessionKey(id: String): String = s"mcp:session:$id"
  private def initKey(id: String): String = s"mcp:init:$id"
  private val sessionsSetKey: String = "mcp:sessions"
  private def channelName(sid: String): String = s"mcp:msg:$sid"
  private def streamKey(sid: String): String = s"mcp:events:$sid"

  private case class HubEntry(
      hub: Hub[String],
      subscriptionFiber: Fiber.Runtime[Throwable, Unit]
  )

  def layer(
      ttl: Duration = DefaultTtl,
      maxLen: Long = DefaultMaxLen
  ): ZLayer[Redis & RedisSubscription, Nothing, SessionStore & MessageRouter & EventReplay] =
    ZLayer.scopedEnvironment {
      for
        redis <- ZIO.service[Redis]
        redisSub <- ZIO.service[RedisSubscription]
        hubMap <- Ref.make(Map.empty[String, HubEntry])
        backend = Live(redis, redisSub, hubMap, ttl, maxLen)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  private final case class Live(
      redis: Redis,
      redisSub: RedisSubscription,
      hubMap: Ref[Map[String, HubEntry]],
      ttl: Duration,
      maxLen: Long
  ) extends PersistenceBackend:

    // -- SessionStore ---------------------------------------------------------

    def create(): UIO[String] =
      val sessionId = UUID.randomUUID().toString
      val key = sessionKey(sessionId)
      (for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _ <- redis.set(key, now.toString).orDie
        _ <- redis.pExpire(key, ttl).orDie
        _ <- redis.sAdd(sessionsSetKey, sessionId).orDie
      yield sessionId)

    def exists(sessionId: String): UIO[Boolean] =
      redis.exists(sessionKey(sessionId)).map(_ > 0L).orDie

    def remove(sessionId: String): UIO[Unit] =
      (for
        _ <- redis.del(sessionKey(sessionId))
        _ <- redis.sRem(sessionsSetKey, sessionId)
      yield ()).orDie

    def allSessionIds: UIO[List[String]] =
      redis.sMembers(sessionsSetKey).returning[String].map(_.toList).orDie

    def markInitialized(sessionId: String): UIO[Unit] =
      (for
        _ <- redis.set(initKey(sessionId), "1").orDie
        _ <- redis.pExpire(initKey(sessionId), ttl).orDie
      yield ())

    def isInitialized(sessionId: String): UIO[Boolean] =
      redis.exists(initKey(sessionId)).map(_ > 0L).orDie

    // -- MessageRouter --------------------------------------------------------

    def publish(sessionId: String, message: String): UIO[Boolean] =
      redis.publish(channelName(sessionId), message)
        .map(_ > 0L)
        .orDie

    def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      for
        entry <- getOrCreateHubEntry(sessionId)
        dequeue <- entry.hub.subscribe
      yield ZStream.fromQueue(dequeue)

    def hasSubscribers(sessionId: String): UIO[Boolean] =
      hubMap.get.map(_.contains(sessionId))

    // -- EventReplay ----------------------------------------------------------

    def append(sessionId: String, message: String): UIO[String] =
      val key = streamKey(sessionId)
      (for
        idOpt <- redis.xAdd(key, "*")("msg" -> message).returning[String]
        id = idOpt.getOrElse("0-0")
        _ <- redis.xTrimWithMaxLen(key, maxLen, approximate = true)
      yield id).orDie

    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] =
      val key = streamKey(sessionId)
      val exclusiveStart = s"($eventId"
      redis
        .xRange(key, exclusiveStart, "+")
        .returning[String, String]
        .map { entries =>
          entries.map { entry =>
            val id = entry.id.toString
            val msg = entry.fields.headOption.map(_._2).getOrElse("")
            EventReplay.Event(id, msg)
          }.toList
        }
        .orDie

    // -- Combined session teardown --------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] =
      val removeHub = hubMap.modify { map =>
        val entry = map.get(sessionId)
        (entry, map - sessionId)
      }.flatMap {
        case Some(entry) => entry.subscriptionFiber.interrupt *> entry.hub.shutdown
        case None        => ZIO.unit
      }
      val removeStream = redis.del(streamKey(sessionId)).unit.orDie
      removeHub *> removeStream

    // -- Internals ------------------------------------------------------------

    private def getOrCreateHubEntry(sessionId: String): UIO[HubEntry] =
      hubMap.get.map(_.get(sessionId)).flatMap {
        case Some(entry) => ZIO.succeed(entry)
        case None =>
          for
            hub <- Hub.bounded[String](HubCapacity)
            fiber <- startRedisSubscription(sessionId, hub).forkDaemon
            entry = HubEntry(hub, fiber)
            result <- hubMap.modify { map =>
              map.get(sessionId) match
                case Some(existing) => (existing, map)
                case None           => (entry, map + (sessionId -> entry))
            }
            _ <- ZIO.when(result ne entry) {
              fiber.interrupt *> hub.shutdown
            }
          yield result
      }

    private def startRedisSubscription(
        sessionId: String,
        hub: Hub[String]
    ): ZIO[Any, Throwable, Unit] =
      redisSub
        .subscribe(channelName(sessionId))
        .returning[String]
        .mapZIO { case (_, msg) => hub.publish(msg) }
        .runDrain
