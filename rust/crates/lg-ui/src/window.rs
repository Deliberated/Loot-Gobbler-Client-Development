//! The whole window (Java: `TomatoGUI`): the frame around the views, every tab's view with its state, the
//! settings it shows, the icons. Shown by the app each frame, and by `ui-shot` for pictures.

use crate::icons::Icons;
use crate::{battles, characters, game_modes, guild, inventory, profile, shell, social, theme};
use lg_app::properties::Properties;
use std::path::Path;

/// What a view asks the app to do (the views only read the records and the settings).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ask {
    /// Save a setting.
    Set(String, String),
    /// Empty the chat log.
    ClearChat,
    /// Mark a finished dungeon visit (by its start) removed.
    HideVisit(i64),
    /// Every dungeon visit gone.
    ClearVisits,
    /// Mark a saved run (by its start) removed.
    HideRun(i64),
    /// Every saved run gone.
    ClearRuns,
    /// Ask first (Java `JOptionPane.showConfirmDialog`): title, text, the yes and no buttons; yes asks `then`.
    Confirm { title: String, text: String, yes: String, no: String, then: Box<Ask> },
}

/// Asks the window to open a tab or page after this frame (see [`Window::open`]; also "Battles:run:<start>" for a
/// run under DPS, "Inventory:item:<id>" for an item). For views deep in helpers that have no context.
pub fn open_later(ctx: &egui::Context, spec: impl Into<String>) {
    let spec = spec.into();
    ctx.data_mut(|d| d.insert_temp(egui::Id::unique("lg-open"), spec));
}

pub struct Window {
    pub shell: shell::Shell,
    pub palette: theme::Palette,
    pub icons: Icons,
    pub en: lg_app::loadout::Enchantments,
    pub loadout: lg_app::loadout::Settings,
    pub props: Properties,
    pub profile: profile::Profile,
    pub characters: characters::CharactersView,
    pub inventory: inventory::InventoryView,
    pub game_modes: game_modes::GameModesView,
    pub battles: battles::BattlesView,
    pub social: social::SocialView,
    pub guild: guild::GuildView,
    themed: bool,
    /// What the views asked for in the last frame; the app takes them (`take_asks`).
    asks: Vec<Ask>,
    /// The question shown over the window, if any.
    confirm: Option<Ask>,
}

const GUILD_PAGES: [&str; 6] = ["Loot Caravan", "Quest Map", "Leaderboard", "Recent Drops", "Member", "Quests"];

impl Window {
    /// With the game data in `assets` and the settings (`realmShark.properties`).
    pub fn new(assets: &Path, props: Properties) -> Window {
        let loadout = lg_app::loadout::Settings::from(&props);
        let palette = theme::palette(props.get("theme"));
        Window {
            shell: shell::Shell::default(),
            palette,
            icons: Icons::new(assets),
            en: lg_app::loadout::Enchantments::load(&assets.join("xml")),
            loadout,
            props,
            profile: profile::Profile::default(),
            characters: characters::CharactersView::default(),
            inventory: inventory::InventoryView::default(),
            game_modes: game_modes::GameModesView::default(),
            battles: battles::BattlesView { selected: -1, ..Default::default() },
            social: social::SocialView::default(),
            guild: guild::GuildView::default(),
            themed: false,
            asks: Vec::new(),
            confirm: None,
        }
    }

    /// Opens a tab, or a page of it: "Inventory:st", "Inventory:log", "Characters:<charId|classes|pets>",
    /// "Battles:dps|overlay", "Social:quests|discord", "Guild:<page name>".
    pub fn open(&mut self, spec: &str) {
        let (tab, page) = spec.split_once(':').map_or((spec, ""), |(t, p)| (t, p));
        self.shell.selected = tab.to_string();
        match (tab, page) {
            ("Inventory", p) if p.starts_with("item:") => {
                // Java `showItem`: the Items page, the item found by its name
                self.inventory.page = 0;
                self.inventory.search = p[5..].to_string();
            }
            ("Inventory", p) => self.inventory.page = ["", "st", "log"].iter().position(|x| *x == p).unwrap_or(0),
            ("Characters", "classes") => self.characters.page = 1,
            ("Characters", "pets") => self.characters.page = 2,
            ("Characters", id) if !id.is_empty() => {
                self.characters.page = 0;
                self.characters.selected = id.parse().unwrap_or(0);
            }
            ("Battles", p) => self.battles.page = ["", "dps", "overlay"].iter().position(|x| *x == p).unwrap_or(0),
            ("Social", p) => self.social.page = ["", "quests", "discord"].iter().position(|x| *x == p).unwrap_or(0),
            ("Guild", p) => self.guild.page = GUILD_PAGES.iter().position(|x| *x == p).unwrap_or(0),
            _ => {}
        }
    }

    /// What the views asked for since the last call. Settings asked to be saved are taken into the shown settings
    /// at once (the caller writes them to the file); a new theme is applied.
    pub fn take_asks(&mut self) -> Vec<Ask> {
        let mut asks = std::mem::take(&mut self.asks);
        // a question is shown, not done
        if let Some(i) = asks.iter().position(|a| matches!(a, Ask::Confirm { .. })) {
            self.confirm = Some(asks.remove(i));
        }
        for a in &asks {
            if let Ask::Set(k, v) = a {
                self.props.set_in_memory(k, v);
                if k == "theme" {
                    self.palette = theme::palette(Some(v));
                    self.themed = false;
                }
            }
        }
        asks
    }

    /// The question shown over the window, if any.
    pub fn asking(&self) -> Option<&Ask> {
        self.confirm.as_ref()
    }

    /// The question asked over the window: its buttons answer it, closing it is "no".
    fn question(&mut self, ctx: &egui::Context) {
        let Some(Ask::Confirm { title, text, yes, no, then }) = &self.confirm else { return };
        let p = self.palette;
        let mut answer: Option<bool> = None;
        let modal = egui::Modal::new(egui::Id::unique("question")).frame(egui::Frame::NONE).show(ctx, |ui| {
            let f = crate::widgets::text(18.0);
            let painter = ui.painter().clone();
            let lines: Vec<&str> = text.lines().collect();
            let lh = crate::jtext::height(&painter, &f);
            let tw = lines.iter().map(|l| crate::jtext::width(&painter, l, &f)).fold(0.0, f32::max);
            let w = (tw + 48.0).max(360.0);
            let h = 34.0 + 20.0 + lines.len() as f32 * lh + 20.0 + 36.0 + 16.0;
            let (r, _) = ui.allocate_exact_size(egui::vec2(w, h), egui::Sense::hover());
            painter.rect(r, 0.0, p.paper, egui::Stroke::new(1.0, p.line), egui::StrokeKind::Inside);
            let head = egui::Rect::from_min_size(r.min, egui::vec2(w, 34.0));
            painter.rect_filled(head, 0.0, p.title);
            crate::jtext::draw(&painter, egui::pos2(head.left() + 12.0, head.center().y), egui::Align2::LEFT_CENTER, title, &f, p.title_fg);
            let mut y = head.bottom() + 20.0;
            for l in &lines {
                crate::jtext::draw(&painter, egui::pos2(r.left() + 24.0, y), egui::Align2::LEFT_TOP, l, &f, p.ink);
                y += lh;
            }
            let small = crate::widgets::small();
            let bw = |t: &str| (crate::jtext::width(&painter, &t.to_uppercase(), &small) + 32.0).max(108.0);
            let nr = egui::Rect::from_min_size(egui::pos2(r.right() - 16.0 - bw(no), r.bottom() - 16.0 - 36.0), egui::vec2(bw(no), 36.0));
            let yr = egui::Rect::from_min_size(egui::pos2(nr.left() - 8.0 - bw(yes), nr.top()), egui::vec2(bw(yes), 36.0));
            if crate::inventory::button(ui, &p, yr, yes, false, "question-yes") {
                answer = Some(true);
            }
            if crate::inventory::button(ui, &p, nr, no, false, "question-no") {
                answer = Some(false);
            }
        });
        if modal.should_close() && answer.is_none() {
            answer = Some(false);
        }
        if let Some(a) = answer {
            if a {
                self.asks.push((**then).clone());
            }
            self.confirm = None;
        }
    }

    /// The whole window in `ui`.
    pub fn show(&mut self, ui: &mut egui::Ui, app: &lg_app::App, now: i64) {
        if !self.themed {
            theme::apply(ui.ctx(), &self.palette);
            self.themed = true;
        }
        let palette = self.palette;
        let Window { shell, icons, en, loadout, props, profile, characters, inventory, game_modes, battles, social, guild, asks, .. } =
            self;
        shell.show(ui, &palette, |ui, tab| {
            let mut c = profile::Ctx { app, p: &palette, icons, now, en, loadout, props, asks };
            match tab {
                "Profile" => profile.show(ui, &mut c),
                "Inventory" => {
                    let rarity = characters.rarity(ui.ctx());
                    inventory.show(ui, &mut c, &rarity)
                }
                "Characters" => characters.show(ui, &mut c),
                "Game Modes" => game_modes.show(ui, &mut c),
                "Battles" => {
                    let rarity = characters.rarity(ui.ctx());
                    battles.show(ui, &mut c, &rarity)
                }
                "Social" => social.show(ui, &mut c),
                "Guild" => guild.show(ui, &mut c),
                "Server" => crate::server::show(ui, &mut c),
                "Settings" => crate::settings::show(ui, &mut c),
                _ => {}
            }
        });
        self.question(ui.ctx());
        if let Some(spec) = ui.ctx().data_mut(|d| d.remove_temp::<String>(egui::Id::unique("lg-open"))) {
            match spec.split_once(":run:").filter(|(t, _)| *t == "Battles") {
                Some((_, start)) => {
                    self.shell.selected = "Battles".into();
                    self.battles.page = 1;
                    self.battles.dps.show_run(&app.dps_history, start.parse().ok());
                }
                None => self.open(&spec),
            }
            ui.ctx().request_repaint();
        }
    }
}
