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

/** Typed extension methods on `ToolContext` for server-to-client requests:
  * `sampling/createMessage`, `roots/list`, `elicitation/create`. They read the
  * fiber-local [[ClientChannel]] the executor bound for this call, so a handler
  * never builds JSON by hand:
  *
  * {{{
  * import fishy.mcp.ClientMessages.*
  *
  * Tool("my-tool").handle { (in: MyInput, ctx: ToolContext) =>
  *   for
  *     result <- ctx.createMessage(CreateMessageParams(
  *       messages = List(SamplingMessage.user("Summarize this")), maxTokens = 500))
  *     roots <- ctx.listRoots
  *   yield Content.Text(result.content.toString)
  * }
  * }}}
  *
  * The user-facing surface is `Task[X]` for ergonomic for-comprehensions; the
  * structured [[ClientRequesterError]] is preserved as the cause of
  * [[ClientCallFailed]] for handlers that want to match on it.
  */
object ClientMessages:

  /** Wraps a [[ClientRequesterError]] for the user-API `Task[X]` boundary.
    * Pattern-match on `cause` to recover the structured error. */
  final class ClientCallFailed(val cause: ClientRequesterError)
      extends RuntimeException(cause.message)

  private def asThrowable(err: ClientRequesterError): Throwable = ClientCallFailed(err)

  private def encode[A: JsonEncoder](value: A): Task[Json] =
    ZIO.fromEither(value.toJsonAST)
      .mapError(err => new RuntimeException(s"Failed to encode params: $err"))

  /** Issue one server→client request through the context's [[ClientChannel]]
    * and decode the reply. Fails `ClientCallFailed(NoClientCallback)` when no
    * client is connected (the channel's default). */
  private def send[A: JsonDecoder](ctx: ToolContext, method: String, params: Json): Task[A] =
    for
      reply  <- ctx.client.request(method, params).mapError(asThrowable)
      result <- ZIO.fromEither(reply.as[A])
                  .mapError(err => new RuntimeException(s"Failed to decode $method result: $err"))
    yield result

  extension (ctx: ToolContext)

    /** Ask the client's LLM to generate a message (sampling/createMessage). */
    def createMessage(params: CreateMessageParams): Task[CreateMessageResult] =
      encode(params).flatMap(send[CreateMessageResult](ctx, "sampling/createMessage", _))

    /** Ask the client for its filesystem roots (roots/list). */
    def listRoots: Task[ListRootsResult] =
      send[ListRootsResult](ctx, "roots/list", Json.Obj())

    /** Ask the client for human input (elicitation/create). */
    def elicit(params: ElicitationParams): Task[ElicitationResult] =
      encode(params).flatMap(send[ElicitationResult](ctx, "elicitation/create", _))
