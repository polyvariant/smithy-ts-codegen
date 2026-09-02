/*
 * Copyright 2026 Polyvariant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polyvariant.smithy.ts.sbt

import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.IvyRepository
import coursierapi.MavenRepository
import sbt._
import sbt.Keys._

import java.io.File

import scala.collection.JavaConverters._

/** Drives the `smithy-ts-codegen` smithy-build plugin from sbt.
  *
  * The plugin does not depend on the codegen at compile time. It resolves the published
  * `smithy-ts-codegen-cli` artifact (at this plugin's own version, baked in via `BuildInfo`) with
  * coursier — a resolver isolated from the enclosing project, so the project's Scala version can't
  * shadow the CLI's Scala 3 runtime — and runs `org.polyvariant.smithy.ts.cli.Main` in a forked
  * JVM. That keeps the (Scala 3) codegen and the (Scala 2.12) sbt plugin fully decoupled.
  *
  * Enable it on a project and point it at your smithy sources:
  *
  * {{{
  * enablePlugins(SmithyTsCodegenPlugin)
  *
  * tsCodegenSmithyDirs := Seq(baseDirectory.value / "src" / "main" / "smithy")
  * tsCodegenOutputFile := baseDirectory.value / "generated.ts"
  * tsCodegenExcludeServices := Seq("myorg.auth#AuthService")
  * tsCodegenPathPrefix := "/internal/v1"
  * }}}
  *
  * then run `tsCodegen`.
  */
object SmithyTsCodegenPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin

  object autoImport {

    val tsCodegen =
      taskKey[File]("Generate the TypeScript file from the smithy model and return it")

    val tsCodegenSmithyDirs =
      settingKey[Seq[File]]("Directories scanned for *.smithy / *.json model sources")

    val tsCodegenOutputFile =
      settingKey[File]("Destination path for the generated TypeScript file")

    val tsCodegenExcludeServices = settingKey[Seq[String]](
      "Fully-qualified service shape ids (namespace#Name) to omit clients for; " +
        "their referenced data shapes are still emitted"
    )

    val tsCodegenPathPrefix = settingKey[String](
      "Path prepended to every operation's @http URI, for a service mounted under a prefix " +
        "the model does not describe (e.g. \"/internal/v1\")"
    )

    val tsCodegenVersion =
      settingKey[String]("Version of the smithy-ts-codegen-cli artifact to resolve and run")
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] =
    Seq(
      tsCodegenVersion := BuildInfo.smithyTsCodegenVersion,
      tsCodegenExcludeServices := Seq.empty,
      tsCodegenPathPrefix := "",
      tsCodegenSmithyDirs := Seq((Compile / sourceDirectory).value / "smithy"),
      tsCodegenOutputFile := (Compile / target).value / "generated.ts",
      tsCodegen := tsCodegenTask.value,
    )

  private val MainClass = "org.polyvariant.smithy.ts.cli.Main"

  /** Resolve the CLI artifact + its transitive deps via coursier. `Dependency.of(org, name,
    * version)` takes the coordinates verbatim (the artifact already carries its Scala 3 suffix),
    * and `Fetch` runs its own resolution independent of any enclosing sbt/project state — so
    * nothing rewrites the Scala library version onto the forked classpath.
    */
  private def resolveCliClasspath(version: String): Seq[File] = {
    val dep = Dependency.of(
      BuildInfo.smithyTsCodegenOrganization,
      s"smithy-ts-codegen-cli_${BuildInfo.smithyTsCodegenScalaBinaryVersion}",
      version,
    )
    Fetch
      .create()
      .addRepositories(
        // Snapshot versions live on Central Snapshots; the local Ivy repo backs
        // `publishLocal` (used by the plugin's own scripted tests).
        MavenRepository.of("https://central.sonatype.com/repository/maven-snapshots/"),
        IvyRepository.of(
          "file://" + sys.props("user.home") +
            "/.ivy2/local/[organisation]/[module](_[scalaVersion])(_[sbtVersion])/" +
            "[revision]/[type]s/[artifact](-[classifier]).[ext]"
        ),
      )
      .addDependencies(dep)
      .fetch()
      .asScala
      .toVector
  }

  private val tsCodegenTask: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val smithyDirs = tsCodegenSmithyDirs.value
    val outFile = tsCodegenOutputFile.value
    val excludeServices = tsCodegenExcludeServices.value
    val pathPrefix = tsCodegenPathPrefix.value
    val version = tsCodegenVersion.value
    val cacheDir = streams.value.cacheDirectory / "smithy-ts-codegen"

    val classpath = resolveCliClasspath(version)

    val smithyInputs =
      smithyDirs
        .flatMap(d => (d ** ("*.smithy" || "*.json")).get)
        .toSet
    val configInput = cacheDir / "exclude-services.txt"
    IO.write(configInput, (excludeServices :+ s"pathPrefix=$pathPrefix").mkString("\n"))

    val cached =
      FileFunction.cached(
        cacheDir,
        inStyle = FilesInfo.hash,
        outStyle = FilesInfo.exists,
      ) { _ =>
        log.info(s"smithy-ts-codegen -> $outFile")
        TsCodegenRunner.run(
          mainClass = MainClass,
          smithyDirs = smithyDirs,
          classpath = classpath,
          outFile = outFile,
          excludeServices = excludeServices,
          pathPrefix = pathPrefix,
          log = log,
        )
        Set(outFile)
      }
    // The resolved jars are part of the cache key: bumping the version (or its
    // deps) re-runs codegen even when the model is unchanged.
    cached(smithyInputs ++ classpath.toSet + configInput)
    outFile
  }

}
