ThisBuild / tlBaseVersion := "0.3"
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

// The generated TypeScript is committed (typecheck/src/generated.ts) and
// type-checked by `nix flake check`. Fail the build if it no longer matches
// what the codegen produces for typecheck/model.smithy — otherwise the tsc run
// would be checking a stale file.
// `project /` first: these tasks live on the root build, not on the rootJVM
// aggregate the surrounding steps have selected.
ThisBuild / githubWorkflowBuildPostamble += WorkflowStep.Sbt(
  List("project /", "tsCodegenSampleCheck"),
  name = Some("Committed sample TypeScript is up to date"),
  cond = Some("matrix.project == 'rootJVM' && matrix.java == 'temurin@11'"),
)

// Type-check the generated TypeScript with the real `tsc` (see nix/typecheck.nix).
// The Scala tests only assert on substrings, so this is what catches an actual
// type error in the emitted file.
ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("cachix", "install-nix-action", "v31"),
    name = Some("Install nix"),
    cond = Some("matrix.project == 'rootJVM' && matrix.java == 'temurin@11'"),
  ),
  WorkflowStep.Run(
    List("nix flake check --print-build-logs"),
    name = Some("Typecheck the generated TypeScript"),
    cond = Some("matrix.project == 'rootJVM' && matrix.java == 'temurin@11'"),
  ),
)

val smithyVersion = "1.71.0"
val alloyVersion = "0.3.40"
val smithy4sVersion = "0.19.8"
val smithy4sNdjsonVersion = "0.1.1"

val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-no-indent",
    "-Wunused:all",
  )
)

// The traits that control codegen, as a plain smithy model under
// `META-INF/smithy` — no Scala code. Kept separate from [[core]] so a model can
// depend on the trait definitions (to `apply` them) without pulling the
// generator, its smithy-build machinery and its transitive deps onto the
// model's classpath.
lazy val traits = project
  .in(file("traits"))
  .settings(
    name := "smithy-ts-codegen-traits",
    commonSettings,
    // Resources only; nothing to compile, and no Scala artifact to cross-build.
    autoScalaLibrary := false,
    crossPaths := false,
    // No previous release to compare against, and no classfiles to compare
    // even once there is one — the artifact is a smithy model.
    tlMimaPreviousVersions := Set.empty,
  )

// A standalone smithy-build plugin that emits a single `generated.ts` of zod
// schemas + TypeScript types + typed HTTP clients (and Storybook mock stubs)
// for a `simpleRestJson` smithy model. JVM-only: it is discovered by
// smithy-build via the SPI file under `META-INF/services`.
lazy val core = project
  .in(file("core"))
  .dependsOn(traits)
  .settings(
    name := "smithy-ts-codegen",
    commonSettings,
    libraryDependencies ++= Seq(
      "software.amazon.smithy" % "smithy-build" % smithyVersion,
      "software.amazon.smithy" % "smithy-codegen-core" % smithyVersion,
      "software.amazon.smithy" % "smithy-model" % smithyVersion,
      "com.disneystreaming.alloy" % "alloy-core" % alloyVersion,
      "com.disneystreaming.smithy4s" % "smithy4s-protocol" % smithy4sVersion,
      // Only the protocol module — the `@ndjsonRestJson` trait definition. The
      // codegen keys off `@streaming` members, so nothing scala-specific from
      // smithy4s-ndjson is needed here.
      "org.polyvariant" % "smithy4s-ndjson-protocol" % smithy4sNdjsonVersion,
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
    // Ivy repo, so publish it and everything it depends on there first — `core`,
    // and `traits` for the trait definitions `core` resolves the model against.
    scripted := scripted
      .dependsOn(cli / publishLocal, core / publishLocal, traits / publishLocal)
      .evaluated,
  )

// The generated TypeScript that `nix flake check` type-checks. It is committed
// (typecheck/src/generated.ts) so the tsc run is hermetic — no JVM in the nix
// sandbox — and so a change to the emitted output shows up in review.
//
//   sbt tsCodegenSample      regenerate it
//   sbt tsCodegenSampleCheck fail if it differs from what is committed (CI)
lazy val tsCodegenSampleFile =
  settingKey[File]("Where the committed sample TypeScript lives")
lazy val tsCodegenSample = taskKey[Unit]("Regenerate the committed sample TypeScript")
lazy val tsCodegenSampleCheck =
  taskKey[Unit]("Check the committed sample TypeScript is up to date")

ThisBuild / tsCodegenSampleFile := (ThisBuild / baseDirectory).value / "typecheck" / "src" / "generated.ts"

tsCodegenSample := {
  // `runMain` is an InputTask, so its argument string has to be built outside
  // the task body (sbt's macro can't close over a val bound in here).
  val _ = sampleCodegenRun.value
  streams.value.log.info(s"wrote ${tsCodegenSampleFile.value}")
}

lazy val sampleCodegenRun = taskKey[Unit]("Run the sample codegen")

sampleCodegenRun := Def.taskDyn {
  val model = (ThisBuild / baseDirectory).value / "typecheck" / "model.smithy"
  val out = tsCodegenSampleFile.value
  (core / Compile / runMain).toTask(
    s" org.polyvariant.smithy.ts.SampleCodegen $model $out"
  )
}.value

tsCodegenSampleCheck := {
  val out = tsCodegenSampleFile.value
  val before =
    if (out.exists)
      IO.read(out)
    else
      ""
  val _ = sampleCodegenRun.value
  val after = IO.read(out)
  if (before != after)
    sys.error(
      s"$out is out of date — run `sbt tsCodegenSample` and commit the result"
    )
}

lazy val root = tlCrossRootProject.aggregate(traits, core, cli, sbtPlugin)
