package fishy.mcp.adapters.inbound.stdio

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.DispatchResult.*
import fishy.mcp.adapters.protocol.jsonrpc.*
import zio.*
import zio.json.*
import zio.stream.*

import java.io.IOException

/** NDJSON stdio transport.
  *
  * Reads JSON-RPC from stdin, dispatches, writes responses to stdout. Logs go to stderr.
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
      val input = readLines
      val output = writeResponses

      input
        .mapZIO(processLine)
        .collect { case Some(response) => response }
        .run(output)
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
      if line.trim.isEmpty then ZIO.none
      else
        line.fromJson[Request] match
          case Left(parseErr) =>
            // Parse error - return error response
            val errorResponse = ErrorResponse.parseError(RequestId.Null)
            ZIO.some(errorResponse.toJson)

          case Right(request) =>
            dispatcher.dispatch(request, None).flatMap(_.toOption).map {
              case Some(Right(response)) => Some(response.toJson)
              case Some(Left(error))     => Some(error.toJson)
              case None                  => None
            }

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
      line.fromJson[Request] match
        case Left(_) =>
          val errorResponse = ErrorResponse.parseError(RequestId.Null)
          ZIO.some(errorResponse.toJson)

        case Right(request) =>
          dispatcher.dispatch(request, None).flatMap(_.toOption).map {
            case Some(Right(response)) => Some(response.toJson)
            case Some(Left(error))     => Some(error.toJson)
            case None                  => None
          }
