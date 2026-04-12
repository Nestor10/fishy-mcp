package fishy.mcp.adapters.storage

import fishy.mcp.application.ports.SubscriptionRegistry
import zio.*

/** In-memory subscription tracking backed by a Ref.
  *
  * Maps session IDs to the set of resource URIs they are subscribed to. All operations are single
  * atomic Ref updates -- no STM needed.
  */
object InMemorySubscriptionRegistry:

  val layer: ULayer[SubscriptionRegistry] =
    ZLayer.fromZIO {
      Ref.make(Map.empty[String, Set[String]]).map(Live(_))
    }

  private final case class Live(
      subscriptions: Ref[Map[String, Set[String]]]
  ) extends SubscriptionRegistry:

    def subscribe(sessionId: String, uri: String): UIO[Unit] =
      subscriptions.update { map =>
        map.updated(sessionId, map.getOrElse(sessionId, Set.empty) + uri)
      }

    def unsubscribe(sessionId: String, uri: String): UIO[Unit] =
      subscriptions.update { map =>
        map.get(sessionId) match
          case None => map
          case Some(uris) =>
            val updated = uris - uri
            if updated.isEmpty then map - sessionId else map.updated(sessionId, updated)
      }

    def subscribers(uri: String): UIO[Set[String]] =
      subscriptions.get.map { map =>
        map.collect { case (sid, uris) if uris.contains(uri) => sid }.toSet
      }

    def removeSession(sessionId: String): UIO[Unit] =
      subscriptions.update(_ - sessionId)
