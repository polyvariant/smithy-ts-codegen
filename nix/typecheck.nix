{
  lib,
  stdenvNoCC,
  pnpm_10,
  nodejs_22,
  fetchPnpmDeps,
  pnpmConfigHook,
}:

# `tsc` over the committed sample in typecheck/ — the only check that proves the
# generated TypeScript is valid TypeScript.
#
# Hermetic: the .ts is committed rather than generated here, so no JVM or sbt is
# needed in the sandbox, and node_modules comes from a pnpm FOD. Regenerate the
# sample with `sbt tsCodegenSample`; CI runs `sbt tsCodegenSampleCheck` to fail
# if it drifts from the model.

let
  pnpm = pnpm_10;
  nodejs = nodejs_22;
in
stdenvNoCC.mkDerivation (finalAttrs: {
  pname = "smithy-ts-codegen-typecheck";
  version = "0.0.0";

  src = lib.cleanSourceWith {
    src = lib.cleanSource ../typecheck;
    filter =
      path: type:
      let
        rel = lib.removePrefix (toString ../typecheck + "/") (toString path);
      in
      !(lib.hasPrefix "node_modules" rel);
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
    hash = "sha256-Ey7ZO5qu7Nnc0udSu0v1e+2FPQmb53KLtxo64jKD0hc=";
  };

  buildPhase = ''
    runHook preBuild
    pnpm run typecheck
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    touch $out
    runHook postInstall
  '';
})
