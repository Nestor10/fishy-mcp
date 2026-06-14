package fishy.mcp.adapters.inbound.stdio

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.{DispatchResult, RequestId}
import fishy.mcp.adapters.protocol.jsonrpc.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import java.io.IOException

/** NDJSON stdio transport.
  *
  * Reads JSON-RPC from stdin, dispatches, writes responses to stdout. Logs go
  * to stderr. Streaming results are drained to the final payload (no SSE on
  * stdio).
  */
trait StdioTransport:
  def run: IO[IOException, Unit]

object StdioTransport:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def run =
    ZIO.serviceWithZIO[StdioTransport](_.run)

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[McpDispatcher, StdioTransport] =
    ZLayer.fromFunction((dispatcher: McpDispatcher) => Live(dispatcher))

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live(dispatcher: McpDispatcher) extends StdioTransport:

    def run: IO[IOException, Unit] =
      readLines
        .mapZIO(processLine)
        .collect { case Some(response) => response }
        .run(writeResponses)
        .unit

    private def readLines: ZStream[Any, IOException, String] =
      ZStream
        .fromInputStream(java.lang.System.in)
        .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)

    private def writeResponses: ZSink[Any, IOException, String, Nothing, Unit] =
      ZSink.foreach { line =>
        ZIO.attemptBlockingIO {
          java.lang.System.out.println(line)
          java.lang.System.out.flush()
        }
      }

    private def processLine(line: String): UIO[Option[String]] =
      StdioTransport.processLine(dispatcher)(line)

  // ---------------------------------------------------------------------------
  // Test utilities
  // ---------------------------------------------------------------------------

  def withStreams(
      input: ZStream[Any, IOException, String],
      output: ZSink[Any, IOException, String, Nothing, Unit]
  ): URLayer[McpDispatcher, StdioTransport] =
    ZLayer.fromFunction { (dispatcher: McpDispatcher) =>
      new StdioTransport:
        def run: IO[IOException, Unit] =
          input
            .mapZIO(processLine(dispatcher))
            .collect { case Some(response) => response }
            .run(output)
            .unit
    }

  private def processLine(dispatcher: McpDispatcher)(line: String): UIO[Option[String]] =
    if line.trim.isEmpty then ZIO.none
    else
      line.fromJson[Json] match
        case Left(_) =>
          ZIO.some(ErrorResponse.parseError(RequestId.Null).toJson)
        case Right(json) =>
          InboundMessage.parse(json) match
            case Left(_) =>
              ZIO.some(ErrorResponse.invalidRequest(RequestId.Null).toJson)
            case Right(InboundMessage.Dispatch(request)) =>
              dispatcher.dispatch(request, None).flatMap(_.toOption).map {
                case Some(payload) => Some(DispatchResultEncoder.encodePayloadJson(payload))
                case None          => None
              }
            case Right(InboundMessage.ClientResponse(id, _)) =>
              ZIO.logWarning(
                s"Ignoring client response on stdio; server-to-client requests are not supported on this transport (id=$id)"
              ).as(None)
