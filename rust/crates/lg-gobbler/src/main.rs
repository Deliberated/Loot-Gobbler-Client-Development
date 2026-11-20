//! Loot Gobbler, without a window yet: captures the game as the Java app does, keeps your records and writes them
//! into its own folder (next to the program, or `--data`) in the Java app's formats. On a first start the Java
//! app's folder (`--java`) is taken over: read only, copied in full.
//!
//!   loot-gobbler [--java <Java app folder>] [--data <folder>] [--assets <folder>] [--offline] [--pcap <file>]
//!
//! `--assets`: the game data (ObjectID.list, xml/...), by default `assets` in the own folder, else in the Java one.
//! `--offline`: never ask the game's web API (character list, exaltations, friends, missions).
//! `--pcap`: a recording instead of capturing, played as if it happened now.

use lg_replay::{Item, Session};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

struct Options {
    java: Option<PathBuf>,
    data: Option<PathBuf>,
    assets: Option<PathBuf>,
    offline: bool,
    pcap: Option<PathBuf>,
}

fn options() -> Result<Options, String> {
    let mut o = Options { java: None, data: None, assets: None, offline: false, pcap: None };
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut value = || args.next().map(PathBuf::from).ok_or(format!("{a} needs a value"));
        match a.as_str() {
            "--java" => o.java = Some(value()?),
            "--data" => o.data = Some(value()?),
            "--assets" => o.assets = Some(value()?),
            "--pcap" => o.pcap = Some(value()?),
            "--offline" => o.offline = true,
            _ => return Err(format!("unknown: {a}")),
        }
    }
    Ok(o)
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

fn main() -> ExitCode {
    let o = match options() {
        Ok(o) => o,
        Err(e) => {
            eprintln!("{e}\nusage: loot-gobbler [--java <dir>] [--data <dir>] [--assets <dir>] [--offline] [--pcap <file>]");
            return ExitCode::FAILURE;
        }
    };
    let own = match o.data.clone().map_or_else(lg_app::files::own_folder, Ok) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("no own folder: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::create_dir_all(&own) {
        eprintln!("cannot make {}: {e}", own.display());
        return ExitCode::FAILURE;
    }
    let assets = o.assets.clone().unwrap_or_else(|| {
        let mine = own.join("assets");
        match &o.java {
            Some(j) if !mine.exists() => j.join("assets"),
            _ => mine,
        }
    });
    let game = lg_gamedata::GameData::load(&assets);
    for p in &game.problems {
        eprintln!("{p}");
    }
    if game.objects.is_empty() {
        eprintln!("no game data in {} (ObjectID.list, xml/...): give --assets or --java", assets.display());
        return ExitCode::FAILURE;
    }
    let first = !lg_app::files::holds_data(&own);
    let mut app = match lg_app::App::start(lg_world::World::new(game), now_ms(), &own, o.java.as_deref()) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("cannot write into {}: {e}", own.display());
            return ExitCode::FAILURE;
        }
    };
    if first && o.java.as_deref().is_some_and(lg_app::files::holds_data) {
        eprintln!("took the Java app's records over into {}", own.display());
    }
    eprintln!("your records: {}", own.display());
    if !o.offline {
        app.web = Box::new(lg_net::Online::new());
    }
    match &o.pcap {
        Some(f) => play(&mut app, &own, f),
        None => capture(&mut app, &own),
    }
}

/// One frame: the packets it completes go to the app, and what changed is written.
struct Feeder {
    session: Option<Session>,
    items: Vec<Item>,
    map: Option<String>,
}

impl Feeder {
    fn frame(&mut self, app: &mut lg_app::App, own: &Path, link_type: u32, now: i64, data: &[u8]) {
        self.session.get_or_insert_with(|| Session::new(link_type)).frame(0, data, &mut self.items);
        for item in self.items.drain(..) {
            let Item::Packet { packet, .. } = item else { continue };
            if lg_app::RAW.contains(&packet.id) {
                app.raw(packet.id, &packet.body, now);
            } else if lg_app::FED.contains(&packet.id) || packet.id == lg_app::HELLO {
                let Some(Ok(d)) = lg_protocol::packets::decode(packet.id, &packet.body) else { continue };
                if let Err(e) = app.packet(&d.packet, now) {
                    eprintln!("{}: {}", packet.name(), e.0);
                }
            } else {
                continue;
            }
            if let Err(e) = app.save_changed(own) {
                eprintln!("cannot save into {}: {e}", own.display());
            }
            let map = app.world.map.as_ref().map(|m| m.name.clone());
            if map != self.map {
                if let Some(m) = &map {
                    eprintln!("in {m}");
                }
                self.map = map;
            }
        }
    }
}

fn feeder() -> Feeder {
    Feeder { session: None, items: Vec::new(), map: None }
}

fn capture(app: &mut lg_app::App, own: &Path) -> ExitCode {
    let cap = match lg_capture::live::start("tcp port 2050") {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{e}");
            if matches!(e, lg_capture::live::Error::Permission(_)) && cfg!(target_os = "linux") {
                eprintln!(
                    "Run it as root, or give it the capture rights once:\n  sudo setcap cap_net_raw,cap_net_admin=eip <path to loot-gobbler>"
                );
            }
            return ExitCode::FAILURE;
        }
    };
    let names: Vec<String> = cap.opened.iter().map(|i| i.name.clone()).collect();
    eprintln!("capturing on {}; start or change servers in the game. Ctrl+C ends.", names.join(", "));
    let mut f = feeder();
    while let Ok(frame) = cap.frames.recv() {
        f.frame(app, own, frame.link_type, (frame.micros / 1000) as i64, &frame.data);
    }
    ExitCode::SUCCESS
}

/// A recording, its times moved to now (the first frame now, the rest as far apart as they were).
fn play(app: &mut lg_app::App, own: &Path, file: &Path) -> ExitCode {
    let bytes = match std::fs::read(file) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("cannot read {}: {e}", file.display());
            return ExitCode::FAILURE;
        }
    };
    let mut r = match lg_capture::pcap::Reader::new(&bytes) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{}: {e}", file.display());
            return ExitCode::FAILURE;
        }
    };
    let link = r.link_type;
    let (start, mut first) = (now_ms(), None);
    let mut f = feeder();
    while let Some(frame) = r.next_frame() {
        let frame = match frame {
            Ok(fr) => fr,
            Err(e) => {
                eprintln!("{}: {e}", file.display());
                return ExitCode::FAILURE;
            }
        };
        let t = (frame.micros / 1000) as i64;
        let now = start + t - *first.get_or_insert(t);
        f.frame(app, own, link, now, frame.data);
    }
    ExitCode::SUCCESS
}
