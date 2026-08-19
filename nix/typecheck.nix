{
  lib,
  stdenvNoCC,
  pnpm_10,
  nodejs_22,
  fetchPnpmDeps,
  pnpmConfigHook,
}:

# `tsc` over the TypeScript in this repo — the only check that proves the
# generated code, and the runtime library that serves it, are valid TypeScript.
#
# Two workspace packages:
#
#   runtime/    the published transport library: built (so its .d.ts exist),
#               typechecked, and unit-tested with node:test.
#   typecheck/  the committed sample generated.ts, a consumer-side usage file,
#               and runtimeUsage.ts — which is what proves the library's
#               structural Transport types still match the ones the codegen
#               emits.
#
# Hermetic: the .ts sample is committed rather than generated here, so no JVM or
# sbt is needed in the sandbox, and node_modules comes from a pnpm FOD.
# Regenerate the sample with `sbt tsCodegenSample`; CI runs
# `sbt tsCodegenSampleCheck` to fail if it drifts from the model.

let
  pnpm = pnpm_10;
  nodejs = nodejs_22;
in
stdenvNoCC.mkDerivation (finalAttrs: {
  pname = "smithy-ts-codegen-typecheck";
  version = "0.0.0";

  # The workspace root: both packages plus the lockfile and workspace manifest.
  # Only the JS side of the repo, so a change to the Scala tree doesn't
  # invalidate this derivation.
  src =
    let
      root = ../.;
      keepTop = [
        "package.json"
        "pnpm-lock.yaml"
        "pnpm-workspace.yaml"
        "runtime"
        "typecheck"
      ];
    in
    lib.cleanSourceWith {
      src = lib.cleanSource root;
      filter =
        path: _type:
        let
          rel = lib.removePrefix (toString root + "/") (toString path);
          top = lib.head (lib.splitString "/" rel);
        in
        builtins.elem top keepTop
        && !(lib.hasInfix "node_modules" rel)
        && !(lib.hasInfix "dist" rel);
    };

  nativeBuildInputs = [
    nodejs
    pnpm
    pnpmConfigHook
  ];

  pnpmDeps = fetchPnpmDeps {
    inherit pnpm;
    inherit (finalAttrs) pname version src;
    fetcherVersion = 3;
    hash = "sha256-X8nNnf+hpzKtNFvXbtPx/g9rimlit2/t3AGpSrjw8rU=";
  };

  buildPhase = ''
    runHook preBuild

    # The library first: typecheck/ imports its built .d.ts, and its own
    # `typecheck` script builds as a prerequisite.
    pnpm --filter @polyvariant/smithy-ts-runtime run check

    # Then the generated sample + the consumer-side usage, including the file
    # that pins the library's transport types to the generated ones.
    pnpm --filter smithy-ts-codegen-typecheck run typecheck

    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    touch $out
    runHook postInstall
  '';
})
