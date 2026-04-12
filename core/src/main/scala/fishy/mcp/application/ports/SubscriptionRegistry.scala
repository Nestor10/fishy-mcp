package fishy.mcp.application.ports

import zio.*

/** Tracks per-session resource URI subscriptions.
  *
  * Clients subscribe to specific resource URIs via `resources/subscribe`. When a resource changes,
  * the server sends `notifications/resources/updated` only to sessions that subscribed to that URI.
  */
trait SubscriptionRegistry:

  /** Record that a session wants updates for a resource URI. */
  def subscribe(sessionId: String, uri: String): UIO[Unit]

  /** Remove a session's subscription to a resource URI. */
  def unsubscribe(sessionId: String, uri: String): UIO[Unit]

  /** All session IDs currently subscribed to a given URI. */
  def subscribers(uri: String): UIO[Set[String]]

  /** Clean up all subscriptions for a disconnected session. */
  def removeSession(sessionId: String): UIO[Unit]

object SubscriptionRegistry:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def subscribe(sessionId: String, uri: String): URIO[SubscriptionRegistry, Unit] =
    ZIO.serviceWithZIO(_.subscribe(sessionId, uri))

  def unsubscribe(sessionId: String, uri: String): URIO[SubscriptionRegistry, Unit] =
    ZIO.serviceWithZIO(_.unsubscribe(sessionId, uri))

  def subscribers(uri: String): URIO[SubscriptionRegistry, Set[String]] =
    ZIO.serviceWithZIO(_.subscribers(uri))

  def removeSession(sessionId: String): URIO[SubscriptionRegistry, Unit] =
    ZIO.serviceWithZIO(_.removeSession(sessionId))
