package fishy.mcp.dsl

import fishy.mcp.adapters.outbound.json.SchemaJsonSchemaEncoder
import fishy.mcp.domain.model.{Content, Tool as CoreTool, ToolContext, ToolError}
import zio.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.{JsonCodec as SchemaJsonCodec}

object Tool:

  def apply(name: String): Builder = Builder(name, None, Set.empty)

  final case class Builder private[Tool] (
      private val toolName: String,
      private val maybeDescription: Option[String],
      private val scopes: Set[String]
  ):

    def description(value: String): Builder =
      copy(maybeDescription = Some(value))

    def requireScopes(scope: String, more: String*): Builder =
      copy(scopes = scopes ++ (scope +: more))

    def handle[R, I: Schema](f: (I, ToolContext) => ZIO[R, ToolError, Content]): CoreTool[R] =
      Tool.handle(toolName, desc, scopes)(f)

    @deprecated("Use .handle instead", "0.2.0")
    def text[R, I: Schema](f: I => ZIO[R, ToolError, String]): CoreTool[R] =
      Tool.text(toolName, desc, scopes)(f)

    @deprecated("Use .handle instead", "0.2.0")
    def content[R, I: Schema](f: I => ZIO[R, ToolError, Content]): CoreTool[R] =
      Tool.content(toolName, desc, scopes)(f)

    @deprecated("Use .handle instead", "0.2.0")
    def pure[I: Schema](f: I => String): CoreTool[Any] =
      Tool.pure(toolName, desc, scopes)(f)

    def noInput[R](f: => ZIO[R, ToolError, String]): CoreTool[R] =
      Tool.noInput(toolName, desc, scopes)(f)

    def noInput[R](f: ToolContext => ZIO[R, ToolError, String]): CoreTool[R] =
      Tool.noInputCtx(toolName, desc, scopes)(f)

    private def desc: String = maybeDescription.getOrElse("")

  def handle[R, I: Schema](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: (I, ToolContext) => ZIO[R, ToolError, Content]): CoreTool[R] =
    val schema = summon[Schema[I]]
    val jsonSchema = SchemaJsonSchemaEncoder.encode(schema) match
      case obj @ Json.Obj(fields)
          if fields.exists { case ("type", Json.Str("object")) => true; case _ => false } => obj
      case _ => Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj())
    val decoder = SchemaJsonCodec.jsonDecoder(schema)
    val tName = name
    val tDesc = description
    val tScopes = scopes

    new CoreTool[R]:
      def name: String = tName
      def description: String = tDesc
      def inputJsonSchema: Json = jsonSchema
      override def requiredScopes: Set[String] = tScopes

      def execute(input: Json, ctx: ToolContext): ZIO[R, ToolError, Content] =
        for
          decoded <- ZIO
            .fromEither(decoder.decodeJson(input.toString))
            .mapError(err => ToolError.InvalidInput("arguments", err))
          result <- f(decoded, ctx)
        yield result

  @deprecated("Use Tool.handle instead", "0.2.0")
  def apply[R, I: Schema](
      name: String,
      description: String
  )(f: I => ZIO[R, ToolError, String]): CoreTool[R] =
    text(name, description)(f)

  @deprecated("Use Tool.handle instead", "0.2.0")
  def text[R, I: Schema](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: I => ZIO[R, ToolError, String]): CoreTool[R] =
    content(name, description, scopes) { (input: I) =>
      f(input).map(Content.Text(_))
    }

  @deprecated("Use Tool.handle instead", "0.2.0")
  def pure[I: Schema](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: I => String): CoreTool[Any] =
    text(name, description, scopes) { (input: I) =>
      ZIO
        .attempt(f(input))
        .mapError(t =>
          ToolError.ExecutionFailed(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
        )
    }

  @deprecated("Use Tool.handle instead", "0.2.0")
  def content[R, I: Schema](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: I => ZIO[R, ToolError, Content]): CoreTool[R] =
    handle(name, description, scopes) { (input: I, _: ToolContext) =>
      f(input)
    }

  def noInput[R](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: => ZIO[R, ToolError, String]): CoreTool[R] =
    val tName = name
    val tDesc = description
    val tScopes = scopes

    new CoreTool[R]:
      def name: String = tName
      def description: String = tDesc
      def inputJsonSchema: Json =
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj()
        )
      override def requiredScopes: Set[String] = tScopes

      def execute(input: Json, ctx: ToolContext): ZIO[R, ToolError, Content] =
        f.map(Content.Text(_))

  def noInputCtx[R](
      name: String,
      description: String,
      scopes: Set[String] = Set.empty
  )(f: ToolContext => ZIO[R, ToolError, String]): CoreTool[R] =
    val tName = name
    val tDesc = description
    val tScopes = scopes

    new CoreTool[R]:
      def name: String = tName
      def description: String = tDesc
      def inputJsonSchema: Json =
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj()
        )
      override def requiredScopes: Set[String] = tScopes

      def execute(input: Json, ctx: ToolContext): ZIO[R, ToolError, Content] =
        f(ctx).map(Content.Text(_))
