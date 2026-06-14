package fishy.mcp.adapters.outbound.oauth.tenant

import fishy.mcp.application.ports.oauth.TenantResolver
import fishy.mcp.domain.model.oauth.Tenant
import zio.*

/** Single-tenant resolver: every request resolves to the same tenant. */
object SingleTenantResolver:

  def layer(tenant: Tenant): ULayer[TenantResolver] =
    ZLayer.succeed(Live(tenant))

  private final case class Live(tenant: Tenant) extends TenantResolver:
    override def resolve(host: String): UIO[Option[Tenant]] = ZIO.succeed(Some(tenant))
    override def all: UIO[List[Tenant]]                     = ZIO.succeed(List(tenant))

/** Host-header lookup across an explicit tenant list. */
object StaticTenantResolver:

  def layer(tenants: List[Tenant]): ULayer[TenantResolver] =
    ZLayer.succeed(Live(tenants))

  private final case class Live(tenants: List[Tenant]) extends TenantResolver:
    private val byHost = tenants.iterator.map(t => t.hostname.toLowerCase -> t).toMap

    override def resolve(host: String): UIO[Option[Tenant]] =
      // strip port if present
      val normalized = host.toLowerCase.takeWhile(_ != ':')
      ZIO.succeed(byHost.get(normalized))

    override def all: UIO[List[Tenant]] = ZIO.succeed(tenants)
