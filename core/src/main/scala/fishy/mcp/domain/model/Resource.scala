package fishy.mcp.domain.model

import zio.*

/** A resource that can be read via MCP, identified by URI. */
trait Resource[-R]:
  def uri: String
  def name: String
  def description: String
  def mimeType: Option[String]
  def read: ZIO[R, ResourceError, List[ResourceContent]]

/** Resource metadata without the environment type parameter. */
final case class ResourceInfo(
    uri: String,
    name: String,
    description: String,
    mimeType: Option[String]
)

sealed trait ResourceContent

object ResourceContent:
  final case class Text(uri: String, mimeType: Option[String], text: String) extends ResourceContent
  final case class Blob(uri: String, mimeType: Option[String], blob: String) extends ResourceContent
