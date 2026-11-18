# Loot Gobbler in Rust (experiment)

A rebuild of the Loot Gobbler client in Rust, to see what it can do. The Java client in this repository stays the real
one: it gets the new features and the releases. Everything here lives on the `rust` branch and is never released.

The plan is to build it from the inside out, checking each part against the Java client: first the sniffer (reading
the game's traffic), then the game logic, the server sync, and the interface (egui) last. The Rust client is meant to
work with the current Loot Gobbler server and its data as they are.

## What is here so far

| Crate | What it does | Java it replaces |
|---|---|---|
| `lg-capture` | pcap files, link layers (Ethernet, VLAN, Linux "any", VPN raw IP, loopback), IPv4, TCP segments in order | `Sniffer`, `LinkLayer`, `Ip4Packet`, `TcpPacket`, `TcpStreamBuilder`, `TcpStreamErrorHandler` |
| `lg-protocol` | RC4, cutting the stream into packets, finding the cipher's position from tick packets, the packet type table, decoders for the 42 packet types the app reads | `PacketConstructor`, `ROTMGPacketConstructor`, `RC4`, `TickAligner`, `RC4Aligner`, `PacketType`, `BufferReader`, `packets.incoming.*`, `packets.outgoing.*`, `packets.data.*` |
| `lg-replay` | the command-line tool below, and made-up sessions for tests | - |

Where it differs from Java on purpose (each one is a bug in Java):

- **Fragmented IPv4 packets** are put together correctly. The Java defragmenter never produces a whole packet.
- **An impossible packet size** (stream out of step) drops the rest of that segment and starts over at the next one.
  Java stops decoding until the next connection.
- **After lost bytes**, the next bytes are read as the start of a packet, so the cipher's position can be found again.
- **A garbled count** (an UPDATE claiming millions of tiles) makes Java allocate a huge array and run out of memory,
  which `PacketProcessor` doesn't catch, so its sniffer thread dies. Rust drops that one packet.

The decoders otherwise copy Java exactly, including how it reacts to bad packets: a packet that can't be read is
dropped, one with bytes left over is kept, and VAULT_UPDATE and DEATH keep what they could read when cut off. Field
names follow Java's and types follow how Java reads them (`byte` is `i8`), so the two can be compared field by field.

## Use

```sh
cd rust
cargo test                                          # all tests
cargo run --release -p lg-replay -- session.pcap    # every packet in a recording
cargo run --release -p lg-replay -- session.pcap --stats
cargo run --release -p lg-replay -- synth /tmp/s.pcap 7   # a made-up session (seed 7)
cargo run --release -p lg-replay -- session.pcap --fields   # every field of the decoded packets
oracle/compare.sh /tmp/s.pcap                       # Rust and Java must agree on packets and fields
```

`oracle/compare.sh` runs the Java client's own classes (`oracle/ReplayOracle.java`) on the same file and compares two
outputs with Rust's, line by line: the packets (sniffer classes: same packets, order and content) and their fields
(packet classes: every field of every packet that has a Rust decoder, read by reflection). It needs the Java client
built first (`./gradlew shadowJar` in the repository). Login tokens and keys only ever appear as checksums in either
output.

## Recording a real session

On Linux, while playing for a few minutes:

```sh
sudo tcpdump -i any -w session.pcap 'tcp port 2050'
```

Start it **before** entering the game or changing servers: a recording that starts mid-connection loses packets until
the cipher's position is found. Then `cargo run --release -p lg-replay -- session.pcap` and `oracle/compare.sh session.pcap`.

**Never commit a recording.** The HELLO packet in it carries your game login token. `*.pcap` files are ignored by git
here, and the tests use made-up sessions only.

## Tests

- Unit tests for every part: link layers, IPv4 fragments, TCP order, duplicates, overlaps, sequence wrap-around, gaps,
  RC4 against a known vector, framing over every possible split, the cipher alignment, the decoders.
- Decoders: compressed ints (Java's overflow included), negative lengths, the MAPINFO modifier split, notification
  types, cut-off VAULT_UPDATE and DEATH packets, ENEMYSHOOT's optional fields.
- `lg-replay/tests/synthetic.rs`: whole made-up sessions (16 of them, in order and mixed up) must come out exactly as
  sent, and a session with lost bytes must find its feet again. The made-up sessions also carry packets of every
  decoded type with made-up content (mostly small numbers, so counts and lengths stay plausible and decoding gets
  far), for the field comparison with Java.
- CI (`.github/workflows/rust.yml`): all of the above on Linux and Windows, plus both comparisons with Java on 8
  sessions (about 650 decoded packets each).
- A real 46-second recording (not in the repository): Rust and Java agree on all 1391 packets and the fields of all
  943 decoded ones.
