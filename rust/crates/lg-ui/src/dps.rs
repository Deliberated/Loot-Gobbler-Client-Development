//! The Battles tab's DPS page (Java `DpsPanel`): the run shown (live, held after it ended, or picked) with your DPS
//! and rank on top, its bosses' damage tables (the latest boss on top), and the run history on the right.

use crate::battles::duration;
use crate::jtext;
use crate::profile::{Ctx, num};
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::dps_history::{Boss, PlayerRow, Run};
use std::collections::HashSet;

const DUNGEON_ICON: f32 = 34.0;
const WEAPON_RACK: i32 = 480;
const ROW_H: f32 = 26.0;
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/// The class colours of the damage tables.
fn class_color(name: &str) -> Color32 {
    let c = match name {
        "Rogue" => 0x6E7B8B,
        "Archer" => 0x5FA343,
        "Wizard" => 0xB36BE0,
        "Priest" => 0xE0C36B,
        "Warrior" => 0xC0503C,
        "Knight" => 0xE88A3A,
        "Paladin" => 0xF2C230,
        "Assassin" => 0x49A07A,
        "Necromancer" => 0x8C5AD6,
        "Huntress" => 0xE0679C,
        "Mystic" => 0x3FC4D6,
        "Trickster" => 0xE85AB4,
        "Sorcerer" => 0x4C8FE0,
        "Ninja" => 0x7A8899,
        "Samurai" => 0xD64545,
        "Bard" => 0xD9B44A,
        "Summoner" => 0x57B894,
        "Kensei" => 0xB5543A,
        "Druid" => 0x6B6B48,
        _ => return fixed::STONE,
    };
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

pub struct DpsView {
    /// The run picked in the history (its start); None: live.
    pub selected: Option<i64>,
    /// LIVE on: between runs the last one stays in view until the next run's first damage.
    pub hold: bool,
    /// Toggled boss blocks: logged runs' opened ones, the live run's collapsed ones.
    pub expanded: HashSet<String>,
    held_opened: i64,
}

impl Default for DpsView {
    fn default() -> Self {
        DpsView { selected: None, hold: true, expanded: HashSet::new(), held_opened: 0 }
    }
}

fn dps(b: &Boss, p: &PlayerRow) -> f64 {
    f64::from(p.damage) / (b.fight_ms as f64 / 1000.0).max(1.0)
}

fn share(b: &Boss, p: &PlayerRow) -> f64 {
    let t: i64 = b.players.iter().map(|x| i64::from(x.damage)).sum();
    if t == 0 { 0.0 } else { 100.0 * f64::from(p.damage) / t as f64 }
}

fn loadout_rank(run: &Run, block: &Boss) -> usize {
    let Some(me) = block.players.iter().find(|x| x.me) else { return 0 };
    let d = dps(block, me);
    1 + run.bosses.iter().filter(|b| b.players.iter().find(|x| x.me).is_some_and(|m| dps(b, m) > d)).count()
}

fn boss_key(run: &Run, live: bool, b: &Boss, index: usize) -> String {
    if live {
        format!("live/{}/{}", b.id, b.object_type)
    } else {
        format!("{}/{}", run.start, if b.id != 0 { b.id as usize } else { index })
    }
}

/// "14 Nov, 13:01".
fn when(zone: &lg_app::localtime::Zone, t: i64) -> String {
    let s = zone.local_date_time(t);
    format!("{} {}, {}", s[8..10].trim_start_matches('0'), MONTHS[s[5..7].parse::<usize>().unwrap_or(1) - 1], &s[11..16])
}

impl DpsView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let hist = &app.dps_history;
        let runs: Vec<&Run> = hist.runs.iter().filter(|r| !r.hidden).collect();
        if self.selected.is_some_and(|s| !runs.iter().any(|r| r.start == s)) {
            self.selected = None;
        }
        let live = hist.live.as_ref();
        let held =
            (self.selected.is_none() && self.hold && live.is_none_or(|l| l.bosses.is_empty())).then(|| runs.first().copied()).flatten();
        if let Some(h) = held
            && self.held_opened != h.start
        {
            self.held_opened = h.start;
            self.open_latest(h);
        }
        let picked = self.selected.and_then(|s| runs.iter().copied().find(|r| r.start == s));
        let shown = picked.or(held).or(live);
        let shown_live = picked.is_none() && held.is_none();
        // the strip on top: the run, your DPS and rank, LIVE
        let painter = ui.painter().clone();
        let strip_h = 70.0;
        let strip = Rect::from_min_size(body.min, vec2(body.width(), strip_h));
        painter.rect(strip, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
        let (title, your, rank) = match shown.filter(|r| !r.bosses.is_empty()) {
            None => ((if self.selected.is_none() { "NO DUNGEON" } else { "NO BOSSES" }).to_string(), "0".to_string(), "-".to_string()),
            Some(run) => {
                let t = format!(
                    "{}{}",
                    run.dungeon.to_uppercase(),
                    if held.is_some_and(|h| std::ptr::eq(h, run)) {
                        "    (LAST RUN)"
                    } else if self.selected.is_none() {
                        "    (LIVE)"
                    } else {
                        "    (LOGGED RUN)"
                    }
                );
                let main = if run.guild_hall { run.bosses.last() } else { run.main_boss() };
                let me = main.and_then(|b| b.players.iter().position(|x| x.me).map(|i| (b, i)));
                match me {
                    None => (t, "0".into(), "-".into()),
                    Some((b, i)) => (
                        t,
                        num(dps(b, &b.players[i]).round() as i64),
                        if run.guild_hall { loadout_rank(run, b).to_string() } else { (i + 1).to_string() },
                    ),
                }
            }
        };
        jtext::draw(&painter, pos2(strip.left() + 12.0, strip.center().y), Align2::LEFT_CENTER, &title, &small(), fixed::GOLD);
        let live_on = self.selected.is_none() && self.hold;
        let lw = jtext::width(&painter, "LIVE", &small()) + 32.0;
        let lr = Rect::from_min_size(pos2(strip.right() - 1.0 - 12.0 - 18.0 - lw, strip.center().y - 18.0), vec2(lw, 36.0));
        painter.rect(lr, 0.0, if live_on { p.gold } else { p.header }, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
        jtext::draw(&painter, lr.center(), Align2::CENTER_CENTER, "LIVE", &small(), if live_on { p.on_accent } else { p.ink });
        let live_resp = ui.interact(lr, ui.scope_id().with("dps-live"), Sense::click());
        widgets::tip(
            ui,
            lr,
            "live-tip",
            "On: follow the dungeon you are in, and keep the last run open until the next one deals damage.\nOff: show only what you click.",
        );
        if live_resp.clicked() {
            let on = self.selected.is_some() || !self.hold;
            if !on && let Some(h) = held {
                self.selected = Some(h.start);
            } else if on {
                self.selected = None;
            }
            self.hold = on;
        }
        let mut x = lr.left() - 18.0;
        for (cap, val) in [("RANK", &rank), ("YOUR DPS", &your)] {
            let w = jtext::width(&painter, cap, &small()).max(jtext::width(&painter, val, &text(26.0)));
            let ch = jtext::height(&painter, &small());
            let vh = jtext::height(&painter, &text(26.0));
            let top = strip.center().y - ((ch + vh) / 2.0).floor();
            jtext::draw(&painter, pos2(x - w / 2.0, top), Align2::CENTER_TOP, cap, &small(), p.ink_soft);
            jtext::draw(&painter, pos2(x - w / 2.0, top + ch), Align2::CENTER_TOP, val, &text(26.0), fixed::GOLD);
            x -= w + 18.0;
        }
        // the boss damage and the run history beside it
        let below = Rect::from_min_max(pos2(body.left(), strip.bottom() + 8.0), body.max);
        let side_w = 400.0;
        let main = Rect::from_min_max(below.min, pos2(below.right() - side_w - 8.0, below.bottom()));
        let side = Rect::from_min_max(pos2(main.right() + 10.0, below.top()), pos2(below.right() - 8.0, below.bottom()));
        let icons = &mut *c.icons;
        let data = app.world.data();
        let mut toggle: Option<String> = None;
        let pending = std::cell::RefCell::new(Vec::new());
        let c_now = c.now;
        let zone = &app.zone;
        widgets::section(
            ui,
            &p,
            main,
            "Boss Damage",
            fixed::GOLD,
            |ui, head| {
                let bw = jtext::width(ui.painter(), "COPY ALL", &small()) + 32.0;
                if crate::inventory::button(
                    ui,
                    &p,
                    Rect::from_min_size(pos2(head.right() - bw, head.top()), vec2(bw, head.height())),
                    "Copy all",
                    false,
                    "dps-copy-all",
                ) && let Some(run) = shown
                {
                    let text: String = run.bosses.iter().map(|b| boss_text(data, zone, run, b) + "\n").collect();
                    ui.ctx().copy_text(text);
                }
            },
            |ui, _| {
                egui::ScrollArea::vertical().id_salt("boss-damage").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let w = ui.available_width() - 16.0 - 1.0;
                    let x = top.x + 8.0;
                    let mut y = top.y + 8.0;
                    match shown.filter(|r| !r.bosses.is_empty()) {
                        None => {
                            jtext::draw(
                                &painter,
                                pos2(x + 14.0, y + 14.0),
                                Align2::LEFT_TOP,
                                "No bosses fought yet.",
                                &text(18.0),
                                fixed::MUTED,
                            );
                            y += 14.0 + jtext::height(&painter, &text(18.0)) + 14.0;
                        }
                        Some(run) => {
                            let is_live = shown_live && live.is_some_and(|l| std::ptr::eq(l, run));
                            let visit = if run.guild_hall { None } else { visit_of(app, run, is_live) };
                            // Moonlight Village: the three dancers' subtotal above them (under Umi)
                            let mut interim = visit.filter(|v| v.mv_phases.iter().any(|ph| DANCERS.contains(&ph.boss)));
                            for i in (0..run.bosses.len()).rev() {
                                let b = &run.bosses[i];
                                if let Some(v) = interim
                                    && DANCERS.contains(&b.object_type)
                                {
                                    y += dancers_bar(ui, &p, v, c_now, pos2(x, y), w) + 6.0;
                                    interim = None;
                                }
                                let key = boss_key(run, is_live, b, i);
                                let open = is_live != self.expanded.contains(&key);
                                let (h, clicked) = boss_block(ui, &p, icons, data, run, b, open, pos2(x, y), w, &key, zone, visit, c_now);
                                if clicked {
                                    toggle = Some(key);
                                }
                                y += h + 6.0;
                            }
                        }
                    }
                    ui.allocate_exact_size(vec2(w + 16.0, y + 2.0 - top.y), Sense::hover());
                });
            },
        );
        if let Some(k) = toggle
            && !self.expanded.remove(&k)
        {
            self.expanded.insert(k);
        }
        let mut pick: Option<Option<i64>> = None;
        widgets::section(
            ui,
            &p,
            side,
            "Run History",
            fixed::BLUE,
            |ui, head| {
                let bw = jtext::width(ui.painter(), "CLEAR ALL", &small()) + 32.0;
                if crate::inventory::button(
                    ui,
                    &p,
                    Rect::from_min_size(pos2(head.right() - bw, head.top()), vec2(bw, head.height())),
                    "Clear all",
                    true,
                    "dps-clear-all",
                ) {
                    pending.borrow_mut().push(crate::window::Ask::Confirm {
                        title: "Clear all".into(),
                        text: "Delete all past dungeons?".into(),
                        yes: "Yes".into(),
                        no: "No".into(),
                        then: Box::new(crate::window::Ask::ClearRuns),
                    });
                }
            },
            |ui, _| {
                egui::ScrollArea::vertical().id_salt("run-history").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let w = ui.available_width() - 16.0 - 1.0;
                    let x = top.x + 8.0;
                    let mut y = top.y + 8.0;
                    if let Some(l) = live {
                        let sel = self.selected.is_none() && held.is_none();
                        if history_row(ui, &p, icons, app, l, true, sel, pos2(x, y), w).0 {
                            pick = Some(None);
                        }
                        y += row_height(&painter);
                    }
                    if live.is_none() && runs.is_empty() {
                        jtext::draw(
                            &painter,
                            pos2(x + 10.0, y + 10.0),
                            Align2::LEFT_TOP,
                            "Your dungeon runs appear here.",
                            &text(18.0),
                            fixed::MUTED,
                        );
                        y += 20.0 + jtext::height(&painter, &text(18.0));
                    }
                    for run in &runs {
                        let sel = self.selected == Some(run.start) || held.is_some_and(|h| std::ptr::eq(h, *run));
                        let (clicked, removed) = history_row(ui, &p, icons, app, run, false, sel, pos2(x, y), w);
                        if clicked {
                            pick = Some(Some(run.start));
                        }
                        if removed {
                            // only marked removed (Java `DpsHistory.remove`), no question asked
                            pending.borrow_mut().push(crate::window::Ask::HideRun(run.start));
                        }
                        y += row_height(&painter);
                    }
                    ui.allocate_exact_size(vec2(w + 16.0, y + 8.0 - top.y), Sense::hover());
                });
            },
        );
        c.asks.extend(pending.take());
        if let Some(sel) = pick {
            self.selected = sel;
            if let Some(run) = sel.and_then(|s| runs.iter().find(|r| r.start == s)) {
                self.open_latest(run);
            }
        }
    }

    /// Java `showRun`: a saved run (None: the one in progress, followed live) with its latest boss opened.
    pub fn show_run(&mut self, history: &lg_app::dps_history::DpsHistory, start: Option<i64>) {
        self.selected = start;
        if let Some(run) = start.and_then(|s| history.runs.iter().find(|r| r.start == s)) {
            self.open_latest(run);
        }
    }

    /// Opens a saved run's latest boss.
    fn open_latest(&mut self, run: &Run) {
        if let Some(b) = run.bosses.last() {
            self.expanded.insert(boss_key(run, false, b, run.bosses.len() - 1));
        }
    }
}

fn row_height(painter: &egui::Painter) -> f32 {
    (2.0 * jtext::height(painter, &text(18.0))).max(DUNGEON_ICON) + 8.0 + 3.0
}

/// One run in the history (Java `historyRow`): portal, name and when, your rank, delete; true when clicked.
fn history_row(
    ui: &Ui,
    p: &Palette,
    icons: &mut crate::icons::Icons,
    app: &lg_app::App,
    r: &Run,
    current: bool,
    selected: bool,
    at: egui::Pos2,
    w: f32,
) -> (bool, bool) {
    let painter = ui.painter();
    let data = app.world.data();
    let h = row_height(painter);
    let rr = Rect::from_min_size(at, vec2(w, h));
    // the row first: its delete button lies over it
    let row = ui.interact(rr, ui.scope_id().with(("run", r.start)), Sense::click());
    painter.rect(rr, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    if selected {
        painter.rect_stroke(rr, 0.0, Stroke::new(2.0, p.gold), StrokeKind::Inside);
    }
    let left = rr.left() + if selected { 10.0 } else { 11.0 };
    let icon = if r.guild_hall {
        icons.object(ui.ctx(), data, WEAPON_RACK, DUNGEON_ICON as u32)
    } else {
        data.dungeons
            .portal_by_name
            .get(&r.dungeon)
            .and_then(|id| icons.fitted(ui.ctx(), data, *id, DUNGEON_ICON as u32, DUNGEON_ICON as u32))
    };
    if let Some(t) = icon {
        painter.image(
            t.id(),
            Rect::from_min_size(pos2(left, rr.center().y - DUNGEON_ICON / 2.0), vec2(DUNGEON_ICON, DUNGEON_ICON)),
            crate::detail::uv(),
            Color32::WHITE,
        );
    }
    let tx = left + DUNGEON_ICON + 10.0;
    let lh = jtext::height(painter, &text(18.0));
    let ty = rr.center().y - lh;
    jtext::draw(painter, pos2(tx, ty), Align2::LEFT_TOP, &r.dungeon, &text(18.0), if selected { p.gold } else { p.ink });
    let when_text = format!("{}  \u{00B7}  {}", when(&app.zone, r.start), duration(r.duration_ms));
    jtext::draw(painter, pos2(tx, ty + lh), Align2::LEFT_TOP, &when_text, &text(18.0), if selected { p.gold } else { fixed::MUTED });
    // at the right: delete, your rank before it
    let mut rx = rr.right() - 6.0;
    let mut removed = false;
    if !current {
        let bw = 36.0;
        let br = Rect::from_min_size(pos2(rx - bw, rr.center().y - 18.0), vec2(bw, 36.0));
        removed = crate::inventory::button(ui, p, br, "x", true, &format!("run-x-{}", r.start));
        rx = br.left() - 8.0;
    }
    if !r.guild_hall {
        let (t, color) = match r.main_boss().and_then(|b| b.players.iter().position(|x| x.me).map(|i| (i + 1, b.players.len()))) {
            None => ("no boss fought".to_string(), fixed::MUTED),
            Some((place, of)) => (format!("#{place} of {of}"), if place == 1 { fixed::GOLD } else { p.ink_soft }),
        };
        let tr = jtext::draw(painter, pos2(rx, rr.center().y), Align2::RIGHT_CENTER, &t, &text(18.0), color);
        if let Some(b) = r.main_boss().filter(|b| b.players.iter().any(|x| x.me)) {
            widgets::tip(ui, tr, ("run-rank", r.start), &format!("Your place by damage on {}", b.name.as_deref().unwrap_or("null")));
        }
    }
    (row.clicked() && !removed, removed)
}

/// One boss (Java `bossBlock`): its head (icon, name, fight length, players; your place, copy) and, when open, the
/// damage table. Gives its height and whether the head was clicked.
fn boss_block(
    ui: &Ui,
    p: &Palette,
    icons: &mut crate::icons::Icons,
    data: &lg_gamedata::GameData,
    run: &Run,
    b: &Boss,
    open: bool,
    at: egui::Pos2,
    w: f32,
    key: &str,
    zone: &lg_app::localtime::Zone,
    visit: Option<&lg_app::dungeon_visits::Visit>,
    now: i64,
) -> (f32, bool) {
    let painter = ui.painter();
    let f = text(18.0);
    let head_h = 46.0;
    let hr = Rect::from_min_size(at, vec2(w, head_h));
    painter.rect(hr, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let mut x = hr.left() + 11.0;
    // an invisible boss part shows the boss itself
    let mut ty = b.object_type;
    if ty > 0
        && data.object(ty).is_none_or(|o| o.texture.is_empty() || o.texture.ends_with(",invisible"))
        && let Some(e) = data.enemy_sprites.candidates(ty, data.object_name(ty)).first()
    {
        ty = *e;
    }
    if ty > 0
        && let Some(t) = icons.object(ui.ctx(), data, ty, 20)
    {
        painter.image(t.id(), Rect::from_min_size(pos2(x, hr.center().y - 10.0), vec2(20.0, 20.0)), crate::detail::uv(), Color32::WHITE);
        x += 20.0 + 10.0;
    }
    let name = format!(
        "{}{}     {}{}",
        if open { "[-]  " } else { "[+]  " },
        b.name.as_deref().unwrap_or(""),
        duration(b.fight_ms),
        if run.guild_hall { String::new() } else { format!("  \u{00B7}  {} players", b.players.len()) }
    );
    let nr = jtext::draw(painter, pos2(x, hr.center().y), Align2::LEFT_CENTER, &name, &f, p.ink);
    if run.guild_hall {
        // the loadout of this block, then your damage with it
        let mut lx = nr.right() + 16.0;
        for i in 0..4 {
            if let Some(id) = b.loadout.map(|l| l[i]).filter(|id| *id > 0)
                && let Some(t) = icons.object(ui.ctx(), data, id, 22)
            {
                painter.image(
                    t.id(),
                    Rect::from_min_size(pos2(lx + i as f32 * 26.0, hr.center().y - 11.0), vec2(22.0, 22.0)),
                    crate::detail::uv(),
                    Color32::WHITE,
                );
            }
        }
        lx += 4.0 * 22.0 + 12.0 + 16.0;
        let total: i64 = b.players.iter().map(|x| i64::from(x.damage)).sum();
        jtext::draw(painter, pos2(lx, hr.center().y), Align2::LEFT_CENTER, &format!("{} total damage", num(total)), &f, fixed::GOLD);
    }
    let bw = jtext::width(painter, "COPY LOG", &small()) + 32.0;
    let br = Rect::from_min_size(pos2(hr.right() - 7.0 - bw, hr.center().y - 18.0), vec2(bw, 36.0));
    if crate::inventory::button(ui, p, br, "Copy log", false, &format!("copy-{key}")) {
        ui.ctx().copy_text(boss_text(data, zone, run, b));
    }
    if let Some(i) = b.players.iter().position(|x| x.me) {
        let place = if run.guild_hall { loadout_rank(run, b) } else { i + 1 };
        let of = if run.guild_hall { run.bosses.iter().filter(|b| b.players.iter().any(|x| x.me)).count() } else { b.players.len() };
        let pr = jtext::draw(
            painter,
            pos2(br.left() - 16.0, hr.center().y),
            Align2::RIGHT_CENTER,
            &format!("#{place} of {of}"),
            &f,
            if place == 1 { fixed::GOLD } else { p.ink_soft },
        );
        let tip = if run.guild_hall { "This loadout's place among your loadouts, by DPS" } else { "Your place by damage on this boss" };
        widgets::tip(ui, pr, ("place", key), tip);
    }
    let clicked = ui.interact(nr, ui.scope_id().with(("boss", key)), Sense::click()).clicked();
    if !open {
        return (head_h, clicked);
    }
    let mut h = table(painter, p, data, b, run.guild_hall, pos2(at.x, hr.bottom()), w);
    // Moonlight Village: the boss's phases below its damage table
    if let Some(v) = visit {
        let phases: Vec<&lg_app::dungeon_visits::MvPhase> = v.mv_phases.iter().filter(|ph| ph.boss == b.object_type).collect();
        if !phases.is_empty() {
            h += 6.0 + phase_table(ui, painter, p, v, &phases, now, pos2(at.x, hr.bottom() + h + 6.0), w);
        }
    }
    (head_h + h, clicked)
}

/// The damage table (Java `bossTable` / `rangeTable`): #, player with a share bar, class, DPS, damage, taken, %.
fn table(painter: &egui::Painter, p: &Palette, data: &lg_gamedata::GameData, b: &Boss, range: bool, at: egui::Pos2, w: f32) -> f32 {
    let cols: &[&str] =
        if range { &["#", "Name", "Class", "DPS", "DMG", "%"] } else { &["#", "Player", "Class", "DPS", "DMG", "DMG Taken", "%"] };
    let n = cols.len();
    let rest = ((w - 40.0 - 70.0) / (n as f32 - 2.0)).floor();
    let mut widths = vec![40.0f32];
    widths.extend(std::iter::repeat_n(rest, n - 2));
    widths.push(w - 40.0 - rest * (n as f32 - 2.0));
    let hf = small();
    let head_h = 36.0;
    let hr = Rect::from_min_size(at, vec2(w, head_h));
    painter.rect_filled(hr, 0.0, p.header);
    let mut x = at.x;
    for (i, c) in cols.iter().enumerate() {
        jtext::draw(painter, pos2(x + widths[i] / 2.0, hr.center().y), Align2::CENTER_CENTER, c, &hf, p.ink_soft);
        x += widths[i];
        if i < n - 1 {
            painter.rect_filled(Rect::from_min_size(pos2(x - 1.0, hr.top() + 4.0), vec2(1.0, head_h - 8.0)), 0.0, p.line);
        }
    }
    painter.rect_filled(Rect::from_min_size(pos2(at.x, hr.bottom() - 1.0), vec2(w, 1.0)), 0.0, p.line);
    let f = text(18.0);
    let max_share =
        if range { b.players.iter().map(|x| share(b, x)).fold(0.0, f64::max) } else { b.players.first().map_or(1.0, |x| share(b, x)) };
    let max_share = if max_share <= 0.0 { 1.0 } else { max_share };
    let mut y = hr.bottom();
    for (row, pl) in b.players.iter().enumerate() {
        let mut bg = if row % 2 == 1 { p.header } else { p.paper };
        if pl.me {
            bg = mix(Color32::from_rgb(0xE8, 0xC5, 0x52), bg, 0.22);
        }
        let rr = Rect::from_min_size(pos2(at.x, y), vec2(w, ROW_H));
        painter.rect_filled(rr, 0.0, bg);
        let class = data.classes.iter().find(|k| k.id == pl.class_type).map_or("?", |k| k.name.as_str());
        let mut cells: Vec<String> =
            vec![(row + 1).to_string(), pl.name.clone(), class.to_string(), num(dps(b, pl).round() as i64), num(i64::from(pl.damage))];
        if !range {
            cells.push(num(i64::from(pl.taken)));
        }
        cells.push(format!("{:.1}%", share(b, pl)));
        let mut x = at.x;
        for (i, t) in cells.iter().enumerate() {
            let cx = x + 8.0;
            let cy = rr.center().y;
            match i {
                1 => {
                    let bar = share(b, pl) / max_share;
                    if bar > 0.0 {
                        let g = fixed::GOLD;
                        painter.rect_filled(
                            Rect::from_min_size(pos2(x, rr.top() + 2.0), vec2((widths[i] as f64 * bar) as i32 as f32, ROW_H - 4.0)),
                            0.0,
                            Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 48),
                        );
                    }
                    let nr = jtext::draw(painter, pos2(cx, cy), Align2::LEFT_CENTER, t, &f, if pl.me { fixed::GOLD } else { p.ink });
                    if pl.died {
                        let mf = text(16.0);
                        jtext::draw_bold(
                            painter,
                            pos2(nr.right() + 8.0, cy),
                            Align2::LEFT_CENTER,
                            "DIED",
                            &mf,
                            Color32::from_rgb(0xD8, 0x46, 0x3F),
                        );
                    }
                }
                2 => {
                    let c = class_color(t);
                    painter.rect_filled(Rect::from_min_size(pos2(cx, cy - 4.0), vec2(8.0, 8.0)), 0.0, c);
                    jtext::draw(painter, pos2(cx + 16.0, cy), Align2::LEFT_CENTER, t, &f, c);
                }
                _ => {
                    let gold = i == n - 1;
                    jtext::draw(painter, pos2(cx, cy), Align2::LEFT_CENTER, t, &f, if gold { fixed::GOLD } else { p.ink });
                }
            }
            x += widths[i];
        }
        y += ROW_H;
    }
    y - at.y
}

/// Java's line end in `String.format("%n")`.
const NL: &str = if cfg!(windows) { "\r\n" } else { "\n" };

/// Java `%-<w>s`: padded on the right, never cut.
fn left(t: &str, w: usize) -> String {
    format!("{t:<w$}")
}

/// Java `bossText`: one boss as text for the clipboard (Moonlight Village's phases are not ported yet).
pub fn boss_text(data: &lg_gamedata::GameData, zone: &lg_app::localtime::Zone, run: &Run, b: &Boss) -> String {
    let class = |t: i32| data.classes.iter().find(|k| k.id == t).map_or("?".to_string(), |k| k.name.clone());
    let mut out =
        format!("{} - {} ({})  {}\n", b.name.as_deref().unwrap_or("null"), run.dungeon, when(zone, run.start), duration(b.fight_ms));
    if run.guild_hall {
        const SLOTS: [&str; 4] = ["Weapon", "Ability", "Armor", "Ring"];
        let loadout = match b.loadout {
            None => "unknown".to_string(),
            Some(l) => (0..4)
                .map(|i| {
                    let id = l[i];
                    let name =
                        if id <= 0 { "empty".to_string() } else { data.items.item(id).map_or(format!("Item {id}"), |it| it.name.clone()) };
                    format!("{}: {name}", SLOTS[i])
                })
                .collect::<Vec<_>>()
                .join(", "),
        };
        out += &format!("{loadout}\n");
        out += &format!("{} {} {} {:>8} {:>10} {:>7}{NL}", left("#", 3), left("Name", 14), left("Class", 12), "DPS", "DMG", "%");
        for (i, pl) in b.players.iter().enumerate() {
            out += &format!(
                "{} {} {} {:>8} {:>10} {:>6.1}%{NL}",
                left(&(i + 1).to_string(), 3),
                left(&pl.name, 14),
                left(&class(pl.class_type), 12),
                num(dps(b, pl).round() as i64),
                num(i64::from(pl.damage)),
                share(b, pl)
            );
        }
        return out;
    }
    out +=
        &format!("{} {} {} {:>8} {:>10} {:>8} {:>7}{NL}", left("#", 3), left("Player", 14), left("Class", 12), "DPS", "DMG", "Taken", "%");
    for (i, pl) in b.players.iter().enumerate() {
        out += &format!(
            "{} {} {} {:>8} {:>10} {:>8} {:>6.1}%{}{NL}",
            left(&(i + 1).to_string(), 3),
            left(&pl.name, 14),
            left(&class(pl.class_type), 12),
            num(dps(b, pl).round() as i64),
            num(i64::from(pl.damage)),
            num(i64::from(pl.taken)),
            share(b, pl),
            if pl.died { "  died" } else { "" }
        );
    }
    out
}

const DANCERS: [i32; 3] = [20450, 20451, 20452];

/// The dungeon visit a run belongs to: the one in progress for the live run, else the one linked to it.
fn visit_of<'a>(app: &'a lg_app::App, run: &Run, live: bool) -> Option<&'a lg_app::dungeon_visits::Visit> {
    if live {
        return app.dungeon_visits.current.as_ref().filter(|v| v.dungeon == run.dungeon);
    }
    app.dungeon_visits.visits.iter().find(|v| v.run_start == run.start)
}

fn phase_ms(ph: &lg_app::dungeon_visits::MvPhase, v: &lg_app::dungeon_visits::Visit, now: i64) -> i64 {
    if ph.duration_ms > 0 { ph.duration_ms } else { (now - v.start - ph.start_ms).max(0) }
}

/// Your damage between two times after entering (the visit keeps it per second).
fn dealt(v: &lg_app::dungeon_visits::Visit, from: i64, to: i64) -> i64 {
    let end = ((to + 999) / 1000).min(v.dealt.len() as i64);
    ((from / 1000)..end).map(|s| i64::from(v.dealt[s as usize])).sum()
}

fn dancer_name(t: i32) -> String {
    match t {
        20450 => "Sage Genji".into(),
        20451 => "Dancer Miko".into(),
        20452 => "Drummer Kaguya".into(),
        _ => t.to_string(),
    }
}

/// Java `dancersBar`: the three dancers' phases summed up (time, your damage and DPS, at the lantern, flames).
fn dancers_bar(ui: &Ui, p: &Palette, v: &lg_app::dungeon_visits::Visit, now: i64, at: egui::Pos2, w: f32) -> f32 {
    let painter = ui.painter();
    let (mut ms, mut dmg, mut lantern, mut range, mut flames) = (0i64, 0i64, 0i64, 0i64, 0i32);
    let mut per: Vec<(i32, i32)> = Vec::new();
    for ph in v.mv_phases.iter().filter(|ph| DANCERS.contains(&ph.boss)) {
        let d = phase_ms(ph, v, now);
        ms += d;
        dmg += dealt(v, ph.start_ms, ph.start_ms + d);
        lantern += ph.lantern_ms;
        range += ph.range_ms;
        flames += ph.flames;
        match per.iter_mut().find(|x| x.0 == ph.boss) {
            Some(x) => x.1 += ph.flames,
            None => per.push((ph.boss, ph.flames)),
        }
    }
    let tf = text(16.0);
    let sf = text(15.0);
    let h = jtext::height(painter, &tf).max(jtext::height(painter, &sf)) + 16.0;
    let r = Rect::from_min_size(at, vec2(w, h));
    painter.rect_filled(r, 0.0, crate::widgets::brighter(p.sand));
    painter.rect_filled(Rect::from_min_size(at, vec2(3.0, h)), 0.0, fixed::GOLD);
    jtext::draw_bold(painter, pos2(r.left() + 14.0, r.center().y), Align2::LEFT_CENTER, "Dancers interim", &tf, fixed::GOLD);
    let at_lantern = if lantern > 0 { format!("{}%", (100.0 * range as f64 / lantern as f64).round()) } else { "-".into() };
    let stats = format!(
        "{}   \u{00B7}   {} DMG   \u{00B7}   {} DPS   \u{00B7}   at lantern {at_lantern}   \u{00B7}   {flames} flames",
        duration(ms),
        num(dmg),
        num((dmg as f64 * 1000.0 / ms.max(1000) as f64).round() as i64)
    );
    jtext::draw(painter, pos2(r.right() - 14.0, r.center().y), Align2::RIGHT_CENTER, &stats, &sf, p.ink);
    let tip = std::iter::once("Kaguya, Genji and Miko together, before Umi".to_string())
        .chain(per.iter().map(|(b, f)| format!("{}: {f} flames", dancer_name(*b))))
        .collect::<Vec<_>>()
        .join("\n");
    widgets::tip(ui, r, ("dancers", v.start), &tip);
    h
}

/// Java `phaseTable`: one row per phase (song, damage or lantern, time, your damage and DPS, at the lantern,
/// flames), then the totals.
#[allow(clippy::too_many_arguments)]
fn phase_table(
    ui: &Ui,
    painter: &egui::Painter,
    p: &Palette,
    v: &lg_app::dungeon_visits::Visit,
    phases: &[&lg_app::dungeon_visits::MvPhase],
    now: i64,
    at: egui::Pos2,
    w: f32,
) -> f32 {
    let cols = ["Phase", "Song", "Type", "Time", "Your DMG", "Your DPS", "At lantern", "Flames"];
    // Phase at most 60, Flames at most 70, Song wider; the rest share
    let rest = ((w - 60.0 - 70.0 - 220.0) / 5.0).floor();
    let widths = [60.0, 220.0 + (w - 60.0 - 70.0 - 220.0 - rest * 5.0), rest, rest, rest, rest, rest, 70.0];
    let head_h = 36.0;
    let hr = Rect::from_min_size(at, vec2(w, head_h));
    painter.rect_filled(hr, 0.0, p.header);
    let mut x = at.x;
    for (i, c) in cols.iter().enumerate() {
        jtext::draw(painter, pos2(x + widths[i] / 2.0, hr.center().y), Align2::CENTER_CENTER, c, &small(), p.ink_soft);
        x += widths[i];
        if i < cols.len() - 1 {
            painter.rect_filled(Rect::from_min_size(pos2(x - 1.0, hr.top() + 4.0), vec2(1.0, head_h - 8.0)), 0.0, p.line);
        }
    }
    painter.rect_filled(Rect::from_min_size(pos2(at.x, hr.bottom() - 1.0), vec2(w, 1.0)), 0.0, p.line);
    let (mut tms, mut tdmg, mut tl, mut tr, mut tf) = (0i64, 0i64, 0i64, 0i64, 0i32);
    let mut rows: Vec<[String; 8]> = Vec::new();
    for (i, ph) in phases.iter().enumerate() {
        let ms = phase_ms(ph, v, now);
        let dmg = dealt(v, ph.start_ms, ph.start_ms + ms);
        let last = ph.lantern && i > 0 && phases[i - 1].lantern;
        rows.push([
            (i + 1).to_string(),
            ph.title.clone(),
            if ph.lantern { if last { "Lantern (final)" } else { "Lantern" } } else { "Damage" }.into(),
            duration(ms),
            num(dmg),
            num((dmg as f64 * 1000.0 / ms.max(1000) as f64).round() as i64),
            if ph.lantern && ph.lantern_ms > 0 {
                format!("{}%", (100.0 * ph.range_ms as f64 / ph.lantern_ms as f64).round())
            } else {
                "-".into()
            },
            if ph.duration_ms > 0 { ph.flames.to_string() } else { "...".into() },
        ]);
        tms += ms;
        tdmg += dmg;
        tl += ph.lantern_ms;
        tr += ph.range_ms;
        tf += ph.flames;
    }
    rows.push([
        "Total".into(),
        String::new(),
        String::new(),
        duration(tms),
        num(tdmg),
        num((tdmg as f64 * 1000.0 / tms.max(1000) as f64).round() as i64),
        if tl > 0 { format!("{}%", (100.0 * tr as f64 / tl as f64).round()) } else { "-".into() },
        tf.to_string(),
    ]);
    let total = rows.len() - 1;
    let mut y = hr.bottom();
    let f = text(18.0);
    for (k, row) in rows.iter().enumerate() {
        let rr = Rect::from_min_size(pos2(at.x, y), vec2(w, 24.0));
        painter.rect_filled(rr, 0.0, if k % 2 == 1 { p.header } else { p.paper });
        let mut x = at.x;
        for (i, t) in row.iter().enumerate() {
            let mut color = p.ink;
            if i == 7 {
                color = fixed::GOLD;
            }
            if i == 6
                && let Some(pct) = t.strip_suffix('%').and_then(|n| n.parse::<i32>().ok())
            {
                color = if pct >= 90 {
                    fixed::GREEN
                } else if pct < 60 {
                    Color32::from_rgb(0xBE, 0x33, 0x2D)
                } else {
                    p.ink
                };
            }
            if k == total {
                color = if i == 7 { fixed::GOLD } else { p.ink_soft };
            }
            let draw = if k == total { jtext::draw_bold } else { jtext::draw };
            draw(painter, pos2(x + 8.0, rr.center().y), Align2::LEFT_CENTER, t, &f, color);
            x += widths[i];
        }
        y += 24.0;
    }
    let r = Rect::from_min_max(at, pos2(at.x + w, y));
    widgets::tip(
        ui,
        r,
        ("phases", v.start, phases[0].boss),
        "At lantern: your time within 3.5 tiles of the lantern while it was there.\nFlames: the score the game gave for the phase.",
    );
    y - at.y
}
