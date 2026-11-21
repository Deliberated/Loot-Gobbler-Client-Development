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
    frames: u64,
    /// The Loot Gobbler server's calls.
    net: crate::net::Net,
    /// The latest saved run as last seen (a new one may clear a quest map stage).
    seen_run: Option<i64>,
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
            frames: 0,
            net: Default::default(),
            seen_run: None,
        };
        g.seen_run = g.app.dps_history.runs.first().map(|r| r.start);
        // Java `GobblerServer.reconnect` on start: the saved server and session
        g.app.server = lg_app::server::Connection::new(&g.window.props, &format!("Loot Gobbler {}", env!("CARGO_PKG_VERSION")));
        g.app.server.reconnect();
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
                lg_app::applog::line("sniffer", &format!("capturing on {}", names.join(", ")));
                self.feeder = feeder(); // a new capture starts its streams afresh
                self.capture = Some(c);
            }
            Err(e) => {
                lg_app::applog::err(&format!("{e}"));
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
        let logging = [lg_app::applog::ENABLED_KEY, lg_app::applog::DIR_KEY, lg_app::applog::KEEP_KEY].contains(&key);
        if let Err(e) = self.window.props.set(key, value, now_ms(), &self.app.zone) {
            lg_app::applog::err(&format!("cannot save the settings: {e}"));
        }
        if logging {
            lg_app::applog::configure(&self.window.props, &self.own.join("logs"));
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

    /// Info > Logs > Export...: the system's save dialog on a thread of its own, then the file copied there.
    fn export_log(&mut self, path: String) {
        let from = PathBuf::from(path);
        std::thread::spawn(move || {
            let name = from.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if let Some(to) = rfd::FileDialog::new().set_file_name(&name).save_file()
                && let Err(e) = std::fs::copy(&from, &to)
            {
                lg_app::applog::err(&format!("Could not export: {e}"));
            }
        });
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
                lg_app::Heard::Bag { object_type, items, enchants, shown } => {
                    if shown {
                        s.bag_contents(self.app.world.data(), &items, &enchants);
                    }
                    s.bag(object_type);
                }
                lg_app::Heard::Object(t) => s.object(self.app.world.data(), t),
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

    /// The server and the sync, once a frame: the local changes noted (before the files are written, which
    /// clears what changed), the answers that came handed on, the run that is due started, the calls made.
    fn server(&mut self, ctx: &egui::Context) {
        let now = now_ms();
        let mut sync = std::mem::take(&mut self.app.sync);
        let (changed, soon) = self.app.sync_changes();
        for k in changed {
            sync.local_changed(k, now);
        }
        if soon {
            sync.upload_soon(now);
        }
        self.app.server.tick();
        self.app.server.watch(now);
        self.app.server.mates_tick(now);
        // gUPE trades: checked against the partner's report; the verdicts go into the runs
        let checking = self.app.upe.checking();
        self.app.server.gupe_tick(&checking, now);
        for (char_id, at, ok, why) in std::mem::take(&mut self.app.server.gupe.verdicts) {
            self.app.upe_trade_checked(char_id, at, ok, why, now);
        }
        let answers: Vec<_> = self.net.rx.try_iter().collect();
        for d in answers {
            if let Some(d) = self.app.server.done(d) {
                let (url, token, ign) = self.link();
                let owner = self.window.props.get(lg_app::sync::KEY_OWNER).map(str::to_string);
                let link = lg_app::sync::Link { url: &url, token: &token, ign: &ign, owner: owner.as_deref() };
                if let Some(job) = sync.done(&mut self.app, &link, d, now) {
                    self.app.server.jobs.push(job);
                }
            }
        }
        sync.account(&self.app.server.account());
        // your drops onto your wagon; your collection on the server: the caravan again (when shown)
        let me = self.app.server.user.as_ref().map(|u| (u.id, u.ign.clone()));
        for (item, find) in std::mem::take(&mut self.app.collection.drops) {
            if let Some((id, ign)) = &me {
                self.app.server.guild.dropped(item, &find, *id, ign);
            }
        }
        if sync.uploaded.contains(&"collection") {
            self.app.server.guild.caravan_stale = true;
        }
        if sync.uploaded.contains(&"collection") || sync.uploaded.contains(&"dps_history") {
            self.app.server.profile_guild.stale = true; // your run or drops reached the server
        }
        // a run of yours ended: the quest map may move on; your run on the server: what it made of it
        let latest = self.app.dps_history.runs.first().map(|r| r.start);
        if latest != self.seen_run {
            self.seen_run = latest;
            if let (Some((id, ign)), Some(run)) = (&me, self.app.dps_history.runs.first()) {
                let run = run.clone();
                self.app.server.guild.cleared(&run, *id, ign, now);
            }
        }
        if sync.uploaded.contains(&"dps_history") {
            if let Some(pc) = &mut self.app.server.guild.pending {
                pc.uploaded = true;
            }
            self.app.server.guild.maps_stale = true;
        }
        sync.uploaded.clear();
        for (k, v) in std::mem::take(&mut sync.props) {
            self.set(&k, &v);
        }
        let (url, token, ign) = self.link();
        let owner = self.window.props.get(lg_app::sync::KEY_OWNER).map(str::to_string);
        let link = lg_app::sync::Link { url: &url, token: &token, ign: &ign, owner: owner.as_deref() };
        if let Some(job) = sync.tick(&mut self.app, &link, now) {
            self.app.server.jobs.push(job);
        }
        for (k, v) in std::mem::take(&mut sync.props).into_iter().chain(std::mem::take(&mut self.app.server.props)) {
            self.set(&k, &v);
        }
        self.app.sync = sync;
        // a reset code is shown once, and copied (Java `resetPasswordFor`)
        if let Some(rc) = self.app.server.moderation.reset_code.take() {
            ctx.copy_text(rc.code.clone());
            let until = {
                const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
                let (y, m, d, h, mi, _) = self.app.zone.fields(rc.expires_at);
                format!("{d} {} {y}, {h:02}:{mi:02}", MONTHS[(m - 1) as usize])
            };
            self.window.ask(lg_ui::window::Ask::Confirm {
                title: "Password reset code".into(),
                text: format!(
                    "Reset code for {} (copied):\n{}\n\nPass it on to {} privately. In Loot Gobbler they choose \"Forgot password?\"\nand enter their IGN, this code and a new password. Works once, until {until}.\nIt is shown only now. A new code replaces this one.",
                    rc.ign, rc.code, rc.ign
                ),
                yes: "OK".into(),
                no: String::new(),
                then: Box::new(lg_ui::window::Ask::Nothing),
            });
        }
        for job in std::mem::take(&mut self.app.server.jobs) {
            self.net.run(job, Some(ctx));
        }
        // a scheduled upload wakes the window
        ctx.request_repaint_after(std::time::Duration::from_secs(1));
    }

    /// The Server tab's buttons.
    fn server_ask(&mut self, s: lg_ui::window::ServerAsk) {
        use lg_ui::window::ServerAsk as S;
        let server = &mut self.app.server;
        match s {
            S::Connect(a) => {
                server.connection_feedback.clear();
                server.test = None;
                server.connect(&a);
            }
            S::Test(a) => server.test_connection(&a),
            S::Forget => server.disconnect(),
            S::Login(ign, pw) => server.login(&ign, &pw),
            S::Register(pw, token) => {
                let known = self.app.characters.player_name.clone();
                self.app.server.register(known.as_deref(), &pw, &token);
            }
            S::Redeem(t) => server.redeem(&t),
            S::Logout => server.logout(),
            S::SyncNow => self.app.sync.sync_now(),
            S::Forgot { ign, code, password, again } => server.forgot_password(&ign, &code, &password, &again),
            S::Change { with_code, proof, password, again } => server.change_password(with_code, &proof, &password, &again),
            S::OpenForgot(_) | S::OpenChange => {} // the window opens these
            S::RefreshModeration => server.refresh_moderation(),
            S::GuildRefresh => server.guild_refresh(),
            S::MarkRead => server.unread = false,
            S::RunLooks(d, start, dur) => server.run_looks(&d, start, dur),
            S::RetryLooks(key) => server.retry_looks(&key),
            S::CaravanLoad => server.caravan_load(),
            S::MapsLoad => server.maps_load(),
            S::ProfileGuildLoad => server.profile_guild_load(),
            S::OpenMember(id) => server.open_member(id),
            S::Accept(id, ign) => server.accept(id, &ign),
            S::Deny(id, ign) => server.deny(id, &ign),
            S::Demote(id, ign, role) => server.set_role(id, &ign, &role),
            S::ResetCode(id) => server.reset_code_for(id),
            S::Remove(id, ign) => server.remove(id, &ign),
            S::CreateToken(note, hours) => server.create_token(&note, hours),
            S::TokenCopied => server.moderation.tokens_feedback = "Token copied.".into(),
            S::RevokeToken(id) => server.revoke_token(id),
            S::SaveScoring(f, d, r) => server.save_scoring(lg_app::server::ScoreWeights { feed_power: f, difficulty: d, rarity: r }),
            S::SaveGuild(b) => server.save_guild(lg_app::server::GuildSettings { baseline_hpm: b }),
            S::Feedback(0, t) => server.moderation.scoring_feedback = t,
            S::Feedback(_, t) => server.moderation.guild_feedback = t,
            S::Adopt => {
                let ign = server.user.as_ref().map(|u| u.ign.clone());
                if let Some(ign) = ign {
                    self.app.sync.adopt(&ign);
                }
            }
        }
    }

    /// Java `ServerSync.flush(5_000)` on closing: the changes not uploaded yet go up now, waiting at most 5 s.
    fn flush(&mut self) {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        let now = now_ms();
        let mut sync = std::mem::take(&mut self.app.sync);
        let (changed, _) = self.app.sync_changes();
        for k in changed {
            sync.local_changed(k, now);
        }
        let (url, token, ign) = self.link();
        let owner = self.window.props.get(lg_app::sync::KEY_OWNER).map(str::to_string);
        let link = lg_app::sync::Link { url: &url, token: &token, ign: &ign, owner: owner.as_deref() };
        // a run already waiting for the server goes on; else the upload starts
        let mut job = if sync.busy() { None } else { sync.flush(&mut self.app, &link, now) };
        loop {
            if let Some(j) = job.take() {
                self.net.run(j, None);
            } else if !sync.busy() {
                break;
            }
            let Some(left) = deadline.checked_duration_since(std::time::Instant::now()) else {
                lg_app::applog::err("upload on closing did not finish");
                break;
            };
            match self.net.rx.recv_timeout(left) {
                Ok(d) => {
                    if let Some(d) = self.app.server.done(d) {
                        job = sync.done(&mut self.app, &link, d, now_ms());
                    }
                }
                Err(_) => {
                    lg_app::applog::err("upload on closing did not finish");
                    break;
                }
            }
        }
        self.app.sync = sync;
    }

    fn link(&self) -> (String, String, String) {
        let s = &self.app.server;
        (s.url.clone(), s.token.clone(), s.user.as_ref().map_or(String::new(), |u| u.ign.clone()))
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
    /// Closing: the sync's last upload, then the files.
    fn on_exit(&mut self) {
        self.flush();
        lg_app::applog::line("app", "Closing");
        if let Err(e) = self.app.save_changed(&self.own) {
            lg_app::applog::err(&format!("cannot save into {}: {e}", self.own.display()));
        }
    }

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
        self.server(ui.ctx());
        // what the views asked for last frame is done before this one is drawn
        if let Err(e) = self.app.save_changed(&self.own) {
            lg_app::applog::err(&format!("cannot save into {}: {e}", self.own.display()));
        }
        let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
        self.window.show(&mut all, &self.app, now_ms());
        // the Server tab took these in
        self.app.server.clear_password = false;
        self.app.server.clear_register_token = false;
        self.app.server.clear_redeem = false;
        self.app.server.moderation.clear_note = false;
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
                lg_ui::window::Ask::Server(s) => self.server_ask(s),
                lg_ui::window::Ask::ExportLog(path) => self.export_log(path),
                lg_ui::window::Ask::CheckUpdates => self.window.ask(lg_ui::window::Ask::Confirm {
                    title: "Check for updates".into(),
                    text: "Updates are not in the Rust app yet.".into(),
                    yes: "OK".into(),
                    no: String::new(),
                    then: Box::new(lg_ui::window::Ask::Nothing),
                }),
                lg_ui::window::Ask::RefreshCharacters => {
                    let status = self.app.refresh_characters(self.capture.is_some(), now_ms());
                    self.window.characters.refresh_status = status;
                }
                lg_ui::window::Ask::Confirm { .. } | lg_ui::window::Ask::Nothing | lg_ui::window::Ask::OpenDialog(_) => {}
                lg_ui::window::Ask::Play(what) => {
                    let s = crate::sound::Sounds(&self.window.props);
                    match what.split_once(':') {
                        Some(("item", key)) => s.item(key),
                        Some(("startup", id)) => s.startup(id),
                        Some(("ping", key)) => s.ping(match key {
                            "pingSound.enchant" => crate::sound::Ping::Enchant,
                            "pingSound.dungeon" => crate::sound::Ping::Dungeon,
                            "pingSound.item" => crate::sound::Ping::Item,
                            _ => crate::sound::Ping::Entity,
                        }),
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
        // the test hook: LG_TRY=card:<charId> or LG_TRY=grid presses that "Copy as image" after start-up
        self.frames += 1;
        if self.frames == 2 {
            // Java `NewcomerGuide.afterStart`: once the window is up
            self.window.guide.after_start(&self.window.props);
        }
        if self.frames == 60 && std::env::var("LG_TRY").is_ok_and(|t| t == "bell") {
            self.window.shell.bell_open = true;
        }
        if self.frames == 32 && std::env::var("LG_TRY").is_ok_and(|t| t == "hide-run-close") {
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
        }
        if self.frames == 30
            && let Ok(t) = std::env::var("LG_TRY")
        {
            match t.strip_prefix("card:").and_then(|i| i.parse::<i32>().ok()) {
                Some(id) => {
                    ui.ctx().data_mut(|d| d.insert_temp(egui::Id::unique("lg-copy-card"), id));
                }
                None if t.starts_with("open:") => self.window.open(&t[5..]),
                None if t.starts_with("guide:") => self.window.guide.start_at(t[6..].parse().unwrap_or(0)),
                None if t.starts_with("info:") => {
                    let mut asks = Vec::new();
                    self.window.info.open(&t[5..], &mut asks);
                    for a in asks {
                        self.window.ask(a);
                    }
                }
                None if t.starts_with("visit:") => {
                    self.window.open("Battles");
                    self.window.battles.selected = t[6..].parse().unwrap_or(-1);
                }
                None if t.starts_with("member:") => {
                    self.window.open("Guild:Member");
                    self.app.server.open_member(t[7..].parse().unwrap_or(0));
                }
                // the upload on closing: a run hidden, then the window closed before the upload that would follow
                None if t == "hide-run-close" => {
                    if let Some(start) = self.app.dps_history.runs.iter().find(|r| !r.hidden).map(|r| r.start) {
                        self.app.dps_history.remove(start);
                        lg_app::applog::line("app", &format!("hid the run of {start}"));
                    }
                }
                None if t == "grid" => {
                    self.window.open("Inventory");
                    self.window.inventory.copy_image_now();
                }
                None => {}
            }
        }
        // the clocks and the live views move on; packets come in between
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(200));
    }
}
