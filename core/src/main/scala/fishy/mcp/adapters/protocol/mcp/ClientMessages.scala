package fishy.mcp.adapters.protocol.mcp

import fishy.mcp.domain.model.{ClientRequesterError, ToolContext}
import fishy.mcp.domain.model.mcp.{
  CreateMessageParams,
  CreateMessageResult,
  ElicitationParams,
  ElicitationResult,
  ListRootsResult
}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Typed extension methods on ToolContext for server-to-client requests.
  *
  * These enable tool handlers to call sampling/createMessage, roots/list, and
  * elicitation/create without manually building JSON.
  *
  * The user-facing API remains `Task[X]` for ergonomic for-comprehensions in
  * tool handlers; the internal callback is typed [[ClientRequesterError]] and
  * the boundary downgrades to a `Throwable` here. Tool authors who want
  * structured handling can pattern-match by re-typing via
  * `.refineToOrDie[ClientRequesterError]`.
  *
  * {{{
  * import fishy.mcp.ClientMessages.*
  *
  * Tool("my-tool").handle { (in: MyInput, ctx: ToolContext) =>
  *   for
  *     result <- ctx.createMessage(CreateMessageParams(
  *       messages = List(SamplingMessage.user("Summarize this")),
  *       maxTokens = 500
  *     ))
  *     roots <- ctx.listRoots
  *   yield Content.Text(result.content.toString)
  * }
  * }}}
  */
object ClientMessages:

  private def requireCallback(
      ctx: ToolContext
  ): IO[ClientRequesterError, (String, Json) => IO[ClientRequesterError, Json]] =
    ctx.sendClientRequest match
      case Some(cb) => ZIO.succeed(cb)
      case None     => ZIO.fail(ClientRequesterError.NoClientCallback)

  /** Convert a typed [[ClientRequesterError]] to a `Throwable` for the
    * user-facing `Task[X]` boundary. The exception preserves the structured
    * error as the cause so handlers that want it can `match`.
    */
  private def asThrowable(err: ClientRequesterError): Throwable =
    new ClientMessages.ClientCallFailed(err)

  /** Wraps a [[ClientRequesterError]] for transport across the user-API
    * `Task[X]` boundary. Pattern-match on `cause` to recover the structured
    * error.
    */
  final class ClientCallFailed(val cause: ClientRequesterError)
      extends RuntimeException(cause.message)

  extension (ctx: ToolContext)

    /** Ask the client's LLM to generate a message (sampling/createMessage). */
    def createMessage(params: CreateMessageParams): Task[CreateMessageResult] =
      for
        send   <- requireCallback(ctx).mapError(asThrowable)
        json   <- params.toJsonAST match
                    case Right(j) => ZIO.succeed(j)
                    case Left(e)  => ZIO.fail(new RuntimeException(s"Failed to encode params: $e"))
        reply  <- send("sampling/createMessage", json).mapError(asThrowable)
        result <- ZIO.fromEither(reply.as[CreateMessageResult])
                    .mapError(err => new RuntimeException(s"Failed to decode CreateMessageResult: $err"))
      yield result

    /** Ask the client for its filesystem roots (roots/list). */
    def listRoots: Task[ListRootsResult] =
      for
        send   <- requireCallback(ctx).mapError(asThrowable)
        reply  <- send("roots/list", Json.Obj()).mapError(asThrowable)
        result <- ZIO.fromEither(reply.as[ListRootsResult])
                    .mapError(err => new RuntimeException(s"Failed to decode ListRootsResult: $err"))
      yield result

    /** Ask the client for human input (elicitation/create). */
    def elicit(params: ElicitationParams): Task[ElicitationResult] =
      for
        send   <- requireCallback(ctx).mapError(asThrowable)
        json   <- params.toJsonAST match
                    case Right(j) => ZIO.succeed(j)
                    case Left(e)  => ZIO.fail(new RuntimeException(s"Failed to encode params: $e"))
        reply  <- send("elicitation/create", json).mapError(asThrowable)
        result <- ZIO.fromEither(reply.as[ElicitationResult])
                    .mapError(err => new RuntimeException(s"Failed to decode ElicitationResult: $err"))
      yield result
