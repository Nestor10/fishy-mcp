package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.Tenant
import zio.*

/** Maps an incoming `Host` header to the tenant that should handle the request.
  *
  * Returning `None` means "no tenant claims this host" -- the caller surfaces a
  * protocol error.
  */
trait TenantResolver:
  def resolve(host: String): UIO[Option[Tenant]]
  def all: UIO[List[Tenant]]

object TenantResolver:
  def resolve(host: String) = ZIO.serviceWithZIO[TenantResolver](_.resolve(host))
  def all                   = ZIO.serviceWithZIO[TenantResolver](_.all)
