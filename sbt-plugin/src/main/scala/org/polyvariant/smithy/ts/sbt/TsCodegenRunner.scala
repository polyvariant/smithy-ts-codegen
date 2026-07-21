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

import sbt.util.Logger

import java.io.File

/** Runs the `smithy-ts-codegen` smithy-build plugin in a forked JVM: the codegen classpath is
  * resolved from the published artifact, and its `TsCodegenMain` helper is invoked with that
  * classpath so the plugin is discovered via the smithy-build SPI.
  */
private[sbt] object TsCodegenRunner {

  def run(
    mainClass: String,
    smithyDirs: Seq[File],
    classpath: Seq[File],
    outFile: File,
    excludeServices: Seq[String],
    log: Logger,
  ): Unit = {
    val cpString = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val smithyArg = smithyDirs.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val baseCmd = Seq(
      javaBin,
      "-cp",
      cpString,
      mainClass,
      smithyArg,
      outFile.getAbsolutePath,
    )
    val cmd =
      if (excludeServices.nonEmpty)
        baseCmd :+ excludeServices.mkString(",")
      else
        baseCmd
    val rc = scala
      .sys
      .process
      .Process(cmd)
      .!(scala.sys.process.ProcessLogger(log.info(_), log.error(_)))
    if (rc != 0)
      sys.error(s"smithy-ts-codegen failed (exit $rc)")
  }

  private def javaBin: String = {
    val home = sys.props.getOrElse("java.home", sys.error("java.home not set"))
    val bin = new File(home, "bin/java")
    if (bin.exists)
      bin.getAbsolutePath
    else
      "java"
  }

}
