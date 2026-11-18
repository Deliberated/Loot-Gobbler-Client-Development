#!/usr/bin/env bash
# Compares the Rust replay with the Java client on one recording, twice:
#   packets: the same packets, in the same order, with the same content (Java's sniffer classes)
#   fields:  every packet that has a Rust decoder decodes to the same fields (Java's packet classes)
# Usage: rust/oracle/compare.sh <session.pcap>
# Needs the Java client built first (./gradlew shadowJar in the repository).
# Login tokens and keys only ever appear as checksums, so the output can be shown.
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
(cd "$here/.." && "$cargo" build -q --release -p lg-replay)
replay="$here/../target/release/lg-replay"
ids=$("$replay" decoded-ids)

status=0
check() { # name, java args..., then rust flag last
    local what=$1 flag=$2
    shift 2
    java -cp "$jar:$work" oracle.ReplayOracle "$pcap" "$@" > "$work/java.txt" 2> "$work/java.log"
    "$replay" "$pcap" "$flag" > "$work/rust.txt" 2> "$work/rust.log"
    if cmp -s "$work/java.txt" "$work/rust.txt"; then
        echo "same $what: $(wc -l < "$work/rust.txt") packets ($(basename "$pcap"))"
    else
        echo "DIFFERENT $what ($(basename "$pcap")): Java $(wc -l < "$work/java.txt") packets, Rust $(wc -l < "$work/rust.txt")"
        diff "$work/java.txt" "$work/rust.txt" | cut -c1-400 | head -40
        status=1
    fi
}
check packets --oracle
check fields --fields --fields "$ids"
exit $status
