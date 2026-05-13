package fishy.mcp.server

import fishy.mcp.application.ports.*
import fishy.mcp.application.usecase.*
import fishy.mcp.adapters.storage.InMemorySubscriptionRegistry
import fishy.mcp.bootstrap.TracingLayers
import fishy.mcp.domain.model.*
import fishy.mcp.domain.model.mcp.*
import zio.*
import zio.stream.ZStream

/** Shared test support: stub session store and layer helpers for constructing service instances
  * without exposing private Live implementations.
  */
object TestLayers:

  val stubSessionStore: ULayer[SessionStore] = ZLayer.fromZIO {
    for
      sessions <- Ref.make(Map.empty[String, Boolean])
      initialized <- Ref.make(Set.empty[String])
    yield new SessionStore:
      def create(): UIO[String] =
        val id = java.util.UUID.randomUUID().toString
        sessions.update(_ + (id -> true)).as(id)
      def exists(sessionId: String): UIO[Boolean] = sessions.get.map(_.contains(sessionId))
      def remove(sessionId: String): UIO[Unit] = sessions.update(_ - sessionId)
      def allSessionIds: UIO[List[String]] = sessions.get.map(_.keys.toList)
      def markInitialized(sessionId: String): UIO[Unit] = initialized.update(_ + sessionId)
      def isInitialized(sessionId: String): UIO[Boolean] =
        initialized.get.map(_.contains(sessionId))
  }

  val stubMessageRouter: ULayer[MessageRouter] = ZLayer.succeed {
    new MessageRouter:
      def publish(sessionId: String, message: String): UIO[Boolean] = ZIO.succeed(false)
      def subscribe(sessionId: String): ZIO[Scope, Nothing, ZStream[Any, Nothing, String]] =
        ZIO.succeed(ZStream.empty)
      def hasSubscribers(sessionId: String): UIO[Boolean] = ZIO.succeed(false)
      def removeSession(sessionId: String): UIO[Unit] = ZIO.unit
  }

  def dispatcherLayer(
      serverInfo: ServerInfo = ServerInfo("test-server", "1.0.0"),
      capabilities: ServerCapabilities = ServerCapabilities.toolsOnly,
      tools: List[Tool[Any]] = Nil,
      resources: List[Resource[Any]] = Nil,
      prompts: List[Prompt[Any]] = Nil
  ): ULayer[McpDispatcher & SessionStore] =
    ZLayer.make[McpDispatcher & SessionStore](
      ToolRegistry.layer(tools),
      ResourceRegistry.layer(resources),
      PromptRegistry.layer(prompts),
      ToolExecutor.layer,
      ResourceExecutor.layer,
      PromptExecutor.layer,
      McpDispatcher.layer(serverInfo, capabilities),
      ClientRequester.layer,
      NotificationSender.layer,
      stubSessionStore,
      stubMessageRouter,
      InMemorySubscriptionRegistry.layer,
      TracingLayers.noop
    )

  def makeDispatcher(
      tools: List[Tool[Any]] = Nil,
      resources: List[Resource[Any]] = Nil,
      prompts: List[Prompt[Any]] = Nil,
      serverInfo: ServerInfo = ServerInfo("test-server", "1.0.0"),
      capabilities: ServerCapabilities = ServerCapabilities.toolsOnly
  ): UIO[(McpDispatcher, SessionStore)] =
    ZIO.scoped {
      dispatcherLayer(serverInfo, capabilities, tools, resources, prompts)
        .build
        .map(env => (env.get[McpDispatcher], env.get[SessionStore]))
    }

  def makeRegistry(tools: List[Tool[Any]]): UIO[ToolRegistry] =
    ZIO.scoped(ToolRegistry.layer(tools).build.map(_.get[ToolRegistry]))
