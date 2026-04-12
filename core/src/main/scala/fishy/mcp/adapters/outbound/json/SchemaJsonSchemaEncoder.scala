package fishy.mcp.adapters.outbound.json

import zio.schema.Schema
import zio.schema.StandardType
import zio.json.ast.Json

/** Converts zio-schema values into MCP-compatible JSON Schema fragments. */
object SchemaJsonSchemaEncoder:

  def encode(schema: Schema[?]): Json =
    loop(Schema.force(schema))

  private def loop(schema: Schema[?]): Json =
    Schema.force(schema) match
      case primitive: Schema.Primitive[?] =>
        primitiveJsonSchema(primitive.standardType)

      case Schema.Optional(inner, _) =>
        Json.Obj(
          "anyOf" -> Json.Arr(
            loop(inner),
            Json.Obj("type" -> Json.Str("null"))
          )
        )

      case Schema.Sequence(elementSchema, _, _, _, _) =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> loop(elementSchema)
        )

      case Schema.NonEmptySequence(elementSchema, _, _, _, _) =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> loop(elementSchema),
          "minItems" -> Json.Num(1)
        )

      case Schema.Set(elementSchema, _) =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> loop(elementSchema),
          "uniqueItems" -> Json.Bool(true)
        )

      case Schema.Map(keySchema, valueSchema, _) =>
        Schema.force(keySchema) match
          case keyPrimitive: Schema.Primitive[?] if keyPrimitive.standardType.tag == "string" =>
            Json.Obj(
              "type" -> Json.Str("object"),
              "additionalProperties" -> loop(valueSchema)
            )
          case _ =>
            Json.Obj(
              "type" -> Json.Str("array"),
              "items" -> Json.Obj(
                "type" -> Json.Str("array"),
                "prefixItems" -> Json.Arr(loop(keySchema), loop(valueSchema)),
                "minItems" -> Json.Num(2),
                "maxItems" -> Json.Num(2)
              )
            )

      case Schema.NonEmptyMap(keySchema, valueSchema, _) =>
        Schema.force(keySchema) match
          case keyPrimitive: Schema.Primitive[?] if keyPrimitive.standardType.tag == "string" =>
            Json.Obj(
              "type" -> Json.Str("object"),
              "additionalProperties" -> loop(valueSchema),
              "minProperties" -> Json.Num(1)
            )
          case _ =>
            Json.Obj(
              "type" -> Json.Str("array"),
              "items" -> Json.Obj(
                "type" -> Json.Str("array"),
                "prefixItems" -> Json.Arr(loop(keySchema), loop(valueSchema)),
                "minItems" -> Json.Num(2),
                "maxItems" -> Json.Num(2)
              ),
              "minItems" -> Json.Num(1)
            )

      case tuple2: Schema.Tuple2[?, ?] =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "prefixItems" -> Json.Arr(loop(tuple2.left), loop(tuple2.right)),
          "minItems" -> Json.Num(2),
          "maxItems" -> Json.Num(2)
        )

      case either: Schema.Either[?, ?] =>
        Json.Obj(
          "oneOf" -> Json.Arr(loop(either.left), loop(either.right))
        )

      case fallback: Schema.Fallback[?, ?] =>
        Json.Obj(
          "oneOf" -> Json.Arr(loop(fallback.left), loop(fallback.right))
        )

      case record: Schema.Record[?] =>
        encodeRecord(record)

      case enumSchema: Schema.Enum[?] =>
        encodeEnum(enumSchema)

      case transform: Schema.Transform[?, ?, ?] =>
        loop(transform.schema)

      case lazySchema: Schema.Lazy[?] =>
        loop(lazySchema.schema)

      case _: Schema.Dynamic =>
        Json.Obj("type" -> Json.Str("object"))

      case _: Schema.Fail[?] =>
        Json.Obj()

  private def isOptionalField(field: Schema.Field[?, ?]): Boolean =
    field.optional || (Schema.force(field.schema) match
      case _: Schema.Optional[?] => true
      case _                     => false
    )

  private def encodeRecord(record: Schema.Record[?]): Json =
    val visibleFields = record.fields.filterNot(_.transient)
    val properties = visibleFields.map { field =>
      field.fieldName -> loop(field.schema)
    }
    val required = visibleFields.filterNot(isOptionalField).map(_.fieldName)

    val baseFields =
      List(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(properties*)
      ) ++ (if required.nonEmpty then List("required" -> Json.Arr(required.map(Json.Str(_))*))
            else Nil) ++
        (if record.rejectExtraFields then List("additionalProperties" -> Json.Bool(false)) else Nil)

    Json.Obj(baseFields*)

  private def encodeEnum(enumSchema: Schema.Enum[?]): Json =
    val visibleCases = enumSchema.cases.filterNot(_.transient)

    val allUnitCases = visibleCases.forall { caseDef =>
      Schema.force(caseDef.schema) match
        case primitive: Schema.Primitive[?] => primitive.standardType.tag == "unit"
        case _                              => false
    }

    if allUnitCases then
      Json.Obj(
        "type" -> Json.Str("string"),
        "enum" -> Json.Arr(visibleCases.map(c => Json.Str(c.caseName))*)
      )
    else
      val taggedSchemas = visibleCases.map { caseDef =>
        withTitle(loop(caseDef.schema), caseDef.caseName)
      }
      Json.Obj("oneOf" -> Json.Arr(taggedSchemas*))

  private def withTitle(schema: Json, title: String): Json =
    schema match
      case Json.Obj(fields) => Json.Obj((fields :+ ("title" -> Json.Str(title)))*)
      case other =>
        Json.Obj(
          "title" -> Json.Str(title),
          "allOf" -> Json.Arr(other)
        )

  private def primitiveJsonSchema(standardType: StandardType[?]): Json =
    standardType.tag match
      case "string" | "char" => Json.Obj("type" -> Json.Str("string"))
      case "boolean"         => Json.Obj("type" -> Json.Str("boolean"))
      case "byte" | "short" | "int" | "long" | "bigInteger" =>
        Json.Obj("type" -> Json.Str("integer"))
      case "float" | "double" | "bigDecimal" =>
        Json.Obj("type" -> Json.Str("number"))
      case "binary" =>
        Json.Obj(
          "type" -> Json.Str("string"),
          "contentEncoding" -> Json.Str("base64")
        )
      case "unit" =>
        Json.Obj("type" -> Json.Str("null"))
      case "uuid" =>
        Json.Obj(
          "type" -> Json.Str("string"),
          "format" -> Json.Str("uuid")
        )
      case "instant" | "offsetDateTime" | "zonedDateTime" =>
        Json.Obj(
          "type" -> Json.Str("string"),
          "format" -> Json.Str("date-time")
        )
      case "localDate" =>
        Json.Obj(
          "type" -> Json.Str("string"),
          "format" -> Json.Str("date")
        )
      case "localTime" | "offsetTime" =>
        Json.Obj(
          "type" -> Json.Str("string"),
          "format" -> Json.Str("time")
        )
      case _ =>
        Json.Obj("type" -> Json.Str("string"))
