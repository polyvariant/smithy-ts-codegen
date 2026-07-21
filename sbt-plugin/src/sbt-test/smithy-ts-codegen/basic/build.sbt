enablePlugins(SmithyTsCodegenPlugin)

scalaVersion := "2.12.20"

// Resolve the codegen artifact (published locally by the scripted run) and the
// snapshot dependencies it pulls in.
resolvers += Resolver.sonatypeCentralSnapshots

tsCodegenSmithyDirs := Seq(baseDirectory.value / "src" / "main" / "smithy")
tsCodegenOutputFile := baseDirectory.value / "target" / "generated.ts"
tsCodegenExcludeServices := Seq("test#HiddenService")

TaskKey[Unit]("checkOutput") := {
  val f = tsCodegenOutputFile.value
  assert(f.exists, s"expected $f to exist")
  val contents = IO.read(f)
  def require(sub: String): Unit =
    assert(contents.contains(sub), s"expected generated.ts to contain: $sub")

  require("export const PersonSchema = z.object({")
  require("export type Person = z.infer<typeof PersonSchema>")
  require("export class GreeterClient {")
  // excluded service must not get a client...
  assert(!contents.contains("export class HiddenServiceClient {"), "HiddenService client should be excluded")
  // ...but its referenced data shape is still emitted
  require("export const SecretSchema = z.object({")
}
