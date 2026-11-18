#!/usr/bin/env bash
# Compares the game data Rust reads from an assets folder with what the Java client reads from it: every object, tile
# and class. Usage: rust/oracle/compare-gamedata.sh <assets folder>   (e.g. the app's data folder's assets/)
# The folder is only read. Needs the Java client built first (./gradlew shadowJar in the repository).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
assets=$(realpath "${1:?usage: compare-gamedata.sh <assets folder>}")
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")

work=$(mktemp -d)
if [ -n "${LG_KEEP:-}" ]; then echo "keeping $work" >&2; else trap 'rm -rf "${work:?}"' EXIT; fi
javac -nowarn -cp "$jar" -d "$work/classes" "$here/GameDataOracle.java"
mkdir "$work/run" && ln -s "$assets" "$work/run/assets"   # Java reads ./assets
(cd "$work/run" && java -Djava.awt.headless=true -cp "$jar:$work/classes" oracle.GameDataOracle) > "$work/java.txt" 2> "$work/java.log"
(cd "$here/.." && "$cargo" run -q --release -p lg-replay -- gamedata "$assets") > "$work/rust.txt" 2> "$work/rust.log"
if cmp -s "$work/java.txt" "$work/rust.txt"; then
    echo "same game data: $(grep -c '^object' "$work/rust.txt") objects, $(grep -c '^tile' "$work/rust.txt") tiles, $(grep -c '^class' "$work/rust.txt") classes, $(grep -c '^scaling' "$work/rust.txt") scaling abilities, $(grep -c '^shotby' "$work/rust.txt") ability projectiles, $(grep -c '^enchant' "$work/rust.txt") enchantments, $(grep -c '^ID_TO_NAME_PORTAL' "$work/rust.txt") dungeon portals, $(grep -c '^enemy' "$work/rust.txt") enemies of one dungeon"
else
    echo "DIFFERENT game data: Java $(wc -l < "$work/java.txt") lines, Rust $(wc -l < "$work/rust.txt")"
    diff "$work/java.txt" "$work/rust.txt" | cut -c1-300 | head -30
    cat "$work/java.log" "$work/rust.log" | head -10
    exit 1
fi
