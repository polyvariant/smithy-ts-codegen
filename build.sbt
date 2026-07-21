ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v")),
)

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / tlFatalWarnings := false
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

ThisBuild / mergifyStewardConfig ~= (_.map(_.withMergeMinors(true)))

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

lazy val root = tlCrossRootProject.aggregate(core)
