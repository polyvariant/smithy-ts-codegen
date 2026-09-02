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
  * }}}
  *
  * then run `tsCodegen`.
  *
  * `tsCodegenExtensions` puts extra artifacts on the forked classpath, which is how a
  * `TsCodegenExtension` reaches the codegen — `ServiceLoader` finds it there.
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

    val tsCodegenVersion =
      settingKey[String]("Version of the smithy-ts-codegen-cli artifact to resolve and run")

    val tsCodegenExtensions = settingKey[Seq[ModuleID]](
      "Artifacts to add to the forked codegen's classpath, for TsCodegenExtension " +
        "implementations discovered via ServiceLoader"
    )

  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] =
    Seq(
      tsCodegenVersion := BuildInfo.smithyTsCodegenVersion,
      tsCodegenExcludeServices := Seq.empty,
      tsCodegenExtensions := Seq.empty,
      tsCodegenSmithyDirs := Seq((Compile / sourceDirectory).value / "smithy"),
      tsCodegenOutputFile := (Compile / target).value / "generated.ts",
      tsCodegen := tsCodegenTask.value,
    )

  private val MainClass = "org.polyvariant.smithy.ts.cli.Main"

  /** Resolve the CLI artifact + its transitive deps via coursier, plus any extension artifacts.
    * `Dependency.of(org, name, version)` takes the coordinates verbatim (the artifact already
    * carries its Scala 3 suffix), and `Fetch` runs its own resolution independent of any enclosing
    * sbt/project state — so nothing rewrites the Scala library version onto the forked classpath.
    *
    * Extensions are resolved in the same `Fetch` as the CLI rather than appended afterwards, so a
    * dependency they share with the codegen (`smithy-model`, `smithy-ts-codegen-api`) is reconciled
    * to one version instead of landing on the classpath twice.
    */
  private def resolveCliClasspath(version: String, extensions: Seq[ModuleID]): Seq[File] = {
    val cli = Dependency.of(
      BuildInfo.smithyTsCodegenOrganization,
      s"smithy-ts-codegen-cli_${BuildInfo.smithyTsCodegenScalaBinaryVersion}",
      version,
    )
    val extensionDeps = extensions.map(coursierDependency)
    val fetch = Fetch
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
      .addDependencies(cli)
    extensionDeps.foreach(d => fetch.addDependencies(d))
    fetch.fetch().asScala.toVector
  }

  /** An sbt `ModuleID` as a coursier `Dependency`.
    *
    * An extension is a plain JVM artifact, so `%` (no suffix) and `%%` (the codegen's own Scala 3
    * suffix) are both meaningful — but the enclosing project's `scalaVersion` is not, since nothing
    * here runs on it. So `%%` is resolved against the *codegen's* binary version, which is what an
    * extension compiled against `smithy-ts-codegen-api` actually carries.
    */
  private def coursierDependency(m: ModuleID): Dependency = {
    val name =
      m.crossVersion match {
        case _: librarymanagement.Binary =>
          s"${m.name}_${BuildInfo.smithyTsCodegenScalaBinaryVersion}"
        case _ => m.name
      }
    Dependency.of(m.organization, name, m.revision)
  }

  private val tsCodegenTask: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val smithyDirs = tsCodegenSmithyDirs.value
    val outFile = tsCodegenOutputFile.value
    val excludeServices = tsCodegenExcludeServices.value
    val version = tsCodegenVersion.value
    val cacheDir = streams.value.cacheDirectory / "smithy-ts-codegen"

    // The extension jars land in the same classpath the fork runs with, which is
    // where ServiceLoader looks for them.
    val classpath = resolveCliClasspath(version, tsCodegenExtensions.value)

    val smithyInputs =
      smithyDirs
        .flatMap(d => (d ** ("*.smithy" || "*.json")).get)
        .toSet
    val configInput = cacheDir / "exclude-services.txt"
    IO.write(configInput, excludeServices.mkString("\n"))

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
