#!/usr/bin/env bash
# Pictures of the Java app's window and the Rust one, tab by tab, side by side and their difference, to compare
# the look: rust/oracle/compare-ui.sh <the Java app's data folder> <out dir> [tab...]
# The Java app runs on a copy of the data folder, without network (unshare -rn: no server is contacted) and without
# a screen (its window is painted offscreen); the Rust window is rendered offscreen too. Needs the Java client built
# first (./gradlew shadowJar in the repository).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
data=$(realpath "${1:?usage: compare-ui.sh <Java data folder> <out dir> [tab...]}")
out=$(mkdir -p "${2:?out dir}" && realpath "$2")
shift 2
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
(cd "$here/.." && "$cargo" build -q --release -p lg-ui)
w=${LG_W:-1799} h=${LG_H:-1005}   # LG_W / LG_H: another window size

work=$(mktemp -d)
if [ -n "${LG_KEEP:-}" ]; then echo "keeping $work" >&2; else trap 'rm -rf "${work:?}"' EXIT; fi
javac -nowarn -cp "$jar" -d "$work/classes" "$here/UiOracle.java" "$here/UiAgent.java"
printf 'Premain-Class: oracle.UiAgent\n' > "$work/manifest.txt"
jar --create --file "$work/ui-agent.jar" --manifest "$work/manifest.txt" -C "$work/classes" oracle/UiAgent.class
mkdir "$work/run"
(cd "$data" && find . -maxdepth 1 ! -name . ! -name logs ! -name '*.lock' ! -name LootGobbler -exec cp -a {} "$work/run/" \;)
sed -i 's/^sniffer=T/sniffer=F/' "$work/run/realmShark.properties" 2>/dev/null || true   # never start capturing
(cd "$work/run" && unshare -rn java -javaagent:"$work/ui-agent.jar" -Djava.awt.headless=true -cp "$jar:$work/classes" \
    oracle.UiOracle "$out/java" $w $h "$@") > "$work/java.log" 2>&1 || { tail -20 "$work/java.log"; exit 1; }
"$here/../target/release/ui-shot" "$out/rust" $w $h --data "$data" "$@" > /dev/null
for j in "$out"/java/*.png; do
    n=$(basename "$j" .png)
    r="$out/rust/$n.png"
    [ -f "$r" ] || continue
    diff=$(magick compare -metric AE "$j" "$r" "$out/$n-diff.png" 2>&1 || true)
    magick "$j" "$r" +append "$out/$n-both.png"
    echo "$n: $diff pixels differ"
done
