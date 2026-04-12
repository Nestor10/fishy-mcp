package fishy.mcp.dsl

import fishy.mcp.domain.model.{Resource as CoreResource, ResourceContent, ResourceError}
import zio.*

/** User-facing DSL for defining MCP resources. */
object Resource:

  /** Create a static text resource. */
  def text(
      uri: String,
      name: String,
      description: String,
      mimeType: String = "text/plain"
  )(content: String): CoreResource[Any] =
    val rUri = uri
    val rName = name
    val rDesc = description
    val rMime = mimeType

    new CoreResource[Any]:
      def uri: String = rUri
      def name: String = rName
      def description: String = rDesc
      def mimeType: Option[String] = Some(rMime)
      def read: UIO[List[ResourceContent]] =
        ZIO.succeed(List(ResourceContent.Text(rUri, Some(rMime), content)))

  /** Create a dynamic text resource backed by an effect. */
  def textEffect[R](
      uri: String,
      name: String,
      description: String,
      mimeType: String = "text/plain"
  )(f: => ZIO[R, ResourceError, String]): CoreResource[R] =
    val rUri = uri
    val rName = name
    val rDesc = description
    val rMime = mimeType

    new CoreResource[R]:
      def uri: String = rUri
      def name: String = rName
      def description: String = rDesc
      def mimeType: Option[String] = Some(rMime)
      def read: ZIO[R, ResourceError, List[ResourceContent]] =
        f.map(text => List(ResourceContent.Text(rUri, Some(rMime), text)))

  /** Create a static binary resource (base64-encoded). */
  def blob(
      uri: String,
      name: String,
      description: String,
      mimeType: String
  )(data: String): CoreResource[Any] =
    val rUri = uri
    val rName = name
    val rDesc = description
    val rMime = mimeType

    new CoreResource[Any]:
      def uri: String = rUri
      def name: String = rName
      def description: String = rDesc
      def mimeType: Option[String] = Some(rMime)
      def read: UIO[List[ResourceContent]] =
        ZIO.succeed(List(ResourceContent.Blob(rUri, Some(rMime), data)))
