package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.AuthorizationRequest
import fishy.mcp.domain.model.oauth.Ids.AuthorizationCode
import zio.*

/** Short-lived store for pending authorization requests.
  *
  * Entries must be removed on first successful `consume`; repeated redemption
  * is an RFC 6749 violation. Expired entries are purged opportunistically by
  * `consume` returning `None`.
  */
trait AuthorizationRequestStore:
  def put(request: AuthorizationRequest): UIO[Unit]
  def get(code: AuthorizationCode): UIO[Option[AuthorizationRequest]]
  def update(request: AuthorizationRequest): UIO[Unit]
  /** Atomically fetch-and-delete. Returns `None` if missing or already consumed. */
  def consume(code: AuthorizationCode): UIO[Option[AuthorizationRequest]]
  /** Look up a pending request by the opaque `upstreamState` we sent to the IdP. */
  def getByUpstreamState(state: String): UIO[Option[AuthorizationRequest]]

object AuthorizationRequestStore:
  def put(r: AuthorizationRequest)                = ZIO.serviceWithZIO[AuthorizationRequestStore](_.put(r))
  def get(c: AuthorizationCode)                   = ZIO.serviceWithZIO[AuthorizationRequestStore](_.get(c))
  def update(r: AuthorizationRequest)             = ZIO.serviceWithZIO[AuthorizationRequestStore](_.update(r))
  def consume(c: AuthorizationCode)               = ZIO.serviceWithZIO[AuthorizationRequestStore](_.consume(c))
  def getByUpstreamState(s: String)               = ZIO.serviceWithZIO[AuthorizationRequestStore](_.getByUpstreamState(s))
