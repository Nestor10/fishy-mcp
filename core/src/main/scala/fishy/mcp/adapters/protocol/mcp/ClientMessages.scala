package fishy.mcp.adapters.protocol.mcp

import fishy.mcp.domain.model.ToolContext
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Typed extension methods on ToolContext for server-to-client requests.
  *
  * These enable tool handlers to call sampling/createMessage, roots/list, and elicitation/create
  * without manually building JSON.
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

  private def requireCallback(ctx: ToolContext): Task[(String, Json) => Task[Json]] =
    ctx.sendClientRequest match
      case Some(cb) => ZIO.succeed(cb)
      case None => ZIO.fail(new IllegalStateException(
          "No client request callback available. " +
            "Server-to-client requests require an active SSE connection (HTTP transport)."
        ))

  extension (ctx: ToolContext)

    /** Ask the client's LLM to generate a message (sampling/createMessage). */
    def createMessage(params: CreateMessageParams): Task[CreateMessageResult] =
      for
        send <- requireCallback(ctx)
        json <- send("sampling/createMessage", params.toJsonAST.toOption.get)
        result <- ZIO.fromEither(json.as[CreateMessageResult])
          .mapError(err => new RuntimeException(s"Failed to decode CreateMessageResult: $err"))
      yield result

    /** Ask the client for its filesystem roots (roots/list). */
    def listRoots: Task[ListRootsResult] =
      for
        send <- requireCallback(ctx)
        json <- send("roots/list", Json.Obj())
        result <- ZIO.fromEither(json.as[ListRootsResult])
          .mapError(err => new RuntimeException(s"Failed to decode ListRootsResult: $err"))
      yield result

    /** Ask the client for human input (elicitation/create). */
    def elicit(params: ElicitationParams): Task[ElicitationResult] =
      for
        send <- requireCallback(ctx)
        json <- send("elicitation/create", params.toJsonAST.toOption.get)
        result <- ZIO.fromEither(json.as[ElicitationResult])
          .mapError(err => new RuntimeException(s"Failed to decode ElicitationResult: $err"))
      yield result
