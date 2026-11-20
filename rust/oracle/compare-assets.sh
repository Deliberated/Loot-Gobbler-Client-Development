#!/usr/bin/env bash
# Compares Rust's extraction of the game data from the game's resources.assets with the Java app's: every file in
# the assets folder byte for byte, the sprite sheets by their pixels (PNG encoders differ).
# Usage: rust/oracle/compare-assets.sh [resources.assets]   (found by itself as the app finds it, if left out)
# The game file is only read. Needs the Java client built first (./gradlew shadowJar in the repository).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
(cd "$here/.." && "$cargo" build -q --release -p lg-replay -p lg-gobbler)
game=${1:-}
if [ -z "$game" ]; then
    game=$(find "$HOME" -path '*RotMG Exalt_Data/resources.assets' -print -quit 2>/dev/null || true)
fi
[ -f "$game" ] || { echo "no resources.assets; give its path" >&2; exit 2; }
game=$(realpath "$game")

work=$(mktemp -d)
if [ -n "${LG_KEEP:-}" ]; then echo "keeping $work" >&2; else trap 'rm -rf "${work:?}"' EXIT; fi
javac -nowarn -cp "$jar" -d "$work/classes" "$here/AssetOracle.java"
mkdir "$work/java" "$work/rust"
(cd "$work/java" && java -Djava.awt.headless=true -cp "$jar:$work/classes" oracle.AssetOracle "$game") > "$work/java.txt" 2> "$work/java.log"
"$here/../target/release/lg-replay" extract "$game" "$work/rust/assets" > "$work/rust.txt" 2> "$work/rust.log"
fail=0
if ! cmp -s "$work/java.txt" "$work/rust.txt"; then
    echo "DIFFERENT sprite pixels:"; diff "$work/java.txt" "$work/rust.txt" || true; fail=1
fi
files=$(cd "$work/java/assets" && find . -type f ! -name '*.png' | sort)
[ "$files" = "$(cd "$work/rust/assets" && find . -type f ! -name '*.png' | sort)" ] || { echo "DIFFERENT file lists"; fail=1; }
differ=0
for f in $files; do
    cmp -s "$work/java/assets/$f" "$work/rust/assets/$f" || { echo "differs: $f"; differ=$((differ + 1)); }
done
[ "$differ" = 0 ] || fail=1
if [ "$fail" = 0 ]; then
    echo "same game data: $(echo "$files" | wc -l) files byte for byte ($(grep -c '' "$work/rust/assets/ObjectID.list") objects, $(grep -c '' "$work/rust/assets/TileID.list") tiles), $(grep -c '^png' "$work/rust.txt") sprite sheets pixel for pixel"
else
    head -5 "$work/java.log" "$work/rust.log"
    exit 1
fi
