//! The window: the views of `lg-ui` over the app's records, fed by the capture while the sniffer runs (Java: the
//! "Start sniffer" button; `sniffer=T` in the settings starts it with the app, as the app was left).

use crate::{Feeder, feeder, now_ms};
use std::path::PathBuf;

/// The window as it was left (Java `WindowMemory`: `windowBounds` "x,y,w,h", `windowMaximized`), else 1799 x 1062.
pub fn viewport(props: &lg_app::properties::Properties) -> egui::ViewportBuilder {
    let mut v = egui::ViewportBuilder::default().with_title("Loot Gobbler").with_min_inner_size([640.0, 420.0]);
    let bounds: Option<Vec<f32>> = props.get("windowBounds").and_then(|b| b.split(',').map(|x| x.trim().parse::<f32>().ok()).collect());
    match bounds.as_deref() {
        Some([x, y, w, h]) if *w >= 200.0 && *h >= 150.0 => v = v.with_position([*x, *y]).with_inner_size([*w, *h]),
        _ => v = v.with_inner_size([1799.0, 1062.0]),
    }
    v.with_maximized(props.get("windowMaximized") == Some("true"))
}

pub struct Gui {
    app: lg_app::App,
    own: PathBuf,
    window: lg_ui::window::Window,
    feeder: Feeder,
    capture: Option<lg_capture::live::Capture>,
    /// The window's place and size as last seen, and when it last changed (saved once it rests 600 ms).
    bounds: Option<(String, bool)>,
    bounds_changed: Option<std::time::Instant>,
    /// What the item sounds were last seen at (amulet saves, the matrix firing, the time).
    seen_saves: usize,
    seen_matrix: i64,
    seen_at: i64,
    overlays: lg_ui::overlay_windows::OverlayWindows,
    /// A file or folder picker open on its thread: the setting it is for, and where its answer comes.
    choosing: Option<(String, std::sync::mpsc::Receiver<Option<PathBuf>>)>,
}

impl Gui {
    pub fn new(app: lg_app::App, own: PathBuf, assets: PathBuf) -> Gui {
        let props = lg_app::properties::Properties::load(&own);
        let start = props.get("sniffer") == Some("T");
        let mut g = Gui {
            app,
            own,
            window: lg_ui::window::Window::new(&assets, props),
            feeder: feeder(),
            capture: None,
            bounds: None,
            bounds_changed: None,
            seen_saves: 0,
            seen_matrix: 0,
            seen_at: now_ms(),
            overlays: Default::default(),
            choosing: None,
        };
        g.seen_saves = g.app.amulet_saves.count();
        g.seen_matrix = g.app.cooldowns.matrix_fired;
        // Java: the startup sound once the window is up
        let sounds = crate::sound::Sounds(&g.window.props);
        sounds.startup(sounds.saved_startup());
        if start {
            g.start();
        }
        g
    }

    fn start(&mut self) {
        match lg_capture::live::start("tcp port 2050") {
            Ok(c) => {
                let names: Vec<String> = c.opened.iter().map(|i| i.name.clone()).collect();
                eprintln!("capturing on {}", names.join(", "));
                self.feeder = feeder(); // a new capture starts its streams afresh
                self.capture = Some(c);
            }
            Err(e) => {
                eprintln!("{e}");
                let line = if matches!(e, lg_capture::live::Error::Permission(_)) && cfg!(target_os = "linux") {
                    "Capturing needs the rights: sudo setcap cap_net_raw,cap_net_admin=eip <path to loot-gobbler>".to_string()
                } else {
                    format!("The sniffer could not start: {e}")
                };
                let time = self.app.zone.local_date_time(now_ms());
                self.app.chat.system(&line, time.get(11..).unwrap_or(""));
            }
        }
    }

    fn set(&mut self, key: &str, value: &str) {
        if let Err(e) = self.window.props.set(key, value, now_ms(), &self.app.zone) {
            eprintln!("cannot save the settings: {e}");
        }
    }
}

impl Gui {
    /// Java `JFileChooser`: the system's picker on a thread of its own (the window goes on drawing), starting where
    /// the current choice is; one at a time.
    fn choose(&mut self, key: String, title: String, folder: bool) {
        if self.choosing.is_some() {
            return;
        }
        let current = self.window.props.get(&key).map(PathBuf::from);
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let mut d = rfd::FileDialog::new().set_title(&title);
            let start = current.as_ref().map(|c| if folder { c.clone() } else { c.parent().map(PathBuf::from).unwrap_or_default() });
            if let Some(dir) = start.filter(|d| d.is_dir()) {
                d = d.set_directory(dir);
            }
            let picked = if folder { d.pick_folder() } else { d.add_filter("WAV sound (*.wav)", &["wav"]).pick_file() };
            let _ = tx.send(picked);
        });
        self.choosing = Some((key, rx));
    }

    /// The picker's answer, once it came: an item sound's WAV (played; one that cannot be played is refused), or
    /// the log folder.
    fn chosen(&mut self) {
        let Some((key, rx)) = &self.choosing else { return };
        let picked = match rx.try_recv() {
            Ok(p) => p,
            Err(std::sync::mpsc::TryRecvError::Empty) => return,
            Err(_) => None,
        };
        let key = key.clone();
        self.choosing = None;
        let Some(path) = picked else { return };
        let path = std::path::absolute(&path).unwrap_or(path);
        if key == "logDir" {
            self.set(&key, &path.display().to_string());
            return;
        }
        if std::fs::read(&path).ok().and_then(|b| crate::sound::decode(&b)).is_some() {
            self.set(&key, &path.display().to_string());
            crate::sound::Sounds(&self.window.props).item(&key);
        } else {
            self.window.ask(lg_ui::window::Ask::Confirm {
                title: "Item Sounds".into(),
                text: "This file cannot be played. Please choose a WAV file.".into(),
                yes: "OK".into(),
                no: String::new(),
                then: Box::new(lg_ui::window::Ask::Nothing),
            });
        }
    }

    /// What should sound since the last frame: the app's bags, chat and trade requests, and the item sounds Java's
    /// timers play (an amulet save and its recharge, the matrix firing, a cooldown running out).
    fn sounds(&mut self) {
        let now = now_ms();
        let s = crate::sound::Sounds(&self.window.props);
        for h in std::mem::take(&mut self.app.heard) {
            match h {
                lg_app::Heard::Bag(t) => s.bag(t),
                lg_app::Heard::Chat { channel, from_me, text } => s.chat(channel, from_me, &text),
                lg_app::Heard::Trade => s.trade(),
            }
        }
        let saves = self.app.amulet_saves.count();
        if saves > self.seen_saves {
            s.item("amuletSoundFile");
        }
        self.seen_saves = saves;
        let last = self.app.amulet_saves.saves.iter().rev().find(|x| !x.removed).map_or(0, |x| x.t);
        let ready = last + lg_app::amulet_saves::RECHARGE_MS;
        if last > 0 && self.seen_at < ready && ready <= now {
            s.item("amuletReadySoundFile");
        }
        if self.app.cooldowns.matrix_fired > self.seen_matrix {
            s.item("matrixBreakSoundFile");
        }
        self.seen_matrix = self.app.cooldowns.matrix_fired;
        for (sound, r) in &self.app.cooldowns.running {
            let end = r.start + r.duration_ms;
            if self.seen_at < end && end <= now {
                s.item(match sound {
                    lg_app::item_cooldowns::Sound::Refinery => "refinerySoundFile",
                    lg_app::item_cooldowns::Sound::Alien => "alienSoundFile",
                    lg_app::item_cooldowns::Sound::Matrix => "matrixSoundFile",
                });
            }
        }
        self.seen_at = now;
    }

    /// Java `WindowMemory.save`: maximized or not, and the normal bounds, once the window rests.
    fn remember_bounds(&mut self, ctx: &egui::Context) {
        let (rect, maximized, minimized) = ctx.input(|i| {
            let v = i.viewport();
            (v.outer_rect, v.maximized.unwrap_or(false), v.minimized.unwrap_or(false))
        });
        let Some(r) = rect else { return };
        let now = (format!("{},{},{},{}", r.left().round(), r.top().round(), r.width().round(), r.height().round()), maximized);
        if minimized {
            return;
        }
        match &self.bounds {
            None => self.bounds = Some(now),
            Some(b) if *b != now => {
                self.bounds = Some(now);
                self.bounds_changed = Some(std::time::Instant::now());
            }
            _ => {}
        }
        if self.bounds_changed.is_some_and(|t| t.elapsed().as_millis() >= 600)
            && let Some((b, max)) = self.bounds.clone()
        {
            self.bounds_changed = None;
            self.set("windowMaximized", if max { "true" } else { "false" });
            if !max {
                self.set("windowBounds", &b);
            }
        }
    }
}

impl eframe::App for Gui {
    /// See-through where nothing is painted: the overlays show only their boxes.
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        [0.0; 4]
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // what was captured since the last frame
        if let Some(c) = &self.capture {
            let frames: Vec<_> = c.frames.try_iter().take(5000).collect();
            for f in frames {
                self.feeder.frame(&mut self.app, &self.own, f.link_type, (f.micros / 1000) as i64, &f.data);
            }
        }
        self.window.shell.sniffing = self.capture.is_some();
        let ids = ["dungeon", "mana", "dps", "buffs"];
        let on_key = |id: &str| lg_ui::overlay_page::key_of(id, "On");
        self.window.shell.overlays_on = ids.iter().any(|id| self.window.props.get(&on_key(id)) == Some("true"));
        self.sounds();
        self.chosen();
        // what the views asked for last frame is done before this one is drawn
        if let Err(e) = self.app.save_changed(&self.own) {
            eprintln!("cannot save into {}: {e}", self.own.display());
        }
        let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
        self.window.show(&mut all, &self.app, now_ms());
        let mut asks = Vec::new();
        self.overlays.show(ui.ctx(), &mut self.window.icons, &self.app, &self.window.props, now_ms(), &mut asks);
        for a in asks {
            self.window.ask(a);
        }
        for ask in self.window.take_asks() {
            match ask {
                lg_ui::window::Ask::Set(k, v) => self.set(&k, &v),
                lg_ui::window::Ask::ClearChat => self.app.chat.clear(),
                lg_ui::window::Ask::HideVisit(start) => self.app.dungeon_visits.remove(start),
                lg_ui::window::Ask::ClearVisits => self.app.dungeon_visits.clear(),
                lg_ui::window::Ask::HideRun(start) => self.app.dps_history.remove(start),
                lg_ui::window::Ask::ClearRuns => self.app.dps_history.clear(),
                lg_ui::window::Ask::ClearCollection => self.app.collection.clear(),
                lg_ui::window::Ask::UpeMark { char_id, on, mode, no_pet } => self.app.upe_mark(char_id, on, mode.as_deref(), no_pet),
                lg_ui::window::Ask::UpeMode(char_id, mode) => self.app.upe_mode(char_id, &mode),
                lg_ui::window::Ask::UpeNoPet(char_id, no_pet) => self.app.upe_no_pet(char_id, no_pet),
                lg_ui::window::Ask::Choose { key, title, folder } => self.choose(key, title, folder),
                lg_ui::window::Ask::Confirm { .. } | lg_ui::window::Ask::Nothing => {}
                lg_ui::window::Ask::Play(what) => {
                    let s = crate::sound::Sounds(&self.window.props);
                    match what.split_once(':') {
                        Some(("item", key)) => s.item(key),
                        Some(("startup", id)) => s.startup(id),
                        _ => s.play(&what),
                    }
                }
            }
        }
        // Java `Overlay.toggleAll`: hide those on (and remember which), or show those again (the dungeon one if none)
        if self.window.shell.clicked_overlay {
            let ids = ["dungeon", "mana", "dps", "buffs"];
            let on_key = |id: &str| lg_ui::overlay_page::key_of(id, "On");
            let on: Vec<&str> = ids.iter().copied().filter(|id| self.window.props.get(&on_key(id)) == Some("true")).collect();
            if !on.is_empty() {
                for id in &on {
                    self.set(&on_key(id), "false");
                }
                self.set("overlayLast", &on.join(","));
            } else {
                let last = self.window.props.get("overlayLast").unwrap_or("").to_string();
                let again: Vec<&str> = ids.iter().copied().filter(|id| format!(",{last},").contains(&format!(",{id},"))).collect();
                if again.is_empty() {
                    self.set(&on_key("dungeon"), "true");
                }
                for id in again {
                    self.set(&on_key(id), "true");
                }
            }
        }
        if self.window.shell.clicked_sniffer {
            if let Some(mut c) = self.capture.take() {
                c.stop();
                self.set("sniffer", "F");
            } else {
                self.start();
                if self.capture.is_some() {
                    self.set("sniffer", "T");
                }
            }
        }
        self.remember_bounds(ui.ctx());
        // the clocks and the live views move on; packets come in between
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(200));
    }
}
