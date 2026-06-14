# fishy-mcp

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nestor10/fishy-mcp_3.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nestor10/fishy-mcp_3)

A Scala 3 SDK for building [Model Context Protocol](https://modelcontextprotocol.io/) servers on ZIO.

## Release Status

This project is in an early release-candidate pass.

- APIs and behavior may change before a stable 1.0 release.
- Expect rough edges; feedback is welcome and useful right now.
- Treat this as production-grade direction, not final polish.

## Install

Maven coordinates:

- Group: `io.github.nestor10`
- Artifact (Scala 3): `fishy-mcp_3`

### sbt

```scala
libraryDependencies += "io.github.nestor10" %% "fishy-mcp" % "<version>"
```

### Maven

```xml
<dependency>
  <groupId>io.github.nestor10</groupId>
  <artifactId>fishy-mcp_3</artifactId>
  <version><version></version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("io.github.nestor10:fishy-mcp_3:<version>")
```

`import fishy.mcp.*` is the one import you need — it brings `MCPServer`, `Tool`,
`Resource`, `Prompt`, `Content`, `ToolContext`, and the auth policies. The OAuth
**authorization server** is a separate `fishy-mcp-oauth` artifact (see
[Authentication](#authentication)).

## Quick Start

```scala
import fishy.mcp.*
import zio.*
import zio.schema.Schema

final case class AddInput(a: Int, b: Int) derives Schema

val add = Tool("add").description("Add two numbers").handle { (in: AddInput, _: ToolContext) =>
  ZIO.succeed(Content.Text(s"${in.a} + ${in.b} = ${in.a + in.b}"))
}

MCPServer
  .withName("my-server").withVersion("0.1.0")
  .withTools(add)
  .serveHttp   // reads PORT env var, default 8080
```

Define a case class, derive a schema, write a function. The SDK handles
JSON-RPC 2.0 framing, JSON Schema generation from `Schema[I]`, MCP capability
negotiation, session management, and transport.

## Tools

Every tool handler receives the decoded input and a `ToolContext` (request ID,
session ID, client `_meta`). Return `Content` -- use `Content.Text(...)` for
the common case.

```scala
final case class EchoInput(message: String) derives Schema

// Typed tool with context
val echo = Tool("echo").description("Echo").handle { (in: EchoInput, ctx: ToolContext) =>
  ZIO.succeed(Content.Text(in.message))
}

// No-input tool (zero parameters)
val time = Tool("time").description("Current time").noInput {
  ZIO.succeed(java.time.Instant.now().toString)
}

// Tool with environment dependency
val append = Tool("append").description("Append to file")
  .handle[FileAppendService, AppendInput] { (in, _) =>
    FileAppendService.append(in.line).as(Content.Text("Done"))
  }
```

Tool inputs use `derives Schema` as the single pillar -- the same schema
instance drives both JSON Schema generation (for `tools/list`) and JSON
decoding (for `tools/call`). No separate `JsonDecoder` needed.

## Resources and Prompts

```scala
val readme = Resource.text(
  "file:///readme.md", "readme", "Project README", "text/markdown"
)("# My Project\n\nREADME content here.")

val status = Resource.textEffect[Any](
  "server:///status", "status", "Server status"
)(ZIO.succeed(s"Running since ${java.time.Instant.now()}"))

val review = Prompt(
  "code-review", "Review code",
  List(PromptArgument("code", "Source to review", required = true))
) { args =>
  ZIO.succeed(List(PromptMessage("user", s"Review:\n${args("code")}")))
}
```

## Running the Server

```scala
// HTTP (Streamable HTTP: POST /mcp + GET /mcp SSE)
// Port comes from PORT env var (default 8080)
MCPServer
  .withName("my-server").withVersion("0.1.0")
  .withTools(echo, add)
  .withResources(readme)
  .withPrompts(review)
  .serveHttp

// stdio (NDJSON, for subprocess-based clients)
server.serveStdio

// With custom layers (e.g. tool that needs a service)
server.serveHttp.provide(FileAppendService.live)
```

Tools with different environment requirements compose naturally:

```scala
val fileTool = Tool("read").handle[FileService, ReadInput] { ... }
val dbTool   = Tool("query").handle[DbService, QueryInput] { ... }

// R = FileService & DbService -- intersection types just work
MCPServer
  .withTools(fileTool)
  .withTools(dbTool)
  .serveHttp
  .provide(FileService.live, DbService.live)
```

## Configuration

All behavior is controlled via environment variables. Same binary, same code,
same Docker image for every environment.

| Variable | Purpose | Default |
|----------|---------|---------|
| `PORT` | HTTP listen port | `8080` |
| `LOG_LEVEL` | Logging threshold (`DEBUG`, `INFO`, `WARN`, `ERROR`) | `INFO` |
| `REDIS_URL` | Redis connection URL. Enables Redis-backed session store, message router, and event replay for horizontal scaling with state. | unset (in-memory) |
| `MCP_STATELESS` | Set `true` for stateless horizontal scaling. Session checks pass unconditionally; event replay is a no-op. | unset |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint (e.g. `http://localhost:4317`). Enables distributed tracing. | unset (noop tracer) |
| `OTEL_SERVICE_NAME` | Service name reported in traces. | `fishy-mcp` |

Setting both `MCP_STATELESS=true` and `REDIS_URL` is a startup error.

## Authentication

Two separate concerns, often conflated:

### Verifying tokens (resource server) — built into `fishy-mcp`

Most servers already have an identity provider and just need to **verify** the
bearer token on each request. That ships in core:

```scala
MCPServer
  .withName("my-server").withTools(echo)
  .withJwtAuth(JwtSecurityPolicy.Config(
    jwksUri  = "https://your-idp/.well-known/jwks.json",
    issuer   = "https://your-idp",
    audience = "https://your-mcp-server"))
  .serveHttp
```

`withJwtAuth` validates each `Authorization: Bearer` JWT against your IdP's JWKS
and exposes the caller as `ctx.auth: Option[AuthContext]`. `withConfigDrivenAuth()`
selects the policy from `AUTH_MODE` / `JWT_*` env vars; `withTrustedHeaders()`
reads identity headers set by a trusted reverse proxy.

### Issuing tokens (authorization server) — `fishy-mcp-oauth`

If you want MCP clients to authenticate *through your server* with OAuth, fishy
can run the OAuth 2.1 authorization-server flow — dynamic client registration,
PKCE, token issue / refresh / revoke, JWKS, discovery — and **delegate the actual
user login to your upstream OIDC provider** (Google, Okta, Keycloak, …).

**fishy is not an identity provider.** It's a relying party to *your* IdP: it
brokers the OIDC code flow and mints its own MCP-scoped tokens. You still bring
the IdP.

The turnkey path is env-driven — set the `OAUTH_*` vars, provide a storage
layer, done:

```scala
import fishy.mcp.*
import fishy.mcp.oauth.*

MCPServer
  .withName("my-server").withTools(echo)
  .withOAuthFromEnv                       // upstream OIDC driver, signing key, admission, tenant from env
  .serveHttp
  .provide(InMemoryOAuthStorage.layer)    // dev; swap for your own store in production
```

| Env var | Selects | Default if unset |
|---|---|---|
| `OAUTH_ISSUER` / `OAUTH_RESOURCE` | this server's identity (required) | — |
| `OAUTH_UPSTREAM_ISSUER` + `OAUTH_UPSTREAM_CLIENT_ID` / `_SECRET` | generic OIDC upstream driver (discovery-based — Google / Okta / Keycloak / …) | stub IdP (refused in production) |
| `OAUTH_SIGNING_KEY_PATH` | RSA signing key from a PEM file | per-JVM generated key (refused in production) |
| `OAUTH_ADMISSION_EMAIL_DOMAINS` | email-domain allowlist admission | admit-all (refused in production) |

Each port resolves to its real adapter when configured, else a dev stub that
`MCP_PROFILE=production` refuses to boot — so you can ramp up incrementally. The
one piece you always bring is **storage**: `InMemoryOAuthStorage` for dev, your
own `OAuthStorage` (Postgres, etc.) for production — same shape as bringing your
own Redis. `.withOAuth(config)` is the all-stubs dev shortcut; `.withCustomOAuth`
wires every port by hand.

Add it with one dependency — it pulls in `fishy-mcp` and the standalone,
MCP-agnostic `fishy-oauth` authorization server transitively:

```scala
libraryDependencies += "io.github.nestor10" %% "fishy-mcp-oauth" % "<version>"
```

The only port you bring is `OAuthStorage` (the in-memory store ships for dev);
shipping a reference Postgres store as a `fishy-mcp-postgres-oauth` module is a
planned follow-up.

## What's Implemented

Core: JSON-RPC 2.0, batch support, capability negotiation with protocol-version
negotiation, session management (`Mcp-Session-Id`).

Primitives: `tools/list`, `tools/call`, `resources/list`, `resources/read`,
`resources/subscribe`, `prompts/list`, `prompts/get`. Content is one unified
`Content` union (text / image / audio).

Transport: Streamable HTTP (`POST /mcp`, `GET /mcp` SSE), NDJSON stdio.

Advanced: progress reporting, cancellation, server-to-client requests
(`sampling/createMessage`, `roots/list`, `elicitation/create`) and notifications
(`list_changed`), SSE event replay on reconnect (`Last-Event-ID`), config-driven
backends (in-memory / Redis / stateless), bearer-token verification (see
[Authentication](#authentication)), `ToolContext` (request ID, session ID,
`_meta`, `auth`, and `client`/`progress`/`resources` capabilities) on every
handler.

OAuth 2.1 authorization server (the separate `fishy-mcp-oauth` artifact, with a
generic OIDC upstream driver) — see [Authentication](#authentication).

Not yet: pagination, completions, URI templates, a reference Postgres
`OAuthStorage`.

## Development Checks

Run these before opening a PR:

```bash
sbt lint
sbt compile
sbt core/test
```

CI runs lint + compile + tests on Java 21 and 23 via [/.github/workflows/ci.yml](.github/workflows/ci.yml).

## Release Flow

Release is gated by lint and tests in `releaseProcess`:

```bash
sbt "release with-defaults"
```

Tag pushes trigger Maven publish via [/.github/workflows/release.yml](.github/workflows/release.yml).

## Community and Contact

Primary community channel:

- Matrix room: `#fish-mcp:matrix.org`
- Join link: https://matrix.to/#/#fish-mcp:matrix.org



## License

MIT -- see [LICENSE](LICENSE).
