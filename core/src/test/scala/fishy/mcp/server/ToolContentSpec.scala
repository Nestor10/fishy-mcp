package fishy.mcp.server

import fishy.mcp.adapters.protocol.mcp.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object ToolContentSpec extends ZIOSpecDefault:

  private def fields(json: String): Map[String, Json] =
    Json.decoder.decodeJson(json).toOption.get.asObject.get.fields.toMap

  def spec = suite("ToolContent JSON encoding")(
    test("text content encodes as flat object with type discriminator") {
      val f = fields(ToolContent.text("hello world").toJson)

      assertTrue(
        f.get("type").contains(Json.Str("text")),
        f.get("text").contains(Json.Str("hello world")),
        f.size == 2
      )
    },
    test("image content encodes as flat object with type discriminator") {
      val f = fields(ToolContent.image("base64data", "image/png").toJson)

      assertTrue(
        f.get("type").contains(Json.Str("image")),
        f.get("data").contains(Json.Str("base64data")),
        f.get("mimeType").contains(Json.Str("image/png")),
        f.size == 3
      )
    },
    test("ToolCallResult encodes content array with flat objects") {
      val resultFields = fields(ToolCallResult.success("test output").toJson)
      val contentArray = resultFields("content").asArray.get
      val item = contentArray.head.asObject.get.fields.toMap

      assertTrue(
        item.get("type").contains(Json.Str("text")),
        item.get("text").contains(Json.Str("test output")),
        item.size == 2
      )
    },
    test("ToolCallResult error encodes with isError flag") {
      val resultFields = fields(ToolCallResult.error("something failed").toJson)
      val contentArray = resultFields("content").asArray.get
      val item = contentArray.head.asObject.get.fields.toMap

      assertTrue(
        resultFields.get("isError").contains(Json.Bool(true)),
        item.get("type").contains(Json.Str("text")),
        item.get("text").contains(Json.Str("something failed"))
      )
    }
  )
