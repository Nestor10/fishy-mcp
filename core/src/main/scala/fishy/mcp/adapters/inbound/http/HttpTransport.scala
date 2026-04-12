package fishy.mcp.adapters.inbound.http

import fishy.mcp.adapters.protocol.mcp.ServerCapabilities
import fishy.mcp.application.ports.{
  EventReplay,
  MessageRouter,
  SessionHooks,
  SessionStore,
  SubscriptionRegistry
}
import fishy.mcp.application.usecase.{ClientRequester, McpDispatcher}
import zio.*
import zio.http.*

/** Streamable HTTP transport for MCP.
  *
  * Composes routes from McpRequestHandler (POST /mcp) and SseHandler (GET /mcp), applies
  * middleware, and starts the HTTP server.
  */
trait HttpTransport:

  /** HTTP routes for the MCP endpoint. */
  def routes: Routes[Any, Response]

  /** Start the HTTP server on the given port. */
  def serve(port: Int): ZIO[Any, Throwable, Nothing]

object HttpTransport:

  private val McpSessionIdHeader = "Mcp-Session-Id"

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def routes: URIO[HttpTransport, Routes[Any, Response]] =
    ZIO.serviceWith(_.routes)

  def serve(port: Int) =
    ZIO.serviceWithZIO[HttpTransport](_.serve(port))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[
    McpDispatcher & SessionStore & MessageRouter & EventReplay & HttpSecurityPolicy & ClientRequester & SessionHooks & SubscriptionRegistry & ServerCapabilities,
    HttpTransport
  ] =
    ZLayer.fromFunction(
      (
          dispatcher: McpDispatcher,
          sessionStore: SessionStore,
          messageRouter: MessageRouter,
          eventReplay: EventReplay,
          securityPolicy: HttpSecurityPolicy,
          clientRequester: ClientRequester,
          sessionHooks: SessionHooks,
          subscriptionRegistry: SubscriptionRegistry,
          capabilities: ServerCapabilities
      ) =>
        Live(
          dispatcher,
          sessionStore,
          messageRouter,
          eventReplay,
          securityPolicy,
          clientRequester,
          sessionHooks,
          subscriptionRegistry,
          capabilities
        )
    )

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live(
      dispatcher: McpDispatcher,
      sessionStore: SessionStore,
      messageRouter: MessageRouter,
      eventReplay: EventReplay,
      securityPolicy: HttpSecurityPolicy,
      clientRequester: ClientRequester,
      sessionHooks: SessionHooks,
      subscriptionRegistry: SubscriptionRegistry,
      capabilities: ServerCapabilities
  ) extends HttpTransport:

    private val requestHandler = McpRequestHandler(dispatcher, sessionStore, clientRequester)
    private val sseHandler = SseHandler(
      sessionStore,
      messageRouter,
      eventReplay,
      sessionHooks,
      subscriptionRegistry,
      initialNotificationsFor(capabilities)
    )

    private def initialNotificationsFor(caps: ServerCapabilities): List[String] =
      List(
        caps.tools.map(_ => """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""),
        caps.resources.map(_ =>
          """{"jsonrpc":"2.0","method":"notifications/resources/list_changed"}"""
        ),
        caps.prompts.map(_ => """{"jsonrpc":"2.0","method":"notifications/prompts/list_changed"}""")
      ).flatten

    private val sessionMiddleware: HandlerAspect[Any, Unit] =
      HandlerAspect.updateRequestZIO { request =>
        request.headers.get(McpSessionIdHeader) match
          case Some(sessionId) =>
            sessionStore.exists(sessionId).map { exists =>
              if exists then request else request.removeHeader(McpSessionIdHeader)
            }
          case None =>
            ZIO.succeed(request)
      }

    def routes: Routes[Any, Response] =
      val mcpRoutes = Routes(
        Method.POST / "mcp" -> handler { (request: Request) =>
          requestHandler.handle(request).catchAllCause { cause =>
            ZIO.logErrorCause("Error in MCP request handler", cause) *>
              ZIO.succeed(Response.text(s"Internal server error: ${cause.prettyPrint}").status(
                Status.InternalServerError
              ))
          }
        },
        Method.GET / "mcp" -> handler { (request: Request) =>
          sseHandler.handle(request).catchAllCause { cause =>
            ZIO.logErrorCause("Error in SSE request handler", cause) *>
              ZIO.succeed(Response.text(s"Internal server error: ${cause.prettyPrint}").status(
                Status.InternalServerError
              ))
          }
        }
      ) @@ sessionMiddleware @@ securityPolicy.middleware

      val healthRoutes = Routes(
        Method.GET / "health" -> handler { (_: Request) =>
          ZIO.succeed(Response.json("""{"status":"ok"}"""))
        }
      )

      mcpRoutes ++ healthRoutes

    def serve(port: Int): ZIO[Any, Throwable, Nothing] =
      Server.serve(routes).provide(Server.defaultWithPort(port))
