package fishy.mcp.oauth

// Re-export the types a deployer needs so `import fishy.mcp.oauth.*` is the one
// import for the OAuth feature (builder extension + config + port bundle).
export fishy.mcp.application.ports.oauth.OAuthConfig
export fishy.mcp.bootstrap.oauth.OAuthLayers
