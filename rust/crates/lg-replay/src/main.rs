//! lg-replay: prints the game packets in a recorded session.
//!
//!   lg-replay <session.pcap>            every packet, with time, direction, size and (for some) its content
//!   lg-replay <session.pcap> --oracle   one line per packet to compare with the Java client (oracle/compare.sh)
//!   lg-replay <session.pcap> --fields   every field of each packet that has a decoder, to compare the same way
//!   lg-replay decoded-ids               the ids of the packets that have a decoder, for oracle/compare.sh
//!   lg-replay gamedata <assets>         the game data read from an assets folder, to compare with Java
//!   lg-replay <session.pcap> --stats    only the summary
//!   lg-replay suggest <data dir>        the suggested loadouts of every character, as oracle/SuggestOracle.java
//!   lg-replay sprites <assets>          every object's sprite and dye mask, as oracle/SpriteOracle.java prints them
//!   lg-replay extract <resources.assets> <assets>
//!                                       the game data extracted from the game as the Java app does, then each
//!                                       sprite sheet's pixels as oracle/AssetOracle.java prints them
//!   lg-replay <session.pcap> --world <assets> [--java-data <dir>] [--web <dir>] [--save <dir>]
//!                                       the world state after each packet the world is fed, to compare
//!                                       with Java (oracle/compare.sh): a checksum, the whole state on map changes
//!   lg-replay synth <out.pcap> [seed]   writes a made-up session, for testing without a recording
//!   lg-replay synth-fight <out.pcap> [seed]   writes a made-up fight, for testing the damage logic
//!   lg-replay synth-login <out.pcap> <answers dir> [seed]
//!                                       writes a made-up login and the game's made-up web answers to it
//!   lg-replay synth-items <out.pcap> [seed]   writes a made-up session with items (vault, moves, forge, trades...)
//!   lg-replay capture [--all] [--world <assets>]
//!                                       capturing itself (libpcap / Npcap), as the Java app's sniffer does
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
        [cmd, dir, remote, out] if cmd == "sync" => sync(dir.as_ref(), remote.as_ref(), out.as_ref()),
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
        [cmd, out, rest @ ..] if cmd == "synth-fight" => {
            let seed = rest.first().and_then(|s| s.parse().ok()).unwrap_or(1);
            let s = lg_replay::fight::fight(seed);
            if let Err(e) = std::fs::write(out, &s.pcap) {
                eprintln!("cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            eprintln!("{out}: a made-up fight, {} packets in, {} out (seed {seed})", s.incoming.len(), s.outgoing.len());
            ExitCode::SUCCESS
        }
        [cmd, out, dir, rest @ ..] if cmd == "synth-login" => {
            let seed = rest.first().and_then(|s| s.parse().ok()).unwrap_or(1);
            let (s, files) = lg_replay::login::login(seed);
            if let Err(e) = std::fs::write(out, &s.pcap).and_then(|()| std::fs::create_dir_all(dir)) {
                eprintln!("cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            for (name, text) in &files {
                if let Err(e) = std::fs::write(std::path::Path::new(dir).join(name), text) {
                    eprintln!("cannot write {name}: {e}");
                    return ExitCode::FAILURE;
                }
            }
            eprintln!(
                "{out}: a made-up login, {} packets in, {} out; {} game answers in {dir} (seed {seed})",
                s.incoming.len(),
                s.outgoing.len(),
                files.len()
            );
            ExitCode::SUCCESS
        }
        [cmd, out, rest @ ..] if cmd == "synth-items" => {
            let seed = rest.first().and_then(|s| s.parse().ok()).unwrap_or(1);
            let s = lg_replay::items::items(seed);
            if let Err(e) = std::fs::write(out, &s.pcap) {
                eprintln!("cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            eprintln!("{out}: a made-up session with items, {} packets in, {} out (seed {seed})", s.incoming.len(), s.outgoing.len());
            ExitCode::SUCCESS
        }
        [cmd, dir] if cmd == "gamedata" => gamedata(std::path::Path::new(dir)),
        [cmd, dir] if cmd == "sprites" => sprites(std::path::Path::new(dir)),
        [cmd, dir] if cmd == "difficulty" => {
            let m = lg_gamedata::difficulty::load(&std::path::Path::new(dir).join("xml"));
            let mut v: Vec<_> = m.into_iter().collect();
            v.sort_by(|a, b| a.0.encode_utf16().cmp(b.0.encode_utf16()));
            for (k, d) in v {
                println!("{k}={d:?}");
            }
            ExitCode::SUCCESS
        }
        [cmd, dir] if cmd == "suggest" => suggest(std::path::Path::new(dir)),
        [cmd, game, out] if cmd == "extract" => extract(std::path::Path::new(game), std::path::Path::new(out)),
        [cmd, rest @ ..] if cmd == "capture" => {
            let all = rest.iter().any(|f| f == "--all");
            let assets = rest.iter().position(|f| f == "--world").and_then(|i| rest.get(i + 1));
            capture(all, assets.map(std::path::Path::new))
        }
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
            if let [flag, assets, rest @ ..] = flags
                && flag == "--world"
            {
                // --java-data <dir>: what the Java app kept in its folder, taken over (read only); --web <dir>: the
                // game's web answers from files, and HELLO handed on (nothing reaches the game)
                let (mut java, mut web, mut save) = (None, None, None);
                let mut i = 0;
                while i < rest.len() {
                    match (rest[i].as_str(), rest.get(i + 1)) {
                        ("--java-data", Some(d)) => java = Some(std::path::Path::new(d)),
                        ("--web", Some(d)) => web = Some(std::path::Path::new(d)),
                        ("--save", Some(d)) => save = Some(std::path::Path::new(d)),
                        _ => {
                            eprintln!("usage: lg-replay <session.pcap> --world <assets> [--java-data <dir>] [--web <dir>] [--save <dir>]");
                            return ExitCode::FAILURE;
                        }
                    }
                    i += 2;
                }
                return world(file, std::path::Path::new(assets), java, web, save);
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
    let link_type = input.link_type;
    eprintln!("listening (link type {link_type}); start or change servers in the game to see everything. Ctrl+C ends.");
    show(all, assets, move || input.next_frame().map(|f| f.map(|(m, d)| (link_type, m, d)).map_err(|e| e.to_string())))
}

/// Capturing itself (Java: the app's own sniffer): every usable interface opened, the game's one kept.
fn capture(all: bool, assets: Option<&std::path::Path>) -> ExitCode {
    let cap = match lg_capture::live::start("tcp port 2050") {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{e}");
            if matches!(e, lg_capture::live::Error::Permission(_)) && cfg!(target_os = "linux") {
                eprintln!(
                    "Run it as root, or give it the capture rights once:\n  sudo setcap cap_net_raw,cap_net_admin=eip <path to lg-replay>"
                );
            }
            return ExitCode::FAILURE;
        }
    };
    let names: Vec<String> = cap.opened.iter().map(|i| i.name.clone()).collect();
    eprintln!("capturing on {}; start or change servers in the game to see everything. Ctrl+C ends.", names.join(", "));
    let mut told = false;
    show(all, assets, move || {
        let f = cap.frames.recv().ok()?;
        if !told {
            told = true;
            eprintln!("the game's packets come on {}", names[f.interface]);
        }
        Some(Ok((f.link_type, f.micros, f.data)))
    })
}

/// Frames as they come (link type, µs, bytes), shown: the packets, and with game data what the world says of you.
fn show(
    all: bool,
    assets: Option<&std::path::Path>,
    mut next_frame: impl FnMut() -> Option<Result<(u32, u64, Vec<u8>), String>>,
) -> ExitCode {
    let mut session: Option<lg_replay::Session> = None;
    let world_for = |a: &std::path::Path| {
        let game = lg_gamedata::GameData::load(a);
        if game.objects.is_empty() {
            eprintln!("no game data in {} (expected ObjectID.list, xml/players.xml)", a.display());
        }
        lg_world::World::new(game)
    };
    let mut world = assets.map(world_for);
    let mut items = Vec::new();
    let mut first = None;
    let mut last_summary = String::new();
    let mut out = std::io::stdout().lock();
    while let Some(frame) = next_frame() {
        let (link_type, micros, data) = match frame {
            Ok(f) => f,
            Err(e) => {
                eprintln!("capture stream broken: {e}");
                return ExitCode::FAILURE;
            }
        };
        session.get_or_insert_with(|| lg_replay::Session::new(link_type)).frame(micros, &data, &mut items);
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
fn world(
    file: &str,
    assets: &std::path::Path,
    java_data: Option<&std::path::Path>,
    web: Option<&std::path::Path>,
    save: Option<&std::path::Path>,
) -> ExitCode {
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
    let mut w = lg_app::App::importing(lg_world::World::new(game), 0, java_data);
    if let Some(dir) = web {
        w.web = Box::new(lg_app::web::Canned::new(dir));
    }
    let fed = |id: u8| lg_app::FED.contains(&id) || (web.is_some() && id == lg_app::HELLO);
    // taking over the Java folder into the own one: a full copy; then what the app writes on starting
    if let (Some(_), Some(j)) = (save, java_data) {
        w.copy_from_java(j);
    }
    if let Some(dir) = save
        && let Err(e) = w.save_changed(dir)
    {
        eprintln!("cannot save into {}: {e}", dir.display());
    }
    let mut out = BufWriter::new(std::io::stdout().lock());
    let full = |out: &mut BufWriter<_>, when: &str, state: &str| {
        line(out, format_args!("state {when}"));
        for l in state.lines() {
            line(out, format_args!("  {l}"));
        }
    };
    for item in &r.items {
        let Item::Packet { packet, micros } = item else { continue };
        if lg_app::RAW.contains(&packet.id) {
            w.raw(packet.id, &packet.body, (*micros / 1000) as i64);
            if let Some(dir) = save
                && let Err(e) = w.save_changed(dir)
            {
                eprintln!("cannot save into {}: {e}", dir.display());
            }
            let state = w.dump();
            line(
                &mut out,
                format_args!(
                    "{} {} {} {:08x}",
                    packet.direction.short(),
                    packet.id,
                    packet.name(),
                    lg_protocol::dump::crc32(state.as_bytes())
                ),
            );
            continue;
        }
        if !fed(packet.id) {
            continue;
        }
        let Some(Ok(d)) = packets::decode(packet.id, &packet.body) else { continue }; // the app drops it
        let is_map = matches!(d.packet, packets::Decoded::MapInfo(_));
        if is_map {
            full(&mut out, &format!("before {}", packet.name()), &w.dump());
        }
        let problem = match w.packet(&d.packet, (*micros / 1000) as i64) {
            Ok(()) => "",
            Err(e) => {
                eprintln!("world: {}: {}", packet.name(), e.0);
                " exception"
            }
        };
        if let Some(dir) = save
            && let Err(e) = w.save_changed(dir)
        {
            eprintln!("cannot save into {}: {e}", dir.display());
        }
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

/// Merging another PC's documents (`<remote>/<kind>.json`) into the records of `dir`, then what each kind would
/// upload (see `oracle/SyncOracle.java`): "<kind> <merged> <count>" and the document.
fn sync(dir: &std::path::Path, remote: &std::path::Path, out: &std::path::Path) -> ExitCode {
    let now = std::env::var("LG_NOW")
        .ok()
        .and_then(|n| n.parse().ok())
        .unwrap_or_else(|| std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64));
    let world = lg_world::World::new(lg_gamedata::GameData::load(&dir.join("assets")));
    let mut app = lg_app::App::importing(world, now, Some(dir));
    // the profile as its file has it (the app's start updates it; Java's oracle doesn't start the app)
    app.profile = lg_app::profile::Profile::import(dir);
    let mut merged = Vec::new();
    for (kind, _) in lg_app::sync::KINDS {
        let m = match std::fs::read_to_string(remote.join(format!("{kind}.json"))) {
            Err(_) => "-".to_string(),
            Ok(text) => match lg_world::json::parse(&text) {
                Err(e) => format!("unreadable: {e}"),
                Ok(j) => app.sync_merge(kind, &j, now).map_or("thrown".into(), |c| c.to_string()),
            },
        };
        merged.push(m);
    }
    let mut text = String::new();
    for ((kind, _), m) in lg_app::sync::KINDS.iter().zip(merged) {
        text.push_str(&format!("{kind} {m} {}\n{}\n", app.sync_count(kind), app.sync_export(kind)));
    }
    match std::fs::write(out, text) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("cannot write {}: {e}", out.display());
            ExitCode::FAILURE
        }
    }
}

/// The suggested loadouts (see `oracle/SuggestOracle.java`).
fn suggest(dir: &std::path::Path) -> ExitCode {
    let world = lg_world::World::new(lg_gamedata::GameData::load(&dir.join("assets")));
    let app = lg_app::App::importing(world, 0, Some(dir));
    let en = lg_app::loadout::Enchantments::load(&dir.join("assets").join("xml"));
    let choices: [&[&str]; 7] =
        [&["MAXHP", "DPS", "HPREGEN"], &["ATT", "DEX"], &["DPS"], &["LOOT"], &["DEF", "MAXHP"], &["WIS", "MPREGEN"], &[]];
    let mut ids: Vec<i32> = app.characters.all().filter(|c| c.alive).map(|c| c.char_id).collect();
    ids.sort_unstable();
    let props = lg_app::properties::Properties::in_memory(&std::fs::read_to_string(dir.join("realmShark.properties")).unwrap_or_default());
    let base = lg_app::loadout::Settings::from(&props);
    let mut out = BufWriter::new(std::io::stdout().lock());
    for id in ids {
        let c = app.characters.get(id).expect("char");
        let class = app.world.data().classes.iter().find(|k| k.id == c.class_type);
        if class.and_then(|k| k.slot_types).is_none() {
            continue;
        }
        let pool = lg_app::loadout::pool(&app, c);
        line(&mut out, format_args!("pool {id} [{}]", pool.iter().map(i32::to_string).collect::<Vec<_>>().join(",")));
        for (k, chosen) in choices.iter().enumerate() {
            for scope in 0..3 {
                for second in [0, 2] {
                    for def in [0.0, 40.0] {
                        if def > 0.0 && !chosen.contains(&"DPS") {
                            continue;
                        }
                        let s = lg_app::loadout::Settings {
                            chosen: chosen.iter().map(|x| x.to_string()).collect(),
                            scope,
                            enemy_def: def,
                            second_chance: second,
                            ..base.clone()
                        };
                        let Some(picks) = lg_app::loadout::suggestion(&app, &en, c, &s) else { continue };
                        for (slot, pk) in picks.iter().enumerate() {
                            let e = pk.enchants.as_ref().map_or("null".to_string(), |v| {
                                format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(","))
                            });
                            let planned: Vec<String> = pk.plan.picks.iter().map(|i| en.get(*i).kind.to_string()).collect();
                            line(
                                &mut out,
                                format_args!(
                                    "suggest {id} {k}/{scope}/{second}/{} {slot} {} {} {e} [{}] {:.6}",
                                    def as i32,
                                    pk.id,
                                    pk.r#where.replace(' ', "_"),
                                    planned.join(","),
                                    pk.plan.score
                                ),
                            );
                        }
                    }
                }
            }
        }
    }
    let _ = out.flush();
    ExitCode::SUCCESS
}

/// Every object's sprite and mask: `sprite|mask <id> <w>x<h> <crc of the ARGB pixels>`, or `empty` / `none`.
fn sprites(dir: &std::path::Path) -> ExitCode {
    let data = lg_gamedata::GameData::load(dir);
    let mut sprites = lg_gamedata::sprites::Sprites::new(dir);
    let mut ids: Vec<i32> = data.objects.keys().copied().filter(|id| *id > 0).collect();
    ids.sort_unstable();
    let describe = |im: Option<lg_gamedata::sprites::Image>, none: &str| match im {
        None => none.to_string(),
        Some(im) => {
            let argb: Vec<u8> = im.rgba.as_chunks::<4>().0.iter().flat_map(|p| [p[3], p[0], p[1], p[2]]).collect();
            format!("{}x{} {:08x}", im.w, im.h, lg_replay::crc32(&argb))
        }
    };
    let mut out = BufWriter::new(std::io::stdout().lock());
    for id in ids {
        let s = describe(sprites.object(&data, id), "empty");
        let m = describe(sprites.mask(&data, id), "none");
        line(&mut out, format_args!("sprite {id} {s}"));
        line(&mut out, format_args!("mask {id} {m}"));
    }
    let _ = out.flush();
    ExitCode::SUCCESS
}

/// The extraction, then `png <name> <width> <height> <crc of the ARGB pixels>` per sprite sheet written.
fn extract(game: &std::path::Path, out: &std::path::Path) -> ExitCode {
    if let Err(e) = lg_gamedata::extract::extract(game, out, &mut |_| {}) {
        eprintln!("{e}");
        return ExitCode::FAILURE;
    }
    let dir = out.join("sprites");
    let mut names: Vec<String> = std::fs::read_dir(&dir)
        .map(|d| d.filter_map(Result::ok).map(|e| e.file_name().to_string_lossy().into_owned()).filter(|n| n.ends_with(".png")).collect())
        .unwrap_or_default();
    names.sort();
    for n in names {
        let dec = png::Decoder::new(std::io::BufReader::new(std::fs::File::open(dir.join(&n)).expect("png")));
        let mut r = dec.read_info().expect("png");
        let mut buf = vec![0; r.output_buffer_size()];
        let info = r.next_frame(&mut buf).expect("png");
        let argb: Vec<u8> = buf[..info.buffer_size()].as_chunks::<4>().0.iter().flat_map(|p| [p[3], p[0], p[1], p[2]]).collect();
        println!("png {n} {} {} {:08x}", info.width, info.height, lg_replay::crc32(&argb));
    }
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
    let mut scaling: Vec<_> = d.abilities.scaling.values().collect();
    scaling.sort_by_key(|s| s.weapon_id);
    for s in scaling {
        line(&mut out, format_args!("scaling {}|{}|{}|{:08x}|{}", s.weapon_id, s.stat, s.min, s.damage_per_stat.to_bits(), s.num_shots));
    }
    let mut shot: Vec<_> = d.abilities.projectile_to_weapon.iter().collect();
    shot.sort();
    for (p, w) in shot {
        line(&mut out, format_args!("shotby {p}|{w}"));
    }
    let mut ench: Vec<_> = d.enchants.values().collect();
    ench.sort_by_key(|e| e.id);
    for e in ench {
        let f = e.effect;
        let floats: Vec<String> = [
            f.min_damage,
            f.max_damage,
            f.rate_of_fire,
            f.stat_damage,
            f.mana_flat_per_sec,
            f.mana_percent_per_sec,
            f.life_flat_per_sec,
            f.life_percent_per_sec,
            f.loot_bonus,
        ]
        .iter()
        .map(|x| format!("{:08x}", x.to_bits()))
        .collect();
        let opt = |s: &Option<String>| s.clone().unwrap_or_else(|| "null".into());
        line(&mut out, format_args!("enchant {}|{}|{}|{}", e.id, opt(&e.name), opt(&e.internal_id), floats.join(" ")));
    }
    let dg = &d.dungeons;
    let tables: [Vec<String>; 4] = [
        dg.portal_names.iter().map(|(k, v)| format!("ID_TO_NAME_PORTAL {k}|{v}")).collect(),
        dg.portal_by_name.iter().map(|(k, v)| format!("NAME_TO_ID_PORTAL {k}|{v}")).collect(),
        dg.modifier_names.iter().map(|(k, v)| format!("ID_TO_NAME_MODS {k}|{v}")).collect(),
        dg.modifier_by_name.iter().map(|(k, v)| format!("NAME_TO_ID_MODS {k}|{v}")).collect(),
    ];
    for mut lines in tables {
        lines.sort();
        for l in lines {
            line(&mut out, format_args!("{l}"));
        }
    }
    for n in dg.all_names() {
        line(&mut out, format_args!("dungeon {n}"));
    }
    let mut en: Vec<_> = dg.enemies.iter().collect();
    en.sort();
    for (k, v) in en {
        line(&mut out, format_args!("enemy {k}|{v}"));
    }
    let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
    let f = |v: f64| format!("{:016x}", v.to_bits());
    let mut ids: Vec<_> = d.items.items.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let i = &d.items.items[&id];
        let mut b = format!(
            "item {}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|",
            i.id,
            q(&i.name),
            i.slot_type,
            q(&i.tier),
            q(&i.description),
            i.min_damage,
            i.max_damage,
            i.mp_cost,
            i.feed_power,
            i.soulbound,
            i.chest,
            i.quickslot_only,
            i.loot_boost
        );
        for st in &i.on_equip {
            b.push_str(&format!("{}:{};", st.stat, st.amount));
        }
        b.push('|');
        b.push_str(&i.labels.iter().cloned().collect::<Vec<_>>().join(","));
        b.push('|');
        if let Some(e) = &i.enchant_slots {
            b.push_str(&format!("{};", q(&e.pool)));
            for v in &e.slot_chance {
                b.push_str(&format!("{},", f(*v)));
            }
            b.push_str(&format!(";{};{};{};", f(e.seasonal_mod), f(e.forge_mod), e.dust.as_deref().map_or("null".into(), q)));
            for v in &e.dust_amounts {
                b.push_str(&format!("{v},"));
            }
        }
        b.push_str(&format!("|{}|", q(&i.key)));
        if let Some(w) = &i.weapon {
            b.push_str(&format!("{};{};", f(w.rate_of_fire), w.num_projectiles));
            for (k, s) in &w.shots {
                b.push_str(&format!("{k}={}/{}/{},", f(s.min), f(s.max), s.armor_piercing));
            }
            b.push(';');
            for v in &w.volleys {
                b.push_str(&format!(
                    "{}/{}/{}/{}/{}/{},",
                    v.projectile,
                    v.count,
                    f(v.rate_of_fire),
                    v.burst,
                    f(v.burst_delay),
                    f(v.burst_min_delay)
                ));
            }
        }
        b.push_str(&format!(
            "|{}|{}|{}|{}|{}",
            i.second_chance,
            i.collection_icon,
            i.named,
            i.is_collectible(),
            i.origin().map_or("null".into(), q)
        ));
        line(&mut out, format_args!("{b}"));
    }
    let c: Vec<String> = d.items.collectible.iter().map(i32::to_string).collect();
    let c = if c.is_empty() { "collectible".to_string() } else { format!("collectible {}", c.join(" ")) };
    line(&mut out, format_args!("{c}"));
    let mut ts: Vec<_> = d.items.transform_skins.iter().copied().collect();
    ts.sort_unstable();
    line(&mut out, format_args!("transform [{}]", ts.iter().map(i32::to_string).collect::<Vec<_>>().join(", ")));
    let mut amulets: Vec<_> = d.amulets.by_item.values().collect();
    amulets.sort_by_key(|a| a.item);
    for a in amulets {
        line(&mut out, format_args!("amulet {}|{}|{:016x}|{}|{}", a.item, a.name, a.threshold.to_bits(), a.effect, a.cooldown_s));
    }
    line(&mut out, format_args!("restoration {}", d.amulets.restoration));
    for s in &d.st_sets {
        let pieces = s.pieces.iter().map(i32::to_string).collect::<Vec<_>>().join(",");
        line(&mut out, format_args!("stset {}|{}|{}|{}|[{pieces}]|{}", q(&s.name), q(&s.title), s.class_name, s.skin, q(&s.bonus)));
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
