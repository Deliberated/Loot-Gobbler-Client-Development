#!/usr/bin/env bash
# Compares the server sync's merging with the Java client: another PC's documents (<remote>/<kind>.json, the
# server's shape) merged into a copy of an app data folder, then what each kind would upload.
# Usage: rust/oracle/compare-sync.sh <data folder> <remote folder>
# Both sides run on their own copy of the folder (Java saves into its copy); the folder itself is never changed.
# Java runs without network (unshare -rn). Needs the Java client built first (./gradlew shadowJar).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
data=$(realpath "${1:?usage: compare-sync.sh <data folder> <remote folder>}")
remote=$(realpath "${2:?usage: compare-sync.sh <data folder> <remote folder>}")
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
work=$(mktemp -d)
if [ -n "${LG_KEEP:-}" ]; then echo "keeping $work" >&2; else trap 'rm -rf "${work:?}"' EXIT; fi
javac -nowarn -cp "$jar" -d "$work/classes" "$here/SyncOracle.java" "$here/ClockAgent.java"
printf 'Premain-Class: oracle.ClockAgent\n' > "$work/manifest.txt"
jar --create --file "$work/clock-agent.jar" --manifest "$work/manifest.txt" -C "$work/classes" oracle/ClockAgent.class
now=$(($(date +%s) * 1000))
(cd "$here/.." && "$cargo" build -q --release -p lg-replay)
cp -r "$data" "$work/java"
cp -r "$data" "$work/rust"
sed -i 's/^sniffer=.*/sniffer=F/' "$work/java/realmShark.properties" 2>/dev/null || true
(cd "$work/java" && unshare -rn java -javaagent:"$work/clock-agent.jar" -Doracle.now=$now -Djava.awt.headless=true -cp "$jar:$work/classes" oracle.SyncOracle "$remote" "$work/java.txt") \
    > "$work/java.log" 2>&1 || { cat "$work/java.log" >&2; exit 1; }
LG_NOW=$now "$here/../target/release/lg-replay" sync "$work/rust" "$remote" "$work/rust.txt"
status=0
while read -r kind; do
    j=$(grep -A1 "^$kind " "$work/java.txt"); r=$(grep -A1 "^$kind " "$work/rust.txt")
    if [ "$j" = "$r" ]; then
        echo "same      $(head -1 <<<"$j")"
    else
        echo "DIFFERENT java: $(head -1 <<<"$j")  rust: $(head -1 <<<"$r")"
        status=1
        if [ -n "${LG_KEEP:-}" ]; then
            tail -1 <<<"$j" > "$work/$kind.java.json"; tail -1 <<<"$r" > "$work/$kind.rust.json"
        fi
    fi
done < <(awk 'NR%2==1 {print $1}' "$work/java.txt")
exit $status
