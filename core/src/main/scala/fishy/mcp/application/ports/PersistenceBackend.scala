package fishy.mcp.application.ports

/** Unified persistence backend for MCP session state.
  *
  * Combines session management, message routing, and event replay into a single infrastructure
  * boundary. Implementations provide either in-memory or external (Redis) persistence.
  *
  * Both `MessageRouter.removeSession` and `EventReplay.removeSession` share the same signature, so
  * the combined implementation naturally cleans up all session state in one call.
  */
trait PersistenceBackend extends SessionStore with MessageRouter with EventReplay
