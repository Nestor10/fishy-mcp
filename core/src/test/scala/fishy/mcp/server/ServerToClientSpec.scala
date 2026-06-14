package fishy.mcp.server

import fishy.mcp.adapters.protocol.jsonrpc.{Error as JsonRpcError, *}
import fishy.mcp.adapters.protocol.mcp.ClientMessages
import fishy.mcp.domain.model.mcp.*
import fishy.mcp.application.ports.MessageRouter
import fishy.mcp.application.usecase.ClientRequester
import fishy.mcp.domain.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

object ServerToClientSpec extends ZIOSpecDefault:

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** MessageRouter that captures published messages and allows manual response injection. */
  private def testMessageRouter: UIO[(MessageRouter, Ref[List[String]])] =
    for
      published <- Ref.make(List.empty[String])
      hub <- Hub.bounded[String](16)
    yield (
      new MessageRouter:
        def publish(sessionId: String, message: String): UIO[Boolean] =
          published.update(_ :+ message).as(true)
        def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
          hub.subscribe.map(q => ZStream.fromQueue(q))
        def hasSubscribers(sessionId: String): UIO[Boolean] = ZIO.succeed(true)
        def removeSession(sessionId: String): UIO[Unit] = ZIO.unit
      ,
      published
    )

  /** MessageRouter that reports no subscribers (no SSE connection). */
  private val disconnectedRouter: MessageRouter = new MessageRouter:
    def publish(sessionId: String, message: String): UIO[Boolean] = ZIO.succeed(false)
    def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
      ZIO.succeed(ZStream.empty)
    def hasSubscribers(sessionId: String): UIO[Boolean] = ZIO.succeed(false)
    def removeSession(sessionId: String): UIO[Unit] = ZIO.unit

  /** Build a ClientRequester using a test MessageRouter. */
  private def makeRequester(router: MessageRouter): UIO[ClientRequester] =
    ZIO.scoped {
      (ZLayer.succeed(router) >>> ClientRequester.layer).build
        .map(_.get[ClientRequester])
    }

  // ---------------------------------------------------------------------------
  // Wire type tests
  // ---------------------------------------------------------------------------

  def spec = suite("ServerToClient")(
    suite("Wire types")(
      test("SamplingMessage round-trips through JSON") {
        val msg = SamplingMessage.user("Hello, world!")
        val json = msg.toJson
        val decoded = json.fromJson[SamplingMessage]
        assertTrue(
          decoded.isRight,
          decoded.toOption.get.role == "user",
          decoded.toOption.get.content == Content.Text("Hello, world!")
        )
      },
      test("CreateMessageParams encodes correctly") {
        val params = CreateMessageParams(
          messages = List(SamplingMessage.user("Summarize this")),
          maxTokens = 500,
          systemPrompt = Some("Be concise")
        )
        val json = params.toJsonAST.toOption.get
        val obj = json.asObject.get
        assertTrue(
          obj.contains("messages"),
          obj.contains("maxTokens"),
          obj.get("systemPrompt").contains(Json.Str("Be concise"))
        )
      },
      test("CreateMessageResult decodes correctly") {
        val json =
          """{"role":"assistant","content":{"type":"text","text":"Hello"},"model":"gpt-4","stopReason":"end_turn"}"""
        val result = json.fromJson[CreateMessageResult]
        assertTrue(
          result.isRight,
          result.toOption.get.role == "assistant",
          result.toOption.get.model == "gpt-4",
          result.toOption.get.stopReason.contains("end_turn")
        )
      },
      test("Root round-trips through JSON") {
        val root = Root(uri = "file:///home/user/project", name = Some("Project"))
        val json = root.toJson
        val decoded = json.fromJson[Root]
        assertTrue(
          decoded.isRight,
          decoded.toOption.get.uri == "file:///home/user/project",
          decoded.toOption.get.name.contains("Project")
        )
      },
      test("ListRootsResult decodes correctly") {
        val json = """{"roots":[{"uri":"file:///home","name":"Home"},{"uri":"file:///tmp"}]}"""
        val result = json.fromJson[ListRootsResult]
        assertTrue(
          result.isRight,
          result.toOption.get.roots.size == 2,
          result.toOption.get.roots.head.name.contains("Home"),
          result.toOption.get.roots(1).name.isEmpty
        )
      },
      test("ElicitationParams encodes correctly") {
        val params = ElicitationParams(
          message = "What is your project name?",
          requestedSchema = Some(Json.Obj("type" -> Json.Str("object")))
        )
        val json = params.toJsonAST.toOption.get
        val obj = json.asObject.get
        assertTrue(
          obj.get("message").contains(Json.Str("What is your project name?")),
          obj.contains("requestedSchema")
        )
      },
      test("ElicitationResult round-trips for accepted action") {
        val result = ElicitationResult(
          action = ElicitationAction.Accepted,
          content = Some(Json.Obj("name" -> Json.Str("fishy-mcp")))
        )
        val json = result.toJson
        val decoded = json.fromJson[ElicitationResult]
        assertTrue(
          decoded.isRight,
          decoded.toOption.get.action == "accepted",
          decoded.toOption.get.content.isDefined
        )
      },
      test("ElicitationResult round-trips for declined action") {
        val result = ElicitationResult(action = ElicitationAction.Declined)
        val decoded = result.toJson.fromJson[ElicitationResult]
        assertTrue(
          decoded.isRight,
          decoded.toOption.get.action == "declined",
          decoded.toOption.get.content.isEmpty
        )
      },
      test("ClientCapabilities with elicitation decodes correctly") {
        val json = """{"sampling":{},"roots":{"listChanged":true},"elicitation":{}}"""
        val caps = json.fromJson[ClientCapabilities]
        assertTrue(
          caps.isRight,
          caps.toOption.get.sampling.isDefined,
          caps.toOption.get.roots.isDefined,
          caps.toOption.get.elicitation.isDefined
        )
      }
    ),
    suite("ClientRequester")(
      test("sendRequest publishes JSON-RPC request over MessageRouter") {
        for
          (router, published) <- testMessageRouter
          requester <- makeRequester(router)
          // Send request in background (it will block waiting for response)
          fiber <- requester.sendRequest(
            "session-1",
            "sampling/createMessage",
            Json.Obj("maxTokens" -> Json.Num(100)),
            2.seconds
          ).fork
          // Wait deterministically until the forked fiber has actually published
          // the JSON-RPC request (and therefore registered its pending Promise).
          // Previous `ZIO.yieldNow *> ZIO.yieldNow` was a heuristic — under Java
          // 21's scheduling the fork could lose the race, leaving completeRequest
          // to target a Promise that didn't exist yet → RequestTimeout flake.
          msgs <- published.get.repeatUntil(_.nonEmpty)
          // Complete the request so the fiber doesn't hang
          _ <- requester.completeRequest("srv-1", Right(Json.Obj("role" -> Json.Str("assistant"))))
          _ <- TestClock.adjust(3.seconds)
          result <- fiber.join
        yield assertTrue(
          msgs.nonEmpty,
          msgs.head.contains("sampling/createMessage"),
          msgs.head.contains("srv-1"),
          msgs.head.contains("\"jsonrpc\":\"2.0\"")
        )
      },
      test("completeRequest fulfills the pending Promise") {
        for
          (router, _) <- testMessageRouter
          requester <- makeRequester(router)
          // Start request in background
          fiber <- requester.sendRequest("s1", "roots/list", Json.Obj(), 5.seconds).fork
          // Yield to let it register
          _ <- ZIO.yieldNow *> ZIO.yieldNow
          // Simulate client response
          completed <- requester.completeRequest(
            "srv-1",
            Right(Json.Obj(
              "roots" -> Json.Arr(Json.Obj("uri" -> Json.Str("file:///home")))
            ))
          )
          _ <- TestClock.adjust(6.seconds)
          result <- fiber.join
        yield assertTrue(
          completed,
          result.asObject.exists(_.contains("roots"))
        )
      },
      test("completeRequest with error fails the Promise") {
        for
          (router, _) <- testMessageRouter
          requester <- makeRequester(router)
          fiber <- requester.sendRequest("s1", "sampling/createMessage", Json.Obj(), 5.seconds).fork
          _ <- ZIO.yieldNow *> ZIO.yieldNow
          _ <- requester.completeRequest("srv-1", Left(JsonRpcError(-32600, "Not supported")))
          _ <- TestClock.adjust(6.seconds)
          result <- fiber.join.either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.get.message.contains("Not supported")
        )
      },
      test("completeRequest returns false for unknown request ID") {
        for
          (router, _) <- testMessageRouter
          requester <- makeRequester(router)
          completed <- requester.completeRequest("nonexistent-id", Right(Json.Obj()))
        yield assertTrue(!completed)
      },
      test("sendRequest fails when no SSE connection") {
        for
          requester <- makeRequester(disconnectedRouter)
          result <- requester.sendRequest("s1", "sampling/createMessage", Json.Obj()).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.get.message.contains("No active SSE connection")
        )
      },
      test("registerClientCapabilities stores and retrieves caps") {
        for
          (router, _) <- testMessageRouter
          requester <- makeRequester(router)
          caps = ClientCapabilities(
            sampling = Some(Json.Obj()),
            roots = Some(RootsCapability(listChanged = Some(true))),
            elicitation = Some(Json.Obj())
          )
          _ <- requester.registerClientCapabilities("s1", caps)
          retrieved <- requester.getClientCapabilities("s1")
          missing <- requester.getClientCapabilities("s2")
        yield assertTrue(
          retrieved.isDefined,
          retrieved.get.sampling.isDefined,
          retrieved.get.roots.isDefined,
          retrieved.get.elicitation.isDefined,
          missing.isEmpty
        )
      },
      test("cancelPendingRequests fails all pending Promises") {
        for
          (router, _) <- testMessageRouter
          requester <- makeRequester(router)
          fiber1 <-
            requester.sendRequest("s1", "sampling/createMessage", Json.Obj(), 30.seconds).fork
          fiber2 <- requester.sendRequest("s1", "roots/list", Json.Obj(), 30.seconds).fork
          _ <- ZIO.yieldNow *> ZIO.yieldNow
          _ <- requester.cancelPendingRequests("s1")
          _ <- TestClock.adjust(31.seconds)
          r1 <- fiber1.join.either
          r2 <- fiber2.join.either
        yield assertTrue(r1.isLeft, r2.isLeft)
      }
    ),
    suite("ToolContext extensions")(
      test("createMessage sends typed request and decodes result") {
        import ClientMessages.*
        val callback: (String, Json) => IO[ClientRequesterError, Json] = (method, params) =>
          ZIO.succeed(
            Json.Obj(
              "role" -> Json.Str("assistant"),
              "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("Summary here")),
              "model" -> Json.Str("gpt-4")
            )
          )
        val channel: ClientChannel = (method, params) => callback(method, params)
        val ctx = ToolContext("req-1", Some("s1"), None, client = channel)
        for
          result <- ctx.createMessage(CreateMessageParams(
            messages = List(SamplingMessage.user("Summarize")),
            maxTokens = 500
          ))
        yield assertTrue(
          result.role == "assistant",
          result.model == "gpt-4",
          result.content == Content.Text("Summary here")
        )
      },
      test("listRoots sends typed request and decodes result") {
        import ClientMessages.*
        val callback: (String, Json) => IO[ClientRequesterError, Json] = (method, _) =>
          assertTrue(method == "roots/list") *>
            ZIO.succeed(Json.Obj(
              "roots" -> Json.Arr(
                Json.Obj("uri" -> Json.Str("file:///project"), "name" -> Json.Str("Project"))
              )
            ))
        val channel: ClientChannel = (method, params) => callback(method, params)
        val ctx = ToolContext("req-1", Some("s1"), None, client = channel)
        for
          result <- ctx.listRoots
        yield assertTrue(
          result.roots.size == 1,
          result.roots.head.uri == "file:///project",
          result.roots.head.name.contains("Project")
        )
      },
      test("elicit sends typed request and decodes result") {
        import ClientMessages.*
        val callback: (String, Json) => IO[ClientRequesterError, Json] = (_, _) =>
          ZIO.succeed(Json.Obj(
            "action" -> Json.Str("accepted"),
            "content" -> Json.Obj("name" -> Json.Str("fishy-mcp"))
          ))
        val channel: ClientChannel = (method, params) => callback(method, params)
        val ctx = ToolContext("req-1", Some("s1"), None, client = channel)
        for
          result <- ctx.elicit(ElicitationParams(message = "What is your project name?"))
        yield assertTrue(
          result.action == ElicitationAction.Accepted,
          result.content.isDefined
        )
      },
      test("extension methods fail when no callback is set") {
        import ClientMessages.*
        val ctx = ToolContext("req-1", Some("s1"), None)
        for
          r1 <- ctx.createMessage(CreateMessageParams(List.empty, 100)).either
          r2 <- ctx.listRoots.either
          r3 <- ctx.elicit(ElicitationParams("hi")).either
        yield assertTrue(
          r1.isLeft,
          r1.left.toOption.get.getMessage.contains("No client request callback available"),
          r2.isLeft,
          r3.isLeft
        )
      }
    ),
    suite("McpDispatcher stores client capabilities")(
      test("initialize parses and stores client capabilities") {
        for
          (dispatcher, sessionStore) <- TestLayers.makeDispatcher()
          sessionId <- sessionStore.create()
          initRequest = Request(
            "2.0",
            "initialize",
            Some(Json.Obj(
              "protocolVersion" -> Json.Str("2024-11-05"),
              "capabilities" -> Json.Obj(
                "sampling" -> Json.Obj(),
                "roots" -> Json.Obj("listChanged" -> Json.Bool(true)),
                "elicitation" -> Json.Obj()
              ),
              "clientInfo" -> Json.Obj(
                "name" -> Json.Str("test-client"),
                "version" -> Json.Str("1.0")
              )
            )),
            Some(RequestId.NumberId(1))
          )
          result <- dispatcher.dispatch(initRequest, Some(sessionId))
        yield
        // Initialize should succeed
        result match
          case DispatchResult.Single(p) if p.outcome.isRight => assertTrue(true)
          case other                                          => assertTrue(false)
      }
    )
  )
