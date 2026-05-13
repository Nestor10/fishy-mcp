package fishy.mcp.adapters.storage.oauth

import fishy.mcp.application.ports.oauth.ClientRegistrationStore
import fishy.mcp.domain.model.oauth.ClientRegistration
import fishy.mcp.domain.model.oauth.Ids.ClientId
import zio.*

object InMemoryClientRegistrationStore:

  val layer: ULayer[ClientRegistrationStore] =
    ZLayer.fromZIO(Ref.make(Map.empty[ClientId, ClientRegistration]).map(Live.apply))

  private final case class Live(store: Ref[Map[ClientId, ClientRegistration]])
      extends ClientRegistrationStore:

    override def put(client: ClientRegistration): UIO[Unit] =
      store.update(_.updated(client.id, client))

    override def get(id: ClientId): UIO[Option[ClientRegistration]] =
      store.get.map(_.get(id))

    override def all: UIO[List[ClientRegistration]] =
      store.get.map(_.values.toList.sortBy(_.createdAt.toEpochMilli))
