// A TsCodegenExtension, discovered by the forked codegen via ServiceLoader.
//
// The extension is a real published artifact rather than a classes dir, because
// that is how a user ships one and it is what `tsCodegenExtensions` resolves.

val codegenVersion = sys.props("plugin.version")

ThisBuild / organization := "myorg"
ThisBuild / version := codegenVersion

lazy val ext = project
  .in(file("ext"))
  .settings(
    name := "my-extension",
    // Scala 3, to match the api artifact the codegen publishes.
    scalaVersion := "3.3.8",
    libraryDependencies += "org.polyvariant" %% "smithy-ts-codegen-api" % codegenVersion,
    resolvers += Resolver.sonatypeCentralSnapshots,
  )

lazy val app = project
  .in(file("app"))
  .enablePlugins(SmithyTsCodegenPlugin)
  .settings(
    scalaVersion := "2.12.21",
    resolvers += Resolver.sonatypeCentralSnapshots,
    tsCodegenSmithyDirs := Seq(baseDirectory.value / "src" / "main" / "smithy"),
    tsCodegenOutputFile := baseDirectory.value / "target" / "generated.ts",
    // `%%` is resolved against the codegen's Scala version, not this project's
    // 2.12 — nothing on the forked classpath runs on 2.12.
    tsCodegenExtensions := Seq("myorg" %% "my-extension" % codegenVersion),
    TaskKey[Unit]("checkOutput") := {
      val f = tsCodegenOutputFile.value
      assert(f.exists, s"expected $f to exist")
      val contents = IO.read(f)
      def require(sub: String): Unit =
        assert(contents.contains(sub), s"expected generated.ts to contain: $sub")

      // The extension ran in the forked JVM: the prefix it derived from the
      // service's `version` is in the client URL...
      require(
        "const url = `/internal/v1/greet/${encodeURIComponent(String(input.name))}`"
      )
      // ...and in the mock router, as separate literal segments, so a mocked
      // route still matches the client that calls it.
      require(
        "segments: [{ literal: 'internal' }, { literal: 'v1' }, { literal: 'greet' }, { label: 'name' }],"
      )
    },
  )
