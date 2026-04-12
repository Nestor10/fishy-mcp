package fishy.mcp.server

import fishy.mcp.application.usecase.McpDispatcher
import fishy.mcp.domain.model.*
import fishy.mcp.dsl.{Prompt, Resource, Tool}
import fishy.mcp.adapters.protocol.jsonrpc.*
import fishy.mcp.adapters.protocol.mcp.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object ResourcePromptSpec extends ZIOSpecDefault:

  val serverInfo = ServerInfo("test-server", "1.0.0")

  val readmeResource: fishy.mcp.domain.model.Resource[Any] =
    Resource.text("file:///readme.md", "readme", "Project README", "text/markdown")(
      "# Hello World"
    )

  val greetPrompt: fishy.mcp.domain.model.Prompt[Any] =
    Prompt(
      "greet",
      "Generate a greeting",
      List(PromptArgument("name", "Name to greet", required = true))
    ) { args =>
      val name = args.getOrElse("name", "World")
      ZIO.succeed(List(PromptMessage("user", s"Hello, $name!")))
    }

  val summaryPrompt: fishy.mcp.domain.model.Prompt[Any] =
    Prompt.static("summary", "Summarize a topic")(
      List(PromptMessage("user", "Please provide a summary."))
    )

  def makeDispatcher(
      resources: List[fishy.mcp.domain.model.Resource[Any]] = Nil,
      prompts: List[fishy.mcp.domain.model.Prompt[Any]] = Nil
  ): UIO[McpDispatcher] =
    val capabilities = ServerCapabilities(
      resources = if resources.nonEmpty then Some(ResourcesCapability()) else None,
      prompts = if prompts.nonEmpty then Some(PromptsCapability()) else None
    )
    TestLayers.makeDispatcher(
      resources = resources,
      prompts = prompts,
      serverInfo = serverInfo,
      capabilities = capabilities
    ).map(_._1)

  def request(
      method: String,
      params: Option[Json] = None,
      id: RequestId = RequestId.NumberId(1)
  ): Request =
    Request("2.0", method, params, Some(id))

  def spec = suite("Resources & Prompts")(
    suite("resources/list")(
      test("returns registered resources") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <- dispatcher.dispatch(request("resources/list"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("readme"),
            json.contains("file:///readme.md"),
            json.contains("text/markdown")
          )
      },
      test("returns empty list when no resources registered") {
        for
          dispatcher <- makeDispatcher()
          result <- dispatcher.dispatch(request("resources/list"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(json.contains("\"resources\":[]"))
      }
    ),
    suite("resources/read")(
      test("reads a text resource by URI") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <- dispatcher.dispatch(
            request("resources/read", Some(Json.Obj("uri" -> Json.Str("file:///readme.md")))),
            None
          ).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("# Hello World"),
            json.contains("file:///readme.md")
          )
      },
      test("returns error for unknown URI") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <- dispatcher.dispatch(
            request("resources/read", Some(Json.Obj("uri" -> Json.Str("file:///nonexistent.md")))),
            None
          ).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.isLeft,
            inner.left.toOption.get.error.code == -32002
          )
      },
      test("returns error for missing params") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <- dispatcher.dispatch(request("resources/read", None), None).flatMap(_.toOption)
        yield assertTrue(result.get.isLeft)
      }
    ),
    suite("prompts/list")(
      test("returns registered prompts") {
        for
          dispatcher <- makeDispatcher(prompts = List(greetPrompt, summaryPrompt))
          result <- dispatcher.dispatch(request("prompts/list"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("greet"),
            json.contains("summary"),
            json.contains("name")
          )
      },
      test("returns empty list when no prompts registered") {
        for
          dispatcher <- makeDispatcher()
          result <- dispatcher.dispatch(request("prompts/list"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(json.contains("\"prompts\":[]"))
      }
    ),
    suite("prompts/get")(
      test("returns prompt messages with arguments") {
        for
          dispatcher <- makeDispatcher(prompts = List(greetPrompt))
          result <- dispatcher.dispatch(
            request(
              "prompts/get",
              Some(Json.Obj(
                "name" -> Json.Str("greet"),
                "arguments" -> Json.Obj("name" -> Json.Str("Alice"))
              ))
            ),
            None
          ).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("Hello, Alice!"),
            json.contains("user"),
            json.contains("\"type\":\"text\"")
          )
      },
      test("returns static prompt messages") {
        for
          dispatcher <- makeDispatcher(prompts = List(summaryPrompt))
          result <- dispatcher.dispatch(
            request("prompts/get", Some(Json.Obj("name" -> Json.Str("summary")))),
            None
          ).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("Please provide a summary."),
            json.contains("user")
          )
      },
      test("returns error for unknown prompt") {
        for
          dispatcher <- makeDispatcher(prompts = List(greetPrompt))
          result <- dispatcher.dispatch(
            request("prompts/get", Some(Json.Obj("name" -> Json.Str("nonexistent")))),
            None
          ).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.isLeft,
            inner.left.toOption.get.error.message.contains("not found")
          )
      },
      test("returns error for missing params") {
        for
          dispatcher <- makeDispatcher(prompts = List(greetPrompt))
          result <- dispatcher.dispatch(request("prompts/get", None), None).flatMap(_.toOption)
        yield assertTrue(result.get.isLeft)
      }
    ),
    suite("resources/templates/list")(
      test("returns empty resource templates list") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <-
            dispatcher.dispatch(request("resources/templates/list"), None).flatMap(_.toOption)
        yield
          val inner = result.get
          assertTrue(
            inner.isRight,
            inner.toOption.get.result.toString.contains("\"resourceTemplates\":[]")
          )
      }
    ),
    suite("capabilities")(
      test("advertises resources capability when resources registered") {
        for
          dispatcher <- makeDispatcher(resources = List(readmeResource))
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("\"resources\""),
            !json.contains("\"tools\""),
            !json.contains("\"prompts\"")
          )
      },
      test("advertises prompts capability when prompts registered") {
        for
          dispatcher <- makeDispatcher(prompts = List(greetPrompt))
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("\"prompts\""),
            !json.contains("\"tools\""),
            !json.contains("\"resources\"")
          )
      },
      test("advertises all capabilities when all primitives registered") {
        val echoTool = Tool("echo").description("Echo").handle { (in: String, _: ToolContext) =>
          ZIO.succeed(Content.Text(in))
        }
        val capabilities = ServerCapabilities(
          tools = Some(ToolsCapability()),
          resources = Some(ResourcesCapability()),
          prompts = Some(PromptsCapability())
        )
        for
          (dispatcher, _) <- TestLayers.makeDispatcher(
            tools = List(echoTool),
            resources = List(readmeResource),
            prompts = List(greetPrompt),
            serverInfo = serverInfo,
            capabilities = capabilities
          )
          result <- dispatcher.dispatch(request("initialize"), None).flatMap(_.toOption)
        yield
          val json = result.get.toOption.get.result.toString
          assertTrue(
            json.contains("\"tools\""),
            json.contains("\"resources\""),
            json.contains("\"prompts\"")
          )
      }
    )
  )
