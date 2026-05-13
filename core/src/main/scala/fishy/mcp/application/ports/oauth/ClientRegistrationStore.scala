package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.ClientRegistration
import fishy.mcp.domain.model.oauth.Ids.ClientId
import zio.*

/** Persistence for RFC 7591 dynamic client registrations. */
trait ClientRegistrationStore:
  def put(client: ClientRegistration): UIO[Unit]
  def get(id: ClientId): UIO[Option[ClientRegistration]]
  def all: UIO[List[ClientRegistration]]

object ClientRegistrationStore:
  def put(c: ClientRegistration) = ZIO.serviceWithZIO[ClientRegistrationStore](_.put(c))
  def get(id: ClientId)          = ZIO.serviceWithZIO[ClientRegistrationStore](_.get(id))
  def all                        = ZIO.serviceWithZIO[ClientRegistrationStore](_.all)
