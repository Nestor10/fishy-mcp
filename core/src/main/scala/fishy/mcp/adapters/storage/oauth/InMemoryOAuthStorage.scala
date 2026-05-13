package fishy.mcp.adapters.storage.oauth

import fishy.mcp.application.ports.oauth.OAuthStorage
import zio.*

/** In-process reference [[OAuthStorage]]: the four in-memory stores composed
  * into a single layer. Dev only -- restart loses every record.
  *
  * Future production adapters (e.g. `PostgresOAuthStorage`,
  * `RedisOAuthStorage`) follow the same shape so a deployment swaps exactly
  * one layer to change backing.
  */
object InMemoryOAuthStorage:

  val layer: ULayer[OAuthStorage] =
    ZLayer.make[OAuthStorage](
      InMemoryUserDirectory.layer,
      InMemoryAuthorizationRequestStore.layer,
      InMemoryClientRegistrationStore.layer,
      InMemoryRefreshTokenStore.layer
    )
