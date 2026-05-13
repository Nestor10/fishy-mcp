# CLAUDE.md — fishy-mcp conventions for AI-assisted work

Repo-specific guidance, additive to `context/architecture.md` and
`context/code-style.md`. Apply these when making changes.

---

## Configuration

- Components define **only** a `case class FooConfig` and a
  `val config: Config[FooConfig]` *descriptor*. No layer that reads env.
- The single read point is `bootstrap/AppConfig.scala`:
  `val load: IO[Config.Error, AppConfig] = ZIO.config(AppConfig.config)`.
  All env / system-property / file resolution flows through this one call.
- Components downstream consume the resolved case class via
  `ZIO.service[FooConfig]`. Their layer types are `URLayer[..., FooConfig, X]`
  (no `Config.Error` in the error channel — it failed at startup or it didn't).
- **Forbidden**: `sys.env.get(...)`, `System.env(...).orDie`,
  `ZLayer.fromZIO(ZIO.config(...))` per-component. If you see one, it's a bug;
  replace with a descriptor and wire it through `AppConfig`.
- Optional features (e.g. OAuth) get `Config[FooConfig].optional` in `AppConfig`,
  and the wiring branches on `Option[FooConfig]`.

## Typed errors

- All application/use-case methods return typed errors (an enum or sealed
  sum). No `Throwable` channels at use-case boundaries. No `Task[X]` (=
  `IO[Throwable, X]`) leaking out of `application/`.
- For wire-format errors (JSON-RPC, OAuth, etc.), follow the **zio-cli
  pattern**:
  - typed `enum FooErrorKind` with standard cases + `Other(code: String | Int)`
    escape hatch for codes received but not classified
  - separate `case class FooError(kind, description, data)` envelope, OR
    parameterless enum cases with payload data on each
  - `extension (e: FooError) def code/message/data` for rendering
  - `given JsonDecoder[FooErrorKind]` mapping wire strings (with `Other(code)`
    as the catch-all fallback)
  - Reference impls: `domain/model/oauth/OAuthError.scala`,
    `domain/model/McpError.scala`, `domain/model/ClientRequesterError.scala`.
- Adapters render typed errors to wire shapes (HTTP status codes, JSON-RPC
  integers). Domain and application never know transport details.

## Layer construction

- Use `ZLayer.make[X](...)` and `ZLayer.makeSome[Input, Output](...)` over
  manual `++` / `>+>` chains. The macro topo-sorts and reports missing deps
  with clear errors.
- Bundle related ports behind type aliases (e.g.
  `type OAuthStorage = UserDirectory & AuthorizationRequestStore & ...`) so
  adapters ship one layer per backing system. Reference impl:
  `application/ports/oauth/OAuthStorage.scala`.
- Companion pattern, in this order, in one file:
  ```
  trait FooService:
    def doIt(...): IO[FooError, X]

  object FooService:
    def doIt(...) = ZIO.serviceWithZIO[FooService](_.doIt(...))   // accessors
    val layer: URLayer[Deps, FooService] = ZLayer.fromFunction(Live(_, _))
    final case class Live(...) extends FooService:
      override def doIt(...) = ...
  ```
- `ZLayer.fromFunction(Live(_, _, _))` is the default for service-style layers.

## Onion boundaries

- `domain/` imports nothing project-local. Only ZIO + stdlib + stable JDK + zio-json.
- `application/` imports `domain/` only.
- `adapters/` imports `application/` and `domain/`.
- `bootstrap/` imports anything; nothing imports bootstrap.
- Tests are exempt from the import rules (they exercise the wire boundary).

### Protocol DTOs (when wire shape ≡ domain shape)

When a case class describes a concept the application logic reasons about
*and* the JSON encoding of that concept (e.g. `ToolDefinition`,
`ClientCapabilities`, `InitializeResult`), it lives in `domain/model/<area>/`
with its `derives JsonEncoder` / `derives JsonDecoder` co-located. The
`derives` annotation is a one-line concession; defining "domain twins" plus a
mapper would double the type count without changing what the type means.
Reference impl: `domain/model/mcp/` -- the entire MCP protocol is modeled
this way.

When wire concerns and domain concerns genuinely diverge (different field
sets, mapping logic, multiple wire formats per domain type), then split into
two case classes with a mapper in `adapters/protocol/`. Reference impl: the
JSON-RPC `Request` / `Response` / `ErrorResponse` envelopes in
`adapters/protocol/jsonrpc/` -- they describe transport framing, not the
application's domain language.

## ZIO idioms

- `FiberRef` for cross-cutting context (auth, tracing, progress). Reference:
  `AuthFiberRef`, `ProgressReporter.current`.
- `ZLayer.scoped` for resources with finalizers. Reference: `TracingLayers`.
- `forkScoped` is the default. `forkDaemon` is a deliberate "outlive my
  parent scope" choice; use sparingly and document why.
- Wire I/O (Redis ops, JWKS fetch, OTLP exporter init) wraps in
  `Schedule.exponentialBackoff(...).recurs(N)` for retry. No `.orDie` on
  network operations.
- Long-running queues / hubs / promise maps must be **bounded**. Unbounded
  state in long-lived services is a DoS vector.

## JSON

- Derive `JsonEncoder` / `JsonDecoder` via `derives` or `DeriveJsonEncoder.gen`.
  Never hand-roll JSON string concatenation.
- The pattern `.toJsonAST.toOption.get` is **forbidden** in production code.
  When encoding can fail, fail typed (e.g. `McpError.InternalError(s"failed
  to encode result: $err")`).
- Wire DTOs live in `adapters/protocol/`. They are snake_case-friendly via
  `@jsonMemberNames(SnakeCase)` when needed; otherwise pure derivation.

## When in doubt

- Read `context/REVIEW.md` for current priorities (top-10 + follow-ups).
- Read `context/zionomicon/` for ZIO idioms;  `context/12factor/` for ops shape.
- The existing OAuth use-cases (`application/usecase/oauth/`) and the dispatch
  domain types (`domain/model/{DispatchResult,ResponsePayload,StreamFrame,
  McpError,RequestId}.scala`) are reference implementations of these
  conventions. Match their shape when adding new code.

## When making changes

- Run `sbt core/test` after every meaningful change. The suite is fast and
  catches regressions early.
- Cross off completed items in `context/REVIEW.md` and rewrite earlier
  sections of the review as if the codebase was always in its current state
  (the review is a *current-state* document, not a *change-log*).
- Add new follow-up findings to section 9 of `context/REVIEW.md` as they
  surface during work.
