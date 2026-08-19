{
  description = "smithy-ts-codegen — a smithy-build plugin emitting zod schemas + typed TypeScript clients";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        # `nix flake check` type-checks the generated TypeScript.
        #
        # The Scala tests assert on substrings of the emitted file, which can't
        # catch a type error — a generator declared as `AsyncIterable`, an
        # intersection with an empty `z.object`, a `Date` cast to a query value.
        # This runs the real `tsc` over a committed sample (typecheck/) covering
        # every construct the codegen emits, plus a consumer-side usage file
        # that exercises the client, the streams and the mocks the way a caller
        # would.
        checks.typecheck = pkgs.callPackage ./nix/typecheck.nix { };

        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.nodejs_22
            pkgs.pnpm_10
            pkgs.sbt
            pkgs.temurin-bin-17
          ];
        };
      }
    );
}
