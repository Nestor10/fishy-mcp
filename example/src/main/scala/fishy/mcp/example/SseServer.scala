package fishy.mcp.example

import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, ToolContext}
import fishy.mcp.adapters.inbound.http.{HttpTransport, HttpSecurityPolicy}
import fishy.mcp.application.usecase.NotificationSender
import zio.*
import zio.schema.Schema
import zio.schema.derived

/** SSE demo server showcasing Phase 5 capabilities.
  *
  * Demonstrates:
  *   - Session-enabled HTTP (Mcp-Session-Id flow)
  *   - GET /mcp SSE stream for receiving server-to-client messages
  *   - POST responses routed through SSE when client has active stream
  *   - Server-initiated notifications (tools/list_changed) via background fiber
  *   - Reconnection with Last-Event-ID replay
  *
  * Testing with curl:
  *
  * 1. Initialize a session:
  *    {{{
  *    curl -v -X POST http://localhost:8891/mcp \
  *      -H 'Content-Type: application/json' \
  *      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
  *    }}}
  *    Note the Mcp-Session-Id in the response headers.
  *
  * 2. Send initialized notification:
  *    {{{
  *    curl -X POST http://localhost:8891/mcp \
  *      -H 'Content-Type: application/json' \
  *      -H 'Mcp-Session-Id: <SESSION_ID>' \
  *      -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  *    }}}
  *
  * 3. Open the GET SSE stream (separate terminal):
  *    {{{
  *    curl -N http://localhost:8891/mcp \
  *      -H 'Accept: text/event-stream' \
  *      -H 'Mcp-Session-Id: <SESSION_ID>'
  *    }}}
  *
  * 4. Call a tool via POST (original terminal):
  *    {{{
  *    curl -X POST http://localhost:8891/mcp \
  *      -H 'Content-Type: application/json' \
  *      -H 'Mcp-Session-Id: <SESSION_ID>' \
  *      -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello SSE!"}}}'
  *    }}}
  *    POST returns 202 Accepted; the response appears on the SSE stream.
  *
  * 5. Watch for periodic tools/list_changed notifications on the SSE stream
  *    (broadcasts every 30 seconds from a background fiber).
  *
  * 6. Reconnect with replay (Last-Event-ID):
  *    {{{
  *    curl -N http://localhost:8891/mcp \
  *      -H 'Accept: text/event-stream' \
  *      -H 'Mcp-Session-Id: <SESSION_ID>' \
  *      -H 'Last-Event-ID: <LAST_ID>'
  *    }}}
  *    Missed events are replayed before the live stream resumes.
  */
object SseServer extends MCPApp:

  // -- Tools ------------------------------------------------------------------

  final case class EchoInput(message: String) derives Schema

  val echo = Tool("echo")
    .description("Echoes the input message back")
    .handle { (input: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"You said: ${input.message}"))
    }

  val time = Tool("time")
    .description("Returns the current server time")
    .noInput {
      ZIO.succeed(java.time.Instant.now().toString)
    }

  final case class CountdownInput(seconds: Int) derives Schema

  val countdown = Tool("countdown")
    .description("Counts down from N to 0, reporting progress each second")
    .handle { (input: CountdownInput, _: ToolContext) =>
      val n = input.seconds.min(10).max(1)
      ZIO.foreach(n.to(0, -1)) { i =>
        ZIO.logInfo(s"countdown: $i") *> ZIO.sleep(1.second).when(i > 0)
      }.as(Content.Text(s"Countdown from $n complete!"))
    }

  // -- Resources --------------------------------------------------------------

  val readme = Resource.text(
    "file:///readme.md",
    "readme",
    "Project README file",
    "text/markdown"
  )("# SSE Demo\n\nDemonstrates Phase 5 GET SSE capabilities in fishy-mcp.")

  // -- Server -----------------------------------------------------------------

  val server = MCPServer
    .withName("fishy-sse-demo")
    .withVersion("0.1.0")
    .withTools(echo, time, countdown)
    .withResources(readme)

  def run: ZIO[Any, Any, Any] =
    // Wire layers manually so we can access NotificationSender alongside HttpTransport.
    // The standard `serveHttp` wraps everything into HttpTransport[R] only;
    // here we expose NotificationSender too so a background fiber can broadcast.
    val layers = ZLayer.make[HttpTransport & NotificationSender](
      fishy.mcp.bootstrap.AppConfig.testDefaults,
      server.buildLayers,
      HttpTransport.layer
    )

    val serve = HttpTransport.serve(8891)

    // Periodically broadcast tools/list_changed to all SSE-connected clients.
    val notifier = (
      ZIO.sleep(30.seconds) *>
        ZIO.logInfo("Broadcasting tools/list_changed to SSE clients") *>
        NotificationSender.toolsListChanged
    ).forever.forkDaemon

    (notifier *> serve).provideLayer(layers)
