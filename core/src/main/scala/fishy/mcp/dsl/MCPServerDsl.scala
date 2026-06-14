package fishy.mcp.dsl

/** Keeps the `fishy.mcp.dsl.MCPServer` / `fishy.mcp.dsl.MCPApp` import paths
  * working by re-exporting the real builder and app base from `bootstrap`.
  *
  * (The previous hand-written facade duplicated `bootstrap.MCPServer`'s entry
  * constructors; those now live in one place — the `MCPServer` companion — and
  * this is a thin alias.)
  */
export fishy.mcp.bootstrap.{MCPServer, MCPApp}
