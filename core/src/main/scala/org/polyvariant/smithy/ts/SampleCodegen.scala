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

package org.polyvariant.smithy.ts

import software.amazon.smithy.model.Model

import java.nio.file.Files
import java.nio.file.Paths

/** Renders one `.smithy` file to TypeScript. Used by the `tsCodegenSample` sbt task to refresh the
  * committed sample that `nix flake check` type-checks — see `typecheck/`.
  *
  * Usage: `SampleCodegen <model.smithy> <out.ts>`
  */
object SampleCodegen {

  def main(args: Array[String]): Unit =
    args.toList match {
      case in :: out :: Nil =>
        val model = Model
          .assembler()
          .discoverModels(getClass.getClassLoader)
          .addImport(in)
          .assemble()
          .unwrap()
        val outPath = Paths.get(out)
        Option(outPath.getParent).foreach { p =>
          val _ = Files.createDirectories(p)
        }
        val _ = Files.writeString(outPath, TsCodegenPlugin.generate(model, Set.empty))
      case _ =>
        Console.err.println("usage: SampleCodegen <model.smithy> <out.ts>")
        sys.exit(2)
    }

}
