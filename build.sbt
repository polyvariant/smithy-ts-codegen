ThisBuild / tlBaseVersion := "0.2"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v")),
)

val scala3 = "3.3.8"
val scala212 = "2.12.20"

ThisBuild / scalaVersion := scala3
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / tlFatalWarnings := false
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

ThisBuild / mergifyStewardConfig ~= (_.map(_.withMergeMinors(true)))

// Run the sbt plugin's scripted tests in CI (they aren't part of `test`).
ThisBuild / githubWorkflowBuildPostamble += WorkflowStep.Sbt(
  List("sbtPlugin/scripted"),
  name = Some("sbt plugin scripted tests"),
  cond = Some("matrix.project == 'rootJVM' && matrix.java == 'temurin@11'"),
)

val smithyVersion = "1.71.0"
val alloyVersion = "0.3.39"
val smithy4sVersion = "0.19.8"

val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-no-indent",
    "-Wunused:all",
  )
)

// A standalone smithy-build plugin that emits a single `generated.ts` of zod
// schemas + TypeScript types + typed HTTP clients (and Storybook mock stubs)
// for a `simpleRestJson` smithy model. JVM-only: it is discovered by
// smithy-build via the SPI file under `META-INF/services`.
lazy val core = project
  .in(file("core"))
  .settings(
    name := "smithy-ts-codegen",
    commonSettings,
    libraryDependencies ++= Seq(
      "software.amazon.smithy" % "smithy-build" % smithyVersion,
      "software.amazon.smithy" % "smithy-codegen-core" % smithyVersion,
      "software.amazon.smithy" % "smithy-model" % smithyVersion,
      "com.disneystreaming.alloy" % "alloy-core" % alloyVersion,
      "com.disneystreaming.smithy4s" % "smithy4s-protocol" % smithy4sVersion,
      "org.scalameta" %% "munit" % "1.2.0" % Test,
    ),
  )

// A thin Scala 3 CLI wrapping [[core]]: assembles a smithy model from source
// dirs and runs the codegen, writing the TypeScript file. Published as
// `smithy-ts-codegen-cli` so the sbt plugin can resolve + fork it.
lazy val cli = project
  .in(file("cli"))
  .dependsOn(core)
  .settings(
    name := "smithy-ts-codegen-cli",
    commonSettings,
  )

// An sbt AutoPlugin that exposes a `tsCodegen` task. It resolves the published
// `smithy-ts-codegen-cli` artifact (at this plugin's own version, baked in via
// BuildInfo) with coursier — an isolated resolver, so the consuming project's
// Scala version can't shadow the CLI's Scala 3 runtime — then forks a JVM to
// run it. sbt 1.x plugins are Scala 2.12; the plugin never depends on the CLI
// at compile time, only resolves + forks it, so the Scala 3 / 2.12 split is fine.
lazy val sbtPlugin = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin, BuildInfoPlugin)
  .settings(
    name := "sbt-smithy-ts-codegen",
    scalaVersion := scala212,
    crossScalaVersions := Seq(scala212),
    libraryDependencies += "io.get-coursier" % "interface" % "1.0.28",
    buildInfoPackage := "org.polyvariant.smithy.ts.sbt",
    buildInfoKeys := Seq[BuildInfoKey](
      "smithyTsCodegenVersion" -> version.value,
      "smithyTsCodegenScalaBinaryVersion" -> (cli / scalaBinaryVersion).value,
      "smithyTsCodegenOrganization" -> organization.value,
    ),
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false,
    // Scripted resolves the CLI artifact at the plugin's version from the local
    // Ivy repo, so publish it (and its `core` dep) there first.
    scripted := scripted.dependsOn(cli / publishLocal, core / publishLocal).evaluated,
  )

lazy val root = tlCrossRootProject.aggregate(core, cli, sbtPlugin)
