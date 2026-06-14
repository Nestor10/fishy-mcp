package fishy.mcp.server

import fishy.mcp.domain.model.Content
import fishy.mcp.domain.model.mcp.ToolCallResult
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The unified [[Content]] union is both the authoring type and the wire shape;
  * these lock the flat `{"type":...}` encoding shared by tool results and
  * sampling messages. */
object ContentSpec extends ZIOSpecDefault:

  private def fields(json: String): Map[String, Json] =
    Json.decoder.decodeJson(json).toOption.get.asObject.get.fields.toMap

  def spec = suite("Content JSON encoding")(
    test("text content encodes as flat object with type discriminator") {
      val f = fields((Content.Text("hello world"): Content).toJson)
      assertTrue(
        f.get("type").contains(Json.Str("text")),
        f.get("text").contains(Json.Str("hello world")),
        f.size == 2
      )
    },
    test("image content encodes as flat object with type discriminator") {
      val f = fields((Content.Image("base64data", "image/png"): Content).toJson)
      assertTrue(
        f.get("type").contains(Json.Str("image")),
        f.get("data").contains(Json.Str("base64data")),
        f.get("mimeType").contains(Json.Str("image/png")),
        f.size == 3
      )
    },
    test("audio content encodes with the audio discriminator") {
      val f = fields((Content.Audio("base64audio", "audio/wav"): Content).toJson)
      assertTrue(
        f.get("type").contains(Json.Str("audio")),
        f.get("data").contains(Json.Str("base64audio")),
        f.get("mimeType").contains(Json.Str("audio/wav"))
      )
    },
    test("Content round-trips through its discriminated codec") {
      val original: Content = Content.Image("xyz", "image/jpeg")
      val decoded = original.toJson.fromJson[Content]
      assertTrue(decoded == Right(original))
    },
    test("ToolCallResult encodes content array with flat objects") {
      val resultFields = fields(ToolCallResult.success("test output").toJson)
      val item = resultFields("content").asArray.get.head.asObject.get.fields.toMap
      assertTrue(
        item.get("type").contains(Json.Str("text")),
        item.get("text").contains(Json.Str("test output")),
        item.size == 2
      )
    },
    test("ToolCallResult error encodes with isError flag") {
      val resultFields = fields(ToolCallResult.error("something failed").toJson)
      val item = resultFields("content").asArray.get.head.asObject.get.fields.toMap
      assertTrue(
        resultFields.get("isError").contains(Json.Bool(true)),
        item.get("type").contains(Json.Str("text")),
        item.get("text").contains(Json.Str("something failed"))
      )
    }
  )
