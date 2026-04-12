package fishy.mcp.example

import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, ToolContext, ToolError}
import zio.*
import zio.schema.DeriveSchema
import zio.schema.Schema

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** Minimal example of a scoped filesystem-backed layer.
  *
  *   - Zionomicon Ch14/15: resource is acquired/released with ZLayer.scoped
  *   - 12-factor backing services: output path comes from env/config
  */
object FileSystemLayerServer extends ZIOAppDefault:

  final case class AppendLogInput(line: String)
  object AppendLogInput:
    given Schema[AppendLogInput] = DeriveSchema.gen

  trait FileAppendService:
    def append(line: String): Task[Unit]
    def outputPath: UIO[String]

  object FileAppendService:
    def append(line: String): ZIO[FileAppendService, Throwable, Unit] =
      ZIO.serviceWithZIO[FileAppendService](_.append(line))

    def outputPath: URIO[FileAppendService, String] =
      ZIO.serviceWithZIO[FileAppendService](_.outputPath)

    val live: ZLayer[Any, Throwable, FileAppendService] =
      ZLayer.scoped {
        val configuredPath = sys.env.getOrElse(
          "FISHY_APPEND_LOG_PATH",
          Paths.get(java.lang.System.getProperty("java.io.tmpdir"), "fishy-mcp-append.log").toString
        )

        for
          path <- ZIO.attempt(Paths.get(configuredPath))
          _ <- ZIO.attempt(Files.createDirectories(path.getParent)).when(path.getParent != null)
          writer <- ZIO.acquireRelease(
            ZIO.attemptBlockingIO(
              Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
              )
            )
          )(closeWriter)
        yield Live(path, writer)
      }

    private def closeWriter(writer: BufferedWriter): UIO[Unit] =
      ZIO.attemptBlockingIO(writer.close()).orDie

    private final case class Live(path: Path, writer: BufferedWriter) extends FileAppendService:
      def append(line: String): Task[Unit] =
        ZIO.attemptBlockingIO {
          writer.synchronized {
            writer.write(line)
            writer.newLine()
            writer.flush()
          }
        }

      def outputPath: UIO[String] =
        ZIO.succeed(path.toAbsolutePath.toString)

  val appendTool =
    Tool.handle[FileAppendService, AppendLogInput](
      "append_log",
      "Append one line to the configured server log file"
    ) { (input, _) =>
      for
        _ <- FileAppendService.append(input.line).mapError(toToolError)
        path <- FileAppendService.outputPath
      yield Content.Text(s"Appended to: $path")
    }

  private def toToolError(t: Throwable): ToolError =
    ToolError.ExecutionFailed(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))

  val server =
    MCPServer
      .withName("fishy-fs-example")
      .withVersion("0.1.0")
      .withTools(appendTool)

  def run: ZIO[Any, Throwable, Nothing] =
    server
      .serveHttp
      .provide(FileAppendService.live)
