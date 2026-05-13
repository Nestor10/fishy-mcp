package fishy.mcp.application.ports.oauth

/** Bundle of the four stateful OAuth ports.
  *
  * Intentionally an intersection-type alias rather than a wrapper trait:
  *
  *   - `ZLayer.make` consumes a layer of `OAuthStorage` and uses it to satisfy
  *     each of the four constituent ports automatically. No accessors or
  *     explosion code required.
  *   - A single backing system (Postgres, Redis, etc.) typically owns all of
  *     these together, so adapters bundle them into one `ULayer[OAuthStorage]`.
  *   - Deployments that need to mix backings (e.g. Postgres for users,
  *     Redis for refresh tokens) can still wire the four ports individually
  *     without changing this alias.
  *
  * `SigningKeySource` is intentionally NOT part of this bundle: it usually
  * lives in a different system (KMS, Vault, file) and changes independently.
  */
type OAuthStorage =
  UserDirectory
    & AuthorizationRequestStore
    & ClientRegistrationStore
    & RefreshTokenStore
