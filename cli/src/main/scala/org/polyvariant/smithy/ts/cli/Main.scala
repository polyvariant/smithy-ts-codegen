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

package org.polyvariant.smithy.ts.cli

import software.amazon.smithy.build.SmithyBuild
import software.amazon.smithy.build.model.SmithyBuildConfig
import software.amazon.smithy.model.loader.ModelAssembler
import software.amazon.smithy.model.node.Node

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.*

/** CLI entry point that drives the `smithy-ts-codegen` smithy-build plugin. Intended to be invoked
  * from a build via a forked `java` process so the plugin lives on the JVM's classpath (and is
  * discovered via the smithy-build SPI).
  *
  * Usage: `Main <smithyDirs> <outFile> [<excludeServices> [<pathPrefix>]]`
  *
  *   - `smithyDirs` — `File.pathSeparator`-joined list of directories containing `.smithy` sources.
  *     Files matching `*.smithy` or `*.json` are loaded into one model.
  *   - `outFile` — destination path for the generated file. Parent directories are created if
  *     necessary.
  *   - `excludeServices` — optional `,`-joined list of fully-qualified service shape ids
  *     (`namespace#Name`) that the plugin should not emit clients for. Their referenced data shapes
  *     are still emitted. Pass `""` to skip it while still supplying `pathPrefix`.
  *   - `pathPrefix` — optional path prepended to every operation's `@http` URI, for a service
  *     mounted under a prefix the model does not describe.
  */
object Main {

  def main(args: Array[String]): Unit =
    args.toList match {
      case smithyDirsArg :: outFileArg :: rest =>
        val dirs = smithyDirsArg.split(java.io.File.pathSeparatorChar).toList.map(Paths.get(_))
        val excluded =
          rest.headOption.filter(_.nonEmpty).map(_.split(',').toList).getOrElse(Nil)
        val pathPrefix = rest.drop(1).headOption.getOrElse("")
        run(dirs, Paths.get(outFileArg), excluded, pathPrefix)
      case _ =>
        Console
          .err
          .println(
            "usage: Main <smithyDirs (path-separator-joined)> <outFile> [<excludeServices (comma-joined)> [<pathPrefix>]]"
          )
        sys.exit(2)
    }

  private def run(
    smithyDirs: List[Path],
    outFile: Path,
    excludeServices: List[String],
    pathPrefix: String,
  ): Unit = {
    val tmp = Files.createTempDirectory("smithy-ts-codegen")
    try {
      val pluginSettings = Node
        .objectNodeBuilder()
        .withMember(
          "excludeServices",
          Node.fromStrings(excludeServices.asJava),
        )
        .withMember("pathPrefix", pathPrefix)
        .build()
      val configNode = Node
        .objectNodeBuilder()
        .withMember("version", "1.0")
        .withMember(
          "plugins",
          Node.objectNodeBuilder().withMember("ts-codegen", pluginSettings).build(),
        )
        .build()
      val config = SmithyBuildConfig.fromNode(configNode)

      // Same approach as scala-swift-rpc: assemble the model ourselves and hand
      // it to SmithyBuild. `registerSources(dir)` does not populate the model
      // reliably across smithy versions.
      val assembler = new ModelAssembler()
      assembler.discoverModels(classOf[Main.type].getClassLoader)
      smithyDirs.foreach { dir =>
        if (!Files.exists(dir))
          sys.error(s"smithy source directory does not exist: $dir")
        Files
          .walk(dir)
          .iterator
          .asScala
          .filter(p => p.toString.endsWith(".smithy") || p.toString.endsWith(".json"))
          .foreach(assembler.addImport(_))
      }
      val model = assembler.assemble().unwrap()

      val build = SmithyBuild
        .create(classOf[Main.type].getClassLoader)
        .config(config)
        .outputDirectory(tmp)
        .model(model)

      val result = build.build()
      if (result.anyBroken) {
        result.getProjectionResults.asScala.foreach { pr =>
          pr.getEvents.asScala.foreach(ev => Console.err.println(ev))
        }
        sys.error("smithy-build produced validation errors")
      }

      val produced = tmp.resolve("source").resolve("ts-codegen").resolve("generated.ts")
      if (!Files.exists(produced))
        sys.error(s"plugin did not produce $produced")

      Option(outFile.getParent).foreach { p =>
        val _ = Files.createDirectories(p)
      }
      val _ = Files.copy(produced, outFile, StandardCopyOption.REPLACE_EXISTING)
    } finally deleteRecursive(tmp)
  }

  private def deleteRecursive(p: Path): Unit =
    if (Files.exists(p))
      Files
        .walk(p)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .iterator
        .asScala
        .foreach(Files.deleteIfExists(_))

}
