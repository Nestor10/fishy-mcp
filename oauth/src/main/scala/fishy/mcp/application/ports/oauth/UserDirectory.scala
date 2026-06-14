package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.{Identity, IdentityStatus, UpstreamRef}
import fishy.mcp.domain.model.oauth.Ids.{IdentityId, TenantId}
import zio.*

/** Persistence boundary for locally-known identities.
  *
  * Host applications provide the concrete implementation (Postgres for praxis,
  * in-memory for tests). The SDK only calls this trait.
  */
trait UserDirectory:
  def findByUpstream(tenantId: TenantId, upstream: UpstreamRef): UIO[Option[Identity]]
  def findById(id: IdentityId): UIO[Option[Identity]]
  def create(
      tenantId: TenantId,
      upstream: UpstreamRef,
      email: String,
      initialStatus: IdentityStatus
  ): UIO[Identity]
  def updateStatus(id: IdentityId, status: IdentityStatus): UIO[Unit]
  def listByTenant(tenantId: TenantId, status: Option[IdentityStatus]): UIO[List[Identity]]

object UserDirectory:

  def findByUpstream(tenantId: TenantId, upstream: UpstreamRef) =
    ZIO.serviceWithZIO[UserDirectory](_.findByUpstream(tenantId, upstream))

  def findById(id: IdentityId) =
    ZIO.serviceWithZIO[UserDirectory](_.findById(id))

  def create(
      tenantId: TenantId,
      upstream: UpstreamRef,
      email: String,
      initialStatus: IdentityStatus
  ) =
    ZIO.serviceWithZIO[UserDirectory](_.create(tenantId, upstream, email, initialStatus))

  def updateStatus(id: IdentityId, status: IdentityStatus) =
    ZIO.serviceWithZIO[UserDirectory](_.updateStatus(id, status))

  def listByTenant(tenantId: TenantId, status: Option[IdentityStatus] = None) =
    ZIO.serviceWithZIO[UserDirectory](_.listByTenant(tenantId, status))
