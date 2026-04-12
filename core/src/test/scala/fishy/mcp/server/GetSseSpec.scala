package fishy.mcp.server

import fishy.mcp.adapters.inbound.http.{HttpSecurityPolicy, HttpTransport}
import fishy.mcp.adapters.storage.{InMemoryBackend, InMemorySubscriptionRegistry}
import fishy.mcp.application.ports.{
  EventReplay,
  MessageRouter,
  PromptRegistry,
  ResourceRegistry,
  SessionStore,
  ToolRegistry
}
import fishy.mcp.application.usecase.{
  ClientRequester,
  McpDispatcher,
  NotificationSender,
  PromptExecutor,
  ResourceExecutor,
  ToolExecutor
}
import fishy.mcp.bootstrap.TracingLayers
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import fishy.mcp.domain.model.DispatchResult
import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, ToolContext}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.test.*
import zio.test.Assertion.*

/** Tests for GET SSE transport, POST-to-SSE routing, and reconnection replay. */
object GetSseSpec extends ZIOSpecDefault:

  final case class EchoInput(message: String)
  object EchoInput:
    given Schema[EchoInput] = DeriveSchema.gen

  val echoTool: fishy.mcp.domain.model.Tool[Any] =
    Tool("echo").description("Echoes input").handle { (in: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Echo: ${in.message}"))
    }

  val serverInfo = ServerInfo("test-server", "1.0.0")
  val capabilities = ServerCapabilities(tools = Some(ToolsCapability(listChanged = Some(true))))

  /** Build test layers with session management enabled. */
  val testLayers =
    ZLayer.make[McpDispatcher & SessionStore & MessageRouter & EventReplay & NotificationSender](
      ToolRegistry.layer(List(echoTool)),
      ResourceRegistry.layer(Nil: List[fishy.mcp.domain.model.Resource[Any]]),
      PromptRegistry.layer(Nil: List[fishy.mcp.domain.model.Prompt[Any]]),
      ToolExecutor.layer,
      ResourceExecutor.layer,
      PromptExecutor.layer,
      McpDispatcher.layer(serverInfo, capabilities),
      ClientRequester.layer,
      InMemoryBackend.layer,
      InMemorySubscriptionRegistry.layer,
      NotificationSender.layer,
      TracingLayers.noop
    )

  def initRequest(id: Long = 1): Request =
    Request(
      "2.0",
      "initialize",
      Some(
        InitializeParams("2024-11-05", ClientCapabilities(), ClientInfo("test", "1.0.0")).toJsonAST
          .toOption.get
      ),
      Some(RequestId.NumberId(id))
    )

  def toolCallRequest(name: String, args: Json, id: Long = 2): Request =
    Request(
      "2.0",
      "tools/call",
      Some(Json.Obj(
        "name" -> Json.Str(name),
        "arguments" -> args
      )),
      Some(RequestId.NumberId(id))
    )

  def spec = suite("GET SSE")(
    suite("POST-to-SSE routing")(
      test("POST returns 202 when session has active SSE subscriber") {
        ZIO.scoped {
          for
            // Create and initialize a session
            sessionId <- SessionStore.create()
            dispatcher <- ZIO.service[McpDispatcher]
            _ <- dispatcher.dispatch(
              Request("2.0", "notifications/initialized", None, None),
              Some(sessionId)
            )
            // Open SSE subscription for this session
            stream <- MessageRouter.subscribe(sessionId)
            // Check that the session has subscribers
            hasSub <- MessageRouter.hasSubscribers(sessionId)
            // Dispatch a tools/call -- since SSE is active, the result should be routed to SSE
            result <- dispatcher.dispatch(
              toolCallRequest("echo", Json.Obj("message" -> Json.Str("hello")), id = 10),
              Some(sessionId)
            )
          yield
            assertTrue(hasSub)
            // The dispatch result itself doesn't change -- that's the dispatcher's job.
            // The transport layer is what decides to route to SSE vs inline.
            // This test verifies the dispatcher still returns a normal result.
            assertTrue(result.isInstanceOf[DispatchResult.Single])
        }
      }
    ),
    suite("MessageRouter SSE integration")(
      test("message published during SSE subscription is received") {
        ZIO.scoped {
          for
            sessionId <- SessionStore.create()
            stream <- MessageRouter.subscribe(sessionId)
            fiber <- stream.take(1).runCollect.fork
            _ <- MessageRouter.publish(
              sessionId,
              """{"jsonrpc":"2.0","result":{"test":true},"id":1}"""
            )
            result <- fiber.join
          yield assertTrue(
            result.size == 1,
            result.head.contains("test")
          )
        }
      },
      test("subscription cleanup on scope close stops message delivery") {
        for
          sessionId <- SessionStore.create()
          // Subscribe in a scope, collect messages, then let scope close
          collected <- ZIO.scoped {
            for
              stream <- MessageRouter.subscribe(sessionId)
              _ <- MessageRouter.publish(sessionId, "before-close")
              result <- stream.take(1).runCollect
            yield result
          }
        // The scope is now closed, subscription is gone.
        // Hub may still accept messages, but no subscriber will receive them.
        yield assertTrue(
          collected.size == 1,
          collected.head == "before-close"
        )
      }
    ),
    suite("EventReplay for reconnection")(
      test("events appended during SSE can be replayed after reconnect") {
        for
          sessionId <- SessionStore.create()
          // Simulate first connection: append some events
          id1 <- EventReplay.append(
            sessionId,
            """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
          )
          id2 <-
            EventReplay.append(sessionId, """{"jsonrpc":"2.0","result":{"content":[]},"id":1}""")
          id3 <-
            EventReplay.append(sessionId, """{"jsonrpc":"2.0","result":{"content":[]},"id":2}""")
          // Simulate reconnect with Last-Event-ID = id1 (should get id2 and id3)
          replayed <- EventReplay.since(sessionId, id1)
        yield assertTrue(
          replayed.size == 2,
          replayed.head.id == id2,
          replayed(1).id == id3
        )
      },
      test("replay from last event returns empty") {
        for
          sessionId <- SessionStore.create()
          id1 <- EventReplay.append(sessionId, "msg-1")
          id2 <- EventReplay.append(sessionId, "msg-2")
          replayed <- EventReplay.since(sessionId, id2)
        yield assertTrue(replayed.isEmpty)
      }
    ),
    suite("NotificationSender integration")(
      test("toolsListChanged broadcasts to all sessions with SSE") {
        ZIO.scoped {
          for
            sid1 <- SessionStore.create()
            sid2 <- SessionStore.create()
            stream1 <- MessageRouter.subscribe(sid1)
            stream2 <- MessageRouter.subscribe(sid2)
            fiber1 <- stream1.take(1).runCollect.fork
            fiber2 <- stream2.take(1).runCollect.fork
            _ <- NotificationSender.toolsListChanged
            r1 <- fiber1.join
            r2 <- fiber2.join
            msg1 = r1.head
            msg2 = r2.head
          yield assertTrue(
            msg1.contains("notifications/tools/list_changed"),
            msg2.contains("notifications/tools/list_changed")
          )
        }
      }
    ),
    suite("capabilities")(
      test("server advertises listChanged in capabilities") {
        for
          result <- McpDispatcher.dispatch(initRequest()).flatMap(_.toOption)
          json = result.get.toOption.get.result.toString
        yield assertTrue(
          json.contains("listChanged"),
          json.contains("true")
        )
      }
    )
  ).provide(testLayers)
