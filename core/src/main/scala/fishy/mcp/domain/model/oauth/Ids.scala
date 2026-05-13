package fishy.mcp.domain.model.oauth

import java.util.UUID

/** Opaque identifier types for OAuth domain entities.
  *
  * Each type is a distinct newtype over `String` (or `UUID`) so the compiler prevents
  * cross-wiring (e.g. passing a `ClientId` where an `IdentityId` is expected).
  */
object Ids:

  opaque type IdentityId = String
  object IdentityId:
    def apply(value: String): IdentityId = value
    def fresh: IdentityId = UUID.randomUUID().toString
    extension (id: IdentityId) def value: String = id

  opaque type TenantId = String
  object TenantId:
    def apply(value: String): TenantId = value
    extension (id: TenantId) def value: String = id

  opaque type ClientId = String
  object ClientId:
    def apply(value: String): ClientId = value
    def fresh: ClientId = UUID.randomUUID().toString
    extension (id: ClientId) def value: String = id

  opaque type AuthorizationCode = String
  object AuthorizationCode:
    def apply(value: String): AuthorizationCode = value
    extension (c: AuthorizationCode) def value: String = c

  opaque type RefreshTokenId = String
  object RefreshTokenId:
    def apply(value: String): RefreshTokenId = value
    extension (id: RefreshTokenId) def value: String = id

  opaque type JwkKid = String
  object JwkKid:
    def apply(value: String): JwkKid = value
    extension (k: JwkKid) def value: String = k
