package fishy.mcp.oauth

// Re-export the types a deployer needs so `import fishy.mcp.oauth.*` is the one
// import for the OAuth feature (builder extension + config + port bundle + the
// storage port and its dev reference impl, supplied at serveHttp.provide).
export fishy.mcp.application.ports.oauth.{OAuthConfig, OAuthStorage}
export fishy.mcp.bootstrap.oauth.OAuthLayers
export fishy.mcp.adapters.storage.oauth.InMemoryOAuthStorage
