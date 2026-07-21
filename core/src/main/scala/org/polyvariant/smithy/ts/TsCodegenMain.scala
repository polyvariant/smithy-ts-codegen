package org.polyvariant.smithy.ts

import software.amazon.smithy.build.SmithyBuild
import software.amazon.smithy.build.model.SmithyBuildConfig
import software.amazon.smithy.model.loader.ModelAssembler
import software.amazon.smithy.model.node.Node

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.*

/** Drives [[TsCodegenPlugin]] programmatically. Intended to be invoked from a build via a forked
  * `java` process so the plugin lives on the JVM's classpath (and is discovered via the
  * smithy-build SPI).
  *
  * Usage: `TsCodegenMain <smithyDirs> <outFile> [<excludeServices>]`
  *
  *   - `smithyDirs` — `File.pathSeparator`-joined list of directories containing `.smithy` sources.
  *     Files matching `*.smithy` or `*.json` are loaded into one model.
  *   - `outFile` — destination path for the generated file. Parent directories are created if
  *     necessary.
  *   - `excludeServices` — optional `,`-joined list of fully-qualified service shape ids
  *     (`namespace#Name`) that the plugin should not emit clients for. Their referenced data shapes
  *     are still emitted.
  */
object TsCodegenMain {

  def main(args: Array[String]): Unit =
    args.toList match {
      case smithyDirsArg :: outFileArg :: rest =>
        val dirs = smithyDirsArg.split(java.io.File.pathSeparatorChar).toList.map(Paths.get(_))
        val excluded =
          rest match {
            case head :: Nil if head.nonEmpty => head.split(',').toList
            case _                            => Nil
          }
        run(dirs, Paths.get(outFileArg), excluded)
      case _ =>
        Console
          .err
          .println(
            "usage: TsCodegenMain <smithyDirs (path-separator-joined)> <outFile> [<excludeServices (comma-joined)>]"
          )
        sys.exit(2)
    }

  private def run(smithyDirs: List[Path], outFile: Path, excludeServices: List[String]): Unit = {
    val tmp = Files.createTempDirectory("smithy-ts-codegen")
    try {
      val pluginSettings = Node
        .objectNodeBuilder()
        .withMember(
          "excludeServices",
          Node.fromStrings(excludeServices.asJava),
        )
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
      assembler.discoverModels(classOf[TsCodegenMain.type].getClassLoader)
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
        .create(classOf[TsCodegenMain.type].getClassLoader)
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
