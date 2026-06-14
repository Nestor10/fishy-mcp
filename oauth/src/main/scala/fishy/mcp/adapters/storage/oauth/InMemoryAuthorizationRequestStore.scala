package fishy.mcp.adapters.storage.oauth

import fishy.mcp.application.ports.oauth.AuthorizationRequestStore
import fishy.mcp.domain.model.oauth.AuthorizationRequest
import fishy.mcp.domain.model.oauth.Ids.AuthorizationCode
import zio.*

/** In-memory pending-authorization store. */
object InMemoryAuthorizationRequestStore:

  val layer: ULayer[AuthorizationRequestStore] =
    ZLayer.fromZIO(Ref.make(Map.empty[AuthorizationCode, AuthorizationRequest]).map(Live.apply))

  private final case class Live(store: Ref[Map[AuthorizationCode, AuthorizationRequest]])
      extends AuthorizationRequestStore:

    override def put(request: AuthorizationRequest): UIO[Unit] =
      store.update(_.updated(request.code, request))

    override def get(code: AuthorizationCode): UIO[Option[AuthorizationRequest]] =
      Clock.instant.flatMap { now =>
        store.modify { m =>
          m.get(code) match
            case Some(r) if r.isExpired(now) => (None, m.removed(code))
            case other                       => (other, m)
        }
      }

    override def update(request: AuthorizationRequest): UIO[Unit] =
      store.update(m => if m.contains(request.code) then m.updated(request.code, request) else m)

    override def consume(code: AuthorizationCode): UIO[Option[AuthorizationRequest]] =
      Clock.instant.flatMap { now =>
        store.modify { m =>
          m.get(code) match
            case Some(r) if !r.isExpired(now) => (Some(r), m.removed(code))
            case _                            => (None, m.removed(code))
        }
      }

    override def getByUpstreamState(state: String): UIO[Option[AuthorizationRequest]] =
      store.get.map(_.values.find(_.upstreamState == state))
