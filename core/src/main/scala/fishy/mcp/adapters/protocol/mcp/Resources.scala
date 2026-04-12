package fishy.mcp.adapters.protocol.mcp

import zio.json.*
import zio.json.ast.Json

/** MCP Resources method types.
  *
  * Wire format for resources/list and resources/read requests and responses.
  */

// ---------------------------------------------------------------------------
// resources/list
// ---------------------------------------------------------------------------

/** Resource definition returned by resources/list. */
final case class ResourceDefinition(
    uri: String,
    name: String,
    description: Option[String] = None,
    mimeType: Option[String] = None
)

object ResourceDefinition:
  given JsonDecoder[ResourceDefinition] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourceDefinition] = DeriveJsonEncoder.gen

/** Result of resources/list method. */
final case class ResourcesListResult(
    resources: List[ResourceDefinition]
)

object ResourcesListResult:
  given JsonDecoder[ResourcesListResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourcesListResult] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// resources/read
// ---------------------------------------------------------------------------

/** Parameters for resources/read method. */
final case class ResourceReadParams(uri: String)

object ResourceReadParams:
  given JsonDecoder[ResourceReadParams] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourceReadParams] = DeriveJsonEncoder.gen

/** A single content item returned by resources/read. */
final case class ResourceContentItem(
    uri: String,
    mimeType: Option[String] = None,
    text: Option[String] = None,
    blob: Option[String] = None
)

object ResourceContentItem:
  given JsonDecoder[ResourceContentItem] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourceContentItem] = DeriveJsonEncoder.gen

/** Result of resources/read method. */
final case class ResourceReadResult(
    contents: List[ResourceContentItem]
)

object ResourceReadResult:
  given JsonDecoder[ResourceReadResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourceReadResult] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// resources/templates/list
// ---------------------------------------------------------------------------

/** Result of resources/templates/list method. */
final case class ResourceTemplatesListResult(
    resourceTemplates: List[ResourceDefinition]
)

object ResourceTemplatesListResult:
  given JsonDecoder[ResourceTemplatesListResult] = DeriveJsonDecoder.gen
  given JsonEncoder[ResourceTemplatesListResult] = DeriveJsonEncoder.gen
