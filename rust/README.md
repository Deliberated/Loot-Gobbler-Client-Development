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
| `lg-protocol` | RC4, cutting the stream into packets, finding the cipher's position from tick packets, the packet type table, decoders for the 45 packet types the app reads | `PacketConstructor`, `ROTMGPacketConstructor`, `RC4`, `TickAligner`, `RC4Aligner`, `PacketType`, `BufferReader`, `packets.incoming.*`, `packets.outgoing.*`, `packets.data.*` |
| `lg-gamedata` | the game data the app extracts from the game: objects, tiles, character classes, loot bags, abilities scaling with a stat, enchantments, the amulets that save you, dungeon portals and modifiers, the enemies of each dungeon | `IdToAsset`, `CharacterClass`, `LootBags`, `Amulets`, `AbilityScalingManager`, `ParseEnchants`, `ParseDungeon`, `GameFileEnemies` |
| `lg-world` | the world as the packets tell it: the map, every object in view and its stats, which one is you (also when the sniffer starts inside a map), players, loot bags; damage: your shots rolled with the map's seed, hits, damage per player, damage you take, boss phases, crucible bonuses; loot: which enemy (or boss, by its last words) dropped which bag, and per dungeon what you hit and what dropped | the core of `TomatoData` and `Entity`, `Projectile`, `Damage`, `RNG`, `CrucibleBonusManager`, `LootAttributionManager`, `DungeonStatData` |
| `lg-app` | Loot Gobbler's own records, built from the world in the order the Java app's listeners run: characters, profile, collection, holdings and their journal, the game mode (UPE) rules, items forged, fed, dropped and used up, the Tinkerer's quests, the boss damage of each run (DPS history), every dungeon visit (hits taken, damage dealt, way walked, quick slot items, exaltations, your death, its run; Moonlight Village's lantern time and boss phases, amulet saves), exaltation progress per class, the times your Amulet of Restoration saved you, item cooldowns (Coral Juice Refinery, Alien Generator, Protective Matrix), your mana, your pets as the Pet Yard shows them | `CharacterStore`, `PlayerProfile`, `CollectionStore`, `Holdings`, `LootEvents`, `Upe`, `ItemUse`, `TinkererQuests`, `DpsHistory`, `DungeonVisits`, `ExaltStore`, `MoonlightLog`, `AmuletSaves`, `ItemCooldowns`, `PlayerVitals`, `PetStore` |
| `lg-replay` | the command-line tool below, and made-up sessions for tests | - |

Where it differs from Java on purpose (each one is a bug in Java):

- **Fragmented IPv4 packets** are put together correctly. The Java defragmenter never produces a whole packet.
- **An impossible packet size** (stream out of step) drops the rest of that segment and starts over at the next one.
  Java stops decoding until the next connection.
- **After lost bytes**, the next bytes are read as the start of a packet, so the cipher's position can be found again.
- **Stat types 200 and up** don't fit Java's stat array: Java stops handling that packet. Rust does the same for
  now, so the two stay comparable.
- **A garbled count** (an UPDATE claiming millions of tiles) makes Java allocate a huge array and run out of memory,
  which `PacketProcessor` doesn't catch, so its sniffer thread dies. Rust drops that one packet.

The decoders otherwise copy Java exactly, including how it reacts to bad packets: a packet that can't be read is
dropped, one with bytes left over is kept, and VAULT_UPDATE and DEATH keep what they could read when cut off. Field
names follow Java's and types follow how Java reads them (`byte` is `i8`), so the two can be compared field by field.

Left out for now (diagnostics only, they change nothing the app keeps or shows): Java's Moonlight Village research
log (`logs/MoonlightVillage-<date>.log`), its shooting range debug file (`guildhall-debug.txt`), the app log's
lines, and the sounds (amulet, cooldowns: they come with the UI). Tomato's old pets panel throws when drawing a pet
whose sprite is missing from the sprite sheets; Rust follows its other throws, and takes the sheets as not loaded
until the sprites are ported with the UI.

## Use

```sh
cd rust
cargo test                                          # all tests
cargo run --release -p lg-replay -- session.pcap    # every packet in a recording
cargo run --release -p lg-replay -- session.pcap --stats
cargo run --release -p lg-replay -- synth /tmp/s.pcap 7   # a made-up session (seed 7)
cargo run --release -p lg-replay -- session.pcap --fields   # every field of the decoded packets
oracle/compare.sh /tmp/s.pcap                       # Rust and Java must agree on packets, fields and world
oracle/compare.sh session.pcap ~/LootGobbler/assets # the same, the world knowing the game data
oracle/compare-gamedata.sh ~/LootGobbler/assets     # Rust and Java must read the game data the same
```

`oracle/compare.sh` runs the Java client's own classes (`oracle/ReplayOracle.java`) on the same file and compares two
outputs with Rust's, line by line: the packets (sniffer classes: same packets, order and content) and their fields
(packet classes: every field of every packet that has a Rust decoder, read by reflection). It needs the Java client
built first (`./gradlew shadowJar` in the repository). Login tokens and keys only ever appear as checksums in either
output.

The third comparison is the world: Java's own `TomatoData` (`oracle/WorldOracle.java`) is fed the same packets the app
feeds it, and after each one the whole state (map, you, every object with its stats, players, bags) must be the same
in Rust, down to where Java throws partway through a packet. Java runs in an empty temporary folder (the app writes
its files next to itself), is never handed HELLO (it would log in to the game's servers with your token), and its
network goes to a closed local port. Damage is part of the compared state: every hit an object took, the sums per
player, the damage roll's position, the crucible bonuses; the bags waiting to be matched, the bosses' loot windows,
and the dungeon statistics (`dungeon.stats` in the app).

`lg-replay synth-fight <out.pcap> [seed]` writes a made-up fight for this comparison: a map with its seed, you with a
class's starting gear, other players and their pets, enemies (Oryx, Chancellor Dammah, the Forgotten King among
them), and tick by tick shots, hits, the server's shots, damage, enemy shots hitting you, area and ground damage,
conditions coming and going, a crucible, a player leaving mid-fight, enemies dying and bags dropping, bosses' last
words, a second map. With the app's assets folder its
weapons, abilities and enchantments are real ones.

The world comparison covers the app's records as well (after every packet, everything they hold). For those, Java
runs on the recording's clock: `oracle/ClockAgent.java`, a Java agent, rewrites the app's own clock calls to the
capture time of the frame being handled (the Java sources stay untouched; it needs Java 24 or newer). So the app's
"every 5 s", last-played times and find times compare to the millisecond. `lg-replay synth-items` writes a made-up
session about items for this: vault visits, moves in the vault, bags, potions, forging, trades, quests, purchases.

The assets folder is the one the app extracts the game data into, next to its other files. It is only read, and the
game's files never go into the repository.

## Watching live

There is no capture of its own yet: `tcpdump` captures, and `lg-replay live` reads what it writes as it comes.

```sh
cd rust && cargo build --release -p lg-replay
sudo tcpdump -i any -U -w - 'tcp port 2050' 2>/dev/null | ./target/release/lg-replay live --world ~/LootGobbler/assets
```

It prints the game's packets as they happen (`--all` adds the many sent every tick), and with `--world` and the
app's assets folder also where you are, who you are and how many players are in view. Start it before entering the
game or changing servers. Ctrl+C ends it. Only `tcpdump` needs root; nothing is saved.

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
- CI (`.github/workflows/rust.yml`): all of the above on Linux and Windows, plus the comparisons with Java on 8
  made-up sessions and 8 made-up fights.
- `lg-world`: entering maps, finding you and the other players, objects leaving the view, finding you when starting
  inside a map (by your own stats, where you moved, your ability), guessing the map by its enemies, the places
  where Java throws.
- Made-up fights (12 seeds, with the real game data): the same world as Java's after every packet, damage and loot
  included, and the same run history and dungeon visits (real dungeon names, the guild hall's shooting range,
  deaths, victories, quick slot items, skins and dyes, transformations, exaltation progress, Moonlight Village's lantern, bosses, songs and flames, the amulet saving you, the matrix armor firing, cooldown items, the Pet Yard). The Java app's own
  files from an earlier start (`oracle/seed`: kept exaltations, amulet saves and pets) are where Java finds them, and Rust takes them over
  with `--java-data`.
- A real 46-second recording (not in the repository): Rust and Java agree on all 1391 packets, the fields of all 946
  decoded ones, and the world state after each of the 935 packets the world is fed. The game data read from a real
  assets folder is the same too (36858 objects, 10399 tiles, 19 classes).
