package fishy.mcp.example

import fishy.mcp.dsl.*
import fishy.mcp.domain.model.{Content, PromptArgument, PromptMessage, ToolContext}
import zio.*
import zio.schema.Schema
import zio.schema.derived

/** A simple example MCP server for testing.
  *
  * Provides basic tools: echo, add, time, and greet.
  */
object SimpleServer extends ZIOAppDefault:

  final case class EchoInput(message: String) derives Schema

  final case class AddInput(a: Int, b: Int) derives Schema

  final case class GreetInput(name: String) derives Schema

  final case class TenParamInput(
      name: String,
      age: Int,
      score: Double,
      active: Boolean,
      tags: List[String],
      flags: List[Boolean],
      retries: Int,
      region: String,
      ratio: Double,
      note: String
  ) derives Schema

  // Echo tool - returns the input
  val echo = Tool("echo")
    .description("Echoes the input message back")
    .handle { (input: EchoInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"You said: ${input.message}"))
    }

  // Add tool - adds two numbers
  val add = Tool("add")
    .description("Adds two numbers together")
    .handle { (input: AddInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"${input.a} + ${input.b} = ${input.a + input.b}"))
    }

  // Time tool - returns current time
  val time = Tool("time")
    .description("Returns the current server time")
    .noInput {
      ZIO.succeed(java.time.Instant.now().toString)
    }

  // Greet tool - personalized greeting
  val greet = Tool("greet")
    .description("Returns a personalized greeting")
    .handle { (input: GreetInput, _: ToolContext) =>
      ZIO.succeed(Content.Text(s"Hello, ${input.name}! Welcome to fishy-mcp."))
    }

  val tenParam = Tool("ten_param_demo")
    .description("Demonstrates a tool with ten typed fields, including a boolean array")
    .handle { (input: TenParamInput, _: ToolContext) =>
      val enabledFlags = input.flags.count(identity)
      ZIO.succeed(Content.Text(
        s"name=${input.name}, age=${input.age}, score=${input.score}, active=${input.active}, " +
          s"tags=${input.tags.mkString("[", ", ", "]")}, enabledFlags=$enabledFlags/${input.flags.size}, " +
          s"retries=${input.retries}, region=${input.region}, ratio=${input.ratio}, note=${input.note}"
      ))
    }

  // Demo resource - static project README
  val readme = Resource.text(
    "file:///readme.md",
    "readme",
    "Project README file",
    "text/markdown"
  )("# fishy-mcp Example\n\nA simple MCP server built with fishy-mcp and ZIO.")

  // Demo resource - dynamic server status
  val status = Resource.textEffect[Any](
    "server:///status",
    "status",
    "Current server status"
  )(ZIO.succeed(s"Server is running. Current time: ${java.time.Instant.now()}"))

  // Demo prompt - code review
  val codeReview = Prompt(
    "code-review",
    "Review code and provide feedback",
    List(
      PromptArgument("code", "The code to review", required = true),
      PromptArgument("language", "Programming language", required = false)
    )
  ) { args =>
    val code = args.getOrElse("code", "")
    val lang = args.getOrElse("language", "unknown")
    ZIO.succeed(List(
      PromptMessage(
        "user",
        s"Please review the following $lang code and provide feedback:\n\n```$lang\n$code\n```"
      )
    ))
  }

  // Demo prompt - summarize
  val summarize = Prompt.static("summarize", "Summarize the given text")(
    List(PromptMessage("user", "Please provide a concise summary of the text I will share."))
  )

  // Build and run the server
  val server = MCPServer
    .withName("fishy-example")
    .withVersion("0.1.0")
    .withTools(echo, add, time, greet, tenParam)
    .withResources(readme, status)
    .withPrompts(codeReview, summarize)

  def run = server.serveHttp
