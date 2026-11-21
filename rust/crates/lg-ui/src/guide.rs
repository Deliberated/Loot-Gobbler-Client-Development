//! The Newcomer's Guide (Java `NewcomerGuide`, `GuideLayer`, `GuideTargets`): a tour through the app over the
//! window. The part a step talks about has a gold frame and the rest of the window is greyed out (still readable),
//! and a card next to it says what it is (Back, Next, Skip). A part to click glows and says CLICK ME; clicking it
//! does what it always does and goes on. Steps that wait for the game (you seen in-game, your characters) or for you
//! (server connected, registered) only open Next once that happened.
//!
//! It starts by itself on every start until it was finished or skipped once, and goes on where it was if the app
//! was closed during it. Info > Newcomer's Guide > Quick tour starts it again. Only the quick tour exists so far.
//!
//! The views mark the parts it points at as they draw them ([`mark`], [`mark_item`]); the guide draws last.

use crate::jtext;
use crate::theme::{self, Palette, fixed};
use crate::window::Ask;
use egui::{Align2, Color32, Id, Pos2, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use std::collections::HashMap;

const KEY_ACTIVE: &str = "guideActive";
const KEY_STEP: &str = "guideStep";
const KEY_CHOSEN: &str = "guideQuickChosen";
/// The guide's version finished or skipped: it no longer starts by itself.
const KEY_DONE: &str = "guideDoneVersion";
/// The guide's version (Java `VERSION`): a version finished or skipped earlier counts as unseen.
pub const VERSION: &str = "2";
const CARD_W: f32 = 390.0;
const RING: f32 = 6.0;
const GLOW: Color32 = Color32::from_rgb(255, 194, 14);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    Welcome,
    Choose,
    Profile,
    ChStats,
    ChLoadout,
    ChProgress,
    ChItem,
    Inventory,
    Battles,
    Dungeon,
    GuildRun,
    Connect,
    Account,
    Guild,
    Settings,
    Done,
}

const STEPS: [Step; 16] = [
    Step::Welcome,
    Step::Choose,
    Step::Profile,
    Step::ChStats,
    Step::ChLoadout,
    Step::ChProgress,
    Step::ChItem,
    Step::Inventory,
    Step::Battles,
    Step::Dungeon,
    Step::GuildRun,
    Step::Connect,
    Step::Account,
    Step::Guild,
    Step::Settings,
    Step::Done,
];

fn ordinal(s: Step) -> usize {
    STEPS.iter().position(|x| *x == s).unwrap_or(0)
}

// ---- the parts of the window, marked by the views as they draw ------------------------------------------------

#[derive(Clone, Default)]
struct Targets {
    rects: HashMap<&'static str, Rect>,
    /// The linked item icons in a character's Loadout and Inventory boxes.
    items: Vec<(Rect, i32)>,
    /// Parts to scroll into sight once they are drawn (true: from their top).
    scroll: Vec<(&'static str, bool)>,
}

fn targets_id() -> Id {
    Id::unique("lg-guide-targets")
}

/// A new frame: what was marked in the last one is gone.
pub fn begin_frame(ctx: &egui::Context) {
    ctx.data_mut(|d| {
        let t = d.get_temp_mut_or_default::<Targets>(targets_id());
        t.rects.clear();
        t.items.clear();
    });
}

/// Marks `r` as the part with this name (Java `GuideTargets.mark`): what of it is in sight.
pub fn mark(ui: &Ui, name: &'static str, r: Rect) {
    let clip = ui.clip_rect();
    let scroll = ui.ctx().data_mut(|d| {
        let t = d.get_temp_mut_or_default::<Targets>(targets_id());
        let seen = r.intersect(clip);
        if seen.width() > 0.0 && seen.height() > 0.0 {
            t.rects.insert(name, seen);
        }
        t.scroll.iter().position(|(n, _)| *n == name).map(|i| t.scroll.remove(i).1)
    });
    if let Some(top) = scroll {
        ui.scroll_to_rect(r, top.then_some(egui::Align::Min));
    }
}

/// A linked item icon in a character's boxes (Java `ItemLinks.ITEM`), for the step about your best items.
pub fn mark_item(ui: &Ui, r: Rect, id: i32) {
    let seen = r.intersect(ui.clip_rect());
    if seen.width() > 0.0 && seen.height() > 0.0 {
        ui.ctx().data_mut(|d| d.get_temp_mut_or_default::<Targets>(targets_id()).items.push((seen, id)));
    }
}

fn targets(ctx: &egui::Context) -> Targets {
    ctx.data(|d| d.get_temp::<Targets>(targets_id())).unwrap_or_default()
}

fn request_scroll(ctx: &egui::Context, names: Vec<(&'static str, bool)>) {
    ctx.data_mut(|d| d.get_temp_mut_or_default::<Targets>(targets_id()).scroll = names);
}

// ---- the guide ---------------------------------------------------------------------------------------------------

/// What the window does for the guide (Java `Nav`): open views, the Info menu, the examples.
#[derive(Debug, Default)]
pub struct Out {
    /// Views to open (`Window::open` specs).
    pub open: Vec<String>,
    /// Open (true) or close the Info menu with its Newcomer's Guide submenu.
    pub menu: Option<bool>,
    /// The example dungeon in Battles and in the title bar, while you have none.
    pub example_run: bool,
    pub preview_dungeon: bool,
}

/// What the guide sees of the window this frame.
pub struct Seen<'a> {
    pub app: &'a lg_app::App,
    pub sniffing: bool,
    pub tab: &'a str,
    pub menu_open: bool,
    pub now: i64,
}

#[derive(Default)]
pub struct Guide {
    running: bool,
    index: usize,
    /// Started from the menu's Quick tour: no "quick or extensive?" step.
    tour_chosen: bool,
    /// An account was registered while the app runs.
    registered: bool,
    /// The "quick or extensive?" step: the Info menu was open (the card then stays where it was).
    menu_was_open: bool,
    card_at: Option<Pos2>,
    last_character_nav: i64,
    pending: Out,
    /// The properties to write (Java `PropertiesManager.setProperties`).
    sets: Vec<(&'static str, String)>,
    /// The parts to scroll into sight, asked of the views on the next draw.
    scroll: Option<Vec<(&'static str, bool)>>,
    /// The step shows a character: the most played one is opened.
    want_character: bool,
}

/// A step as it is now: its text, what it frames, what glows, whether Next is open.
struct View {
    head: String,
    title: String,
    body: String,
    waiting: Option<String>,
    spots: Vec<Rect>,
    clicks: Vec<Rect>,
    on_click: bool,
    open: bool,
    next: &'static str,
    choose: bool,
}

enum Press {
    Back,
    Next,
    Quick,
    Skip,
}

impl Guide {
    /// Whether the guide is on the screen now.
    pub fn running(&self) -> bool {
        self.running
    }

    fn step(&self) -> Step {
        STEPS[self.index]
    }

    fn set(&mut self, k: &'static str, v: impl Into<String>) {
        self.sets.push((k, v.into()));
    }

    /// After the window is up (Java `afterStart`): if the app was closed during the guide, it goes on where it
    /// was; else it starts while its version was not finished or skipped (it asks "quick or extensive?").
    pub fn after_start(&mut self, props: &lg_app::properties::Properties) {
        if props.get(KEY_ACTIVE) == Some("true") {
            let at = props.get(KEY_STEP).and_then(|s| s.trim().parse().ok()).unwrap_or(0);
            self.tour_chosen = props.get(KEY_CHOSEN) == Some("true");
            self.begin(at);
        } else if props.get(KEY_DONE) != Some(VERSION) {
            self.choose(false);
            self.begin(0);
        }
    }

    /// Info > Newcomer's Guide > Quick tour: from the start.
    pub fn start_quick(&mut self) {
        let s = self.running.then(|| self.step());
        if s == Some(Step::Choose) {
            // picked in the menu the guide opened: go on, do not start over
            self.pending.menu = Some(false);
            self.go(ordinal(Step::Profile), false);
        } else {
            self.choose(true);
            if s != Some(Step::Welcome) {
                self.begin(0);
            }
        }
    }

    /// The guide at a step, as if started from the beginning (for trying it out).
    pub fn start_at(&mut self, at: usize) {
        self.choose(false);
        self.begin(at);
    }

    /// The Server tab registered an account.
    pub fn registered_account(&mut self) {
        self.registered = true;
    }

    fn choose(&mut self, chosen: bool) {
        self.tour_chosen = chosen;
        self.set(KEY_CHOSEN, chosen.to_string());
    }

    fn begin(&mut self, at: usize) {
        if self.running && self.index != usize::MAX && self.step() == Step::Choose {
            self.pending.menu = Some(false);
        }
        self.running = true;
        self.set(KEY_ACTIVE, "true");
        self.index = usize::MAX;
        self.go(at.min(STEPS.len() - 1), false);
    }

    fn end(&mut self) {
        self.set(KEY_ACTIVE, "false");
        self.set(KEY_DONE, VERSION);
        if self.running && self.step() == Step::Choose {
            self.pending.menu = Some(false);
        }
        self.running = false;
    }

    /// To step i; by_click: a click already opened what the step shows.
    fn go(&mut self, i: usize, by_click: bool) {
        if i >= STEPS.len() {
            self.end();
            return;
        }
        let was = (self.index != usize::MAX).then(|| self.step());
        let mut i = i;
        if self.tour_chosen && STEPS[i] == Step::Choose {
            // already chosen: past the question
            i = if self.index == usize::MAX || i > self.index { ordinal(Step::Profile) } else { ordinal(Step::Welcome) };
        }
        self.index = i;
        if was == Some(Step::Choose) && self.step() != Step::Choose {
            self.pending.menu = Some(false);
        }
        self.set(KEY_STEP, i.to_string());
        self.menu_was_open = false;
        self.card_at = None;
        if !by_click {
            self.open(self.step());
        }
        self.scroll_spots();
    }

    /// The parts a step frames scroll into sight once (merged: from the first one's top).
    fn scroll_spots(&mut self) {
        let names: &[&'static str] = match self.step() {
            Step::Profile => &["profile.overview"],
            Step::ChStats => &["characters.card"],
            Step::ChLoadout | Step::ChItem => &["characters.loadout"],
            Step::ChProgress => &["characters.fame"],
            Step::Inventory => &["inventory.grid"],
            Step::GuildRun => &["battles.guild"],
            Step::Connect => &["server.connection"],
            Step::Account => &["server.account"],
            Step::Settings => &["settings.itemSounds"],
            _ => &[],
        };
        let merged = matches!(self.step(), Step::ChLoadout | Step::ChItem);
        self.scroll = Some(names.iter().map(|n| (*n, merged)).collect());
    }

    /// What a step shows when it starts.
    fn open(&mut self, s: Step) {
        let open = &mut self.pending.open;
        match s {
            Step::Welcome | Step::Profile | Step::Done => open.push("Profile".into()),
            Step::Choose => self.pending.menu = Some(true),
            Step::ChStats | Step::ChLoadout | Step::ChProgress | Step::ChItem => {
                open.push("Characters".into());
                self.want_character = true;
            }
            Step::Inventory => open.push("Inventory".into()),
            Step::Battles => open.push("Battles".into()),
            Step::Dungeon | Step::GuildRun => open.push("tab:Battles".into()),
            Step::Connect | Step::Account => open.push("Server".into()),
            Step::Guild => open.push("Guild:Loot Caravan".into()),
            Step::Settings => open.push("Settings".into()),
        }
    }

    /// The most played character (else the last played) in Characters.
    fn show_a_character(&mut self, app: &lg_app::App, now: i64) {
        let c = app.characters.most_played(now).or_else(|| app.characters.last_played_char());
        if let Some(c) = c {
            self.pending.open.push(format!("Characters:{}", c.char_id));
            self.last_character_nav = now;
        }
    }

    fn describe(&self, s: Step, seen: &Seen, t: &Targets) -> View {
        let app = seen.app;
        let head = if matches!(s, Step::Welcome | Step::Choose) {
            "NEWCOMER'S GUIDE".to_string()
        } else {
            format!("QUICK TOUR · {} / {}", ordinal(s) - 1, STEPS.len() - 2)
        };
        let mut spots: Vec<Rect> = Vec::new();
        let mut clicks: Vec<Rect> = Vec::new();
        let add = |to: &mut Vec<Rect>, name: &str| {
            if let Some(r) = t.rects.get(name) {
                to.push(*r);
            }
        };
        let (mut waiting, mut next, mut open, mut on_click, mut merged) = (None, "NEXT", true, false, false);
        let name = app.characters.player_name.as_deref().filter(|n| !n.trim().is_empty());
        let loaded = app.characters.all().any(|c| c.alive && c.class_type > 0);
        let (title, body): (String, String) = match s {
            Step::Welcome => {
                if let Some(n) = name {
                    next = "CONTINUE";
                    (
                        format!("Welcome, {n}!"),
                        "The Gobbler found you. It keeps track of your loot, your characters, your dungeon runs and your guild. Let's have a look around.".into(),
                    )
                } else if !seen.sniffing {
                    add(&mut spots, "sniffer");
                    add(&mut clicks, "sniffer");
                    open = false;
                    waiting = Some("Next opens once the Gobbler sees you in the game.");
                    (
                        "Welcome to Loot Gobbler".into(),
                        "First the Gobbler has to find you.\n1. Click Start Sniffer.\n2. Then open the game.".into(),
                    )
                } else {
                    open = false;
                    waiting = Some("Waiting for the game...");
                    (
                        "Now go into the game".into(),
                        "Open the game and log in. Already playing? Change realm, or go to the Nexus or your vault, so the Gobbler sees you.".into(),
                    )
                }
            }
            Step::Choose => {
                add(&mut spots, "menu.info");
                add(&mut spots, "menu.info.popup");
                add(&mut spots, "menu.guide.popup");
                (
                    "Quick or extensive?".into(),
                    "The quick tour shows the essentials in about two minutes. The extensive one, going through every tab, is coming later.\nYou can start the guide again any time: Info > Newcomer's Guide.".into(),
                )
            }
            Step::Profile => {
                add(&mut spots, "profile.overview");
                if spots.is_empty() {
                    add(&mut spots, "profile.character");
                }
                add(&mut clicks, "profile.character");
                on_click = true;
                (
                    "Your overview".into(),
                    "Your profile sums up your fame, your characters and your latest runs. Click your character to look closer.".into(),
                )
            }
            Step::ChStats => {
                if !loaded {
                    open = false;
                    waiting = Some("Waiting for your characters...");
                    (
                        "Your characters are not loaded yet".into(),
                        "Go into the game and enter the Nexus, or change realm: the Gobbler then reads all your characters.".into(),
                    )
                } else {
                    add(&mut spots, "characters.list");
                    add(&mut clicks, "characters.card");
                    on_click = true;
                    (
                        "All your characters".into(),
                        "Every character with its gear, how many stats are maxed, its fame and what it gained lately. Click your character to see it on the right.".into(),
                    )
                }
            }
            Step::ChLoadout => {
                add(&mut spots, "characters.loadout");
                add(&mut spots, "characters.suggested");
                add(&mut spots, "characters.inventory");
                merged = true;
                (
                    "Craft your loadout".into(),
                    "The character you picked: Loadout is what it wears now. Suggested Loadout puts together the best gear from everything you own, enchantments included: click the stats you prefer.".into(),
                )
            }
            Step::ChProgress => {
                add(&mut spots, "characters.fame");
                ("Its progress".into(), "Fame gained, fame per hour and time played, for each character on its own.".into())
            }
            Step::ChItem => {
                let data = app.world.data();
                for (r, id) in &t.items {
                    if clicks.len() < 6 && data.items.item(*id).is_some_and(|i| i.is_ut() || i.is_st()) {
                        clicks.push(*r);
                    }
                }
                add(&mut spots, "characters.loadout");
                add(&mut spots, "characters.suggested");
                add(&mut spots, "characters.inventory");
                merged = true;
                let body = if clicks.is_empty() {
                    "Untiered and set items (UT and ST) on a character can be clicked anywhere: they open in the inventory. This one has none yet."
                } else {
                    "Your untiered and set items glow. Click one to see where you keep it and how many you have."
                };
                ("Your best items".into(), body.into())
            }
            Step::Inventory => {
                add(&mut spots, "inventory.grid");
                if !app.holdings.places.contains_key(lg_app::holdings::VAULT) {
                    (
                        "Open your vault once".into(),
                        "The inventory shows everything you own. Your characters are in already, your vault is not: walk into your vault in the game and it fills in.".into(),
                    )
                } else {
                    (
                        "Everything you own".into(),
                        "Characters, vault and gift chest in one place. An item you click anywhere is picked out here.".into(),
                    )
                }
            }
            Step::Battles => {
                add(&mut spots, "battles.detail");
                let example = app.dungeon_visits.current.is_none() && app.dungeon_visits.visits.iter().all(|v| v.hidden);
                (
                    "Every dungeon run".into(),
                    format!(
                        "Each dungeon you enter is logged: how long it took, your damage and what dropped.{}",
                        if example { "\nHere, an example: your own dungeons show up by themselves." } else { "" }
                    ),
                )
            }
            Step::Dungeon => {
                add(&mut spots, "title.dungeon");
                (
                    "The dungeon you are in".into(),
                    format!(
                        "While you play, the title bar shows your dungeon, how hard it is and how long you have been in. Click it to open the run.{}",
                        if app.dungeon_visits.current.is_none() {
                            "\nHere, an example: it shows up by itself in your next dungeon."
                        } else {
                            ""
                        }
                    ),
                )
            }
            Step::GuildRun => {
                add(&mut spots, "battles.guild");
                (
                    "Did you notice your guild?".into(),
                    "Below each run the Gobbler compares you with the guildmates who were there. A Loot Gobbler server makes that work: every Gobbler shares what it saw, so they all know who played with whom. That is the heart of the Gobbler.\nYou do not need it to use the app, but it opens up the multiplayer side: challenges, the guild leaderboard, loot collections and more.".into(),
                )
            }
            Step::Connect => {
                add(&mut spots, "server.connection");
                if app.server.state != lg_app::server::State::Online {
                    add(&mut clicks, "server.connect");
                    open = false;
                    waiting = Some("Next opens once the connection works.");
                    (
                        "Connect to your server".into(),
                        "Ask your guild's admin for the server address, or enter it if you know it. Then click Connect.".into(),
                    )
                } else {
                    ("Connected".into(), "The server answers. Next: your account.".into())
                }
            }
            Step::Account => {
                add(&mut spots, "server.account");
                if !self.registered && app.server.user.is_none() {
                    add(&mut clicks, "server.register");
                    open = false;
                    waiting = Some(if app.server.state == lg_app::server::State::Online {
                        "Next opens once you are registered."
                    } else {
                        "The server is not connected: go back a step."
                    });
                    (
                        "Create your account".into(),
                        "Your name comes from the game, so it is filled in already. Choose a password and click Register.".into(),
                    )
                } else {
                    (
                        "That is all for now".into(),
                        "An admin or moderator has to accept you. Until then everything that does not need the server works as usual."
                            .into(),
                    )
                }
            }
            Step::Guild => {
                add(&mut spots, "guild.caravan");
                (
                    "Your guild".into(),
                    "Once you are accepted, your guild shows up here. The Loot Caravan, for example, collects what everyone in the guild finds.".into(),
                )
            }
            Step::Settings => {
                add(&mut spots, "settings.itemSounds");
                ("Make it yours".into(), "Sounds for items and key pops, the overlays, fonts and the loot filter.".into())
            }
            Step::Done => {
                next = "FINISH";
                ("You are all set".into(), "Happy looting! Start the guide again any time from Info > Newcomer's Guide.".into())
            }
        };
        if merged && spots.len() > 1 {
            // one frame around all of them
            let u = spots.iter().skip(1).fold(spots[0], |u, r| u.union(*r));
            spots = vec![u];
        }
        View { head, title, body, waiting: waiting.map(String::from), spots, clicks, on_click, open, next, choose: s == Step::Choose }
    }

    /// Over the window, after the views drew (and marked their parts): the greyed window, the frames, the glows and
    /// the card. Gives what the window is to do for it; the settings it changed go to `asks`.
    pub fn show(&mut self, ctx: &egui::Context, p: &Palette, seen: &Seen, asks: &mut Vec<Ask>) -> Out {
        if let Some(names) = self.scroll.take() {
            request_scroll(ctx, names);
        }
        if self.running {
            self.frame(ctx, p, seen);
        }
        for (k, v) in self.sets.drain(..) {
            asks.push(Ask::Set(k.into(), v));
        }
        let mut out = std::mem::take(&mut self.pending);
        out.example_run = self.running && matches!(self.step(), Step::Battles | Step::Dungeon | Step::GuildRun);
        out.preview_dungeon = self.running && self.step() == Step::Dungeon;
        out
    }

    fn frame(&mut self, ctx: &egui::Context, p: &Palette, seen: &Seen) {
        let s = self.step();
        let chars = matches!(s, Step::ChStats | Step::ChLoadout | Step::ChProgress | Step::ChItem);
        let t = targets(ctx);
        let loaded = seen.app.characters.all().any(|c| c.alive && c.class_type > 0);
        if chars
            && loaded
            && (self.want_character || (!t.rects.contains_key("characters.loadout") && seen.now - self.last_character_nav > 1500))
        {
            // the characters came in while waiting: show one
            self.want_character = false;
            self.show_a_character(seen.app, seen.now);
        }
        if seen.app.server.account_feedback.starts_with("Registered.") {
            self.registered = true;
        }
        let v = self.describe(s, seen, &t);
        // a click on what glows does what it always does, then the guide goes on
        if v.on_click
            && ctx
                .input(|i| i.pointer.primary_clicked() && i.pointer.interact_pos().is_some_and(|q| v.clicks.iter().any(|r| r.contains(q))))
        {
            self.go(self.index + 1, true);
            ctx.request_repaint();
            return;
        }
        // an item opened in the Inventory: on to the Inventory step
        if s == Step::ChItem && seen.tab == "Inventory" {
            self.go(ordinal(Step::Inventory), true);
            ctx.request_repaint();
            return;
        }
        let screen = ctx.content_rect();
        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, Id::unique("lg-guide-layer")));
        paint_layer(&painter, p, screen, &v, ctx.input(|i| i.time));
        // the card: below what is framed if there is room, else above, right or left; in the middle without
        let size = card_size(&painter, p, &v, self.index > 0, s != Step::Done);
        let placed = place(&v.spots, &v.clicks, size, screen);
        let at = if s == Step::Choose {
            // stays where it was once the menu closed, so it does not jump from under the mouse
            if seen.menu_open {
                self.menu_was_open = true;
                placed
            } else if self.menu_was_open {
                *self.card_at.get_or_insert(placed)
            } else {
                placed
            }
        } else {
            placed
        };
        if s != Step::Choose || seen.menu_open {
            self.card_at = Some(at);
        }
        let mut press = None;
        egui::Area::new(Id::unique("lg-guide-card")).order(egui::Order::Tooltip).fixed_pos(at).show(ctx, |ui| {
            press = card(ui, p, &v, size, self.index > 0, s != Step::Done);
        });
        match press {
            Some(Press::Back) => self.go(self.index.saturating_sub(1), false),
            Some(Press::Next) => self.go(self.index + 1, false),
            Some(Press::Quick) => {
                self.pending.menu = Some(false);
                self.go(self.index + 1, false);
            }
            Some(Press::Skip) => self.end(),
            None => {}
        }
        // the glow pulses
        ctx.request_repaint_after(std::time::Duration::from_millis(40));
    }
}

/// The rounded frame around a part (Java `GuideLayer.round`).
fn ring(r: Rect) -> Rect {
    r.expand(RING)
}

/// The window greyed out but for the framed parts (Java `GuideLayer.paintComponent`), the gold frames with a soft
/// glow, what to click glowing with CLICK ME.
fn paint_layer(painter: &egui::Painter, p: &Palette, screen: Rect, v: &View, time: f64) {
    let grey = Color32::from_rgba_unmultiplied(24, 22, 20, 140);
    // the grey in the cells of a grid through the frames' edges that no frame covers
    let holes: Vec<Rect> = v.spots.iter().map(|r| ring(*r)).collect();
    let mut xs = vec![screen.left(), screen.right()];
    let mut ys = vec![screen.top(), screen.bottom()];
    for h in &holes {
        xs.extend([h.left().clamp(screen.left(), screen.right()), h.right().clamp(screen.left(), screen.right())]);
        ys.extend([h.top().clamp(screen.top(), screen.bottom()), h.bottom().clamp(screen.top(), screen.bottom())]);
    }
    xs.sort_by(f32::total_cmp);
    xs.dedup();
    ys.sort_by(f32::total_cmp);
    ys.dedup();
    for wy in ys.windows(2) {
        for wx in xs.windows(2) {
            let cell = Rect::from_min_max(pos2(wx[0], wy[0]), pos2(wx[1], wy[1]));
            if cell.width() <= 0.0 || cell.height() <= 0.0 || holes.iter().any(|h| h.contains_rect(cell)) {
                continue;
            }
            painter.rect_filled(cell, 0.0, grey);
        }
    }
    let gold = p.gold;
    for h in &holes {
        for i in (1..=6).rev() {
            let falloff = 1.0 - (i - 1) as f32 / 6.0;
            let a = (110.0 * falloff * falloff).round() as u8;
            let c = Color32::from_rgba_unmultiplied(gold.r(), gold.g(), gold.b(), a);
            painter.rect_stroke(h.expand(i as f32), (12.0 + i as f32) / 2.0, Stroke::new(2.0, c), StrokeKind::Middle);
        }
        painter.rect_stroke(*h, 6.0, Stroke::new(3.0, gold), StrokeKind::Middle);
    }
    let pulse = (0.5 + 0.5 * (time * 1000.0 / 1100.0 * std::f64::consts::TAU).sin()) as f32;
    for r in &v.clicks {
        let size = 4 + (10.0 * pulse).round() as i32;
        for i in (1..=size).rev() {
            let falloff = 1.0 - (i - 1) as f32 / size as f32;
            let a = (255.0 * (0.25 + 0.4 * pulse) * falloff * falloff).round().min(255.0) as u8;
            let c = Color32::from_rgba_unmultiplied(GLOW.r(), GLOW.g(), GLOW.b(), a);
            painter.rect_stroke(r.expand(i as f32), (8.0 + i as f32) / 2.0, Stroke::new(2.0, c), StrokeKind::Middle);
        }
        painter.rect_stroke(r.expand(1.0), 4.0, Stroke::new(2.0, GLOW), StrokeKind::Middle);
    }
    if let Some(r) = v.clicks.first() {
        // "CLICK ME" under it (above it at the bottom edge), pulsing with the glow
        let f = theme::head((20.0 + 3.0 * pulse).round());
        let w = jtext::width(painter, "CLICK ME", &f) + 16.0;
        let h = jtext::height(painter, &f) + 2.0;
        let mut y = r.bottom() + 16.0;
        if y + h > screen.bottom() - 4.0 {
            y = r.top() - 16.0 - h;
        }
        let x = (r.center().x - w / 2.0).clamp(screen.left() + 4.0, (screen.right() - w - 4.0).max(screen.left() + 4.0));
        let b = Rect::from_min_size(pos2(x, y), vec2(w, h));
        painter.rect_filled(b, 4.0, GLOW);
        jtext::draw(painter, b.center(), Align2::CENTER_CENTER, "CLICK ME", &f, Color32::from_rgb(0x1F, 0x1A, 0x14));
    }
}

/// Java `GuideLayer.placeCard`.
fn place(spots: &[Rect], glows: &[Rect], size: egui::Vec2, screen: Rect) -> Pos2 {
    let (w, h) = (size.x, size.y);
    let gap = 18.0;
    let (x, y) = if spots.is_empty() {
        (screen.center().x - w / 2.0, screen.center().y - h / 2.0)
    } else {
        let u = spots.iter().skip(1).fold(spots[0], |u, r| u.union(*r)).expand(RING);
        if u.bottom() + gap + h + 30.0 <= screen.bottom() {
            (u.left(), u.bottom() + gap + if glows.is_empty() { 0.0 } else { 30.0 })
        } else if u.top() - gap - h >= screen.top() + 8.0 {
            (u.left(), u.top() - gap - h)
        } else if u.right() + gap + w <= screen.right() {
            (u.right() + gap, u.top())
        } else if u.left() - gap - w >= screen.left() + 8.0 {
            (u.left() - gap - w, u.top())
        } else if let Some(t) = glows.first() {
            // a big frame: under what to click, inside it
            (t.left(), t.bottom() + 16.0 + 34.0)
        } else {
            (screen.right() - w - 24.0, screen.bottom() - h - 24.0)
        }
    };
    let x = x.min(screen.right() - w - 8.0).max(screen.left() + 8.0);
    let y = y.min(screen.bottom() - h - 8.0).max(screen.top() + 8.0);
    pos2(x.round(), y.round())
}

const PAD_X: f32 = 18.0;
const PAD_Y: f32 = 14.0;
const BUTTON_H: f32 = 34.0;

fn body_width() -> f32 {
    CARD_W - 2.0 * PAD_X - 8.0
}

fn title_font() -> egui::FontId {
    theme::head(30.0)
}

fn body_galley(painter: &egui::Painter, p: &Palette, body: &str) -> std::sync::Arc<egui::Galley> {
    painter.layout(body.to_string(), theme::body(18.0), p.ink, body_width())
}

fn button_w(painter: &egui::Painter, t: &str) -> f32 {
    jtext::width(painter, t, &crate::widgets::small()) + 32.0
}

fn card_size(painter: &egui::Painter, p: &Palette, v: &View, back: bool, skip: bool) -> egui::Vec2 {
    let small = crate::widgets::small();
    let head_h = jtext::height(painter, &small).max(if skip { 26.0 } else { 0.0 });
    let title_w = jtext::width(painter, &v.title, &title_font());
    let body = body_galley(painter, p, &v.body);
    let mut h = PAD_Y + head_h + jtext::height(painter, &title_font()) + 6.0 + body.size().y;
    let mut w = (CARD_W - 2.0 * PAD_X).max(title_w);
    if let Some(wt) = &v.waiting {
        h += 8.0 + jtext::height(painter, &theme::body(18.0));
        w = w.max(jtext::width(painter, wt, &theme::body(18.0)));
    }
    let mut bw = if back { button_w(painter, "BACK") + 8.0 } else { 0.0 };
    bw += if v.choose { button_w(painter, "QUICK") + 8.0 + button_w(painter, "EXTENSIVE") } else { button_w(painter, v.next) };
    w = w.max(bw);
    h += 10.0 + BUTTON_H + PAD_Y;
    vec2((w + 2.0 * PAD_X).ceil(), h.ceil())
}

/// A card button (FlatLaf's: gold when primary, greyed when off); true when clicked.
fn button(ui: &mut Ui, p: &Palette, r: Rect, t: &str, primary: bool, on: bool) -> bool {
    let resp = ui.interact(r, ui.scope_id().with(("guide-button", t)), if on { Sense::click() } else { Sense::hover() });
    let painter = ui.painter();
    let (fill, fg) = if primary {
        (if resp.hovered() && on { p.gold_hover } else { p.gold }, p.on_accent)
    } else {
        (if resp.hovered() && on { p.sand } else { p.header }, p.ink)
    };
    let alpha = if on { 1.0 } else { 0.45 };
    painter.rect(r, 4.0, fill.gamma_multiply(alpha), Stroke::new(1.0, Color32::BLACK.gamma_multiply(alpha)), StrokeKind::Inside);
    jtext::draw(painter, r.center(), Align2::CENTER_CENTER, t, &crate::widgets::small(), fg.gamma_multiply(alpha));
    if !on && t == "EXTENSIVE" {
        resp.clone().on_hover_text("Coming soon");
    }
    on && resp.clicked()
}

/// The step's card: paper with a gold edge and a soft shadow; head (and Skip), title, text, what it waits for, the
/// buttons.
fn card(ui: &mut Ui, p: &Palette, v: &View, size: egui::Vec2, back: bool, skip: bool) -> Option<Press> {
    let (r, _) = ui.allocate_exact_size(size, Sense::click());
    let painter = ui.painter().clone();
    painter.rect_filled(r.translate(vec2(4.0, 6.0)), 7.0, Color32::from_black_alpha(90));
    painter.rect_filled(r, 7.0, p.paper);
    painter.rect_stroke(r.shrink(1.0), 7.0, Stroke::new(2.0, p.gold), StrokeKind::Middle);
    let small = crate::widgets::small();
    let x = r.left() + PAD_X;
    let mut y = r.top() + PAD_Y;
    let head_h = jtext::height(&painter, &small).max(if skip { 26.0 } else { 0.0 });
    jtext::draw(&painter, pos2(x, y + head_h / 2.0), Align2::LEFT_CENTER, &v.head, &small, fixed::MUTED);
    let mut press = None;
    if skip {
        let f = theme::body(18.0);
        let sw = jtext::width(&painter, "Skip", &f) + 16.0;
        let sr = Rect::from_min_size(pos2(r.right() - PAD_X - sw, y), vec2(sw, head_h));
        let resp = ui
            .interact(sr, ui.scope_id().with("guide-skip"), Sense::click())
            .on_hover_text("Ends the guide. Info > Newcomer's Guide starts it again.");
        if resp.hovered() {
            painter.rect_filled(sr, 4.0, p.sand.gamma_multiply(0.6));
        }
        jtext::draw(&painter, sr.center(), Align2::CENTER_CENTER, "Skip", &f, p.ink);
        if resp.clicked() {
            press = Some(Press::Skip);
        }
    }
    y += head_h;
    let t = jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, &v.title, &title_font(), p.gold);
    y = t.bottom() + 6.0;
    let body = body_galley(&painter, p, &v.body);
    let bh = body.size().y;
    painter.galley(pos2(x, y), body, p.ink);
    y += bh;
    if let Some(w) = &v.waiting {
        y += 8.0;
        let t = jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, w, &theme::body(18.0), p.gold);
        y = t.bottom();
    }
    y += 10.0;
    let mut bx = x;
    let mut put = |ui: &mut Ui, t: &str, primary: bool, on: bool| {
        let w = button_w(&painter, t);
        let clicked = button(ui, p, Rect::from_min_size(pos2(bx, y), vec2(w, BUTTON_H)), t, primary, on);
        bx += w + 8.0;
        clicked
    };
    if back && put(ui, "BACK", false, true) {
        press = Some(Press::Back);
    }
    if v.choose {
        if put(ui, "QUICK", true, true) {
            press = Some(Press::Quick);
        }
        put(ui, "EXTENSIVE", false, false);
    } else if put(ui, v.next, true, v.open) {
        press = Some(Press::Next);
    }
    press
}
