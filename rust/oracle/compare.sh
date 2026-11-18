#!/usr/bin/env bash
# Compares the Rust replay with the Java client's sniffer on one recording: the same packets, in the same order, with
# the same content. Usage: rust/oracle/compare.sh <session.pcap>
# Needs the Java client built first (./gradlew shadowJar in the repository).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
pcap=$(realpath "${1:?usage: compare.sh <session.pcap>}")
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -nowarn -cp "$jar" -d "$work" "$here/ReplayOracle.java"
java -cp "$jar:$work" oracle.ReplayOracle "$pcap" > "$work/java.txt" 2> "$work/java.log"
(cd "$here/.." && "$cargo" run -q --release -p lg-replay -- "$pcap" --oracle) > "$work/rust.txt" 2> "$work/rust.log"

if cmp -s "$work/java.txt" "$work/rust.txt"; then
    echo "same: $(wc -l < "$work/rust.txt") packets ($(basename "$pcap"))"
else
    echo "DIFFERENT ($(basename "$pcap")): Java $(wc -l < "$work/java.txt") packets, Rust $(wc -l < "$work/rust.txt")"
    diff "$work/java.txt" "$work/rust.txt" | head -40
    exit 1
fi
