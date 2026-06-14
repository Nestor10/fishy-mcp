package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.MessageRouter
import zio.*
import zio.stream.ZStream

/** In-process SSE message routing: one bounded `Hub` per session.
  *
  * Shared by the in-memory and stateless backends, which differ only in their
  * session and event-replay state — not in how a live message fans out to a
  * connected SSE stream — so this is the one place that logic lives.
  * (zionomicon ch.12 — Hub for broadcasting; ch.9 — atomic `Ref` create-or-get.)
  */
final class HubMessageRouter(hubs: Ref[Map[String, Hub[String]]], hubCapacity: Int)
    extends MessageRouter:

  def publish(sessionId: String, message: String): UIO[Boolean] =
    hubs.get.map(_.get(sessionId)).flatMap {
      case Some(hub) => hub.publish(message)
      case None      => ZIO.succeed(false)
    }

  def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
    getOrCreateHub(sessionId).flatMap(_.subscribe).map(ZStream.fromQueue(_))

  def hasSubscribers(sessionId: String): UIO[Boolean] =
    hubs.get.map(_.contains(sessionId))

  def removeSession(sessionId: String): UIO[Unit] =
    hubs.modify(map => (map.get(sessionId), map - sessionId)).flatMap {
      case Some(hub) => hub.shutdown
      case None      => ZIO.unit
    }

  private def getOrCreateHub(sessionId: String): UIO[Hub[String]] =
    hubs.get.map(_.get(sessionId)).flatMap {
      case Some(hub) => ZIO.succeed(hub)
      case None =>
        Hub.bounded[String](hubCapacity).flatMap { fresh =>
          hubs.modify { map =>
            map.get(sessionId) match
              case Some(existing) => (existing, map)
              case None           => (fresh, map + (sessionId -> fresh))
          }
        }
    }

object HubMessageRouter:
  def make(hubCapacity: Int): UIO[HubMessageRouter] =
    Ref.make(Map.empty[String, Hub[String]]).map(new HubMessageRouter(_, hubCapacity))
