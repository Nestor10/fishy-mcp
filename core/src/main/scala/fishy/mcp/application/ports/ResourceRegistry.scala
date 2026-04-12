package fishy.mcp.application.ports

import fishy.mcp.domain.model.*
import zio.*

/** Service for managing and reading resources.
  *
  * R is erased at this boundary.
  */
trait ResourceRegistry:

  /** List metadata of all registered resources. */
  def list: UIO[List[ResourceInfo]]

  /** Read a resource by URI. */
  def read(uri: String): IO[ResourceError, List[ResourceContent]]

object ResourceRegistry:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def list: URIO[ResourceRegistry, List[ResourceInfo]] =
    ZIO.serviceWithZIO[ResourceRegistry](_.list)

  def read(uri: String) =
    ZIO.serviceWithZIO[ResourceRegistry](_.read(uri))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  def layer[R](resources: List[Resource[R]]): ZLayer[R, Nothing, ResourceRegistry] =
    ZLayer.fromZIO {
      ZIO.environment[R].map(env => Live(resources, env): ResourceRegistry)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live[R](resources: List[Resource[R]], env: ZEnvironment[R])
      extends ResourceRegistry:

    private val resourceMap: Map[String, Resource[R]] =
      resources.map(r => r.uri -> r).toMap

    private val resourceInfos: List[ResourceInfo] =
      resources.map(r => ResourceInfo(r.uri, r.name, r.description, r.mimeType))

    def list: UIO[List[ResourceInfo]] =
      ZIO.succeed(resourceInfos)

    def read(uri: String): IO[ResourceError, List[ResourceContent]] =
      resourceMap.get(uri) match
        case None           => ZIO.fail(ResourceError.NotFound(uri))
        case Some(resource) => resource.read.provideEnvironment(env)
