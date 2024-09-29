with (import <nixpkgs> {});
let
   nixpkgs-b2454 = import (builtins.fetchTarball {
        url = "https://github.com/NixOS/nixpkgs/archive/459104f841356362bfb9ce1c788c1d42846b2454.tar.gz";
    }) {};
    clojure-1-11-1-1413 = nixpkgs-b2454.clojure;

in mkShell {
  buildInputs = [
    yarn
    nodejs_18
    clojure-1-11-1-1413
    babashka
    gradle
    ungoogled-chromium
  ];

  #shellHook = ''
  #  export JAVA_HOME=${jdk22.outPath}
  #'';
}
