package fishy.mcp.dsl

import fishy.mcp.bootstrap
import fishy.mcp.domain.model.{Prompt, Resource, Tool}
import scala.annotation.targetName
import scala.util.NotGiven

export bootstrap.MCPApp

/** Facade keeping `fishy.mcp.dsl.MCPServer` as the user import path while the implementation lives
  * in bootstrap.
  */
object MCPServer:

  def apply(): bootstrap.MCPServer[Any] =
    bootstrap.MCPServer()

  @targetName("withAnyToolsDsl")
  def withTools(tools: Tool[Any]*): bootstrap.MCPServer[Any] =
    bootstrap.MCPServer.withTools(tools*)

  def withTools[R](tools: Tool[R]*): bootstrap.MCPServer[R] =
    bootstrap.MCPServer.withTools(tools*)

  /** Create a new MCPServer builder with a name. */
  def withName(name: String): bootstrap.MCPServer[Any] =
    bootstrap.MCPServer.withName(name)

  /** Create a new MCPServer builder with initial resources. */
  @targetName("withAnyResourcesDsl")
  def withResources(resources: Resource[Any]*): bootstrap.MCPServer[Any] =
    bootstrap.MCPServer().withResources(resources*)

  /** Create a new MCPServer builder with environment-aware resources. */
  def withResources[R](resources: Resource[R]*): bootstrap.MCPServer[R] =
    bootstrap.MCPServer().withResources(resources*)

  /** Create a new MCPServer builder with initial prompts. */
  @targetName("withAnyPromptsDsl")
  def withPrompts(prompts: Prompt[Any]*): bootstrap.MCPServer[Any] =
    bootstrap.MCPServer().withPrompts(prompts*)

  /** Create a new MCPServer builder with environment-aware prompts. */
  def withPrompts[R](prompts: Prompt[R]*): bootstrap.MCPServer[R] =
    bootstrap.MCPServer().withPrompts(prompts*)
