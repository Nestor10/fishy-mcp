package fishy.mcp.application.ports.oauth

/** Marker mixed into SDK-shipped placeholder OAuth adapters that exist for
  * compile-time convenience (so `withOAuth(config)` works in dev) but should
  * never reach production.
  *
  * The audit hook ([[fishy.mcp.bootstrap.oauth.OAuthLayers.audit]]) checks
  * resolved port instances for this marker and fails fast when
  * `MCP_PROFILE=production` finds one wired.
  *
  * Reference impls:
  *   - `NullUpstreamIdP` -- always fails authorization
  *   - `AllowAllAdmission` -- admits every verified upstream identity
  *   - `RsaSigningKeySource.generated` -- mints fresh JVM-local keys; tokens
  *     issued by one instance won't validate on another
  */
trait StubMarker:
  /** Human-readable identifier used in audit log lines and refusal messages. */
  def stubId: String
