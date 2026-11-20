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
        }
    }

    /// Opens a tab, or a page of it: "Inventory:st", "Inventory:log", "Characters:<charId|classes|pets>",
    /// "Battles:dps|overlay", "Social:quests|discord", "Guild:<page name>".
    pub fn open(&mut self, spec: &str) {
        let (tab, page) = spec.split_once(':').map_or((spec, ""), |(t, p)| (t, p));
        self.shell.selected = tab.to_string();
        match (tab, page) {
            ("Inventory", p) => self.inventory.page = ["", "st", "log"].iter().position(|x| *x == p).unwrap_or(0),
            ("Characters", "classes") => self.characters.page = 1,
            ("Characters", "pets") => self.characters.page = 2,
            ("Characters", id) if !id.is_empty() => self.characters.selected = id.parse().unwrap_or(0),
            ("Battles", p) => self.battles.page = ["", "dps", "overlay"].iter().position(|x| *x == p).unwrap_or(0),
            ("Social", p) => self.social.page = ["", "quests", "discord"].iter().position(|x| *x == p).unwrap_or(0),
            ("Guild", p) => self.guild.page = GUILD_PAGES.iter().position(|x| *x == p).unwrap_or(0),
            _ => {}
        }
    }

    /// What the views asked for since the last call. Settings asked to be saved are taken into the shown settings
    /// at once (the caller writes them to the file); a new theme is applied.
    pub fn take_asks(&mut self) -> Vec<Ask> {
        let asks = std::mem::take(&mut self.asks);
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
    }
}
