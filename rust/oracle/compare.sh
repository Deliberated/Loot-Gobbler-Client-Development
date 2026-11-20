#!/usr/bin/env bash
# Compares the Rust replay with the Java client on one recording, twice:
#   packets: the same packets, in the same order, with the same content (Java's sniffer classes)
#   fields:  every packet that has a Rust decoder decodes to the same fields (Java's packet classes)
#   world:   the same world state after each packet the app's TomatoData is fed (Java's TomatoData itself)
# Usage: rust/oracle/compare.sh <session.pcap> [assets folder] [game answers folder]
# With a folder of the game's web answers (its char list, exalts...: made up, see lg-replay synth-login), both sides
# are also handed HELLO and answered from it; without, HELLO is never handed on. Either way nothing reaches the game.
# With the app's assets folder (the game data it extracts; only read, never changed) the world knows the game's
# classes and objects; without, both sides run without game data.
# For the world, Java runs in an empty temporary folder (the app writes its files next to itself), is never handed
# HELLO (it would log in to the game's servers), and its network goes to a closed local port.
# Needs the Java client built first (./gradlew shadowJar in the repository).
# Login tokens and keys only ever appear as checksums, so the output can be shown.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
pcap=$(realpath "${1:?usage: compare.sh <session.pcap> [assets folder]}")
assets=${2:+$(realpath "$2")}
web=${3:+$(realpath "$3")}
jar=$(ls -t "$repo"/build/libs/LootGobbler-*.jar 2>/dev/null | head -1 || true)
[ -n "$jar" ] || { echo "Build the Java client first: ./gradlew shadowJar (in $repo)" >&2; exit 2; }
cargo=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")

work=$(mktemp -d)
if [ -n "${LG_KEEP:-}" ]; then echo "keeping $work" >&2; else trap 'rm -rf "${work:?}"' EXIT; fi
javac -nowarn -cp "$jar" -d "$work" "$here/ReplayOracle.java" "$here/WorldOracle.java" "$here/ClockAgent.java"
printf 'Premain-Class: oracle.ClockAgent\n' > "$work/manifest.txt"
jar --create --file "$work/clock-agent.jar" --manifest "$work/manifest.txt" -C "$work" oracle/ClockAgent.class
(cd "$here/.." && "$cargo" build -q --release -p lg-replay)
replay="$here/../target/release/lg-replay"
ids=$("$replay" decoded-ids)

# Tomato's old panels, which the app builds and its packet handlers call into, need a display (AWT refuses them
# headless); none is ever shown. A virtual one (xvfb-run) if installed, else the session's.
if command -v xvfb-run >/dev/null; then
    display="xvfb-run -a"
elif [ -n "${DISPLAY:-}" ]; then
    display=""
else
    echo "The world comparison needs a display: install Xvfb (xvfb-run) or run it in a desktop session" >&2
    exit 2
fi

status=0
check() { # name, rust flag, then java args
    local what=$1 flag=$2
    shift 2
    if [ "$what" = world ]; then
        mkdir -p "$work/run/assets"
        if [ -n "$assets" ]; then cp -r "$assets/ObjectID.list" "$assets/TileID.list" "$assets/xml" "$work/run/assets/"; fi
        # the Java app's own files from an earlier start (made up: oracle/seed): Java finds them next to itself, Rust
        # takes them over from a copy (Java changes its own as it runs)
        # (LG_SEED: another folder of them, e.g. what Java wrote in an earlier comparison, kept with LG_KEEP)
        mkdir -p "$work/seed"
        find "${LG_SEED:-$here/seed}" -maxdepth 1 -type f -exec cp -p {} "$work/seed/" \;
        find "${LG_SEED:-$here/seed}" -maxdepth 1 -type f -exec cp -p {} "$work/run/" \;
        (cd "$work/run" && $display java -javaagent:"$work/clock-agent.jar" -Djava.net.useSystemProxies=false \
            -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 \
            ${web:+-Doracle.web="$web"} -cp "$jar:$work" oracle.ReplayOracle "$pcap" "$@") > "$work/java.txt" 2> "$work/java.log"
        mkdir -p "$work/rustfiles"
        "$replay" "$pcap" "$flag" "$work/run/assets" --java-data "$work/seed" ${web:+--web "$web"} --save "$work/rustfiles" \
            > "$work/rust.txt" 2> "$work/rust.log"
    else
        java -cp "$jar:$work" oracle.ReplayOracle "$pcap" "$@" > "$work/java.txt" 2> "$work/java.log"
        "$replay" "$pcap" "$flag" > "$work/rust.txt" 2> "$work/rust.log"
    fi
    if cmp -s "$work/java.txt" "$work/rust.txt"; then
        echo "same $what: $(grep -vc '^ \|^state' "$work/rust.txt") packets ($(basename "$pcap"))"
    else
        echo "DIFFERENT $what ($(basename "$pcap")): Java $(wc -l < "$work/java.txt") packets, Rust $(wc -l < "$work/rust.txt")"
        diff "$work/java.txt" "$work/rust.txt" | cut -c1-400 | head -40
        status=1
    fi
}
check packets --oracle
check fields --fields --fields "$ids"
check world --world --world
# the files: every file Rust wrote is the one Java wrote, byte for byte
files_same=0
for f in "$work"/rustfiles/*; do
    [ -e "$f" ] || continue
    n=$(basename "$f")
    if cmp -s "$f" "$work/run/$n"; then
        files_same=$((files_same + 1))
    else
        echo "DIFFERENT file $n ($(basename "$pcap"))"
        diff <(tr ',' '\n' < "$work/run/$n" 2>/dev/null) <(tr ',' '\n' < "$f") | head -10
        status=1
    fi
done
echo "same files: $files_same written ($(basename "$pcap"))"
exit $status
