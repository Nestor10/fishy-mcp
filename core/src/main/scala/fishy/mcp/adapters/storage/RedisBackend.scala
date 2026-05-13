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
  * Message routing uses Redis Pub/Sub with a local Hub relay per session for
  * multi-instance deployments.
  *
  * Resilience: every Redis I/O call is wrapped in [[resilient]] which retries
  * with exponential backoff (100ms → 800ms, 4 attempts) before downgrading
  * persistent failures to defects. The port contracts return `UIO`, so a
  * Redis-down condition that survives the retry budget kills the fiber --
  * which is the right behavior, since the application can't reasonably
  * proceed without its session store. Deployers point a watchdog at the
  * resulting fatal log line.
  */
object RedisBackend:

  val DefaultTtl: Duration = 24.hours
  val DefaultMaxLen: Long = 1000L
  private val HubCapacity = 256

  /** Exponential backoff for transient Redis blips: 100ms, 200ms, 400ms,
    * 800ms (4 retries, ~1.5s total) before the I/O dies. Per zionomicon
    * ch.24.
    */
  private val retrySchedule: Schedule[Any, Throwable, Any] =
    Schedule.exponential(100.millis) && Schedule.recurs(4)

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
        backendScope <- ZIO.scope
        redis        <- ZIO.service[Redis]
        redisSub     <- ZIO.service[RedisSubscription]
        hubMap       <- Ref.Synchronized.make(Map.empty[String, HubEntry])
        backend       = Live(redis, redisSub, hubMap, backendScope, ttl, maxLen)
      yield ZEnvironment[SessionStore](backend) ++
        ZEnvironment[MessageRouter](backend) ++
        ZEnvironment[EventReplay](backend)
    }

  /** Retry transient Redis failures, then surface the persistent failure as a
    * defect. Used for every Redis I/O on the port-implementation hot path so
    * a network blip doesn't kill the process.
    */
  private def resilient[A](z: ZIO[Any, Throwable, A]): UIO[A] =
    z.retry(retrySchedule).orDie

  private final case class Live(
      redis: Redis,
      redisSub: RedisSubscription,
      hubMap: Ref.Synchronized[Map[String, HubEntry]],
      backendScope: Scope,
      ttl: Duration,
      maxLen: Long
  ) extends PersistenceBackend:

    // -- SessionStore ---------------------------------------------------------

    def create(): UIO[String] =
      val sessionId = UUID.randomUUID().toString
      val key = sessionKey(sessionId)
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _   <- resilient(redis.set(key, now.toString))
        _   <- resilient(redis.pExpire(key, ttl).unit)
        _   <- resilient(redis.sAdd(sessionsSetKey, sessionId).unit)
      yield sessionId

    def exists(sessionId: String): UIO[Boolean] =
      resilient(redis.exists(sessionKey(sessionId))).map(_ > 0L)

    def remove(sessionId: String): UIO[Unit] =
      resilient(
        for
          _ <- redis.del(sessionKey(sessionId))
          _ <- redis.sRem(sessionsSetKey, sessionId)
        yield ()
      )

    def allSessionIds: UIO[List[String]] =
      resilient(redis.sMembers(sessionsSetKey).returning[String]).map(_.toList)

    def markInitialized(sessionId: String): UIO[Unit] =
      for
        _ <- resilient(redis.set(initKey(sessionId), "1"))
        _ <- resilient(redis.pExpire(initKey(sessionId), ttl).unit)
      yield ()

    def isInitialized(sessionId: String): UIO[Boolean] =
      resilient(redis.exists(initKey(sessionId))).map(_ > 0L)

    // -- MessageRouter --------------------------------------------------------

    def publish(sessionId: String, message: String): UIO[Boolean] =
      resilient(redis.publish(channelName(sessionId), message)).map(_ > 0L)

    def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      for
        entry   <- getOrCreateHubEntry(sessionId)
        dequeue <- entry.hub.subscribe
      yield ZStream.fromQueue(dequeue)

    def hasSubscribers(sessionId: String): UIO[Boolean] =
      hubMap.get.map(_.contains(sessionId))

    // -- EventReplay ----------------------------------------------------------

    def append(sessionId: String, message: String): UIO[String] =
      val key = streamKey(sessionId)
      resilient(
        for
          idOpt <- redis.xAdd(key, "*")("msg" -> message).returning[String]
          _     <- redis.xTrimWithMaxLen(key, maxLen, approximate = true)
        yield idOpt.getOrElse("0-0")
      )

    def since(sessionId: String, eventId: String): UIO[List[EventReplay.Event]] =
      val key = streamKey(sessionId)
      val exclusiveStart = s"($eventId"
      resilient(
        redis.xRange(key, exclusiveStart, "+").returning[String, String]
      ).map { entries =>
        entries.map { entry =>
          val id = entry.id.toString
          val msg = entry.fields.headOption.map(_._2).getOrElse("")
          EventReplay.Event(id, msg)
        }.toList
      }

    // -- Combined session teardown --------------------------------------------

    def removeSession(sessionId: String): UIO[Unit] =
      val removeHub = hubMap.modify { map =>
        val entry = map.get(sessionId)
        (entry, map - sessionId)
      }.flatMap {
        case Some(entry) => entry.subscriptionFiber.interrupt *> entry.hub.shutdown
        case None        => ZIO.unit
      }
      val removeStream = resilient(redis.del(streamKey(sessionId)).unit)
      removeHub *> removeStream

    // -- Internals ------------------------------------------------------------

    /** Atomic create-or-fetch via `Ref.Synchronized.modifyZIO` (zionomicon
      * ch.9). Eliminates the previous compare-and-swap race that could
      * allocate a Hub + subscription fiber and then immediately tear them
      * down when a concurrent caller won.
      *
      * The subscription fiber is forked in the layer's `backendScope`, so it
      * lives exactly as long as the backend itself -- not pinned to the per-
      * request scope, not orphaned via `forkDaemon`.
      */
    private def getOrCreateHubEntry(sessionId: String): UIO[HubEntry] =
      hubMap.modifyZIO { map =>
        map.get(sessionId) match
          case Some(existing) => ZIO.succeed((existing, map))
          case None =>
            for
              hub   <- Hub.bounded[String](HubCapacity)
              fiber <- startRedisSubscription(sessionId, hub).forkIn(backendScope)
              entry  = HubEntry(hub, fiber)
            yield (entry, map + (sessionId -> entry))
      }

    private def startRedisSubscription(
        sessionId: String,
        hub: Hub[String]
    ): ZIO[Any, Throwable, Unit] =
      // Subscription itself uses retry: if the subscription fiber dies on a
      // transient Redis blip, retry the whole stream rather than tearing down
      // the hub (which would force every subscriber to reconnect).
      redisSub
        .subscribe(channelName(sessionId))
        .returning[String]
        .mapZIO { case (_, msg) => hub.publish(msg) }
        .runDrain
        .retry(retrySchedule)
