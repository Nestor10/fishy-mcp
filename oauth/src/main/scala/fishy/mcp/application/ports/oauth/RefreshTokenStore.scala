package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.RefreshTokenRecord
import fishy.mcp.domain.model.oauth.Ids.{IdentityId, RefreshTokenId}
import zio.*

/** Rotating refresh-token persistence.
  *
  * `consume` implements the rotation contract: on hit, mark the current record
  * consumed and return it; callers then `put` a fresh record.
  */
trait RefreshTokenStore:
  def put(record: RefreshTokenRecord): UIO[Unit]
  def consume(id: RefreshTokenId): UIO[Option[RefreshTokenRecord]]
  def revokeAllFor(identityId: IdentityId): UIO[Unit]

object RefreshTokenStore:
  def put(r: RefreshTokenRecord)            = ZIO.serviceWithZIO[RefreshTokenStore](_.put(r))
  def consume(id: RefreshTokenId)           = ZIO.serviceWithZIO[RefreshTokenStore](_.consume(id))
  def revokeAllFor(identityId: IdentityId)  = ZIO.serviceWithZIO[RefreshTokenStore](_.revokeAllFor(identityId))
