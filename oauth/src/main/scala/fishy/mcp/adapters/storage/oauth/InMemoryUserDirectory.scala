package fishy.mcp.adapters.storage.oauth

import fishy.mcp.application.ports.oauth.UserDirectory
import fishy.mcp.domain.model.oauth.{Identity, IdentityStatus, UpstreamRef}
import fishy.mcp.domain.model.oauth.Ids.{IdentityId, TenantId}
import zio.*

import java.time.Instant

/** In-memory reference implementation of `UserDirectory`.
  *
  * Intended for tests and homelab / single-instance deployments. Production
  * hosts implement their own persistence.
  */
object InMemoryUserDirectory:

  val layer: ULayer[UserDirectory] =
    ZLayer.fromZIO(Ref.make(Map.empty[IdentityId, Identity]).map(Live.apply))

  private final case class Live(store: Ref[Map[IdentityId, Identity]]) extends UserDirectory:

    override def findByUpstream(tenantId: TenantId, upstream: UpstreamRef): UIO[Option[Identity]] =
      store.get.map(_.values.find(i => i.tenantId == tenantId && i.upstream == upstream))

    override def findById(id: IdentityId): UIO[Option[Identity]] =
      store.get.map(_.get(id))

    override def create(
        tenantId: TenantId,
        upstream: UpstreamRef,
        email: String,
        initialStatus: IdentityStatus
    ): UIO[Identity] =
      for
        now <- Clock.instant
        identity = Identity(
          id = IdentityId.fresh,
          tenantId = tenantId,
          upstream = upstream,
          email = email,
          status = initialStatus,
          createdAt = now
        )
        _ <- store.update(_.updated(identity.id, identity))
      yield identity

    override def updateStatus(id: IdentityId, status: IdentityStatus): UIO[Unit] =
      store.update(m => m.get(id).fold(m)(i => m.updated(id, i.copy(status = status))))

    override def listByTenant(
        tenantId: TenantId,
        status: Option[IdentityStatus]
    ): UIO[List[Identity]] =
      store.get.map { m =>
        m.values.toList
          .filter(_.tenantId == tenantId)
          .filter(i => status.forall(_ == i.status))
          .sortBy(_.createdAt.toEpochMilli)
      }
