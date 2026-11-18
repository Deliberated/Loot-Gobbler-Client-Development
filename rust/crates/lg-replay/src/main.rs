//! lg-replay: prints the game packets in a recorded session.
//!
//!   lg-replay <session.pcap>            every packet, with time, direction, size and (for some) its content
//!   lg-replay <session.pcap> --oracle   one line per packet to compare with the Java client (oracle/compare.sh)
//!   lg-replay <session.pcap> --fields   every field of each packet that has a decoder, to compare the same way
//!   lg-replay decoded-ids               the ids of the packets that have a decoder, for oracle/compare.sh
//!   lg-replay gamedata <assets>         the game data read from an assets folder, to compare with Java
//!   lg-replay <session.pcap> --stats    only the summary
//!   lg-replay <session.pcap> --world <assets>   the world state after each packet the world is fed, to compare
//!                                       with Java (oracle/compare.sh): a checksum, the whole state on map changes
//!   lg-replay synth <out.pcap> [seed]   writes a made-up session, for testing without a recording
//!   lg-replay live [--all] [--world <assets>]
//!                                       the game packets of a live capture piped in, as they happen:
//!                                       sudo tcpdump -i any -U -w - 'tcp port 2050' | lg-replay live
//!                                       (--all: also the many per-tick packets; --world: also who and where you are)

use lg_protocol::aligner::AlignNote;
use lg_protocol::{Note, packets};
use lg_replay::{Item, oracle_line, replay, synth};
use std::collections::BTreeMap;
use std::io::{BufWriter, Write};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.as_slice() {
        [cmd, out, rest @ ..] if cmd == "synth" => {
            let seed = rest.first().and_then(|s| s.parse().ok()).unwrap_or(1);
            let s = synth::session(&synth::Options { seed, ..Default::default() });
            if let Err(e) = std::fs::write(out, &s.pcap) {
                eprintln!("cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            eprintln!("{out}: {} packets in, {} out (seed {seed})", s.incoming.len(), s.outgoing.len());
            ExitCode::SUCCESS
        }
        [cmd, dir] if cmd == "gamedata" => gamedata(std::path::Path::new(dir)),
        [cmd, rest @ ..] if cmd == "live" => {
            let all = rest.iter().any(|f| f == "--all");
            let assets = rest.iter().position(|f| f == "--world").and_then(|i| rest.get(i + 1));
            live(all, assets.map(std::path::Path::new))
        }
        [cmd] if cmd == "decoded-ids" => {
            println!("{}", packets::DECODED_IDS.iter().map(u8::to_string).collect::<Vec<_>>().join(","));
            ExitCode::SUCCESS
        }
        [file, flags @ ..] if !file.starts_with('-') => {
            let has = |f: &str| flags.iter().any(|x| x == f);
            if let [flag, assets] = flags
                && flag == "--world"
            {
                return world(file, std::path::Path::new(assets));
            }
            let mode = if has("--oracle") {
                Mode::Oracle
            } else if has("--fields") {
                Mode::Fields
            } else if has("--stats") {
                Mode::Stats
            } else {
                Mode::List
            };
            run(file, mode)
        }
        _ => {
            eprintln!(
                "usage: lg-replay <session.pcap> [--oracle | --fields | --stats]\n       lg-replay synth <out.pcap> [seed]\n       lg-replay decoded-ids"
            );
            ExitCode::from(2)
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    List,
    Oracle,
    Fields,
    Stats,
}

fn run(file: &str, mode: Mode) -> ExitCode {
    let oracle = mode == Mode::Oracle || mode == Mode::Fields;
    let stats_only = mode == Mode::Stats;
    let data = match std::fs::read(file) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("cannot read {file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let started = std::time::Instant::now();
    let r = match replay(&data) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let took = started.elapsed();
    let first = r.items.iter().map(item_micros).next().unwrap_or(0);
    let mut counts: BTreeMap<(&str, &str), usize> = BTreeMap::new();
    let mut notes = 0;
    let mut out = BufWriter::new(std::io::stdout().lock());
    for item in &r.items {
        match item {
            Item::Packet { micros, packet } => {
                *counts.entry((packet.direction.short(), packet.name())).or_default() += 1;
                if mode == Mode::Oracle {
                    line(&mut out, format_args!("{}", oracle_line(packet)));
                } else if mode == Mode::Fields {
                    if let Some(f) = packets::fields_line(packet.id, &packet.body) {
                        line(&mut out, format_args!("{} {} {} {f}", packet.direction.short(), packet.id, packet.name()));
                    }
                } else if !stats_only {
                    let about = packets::describe(packet.id, &packet.body).unwrap_or_default();
                    line(
                        &mut out,
                        format_args!(
                            "{:>10}  {:<3} {:<24} {:>6} B  {}",
                            time(*micros - first),
                            packet.direction.short(),
                            packet.name(),
                            packet.size(),
                            about
                        ),
                    );
                }
            }
            Item::Note { micros, direction, note } => {
                notes += 1;
                if !oracle && !stats_only {
                    line(&mut out, format_args!("{:>10}  {:<3} -- {}", time(*micros - first), direction.short(), describe_note(note)));
                }
            }
            Item::Gap { micros, direction, skipped } => {
                notes += 1;
                if !oracle && !stats_only {
                    line(
                        &mut out,
                        format_args!("{:>10}  {:<3} -- packets missing: skipped {skipped} bytes", time(*micros - first), direction.short()),
                    );
                }
            }
        }
    }
    if out.flush().is_err() {
        return ExitCode::SUCCESS; // the reader went away (e.g. `| head`)
    }
    drop(out);
    let total: usize = counts.values().sum();
    eprintln!("{file}: {} frames (link type {}), {total} game packets, {notes} notes, in {:.0?}", r.frames, r.link_type, took);
    if stats_only {
        let mut by_count: Vec<_> = counts.into_iter().collect();
        by_count.sort_by_key(|(_, n)| std::cmp::Reverse(*n));
        for ((dir, name), n) in by_count {
            eprintln!("  {n:>7}  {dir:<3} {name}");
        }
    }
    ExitCode::SUCCESS
}

/// Packets that come many times a second: shown in `live` only with `--all`.
const BUSY: &[&str] = &[
    "NEWTICK",
    "MOVE",
    "UPDATE",
    "UPDATEACK",
    "PING",
    "PONG",
    "PLAYERSHOOT",
    "SERVERPLAYERSHOOT",
    "ALLYSHOOT",
    "SHOOT_ACK",
    "ENEMYSHOOT",
    "ENEMYHIT",
    "OTHERHIT",
    "PLAYERHIT",
    "SQUAREHIT",
    "DAMAGE",
    "AOE",
    "AOEACK",
    "GROUNDDAMAGE",
    "GOTO",
    "GOTOACK",
    "SHOWEFFECT",
];

/// A live capture piped in (pcap from `tcpdump -U -w -`): packets as they come, and with game data what the world
/// says about you.
fn live(all: bool, assets: Option<&std::path::Path>) -> ExitCode {
    let mut input = match lg_capture::pcap::StreamReader::new(std::io::stdin().lock()) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("no capture on standard input ({e}). Pipe one in:\n  sudo tcpdump -i any -U -w - 'tcp port 2050' | lg-replay live");
            return ExitCode::FAILURE;
        }
    };
    let mut world = assets.map(|a| {
        let game = lg_gamedata::GameData::load(a);
        if game.objects.is_empty() {
            eprintln!("no game data in {} (expected ObjectID.list, xml/players.xml)", a.display());
        }
        lg_world::World::new(game, std::collections::HashMap::new())
    });
    eprintln!("listening (link type {}); start or change servers in the game to see everything. Ctrl+C ends.", input.link_type);
    let mut session = lg_replay::Session::new(input.link_type);
    let mut items = Vec::new();
    let mut first = None;
    let mut last_summary = String::new();
    let mut out = std::io::stdout().lock();
    while let Some(frame) = input.next_frame() {
        let (micros, data) = match frame {
            Ok(f) => f,
            Err(e) => {
                eprintln!("capture stream broken: {e}");
                return ExitCode::FAILURE;
            }
        };
        session.frame(micros, &data, &mut items);
        for item in items.drain(..) {
            let at = item_micros(&item);
            let t = time(at - *first.get_or_insert(at));
            match &item {
                Item::Packet { packet, .. } => {
                    if all || !BUSY.contains(&packet.name()) {
                        let about = packets::describe(packet.id, &packet.body).unwrap_or_default();
                        line(
                            &mut out,
                            format_args!(
                                "{t:>10}  {:<3} {:<24} {:>6} B  {}",
                                packet.direction.short(),
                                packet.name(),
                                packet.size(),
                                about
                            ),
                        );
                    }
                    if let Some(w) = world.as_mut()
                        && lg_world::FED.contains(&packet.id)
                        && let Some(Ok(d)) = packets::decode(packet.id, &packet.body)
                    {
                        let _ = w.packet(&d.packet);
                        let summary = summary(w);
                        if summary != last_summary {
                            line(&mut out, format_args!("{t:>10}  ==  {summary}"));
                            last_summary = summary;
                        }
                    }
                }
                Item::Note { direction, note, .. } => {
                    line(&mut out, format_args!("{t:>10}  {:<3} -- {}", direction.short(), describe_note(note)))
                }
                Item::Gap { direction, skipped, .. } => {
                    line(&mut out, format_args!("{t:>10}  {:<3} -- packets missing: skipped {skipped} bytes", direction.short()))
                }
            }
        }
    }
    ExitCode::SUCCESS
}

/// Where you are and who you are, in one line.
fn summary(w: &lg_world::World) -> String {
    let map = w.map.as_ref().map_or("an unknown map".to_string(), |m| {
        let name = if m.display_name.starts_with('{') { &m.name } else { &m.display_name };
        if w.map_guessed { format!("{name} (guessed)") } else { name.clone() }
    });
    let you = match w.me() {
        Some(me) => {
            let name = me.text(lg_world::stats::NAME).map(|n| n.split(',').next().unwrap_or(n).to_string());
            let class = w.data().class(me.object_type).map(|c| c.name.clone());
            let level = me.value(lg_world::stats::LEVEL);
            let mut s = name.unwrap_or_else(|| format!("object {}", me.id));
            if let Some(c) = class {
                s.push_str(&format!(", {c}"));
            }
            if let Some(l) = level {
                s.push_str(&format!(" level {l}"));
            }
            s
        }
        None if w.world_player_id > 0 => format!("object {} (not in view yet)", w.world_player_id),
        None => "not known yet".into(),
    };
    let others = w.player_list_updated.iter().filter(|id| Some(**id) != w.player).count();
    format!("in {map}; you: {you}; {others} other players in view")
}

/// The world state after each packet it is fed, as `oracle/WorldOracle.java` prints Java's.
fn world(file: &str, assets: &std::path::Path) -> ExitCode {
    let data = match std::fs::read(file) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("cannot read {file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let r = match replay(&data) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let game = lg_gamedata::GameData::load(assets);
    for p in &game.problems {
        eprintln!("{p}");
    }
    let mut w = lg_world::World::new(game, std::collections::HashMap::new());
    let mut out = BufWriter::new(std::io::stdout().lock());
    let full = |out: &mut BufWriter<_>, when: &str, state: &str| {
        line(out, format_args!("state {when}"));
        for l in state.lines() {
            line(out, format_args!("  {l}"));
        }
    };
    for item in &r.items {
        let Item::Packet { packet, .. } = item else { continue };
        if !lg_world::FED.contains(&packet.id) {
            continue;
        }
        let Some(Ok(d)) = packets::decode(packet.id, &packet.body) else { continue }; // the app drops it
        let is_map = matches!(d.packet, packets::Decoded::MapInfo(_));
        if is_map {
            full(&mut out, &format!("before {}", packet.name()), &w.dump());
        }
        let problem = match w.packet(&d.packet) {
            Ok(()) => "",
            Err(e) => {
                eprintln!("world: {}: {}", packet.name(), e.0);
                " exception"
            }
        };
        let state = w.dump();
        line(
            &mut out,
            format_args!(
                "{} {} {} {:08x}{problem}",
                packet.direction.short(),
                packet.id,
                packet.name(),
                lg_protocol::dump::crc32(state.as_bytes())
            ),
        );
        if is_map {
            full(&mut out, &format!("after {}", packet.name()), &state);
        }
    }
    full(&mut out, "end", &w.dump());
    let _ = out.flush();
    ExitCode::SUCCESS
}

/// Every object, tile and class as `oracle/GameDataOracle.java` prints them.
fn gamedata(dir: &std::path::Path) -> ExitCode {
    let d = lg_gamedata::GameData::load(dir);
    for p in &d.problems {
        eprintln!("{p}");
    }
    let mut out = BufWriter::new(std::io::stdout().lock());
    let mut ids: Vec<_> = d.objects.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let o = &d.objects[&id];
        let p: Vec<String> =
            o.projectiles.iter().map(|p| format!("{}/{}/{}/{}", p.min_damage, p.max_damage, p.armor_piercing, p.slot_type)).collect();
        line(
            &mut out,
            format_args!("object {id}|{}|{}|{}|{}|{}|{}|{}", o.name(), o.class, o.group, o.label, o.texture, o.id_name, p.join(" ")),
        );
    }
    let mut ids: Vec<_> = d.tiles.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let t = &d.tiles[&id];
        line(&mut out, format_args!("tile {id}|{}|{}|{}", t.texture, t.damage, t.id_name));
    }
    let join = |v: &[i32]| v.iter().map(i32::to_string).collect::<Vec<_>>().join(" ");
    for c in &d.classes {
        line(&mut out, format_args!("class {}|{}|{}|{}|{}", c.id, c.name, join(&c.max_stats), join(&c.base_gear), join(&c.weapon_group)));
    }
    if out.flush().is_err() {
        return ExitCode::SUCCESS;
    }
    ExitCode::SUCCESS
}

/// One line of output; stops quietly when the reader went away (e.g. `| head`).
fn line(out: &mut impl Write, args: std::fmt::Arguments) {
    if writeln!(out, "{args}").is_err() {
        std::process::exit(0);
    }
}

fn item_micros(i: &Item) -> u64 {
    match i {
        Item::Packet { micros, .. } | Item::Note { micros, .. } | Item::Gap { micros, .. } => *micros,
    }
}

/// Time since the first item, as seconds with milliseconds.
fn time(micros: u64) -> String {
    format!("{}.{:03}s", micros / 1_000_000, micros % 1_000_000 / 1000)
}

fn describe_note(n: &Note) -> String {
    match n {
        Note::Align(AlignNote::Synced { offset, tick }) => format!("cipher in step (offset {offset}, tick {tick})"),
        Note::Align(AlignNote::Lost { got, expected }) => format!("cipher out of step: tick {got}, expected {expected}"),
        Note::Align(AlignNote::Failed) => "cipher position not found".into(),
        Note::BadSize(s) => format!("impossible packet size {s}: stream out of step"),
    }
}
