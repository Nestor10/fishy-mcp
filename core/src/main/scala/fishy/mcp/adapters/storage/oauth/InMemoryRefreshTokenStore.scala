package fishy.mcp.adapters.storage.oauth

import fishy.mcp.application.ports.oauth.RefreshTokenStore
import fishy.mcp.domain.model.oauth.RefreshTokenRecord
import fishy.mcp.domain.model.oauth.Ids.{IdentityId, RefreshTokenId}
import zio.*

object InMemoryRefreshTokenStore:

  val layer: ULayer[RefreshTokenStore] =
    ZLayer.fromZIO(Ref.make(Map.empty[RefreshTokenId, RefreshTokenRecord]).map(Live.apply))

  private final case class Live(store: Ref[Map[RefreshTokenId, RefreshTokenRecord]])
      extends RefreshTokenStore:

    override def put(record: RefreshTokenRecord): UIO[Unit] =
      store.update(_.updated(record.id, record))

    override def consume(id: RefreshTokenId): UIO[Option[RefreshTokenRecord]] =
      Clock.instant.flatMap { now =>
        store.modify { m =>
          m.get(id) match
            case Some(r) if r.isActive(now) =>
              (Some(r), m.updated(id, r.copy(consumedAt = Some(now))))
            case _ => (None, m)
        }
      }

    override def revokeAllFor(identityId: IdentityId): UIO[Unit] =
      Clock.instant.flatMap { now =>
        store.update(_.map {
          case (k, v) if v.identityId == identityId && v.consumedAt.isEmpty =>
            k -> v.copy(consumedAt = Some(now))
          case kv => kv
        })
      }
