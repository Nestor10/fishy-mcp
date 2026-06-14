import _root_.scalafix.sbt.ScalafixPlugin.autoImport._

val scala3Version = "3.8.2"
val zioVersion = "2.1.24"
val zioHttpVersion = "3.9.0"
val zioJsonVersion = "0.9.0"
val zioSchemaVersion = "1.8.3"
val zioLoggingVersion = "2.5.3"
val zioRedisVersion = "1.2.0"
val zioOpenTelemetryVersion = "3.1.15"
val openTelemetryVersion = "1.42.1"

inThisBuild(
  List(
    organization := "io.github.nestor10",
    versionScheme := Some("early-semver"),
    homepage := Some(url("https://github.com/Nestor10/fishy-mcp")),
    licenses := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        "Nestor10",
        "Eric Smith",
        "ericsmith.lpi@gmail.com",
        url("https://github.com/Nestor10")
      )
    )
  )
)

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
addCommandAlias("fix", ";scalafixAll")
addCommandAlias("lint", ";scalafixAll --check")

// Common settings shared across projects
lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-Werror",
    "-language:implicitConversions"
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

// Assembly settings for fat JARs
lazy val assemblySettings = Seq(
  assembly / assemblyJarName := s"${name.value}-${version.value}.jar",
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
    case PathList("META-INF", "services", _*) => MergeStrategy.concat
    case PathList("META-INF", _*)             => MergeStrategy.discard
    case "reference.conf"                     => MergeStrategy.concat
    case _                                    => MergeStrategy.first
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(core, oauth, mcpOauth, example, integration)
  .settings(
    name := "fishy-mcp-root",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "fishy-mcp",
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % zioVersion,

      // HTTP Server
      "dev.zio" %% "zio-http" % zioHttpVersion,

      // JSON & Schema
      "dev.zio" %% "zio-json" % zioJsonVersion,
      "dev.zio" %% "zio-schema" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-json" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,

      // Streaming
      "dev.zio" %% "zio-streams" % zioVersion,

      // Redis
      "dev.zio" %% "zio-redis" % zioRedisVersion,

      // Logging (structured JSON via zio-logging)
      "dev.zio" %% "zio-logging" % zioLoggingVersion,

      // Distributed tracing (OpenTelemetry via zio-opentelemetry)
      "dev.zio" %% "zio-opentelemetry" % zioOpenTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-sdk" % openTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion,

      // JWT / JWKS signature verification
      "com.nimbusds" % "nimbus-jose-jwt" % "9.37.3",

      // Testing
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    )
  )

// Standalone OAuth 2.1 / OIDC authorization server. Knows NOTHING about MCP:
// it depends on no fishy module, exposes plain zio-http `Routes` + its port
// set, and is usable by any zio-http app. This is the reusable artifact; the
// MCP wiring lives in `mcp-oauth` below.
lazy val oauth = project
  .in(file("oauth"))
  .settings(commonSettings)
  .settings(
    name := "fishy-oauth",
    // Flip to false to publish the standalone AS once it's validated downstream.
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion,
      // JWT / JWKS signing + verification
      "com.nimbusds" % "nimbus-jose-jwt" % "9.37.3",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    )
  )

// Thin glue binding the standalone OAuth AS to the MCP SDK: mounts the AS
// routes at core's `HttpExtraRoutes` seam, adds the `MCPServer.withOAuth*`
// builder extension, and runs the production stub-audit against the
// deployment profile. dependsOn(core, oauth); core itself stays OAuth-free.
lazy val mcpOauth = project
  .in(file("mcp-oauth"))
  .dependsOn(core, oauth)
  .settings(commonSettings)
  .settings(
    name := "fishy-mcp-oauth",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    )
  )

lazy val example = project
  .in(file("example"))
  .dependsOn(core)
  .enablePlugins(AssemblyPlugin, JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(assemblySettings)
  .settings(
    name := "fishy-mcp-example",
    publish / skip := true,
    Compile / mainClass := Some("fishy.mcp.example.SimpleServer"),
    assembly / mainClass := Some("fishy.mcp.example.SimpleServer"),
    Docker / packageName := "fishy-mcp-example",
    Docker / version := "latest",
    dockerBaseImage := "eclipse-temurin:21-jre-alpine",
    dockerExposedPorts := Seq(8889),
    dockerExecCommand := Seq("podman")
  )

lazy val integration = project
  .in(file("integration"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "fishy-mcp-integration",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
      "org.testcontainers" % "testcontainers" % "1.20.4" % Test
    )
  )
