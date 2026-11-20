//! The Battles tab (Java: `DpsLoggerView`): pages Dungeons, DPS, Overlay. Dungeons: every dungeon you entered, per
//! day with what the day added up to, a tile each (your rank, how long, the exaltation progress made there, the
//! difficulty); the one clicked on the right.

use crate::detail::{Rarity, enchant_badge, uv};
use crate::jtext;
use crate::profile::{Ctx, num};
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::dungeon_visits::{self, Exalt, Visit};
use std::collections::HashMap;

const TILE_MIN_WIDTH: f32 = 400.0;
const GAP: f32 = 8.0;
const ICON: f32 = 64.0;
const POTION: f32 = 22.0;
const SINGLES: usize = 4;
const MULTIS: usize = 2;
const AMULET: i32 = 23624;
const GRAVESTONE: i32 = 1282;
const EXALT_DUNGEON: f64 = 7.0;
const STAT_POTION: [i32; 8] = [0xae9, 0xaea, 0xa1f, 0xa20, 0xa21, 0xa4c, 0xa34, 0xa35];
/// The potion of each stat in the exaltation data's order (dex, spd, vit, wis, def, att, mana, life).
const EXALT_POTION: [usize; 8] = [5, 4, 6, 7, 3, 2, 1, 0];
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const RARITY: [&str; 5] = ["Common", "Uncommon", "Rare", "Legendary", "Divine"];

#[derive(Default)]
pub struct BattlesView {
    pub page: usize,
    /// The visit shown on the right (its start), -1: the newest.
    pub selected: i64,
    pub collapsed: Vec<String>,
    pub dps: crate::dps::DpsView,
}

/// What a day adds up to (Java `DaySum`).
#[derive(Default, Clone)]
struct DaySum {
    white: i64,
    orange: i64,
    exalts: i64,
    completed: i64,
    exalt_dungeons: i64,
    best_item: i32,
    best_enchants: i32,
    fame: i64,
}

/// Java `DpsPanel.duration`: 4:28, and 1:02:05 past an hour.
pub fn duration(ms: i64) -> String {
    let s = ms / 1000;
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

/// The local day of a time, "yyyy-mm-dd".
fn day_of(zone: &lg_app::localtime::Zone, t: i64) -> String {
    zone.local_date_time(t)[..10].to_string()
}

/// "14 Nov 2026".
fn day_label(d: &str) -> String {
    format!("{} {} {}", d[8..10].trim_start_matches('0'), MONTHS[d[5..7].parse::<usize>().unwrap_or(1) - 1], &d[..4])
}

/// Java `merged`: a visit's exaltation progress, one entry per class and stat.
fn merged(v: &Visit) -> Vec<Exalt> {
    let mut out: Vec<Exalt> = Vec::new();
    for e in &v.exalts {
        match out.iter_mut().find(|m| m.class_type == e.class_type && m.stat == e.stat) {
            Some(m) => {
                m.before = m.before.min(e.before);
                m.after = m.after.max(e.after);
            }
            None => out.push(*e),
        }
    }
    out
}

impl BattlesView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &Rarity) {
        let p = *c.p;
        let all = ui.max_rect().shrink(8.0);
        if let Some(i) =
            widgets::segmented(ui, &p, pos2(all.left() + 7.0, all.top()), 36.0, &["Dungeons", "DPS", "Overlay"], self.page, false, &[])
        {
            self.page = i;
        }
        let body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        if self.page == 1 {
            self.dps.show(ui, c, body);
            return;
        }
        if self.page == 2 {
            crate::overlay_page::show(ui, c, body);
            return;
        }
        self.dungeons(ui, c, rarity, body);
    }

    fn day_sums(&self, app: &lg_app::App, visits: &[&Visit]) -> HashMap<String, DaySum> {
        let zone = &app.zone;
        let cap = *lg_app::exalt_store::THRESHOLDS.last().expect("thresholds");
        let mut out: HashMap<String, DaySum> = HashMap::new();
        for v in visits {
            let d = out.entry(day_of(zone, v.start)).or_default();
            d.fame += dungeon_visits::fame_gained(v, &app.characters).max(0);
            for e in &v.exalts {
                d.exalts += i64::from((e.after.min(cap) - e.before.min(cap)).max(0));
            }
            if dungeon_visits::completed(v, &app.dps_history) == Some(true) {
                d.completed += 1;
                if app.world.data().difficulty.get(&v.dungeon).is_some_and(|x| *x >= EXALT_DUNGEON) {
                    d.exalt_dungeons += 1;
                }
            }
        }
        // bags per day from the collection's drops (one bag: drops of a kind within 3 s), and the day's rarest item
        let mut drops: Vec<(i64, usize)> = Vec::new();
        let mut best: HashMap<String, (i32, i32, i64)> = HashMap::new();
        for (id, e) in app.collection.all() {
            for f in e.history.iter().flatten() {
                if f.at <= 0 || f.source != lg_app::collection::DROP {
                    continue;
                }
                let enchants = f.enchant_ids.as_ref().map_or(f.enchants, |v| v.len() as i32);
                if enchants >= 0 {
                    let b = best.entry(day_of(zone, f.at)).or_insert((0, -1, 0));
                    if enchants > b.1 || (enchants == b.1 && f.at > b.2) {
                        *b = (id, enchants, f.at);
                    }
                }
                let bag = f.bag.strip_prefix("Boosted ").unwrap_or(&f.bag);
                match bag {
                    "White" => drops.push((f.at, 0)),
                    "Orange" => drops.push((f.at, 1)),
                    _ => {}
                }
            }
        }
        drops.sort_by_key(|d| d.0);
        let mut last = [i64::MIN / 2; 2];
        let mut bags: HashMap<String, [i64; 2]> = HashMap::new();
        for (t, kind) in drops {
            if t - last[kind] > 3000 {
                bags.entry(day_of(zone, t)).or_default()[kind] += 1;
            }
            last[kind] = t;
        }
        for (day, d) in out.iter_mut() {
            if let Some(b) = bags.get(day) {
                d.white = b[0];
                d.orange = b[1];
            }
            match best.get(day) {
                Some(b) => {
                    d.best_item = b.0;
                    d.best_enchants = b.1;
                }
                None => d.best_enchants = -1,
            }
        }
        out
    }

    fn dungeons(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &Rarity, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let zone = &app.zone;
        let now = app.dungeon_visits.current.as_ref();
        let visits: Vec<&Visit> = app.dungeon_visits.visits.iter().filter(|v| !v.hidden).collect();
        let mut all: Vec<&Visit> = visits.clone();
        if let Some(n) = now {
            all.push(n);
        }
        let sums = self.day_sums(app, &all);
        let shown = now
            .filter(|n| n.start == self.selected)
            .or_else(|| visits.iter().copied().find(|v| v.start == self.selected))
            .or(now)
            .or_else(|| visits.first().copied())
            .map(|v| v.start);
        // the split: the side 420 px, the tiles snapped down to whole columns
        let overhead = 2.0 + 12.0;
        let main_w = body.width() - 420.0 - 8.0;
        let cols = (((main_w - overhead - 20.0 + GAP) / (TILE_MIN_WIDTH + GAP)).floor()).max(1.0);
        let main = Rect::from_min_size(body.min, vec2(overhead + cols * (TILE_MIN_WIDTH + GAP) - GAP + 20.0, body.height()));
        let mut picked = None;
        let mut toggled = None;
        let collapsed = self.collapsed.clone();
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            main,
            "Dungeons",
            fixed::BLUE,
            |ui, head| {
                let painter = ui.painter();
                let bw = jtext::width(painter, "CLEAR ALL", &small()) + 32.0;
                crate::inventory::button(
                    ui,
                    &p,
                    Rect::from_min_size(pos2(head.right() - bw, head.top()), vec2(bw, head.height())),
                    "Clear all",
                    true,
                    "clear-visits",
                );
            },
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("dungeons").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let x = top.x + 10.0;
                    let w = r.width() - 12.0 - 20.0;
                    let tw = ((w - (cols - 1.0) * GAP) / cols).floor();
                    let mut y = top.y + 4.0;
                    if all.is_empty() {
                        jtext::draw(
                            &painter,
                            pos2(x, y),
                            Align2::LEFT_TOP,
                            "The dungeons you enter appear here.",
                            &text(18.0),
                            fixed::MUTED,
                        );
                    }
                    // newest first, the one in progress on top; a new day starts its own group
                    let mut order: Vec<(&Visit, bool)> = now.map(|n| (n, true)).into_iter().collect();
                    order.extend(visits.iter().map(|v| (*v, false)));
                    let mut day: Option<String> = None;
                    let mut col = 0usize;
                    let mut row_h: f32 = 0.0;
                    let th = tile_height(&painter);
                    for (v, current) in order {
                        let d = day_of(zone, v.start);
                        if day.as_ref() != Some(&d) {
                            if col > 0 {
                                y += row_h + GAP;
                            }
                            col = 0;
                            let first = day.is_none();
                            day = Some(d.clone());
                            let open = !collapsed.contains(&d);
                            y += if first { 4.0 } else { 18.0 };
                            let t = jtext::draw(&painter, pos2(x + 6.0, y + 2.0), Align2::LEFT_TOP, &day_label(&d), &text(18.0), p.gold);
                            // the arrow: down while open, right when collapsed
                            let (ax, ay) = (t.right() + 6.0 + 5.0, t.center().y);
                            let tri = if open {
                                vec![pos2(ax - 5.0, ay - 3.0), pos2(ax + 5.0, ay - 3.0), pos2(ax, ay + 3.0)]
                            } else {
                                vec![pos2(ax - 3.0, ay - 5.0), pos2(ax + 3.0, ay), pos2(ax - 3.0, ay + 5.0)]
                            };
                            painter.add(egui::Shape::convex_polygon(tri, fixed::MUTED, Stroke::NONE));
                            if ui
                                .interact(
                                    Rect::from_min_max(t.min, pos2(ax + 6.0, t.bottom())),
                                    ui.scope_id().with(("day", &d)),
                                    Sense::click(),
                                )
                                .clicked()
                            {
                                toggled = Some(d.clone());
                            }
                            y = t.bottom() + 4.0;
                            painter.rect_filled(Rect::from_min_size(pos2(x + 4.0, y + 1.0), vec2(w - 8.0, 2.0)), 0.0, p.gold);
                            y += 4.0;
                            if open {
                                y += day_summary(ui, &p, icons, data, rarity, &sums.get(&d).cloned().unwrap_or_default(), pos2(x, y));
                                y += 3.0;
                                y += 8.0;
                            }
                            row_h = 0.0;
                        }
                        if collapsed.contains(&d) {
                            continue;
                        }
                        let tr = Rect::from_min_size(pos2(x + col as f32 * (tw + GAP), y), vec2(tw, th));
                        if tile(ui, &p, icons, app, v, current, shown == Some(v.start), tr) {
                            picked = Some(v.start);
                        }
                        row_h = th;
                        col += 1;
                        if col as f32 >= cols {
                            col = 0;
                            y += th + GAP;
                            row_h = 0.0;
                        }
                    }
                    if col > 0 {
                        y += row_h;
                    }
                    ui.allocate_exact_size(vec2(r.width() - 12.0, y + 10.0 - top.y), Sense::hover());
                });
            },
        );
        let side = Rect::from_min_max(pos2(main.right() + 8.0, body.top()), body.max);
        let shown_visit = now
            .filter(|n| Some(n.start) == shown)
            .map(|n| (n, true))
            .or_else(|| visits.iter().copied().find(|v| Some(v.start) == shown).map(|v| (v, false)));
        if let Some((v, current)) = shown_visit {
            detail(ui, c, v, current, side);
        }
        if let Some(s) = picked {
            self.selected = s;
        }
        if let Some(d) = toggled {
            if let Some(i) = self.collapsed.iter().position(|x| *x == d) {
                self.collapsed.remove(i);
            } else {
                self.collapsed.push(d);
            }
        }
    }
}

/// A day's summary (Java `daySummary`): bags, the rarest item, exaltations, completed, fame, exalt dungeons; gives
/// its height.
fn day_summary(
    ui: &Ui,
    p: &Palette,
    icons: &mut crate::icons::Icons,
    data: &lg_gamedata::GameData,
    rarity: &Rarity,
    s: &DaySum,
    at: egui::Pos2,
) -> f32 {
    let painter = ui.painter();
    let nf = text(20.0);
    let mut x = at.x + 6.0;
    let y = at.y + 8.0;
    let cap_h = jtext::height(painter, &small());
    let top_h = cap_h.max(32.0);
    let mut item = |icon: Option<(Option<egui::TextureHandle>, f32, usize)>, number: &str, caption: &str, color: Color32| {
        let nw = jtext::width(painter, number, &nf);
        let tw = match &icon {
            Some((_, size, _)) => *size,
            None => jtext::width(painter, &caption.to_uppercase(), &small()),
        };
        let w = nw.max(tw);
        let cx = x + w / 2.0;
        match icon {
            Some((t, size, ench)) => {
                if let Some(t) = t {
                    let ir = Rect::from_min_size(pos2(cx - size / 2.0, y + (top_h - size) / 2.0), vec2(size, size));
                    painter.image(t.id(), ir, uv(), Color32::WHITE);
                    enchant_badge(painter, rarity, ir.right_bottom() - vec2(1.0, 1.0), ench, 6.0);
                }
            }
            None => {
                jtext::draw(painter, pos2(cx, y + top_h / 2.0), Align2::CENTER_CENTER, &caption.to_uppercase(), &small(), fixed::MUTED);
            }
        }
        jtext::draw(painter, pos2(cx, y + top_h), Align2::CENTER_TOP, number, &nf, color);
        x += w + 22.0;
    };
    let white = icons.object(ui.ctx(), data, 1292, 26);
    let orange = icons.object(ui.ctx(), data, 1295, 26);
    item(Some((white, 26.0, 0)), &num(s.white), "White bags", p.ink);
    item(Some((orange, 26.0, 0)), &num(s.orange), "Orange bags", p.ink);
    if s.best_enchants >= 0 {
        let t = icons.object(ui.ctx(), data, s.best_item, 32);
        item(Some((t, 32.0, s.best_enchants as usize)), RARITY[s.best_enchants.min(4) as usize], "Highest Rarity", p.ink);
    }
    item(None, &num(s.exalts), "Exaltations", p.ink);
    item(None, &num(s.completed), "Completed", p.ink);
    item(None, &format!("{}{}", if s.fame > 0 { "+" } else { "" }, num(s.fame)), "Fame", p.gold);
    item(None, &num(s.exalt_dungeons), "Exalt dungeons", p.ink);
    8.0 + top_h + jtext::height(painter, &nf) + 2.0
}

fn tile_height(painter: &egui::Painter) -> f32 {
    8.0 + ICON + 6.0 + jtext::height(painter, &text(20.0)) + 8.0
}

/// One visit (Java `tile`); true when clicked.
fn tile(
    ui: &Ui,
    p: &Palette,
    icons: &mut crate::icons::Icons,
    app: &lg_app::App,
    v: &Visit,
    current: bool,
    selected: bool,
    r: Rect,
) -> bool {
    let painter = ui.painter();
    let data = app.world.data();
    painter.rect_filled(r, 0.0, if selected { mix(p.gold, p.header, 0.3) } else { p.header });
    painter.rect_stroke(
        r,
        0.0,
        Stroke::new(if selected { 2.0 } else { 1.0 }, if selected { p.gold } else { Color32::BLACK }),
        StrokeKind::Inside,
    );
    if v.late {
        painter.circle_filled(r.min + vec2(10.0, 10.0), 6.0, Color32::from_rgb(0x1A, 0x12, 0x0A));
        painter.circle_filled(r.min + vec2(10.0, 10.0), 5.0, Color32::from_rgb(0xF2, 0xC2, 0x30));
    }
    let (x, y) = (r.left() + 8.0, r.top() + 8.0);
    let portal = data.dungeons.portal_by_name.get(&v.dungeon).copied().unwrap_or(-1);
    if let Some(t) = icons.fitted(ui.ctx(), data, portal, 64, ICON as u32) {
        painter.image(t.id(), Rect::from_min_size(pos2(x, y), vec2(ICON, ICON)), uv(), Color32::WHITE);
    }
    // the rank and how long, then the potions
    let mx = x + ICON + 10.0;
    let run = dungeon_visits::run_of(v, &app.dps_history);
    let (rank, rank_color) = match run.filter(|r| !r.guild_hall) {
        None => ("no boss".to_string(), fixed::MUTED),
        Some(run) => match run.main_boss().and_then(|b| b.players.iter().position(|x| x.me).map(|i| (i + 1, b.players.len()))) {
            None => ("no boss fought".to_string(), fixed::MUTED),
            Some((place, of)) => (format!("#{place} of {of}"), if place == 1 { fixed::GOLD } else { p.ink_soft }),
        },
    };
    let rr = jtext::draw(painter, pos2(mx, y), Align2::LEFT_TOP, &rank, &text(16.0), rank_color);
    let dur = if current { format!("NOW  ·  {}", duration(v.duration_ms)) } else { duration(v.duration_ms) };
    jtext::draw(
        painter,
        pos2(rr.right() + 12.0, rr.center().y),
        Align2::LEFT_CENTER,
        &dur,
        &text(18.0),
        if current { fixed::GOLD } else { fixed::MUTED },
    );
    let py = rr.bottom().max(y + jtext::height(painter, &text(18.0))) + 6.0;
    let mut per_stat: Vec<(i32, i32)> = Vec::new();
    for e in merged(v) {
        let n = (e.after - e.before).max(1);
        match per_stat.iter_mut().find(|s| s.0 == e.stat) {
            Some(s) => s.1 += n,
            None => per_stat.push((e.stat, n)),
        }
    }
    let f = text(18.0);
    let (mut sx, mut mx2) = (mx, mx);
    let (mut singles, mut multis) = (0, 0);
    let has_singles = per_stat.iter().any(|s| s.1 == 1);
    let multi_y = py + if has_singles { POTION + 4.0 } else { 0.0 };
    for (stat, count) in &per_stat {
        let potion = icons.object(ui.ctx(), data, STAT_POTION[EXALT_POTION[(*stat).clamp(0, 7) as usize]], POTION as u32);
        if *count > 1 {
            if multis >= MULTIS {
                continue;
            }
            if multis > 0 {
                mx2 += 12.0;
            }
            let t = jtext::draw(painter, pos2(mx2, multi_y + POTION / 2.0), Align2::LEFT_CENTER, &format!("{count}x"), &f, p.ink);
            mx2 = t.right() + 3.0;
            if let Some(tx) = potion {
                painter.image(tx.id(), Rect::from_min_size(pos2(mx2, multi_y), vec2(POTION, POTION)), uv(), Color32::WHITE);
            }
            mx2 += POTION;
            multis += 1;
        } else {
            if singles >= SINGLES {
                continue;
            }
            if singles > 0 {
                sx += 6.0;
            }
            if let Some(tx) = potion {
                painter.image(tx.id(), Rect::from_min_size(pos2(sx, py), vec2(POTION, POTION)), uv(), Color32::WHITE);
            }
            sx += POTION;
            singles += 1;
        }
    }
    // the amulet, after the room the fullest potion rows take
    if !v.amulet_saves.is_empty() {
        let room = (SINGLES as f32 * POTION + (SINGLES as f32 - 1.0) * 6.0)
            .max(MULTIS as f32 * (jtext::width(painter, "99x", &f) + 3.0 + POTION) + (MULTIS as f32 - 1.0) * 12.0);
        let mut ax = mx + room + 10.0;
        let saves = v.amulet_saves.len();
        if saves > 1 {
            let t = jtext::draw(painter, pos2(ax, py + 14.0), Align2::LEFT_CENTER, &format!("{saves}x"), &f, p.ink);
            ax = t.right() + 3.0;
        }
        if let Some(t) = icons.object(ui.ctx(), data, AMULET, 28) {
            painter.image(t.id(), Rect::from_min_size(pos2(ax, py), vec2(28.0, 28.0)), uv(), Color32::WHITE);
        }
    }
    // the name along the bottom; at the right "NOT COMPLETED" and the difficulty with the gravestone
    let nf = text(20.0);
    let ny = r.bottom() - 8.0 - jtext::height(painter, &nf);
    let name = jtext::draw(painter, pos2(x, ny), Align2::LEFT_TOP, &v.dungeon, &nf, if current { fixed::GOLD } else { p.ink });
    let base = name.top() + (20.0f32 * 1050.0 / 1350.0).ceil();
    let mut rx = r.right() - 10.0;
    if let Some(d) = data.difficulty.get(&v.dungeon) {
        let t = if d.fract() == 0.0 { format!("{}", *d as i64) } else { format!("{d}") };
        let bf = text((15.0f32 * 1.3).floor());
        let digits = (19.0f32 * 0.62).ceil() + 1.0;
        if let Some(g) = icons.plain_cut(ui.ctx(), data, GRAVESTONE, digits as u32) {
            let [gw, gh] = g.size();
            rx -= gw as f32;
            painter.image(g.id(), Rect::from_min_size(pos2(rx, base - gh as f32), vec2(gw as f32, gh as f32)), uv(), Color32::WHITE);
            rx -= 5.0;
        }
        let asc = (bf.size * 1050.0 / 1350.0).ceil();
        let tw = jtext::bold_width(painter, &t, &bf);
        let color = Color32::from_rgb(0xF2, 0xEC, 0xDC);
        jtext::draw_bold(painter, pos2(rx - tw, base - asc), Align2::LEFT_TOP, &t, &bf, color);
        rx -= tw;
    }
    let not_done = !current && dungeon_visits::completed(v, &app.dps_history) == Some(false);
    if not_done {
        rx -= 10.0;
        let red = Color32::from_rgb(0xD8, 0x5A, 0x4A);
        let ff = text(13.0);
        let tw = jtext::bold_width(painter, "NOT COMPLETED", &ff) + 12.0 + 2.0;
        let h = jtext::height(painter, &ff) + 4.0;
        let br = Rect::from_min_size(pos2(rx - tw, name.center().y - h / 2.0), vec2(tw, h));
        painter.rect(br, 0.0, Color32::from_black_alpha(90), Stroke::new(1.0, red), StrokeKind::Inside);
        jtext::draw_bold(painter, br.center(), Align2::CENTER_CENTER, "NOT COMPLETED", &ff, red);
    }
    ui.interact(r, ui.scope_id().with(("visit", v.start)), Sense::click()).clicked()
}

const EXALT_STATS: [&str; 8] = ["DEX", "SPD", "VIT", "WIS", "DEF", "ATT", "MP", "HP"];

/// Java `whenText`: "14 Nov, 13:01  ·  10:25", "NOW  ·  since ..." for the one in progress.
fn when_text(zone: &lg_app::localtime::Zone, v: &Visit, current: bool, now: i64) -> String {
    let t = zone.local_date_time(v.start);
    let d = &t[..10];
    let label = format!("{} {}, {}", d[8..10].trim_start_matches('0'), MONTHS[d[5..7].parse::<usize>().unwrap_or(1) - 1], &t[11..16]);
    let dur = if v.duration_ms > 0 { v.duration_ms } else { now - v.start };
    format!("{}{label}  \u{00B7}  {}", if current { "NOW  \u{00B7}  since " } else { "" }, duration(dur))
}

/// Java `takenText`: "Dealt 421,756  ·  taken 5,649 in 60 hits  ·  biggest 195 (Marble Colossus)".
fn taken_text(data: &lg_gamedata::GameData, v: &Visit) -> String {
    let dealt: i64 = v.dealt.iter().map(|d| i64::from(*d)).sum();
    let mut dealt_text = if dealt > 0 { format!("Dealt {}  \u{00B7}  ", num(dealt)) } else { String::new() };
    let n = v.hit_damage.len();
    if n == 0 {
        return dealt_text + "No damage taken.";
    }
    let total: i64 = v.hit_damage.iter().map(|d| i64::from(*d)).sum();
    let mut biggest = 0;
    for i in 0..n {
        if v.hit_damage[i] > v.hit_damage[biggest] {
            biggest = i;
        }
    }
    let enemy = v.hit_enemy.get(biggest).copied().unwrap_or(0);
    let who = if enemy != 0 { format!(" ({})", hit_source(data, enemy)) } else { String::new() };
    let saves = v.amulet_saves.len();
    if saves > 0 {
        dealt_text = format!(
            "{}  \u{00B7}  {dealt_text}",
            if saves == 1 { "Amulet saved you once".to_string() } else { format!("Amulet saved you {saves} times") }
        );
    }
    format!("{dealt_text}taken {} in {} hits  \u{00B7}  biggest {}{who}", num(total), num(n as i64), v.hit_damage[biggest])
}

/// Java `DungeonVisits.hitSource`.
fn hit_source(data: &lg_gamedata::GameData, enemy: i32) -> String {
    if enemy > 0 {
        return data.object_name(enemy).unwrap_or("").to_string();
    }
    if enemy < 0 {
        return match data.tiles.get(&-enemy).map(|t| t.id_name.trim()).filter(|n| !n.is_empty()) {
            Some(n) => format!("Ground: {n}"),
            None => "Damaging ground".into(),
        };
    }
    "Ground or area effect".into()
}

/// The column on the right (Java `showDetail`): the dungeon, its boss fights, the damage graph, your death, the
/// exaltation progress.
fn detail(ui: &mut Ui, c: &mut Ctx, v: &Visit, current: bool, side: Rect) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let now = c.now;
    let icons = &mut *c.icons;
    let mut col = ui.new_child(egui::UiBuilder::new().max_rect(side));
    egui::ScrollArea::vertical().id_salt("dungeon-detail").show(&mut col, |ui| {
        let top = ui.cursor().min;
        let w = side.width() - 14.0;
        let mut y = top.y;
        let f = text(18.0);
        let fh = jtext::height(ui.painter(), &f);
        // the dungeon: its portal, name, when, fame
        y += widgets::section_box(
            ui,
            &p,
            pos2(top.x, y),
            w,
            "Dungeon",
            fixed::BLUE,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let portal = data.dungeons.portal_by_name.get(&v.dungeon).copied().unwrap_or(-1);
                let mut lines: Vec<(String, egui::FontId, Color32)> =
                    vec![(v.dungeon.clone(), text(26.0), p.ink), (when_text(&app.zone, v, current, now), f.clone(), fixed::MUTED)];
                if v.late {
                    lines.push((
                        "Joined late: the sniffer started in the middle of it".into(),
                        f.clone(),
                        Color32::from_rgb(0xF2, 0xC2, 0x30),
                    ));
                }
                let gained = dungeon_visits::fame_gained(v, &app.characters);
                if gained > 0 {
                    lines.push((format!("+{} fame", num(gained)), text(18.0), p.gold));
                }
                if v.lantern_active_ms > 0 {
                    let a = v.lantern_active_ms as f64;
                    lines.push((
                        format!(
                            "Lantern: near it {:.0}% of {} ({})  \u{00B7}  close {:.0}%",
                            100.0 * v.lantern_range_ms as f64 / a,
                            duration(v.lantern_active_ms),
                            duration(v.lantern_range_ms),
                            100.0 * v.lantern_near_ms as f64 / a
                        ),
                        f.clone(),
                        fixed::MUTED,
                    ));
                }
                let th: f32 = lines.iter().map(|l| jtext::height(&painter, &l.1)).sum();
                let mut btn_h = 0.0;
                if !current {
                    let bw = jtext::width(&painter, "DELETE", &small()) + 32.0;
                    btn_h = 36.0;
                    crate::inventory::button(
                        ui,
                        &p,
                        Rect::from_min_size(pos2(r.right() - bw, r.top()), vec2(bw, btn_h)),
                        "Delete",
                        true,
                        "delete-visit",
                    );
                }
                let h = th.max(64.0).max(btn_h);
                if let Some(t) = icons.fitted(ui.ctx(), data, portal, 64, 64) {
                    painter.image(
                        t.id(),
                        Rect::from_min_size(pos2(r.left(), r.top() + ((h - 64.0) / 2.0).floor()), vec2(64.0, 64.0)),
                        uv(),
                        Color32::WHITE,
                    );
                }
                let mut ty = r.top() + ((h - th) / 2.0).floor();
                for (t, font, color) in &lines {
                    jtext::draw(&painter, pos2(r.left() + 64.0 + 14.0, ty), Align2::LEFT_TOP, t, font, *color);
                    ty += jtext::height(&painter, font);
                }
                h
            },
        );
        // the boss fights: your place and DPS on each
        let run = if current { app.dps_history.live.as_ref() } else { dungeon_visits::run_of(v, &app.dps_history) };
        y += widgets::section_box(
            ui,
            &p,
            pos2(top.x, y),
            w,
            "Boss Fights",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let Some(run) = run else {
                    jtext::draw(
                        &painter,
                        r.min,
                        Align2::LEFT_TOP,
                        if current { "No boss hit yet." } else { "No boss fought." },
                        &f,
                        fixed::MUTED,
                    );
                    return fh;
                };
                let mut ty = r.top();
                for b in &run.bosses {
                    let Some(place) = b.players.iter().position(|x| x.me) else { continue };
                    let me = &b.players[place];
                    let dps = f64::from(me.damage) / (b.fight_ms as f64 / 1000.0).max(1.0);
                    let t = format!(
                        "{}:  #{} of {}   \u{00B7}   {} DPS",
                        b.name.as_deref().unwrap_or(""),
                        place + 1,
                        b.players.len(),
                        num(dps.round() as i64)
                    );
                    jtext::draw(&painter, pos2(r.left(), ty), Align2::LEFT_TOP, &t, &f, p.ink);
                    ty += fh;
                }
                ty += 8.0;
                let bw = jtext::width(&painter, "SHOW BOSS DAMAGE", &small()) + 32.0;
                crate::inventory::button(
                    ui,
                    &p,
                    Rect::from_min_size(pos2(r.left(), ty), vec2(bw, 36.0)),
                    "Show boss damage",
                    false,
                    "boss-damage",
                );
                ty + 36.0 - r.top()
            },
        );
        // the damage: dealt above the line, taken below
        let fights = crate::damage_graph::fights(v, run);
        y += widgets::section_box(
            ui,
            &p,
            pos2(top.x, y),
            w,
            "Damage",
            fixed::RED,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                jtext::draw(&painter, r.min, Align2::LEFT_TOP, &taken_text(data, v), &f, fixed::MUTED);
                let mut ty = r.top() + fh;
                let any_dealt = v.dealt.iter().any(|d| *d > 0);
                if v.hit_damage.is_empty() && !any_dealt && !current {
                    return ty - r.top();
                }
                ty += 8.0;
                let n = crate::damage_graph::dangerous(v, crate::damage_graph::WINDOW_MS, now);
                let caption = format!("Per 2 s  \u{00B7}  {n} over 30% / 300").to_uppercase();
                jtext::draw(&painter, pos2(r.left(), ty), Align2::LEFT_TOP, &caption, &small(), p.ink_soft);
                ty += jtext::height(&painter, &small()) + 2.0;
                let gh = crate::damage_graph::height(&fights);
                let gr = Rect::from_min_size(pos2(r.left(), ty), vec2(r.width(), gh));
                let bar = crate::damage_graph::paint(&painter, ui.ctx(), icons, data, &p, v, &fights, now, gr);
                ty += gh;
                if let Some((at, len)) = bar {
                    // the scrollbar under it, at its latest end
                    ty += 2.0;
                    let track = Rect::from_min_size(pos2(r.left(), ty), vec2(r.width(), 18.0));
                    let tw = (len * (track.width() - 6.0)).max(20.0);
                    let thumb =
                        Rect::from_min_size(pos2(track.left() + 3.0 + at * (track.width() - 6.0), track.top() + 5.0), vec2(tw, 8.0));
                    painter.rect_filled(thumb, 4.0, p.line);
                    ty += 18.0;
                }
                ty - r.top()
            },
        );
        if v.died_at >= 0 {
            y += widgets::section_box(ui, &p, pos2(top.x, y), w, "Death", fixed::RED, |_, _| {}, |ui, r| death(ui, &p, icons, data, v, r));
        }
        y += widgets::section_box(
            ui,
            &p,
            pos2(top.x, y),
            w,
            "Exaltations",
            fixed::PURPLE,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                if v.exalts.is_empty() {
                    jtext::draw(
                        &painter,
                        r.min,
                        Align2::LEFT_TOP,
                        if current { "No exaltation progress yet." } else { "No exaltation progress in this dungeon." },
                        &f,
                        fixed::MUTED,
                    );
                    return fh;
                }
                let mut ty = r.top();
                let full = *lg_app::exalt_store::THRESHOLDS.last().expect("thresholds");
                for e in merged(v) {
                    let cy = ty + 18.0;
                    if let Some(t) = icons.object(ui.ctx(), data, e.class_type, 32) {
                        painter.image(t.id(), Rect::from_min_size(pos2(r.left(), cy - 16.0), vec2(32.0, 32.0)), uv(), Color32::WHITE);
                    }
                    let cls = data
                        .classes
                        .iter()
                        .find(|k| k.id == e.class_type)
                        .map_or_else(|| format!("Class {}", e.class_type), |k| k.name.clone());
                    jtext::draw(&painter, pos2(r.left() + 40.0, cy), Align2::LEFT_CENTER, &cls, &f, p.ink);
                    let x = r.left() + 150.0 + 10.0;
                    if let Some(t) = icons.object(ui.ctx(), data, STAT_POTION[EXALT_POTION[e.stat.clamp(0, 7) as usize]], 20) {
                        painter.image(t.id(), Rect::from_min_size(pos2(x, cy - 10.0), vec2(20.0, 20.0)), uv(), Color32::WHITE);
                    }
                    let earned = lg_app::exalt_store::level(e.after) - lg_app::exalt_store::level(e.before);
                    let t = if e.before >= full {
                        "Already exalted".to_string()
                    } else if e.after >= full {
                        "Exalted".to_string()
                    } else {
                        format!(
                            "{} {}/{} (+{}){}",
                            EXALT_STATS[e.stat.clamp(0, 7) as usize],
                            e.after,
                            full,
                            e.after - e.before,
                            if earned > 0 {
                                format!(
                                    "  \u{00B7}  exaltation {}/{}!",
                                    lg_app::exalt_store::level(e.after),
                                    lg_app::exalt_store::THRESHOLDS.len()
                                )
                            } else {
                                String::new()
                            }
                        )
                    };
                    jtext::draw(&painter, pos2(x + 28.0, cy), Align2::LEFT_CENTER, &t, &f, if e.after >= full { p.gold } else { p.ink });
                    ty += 36.0;
                }
                ty - r.top()
            },
        );
        y += widgets::section_box(
            ui,
            &p,
            pos2(top.x, y),
            w,
            "Guild",
            fixed::GREEN,
            |_, _| {},
            |ui, r| guild(ui, &p, icons, app, v, run, &fights, current, now, r),
        );
        ui.allocate_exact_size(vec2(w, y - top.y), Sense::hover());
    });
}

/// Java's derived bold.
fn bold(painter: &egui::Painter, at: egui::Pos2, align: Align2, t: &str, font: &egui::FontId, color: Color32) -> Rect {
    jtext::draw_bold(painter, at, align, t, font, color)
}

const DODGE_STOPS: [(u8, u8, u8); 4] = [(0xE0, 0x53, 0x3F), (0xE8, 0x89, 0x3A), (0xE6, 0xC8, 0x4A), (0x5D, 0xBB, 0x63)];

/// Shots Dodged 0..100 along red, orange, yellow, green.
fn dodge_color(pct: f64) -> Color32 {
    let t = (pct / 100.0).clamp(0.0, 1.0) * 3.0;
    let k = (t as usize).min(2);
    let f = t - k as f64;
    let (a, b) = (DODGE_STOPS[k], DODGE_STOPS[k + 1]);
    let m = |x: u8, y: u8| (f64::from(x) + (f64::from(y) - f64::from(x)) * f).round() as u8;
    Color32::from_rgb(m(a.0, b.0), m(a.1, b.1), m(a.2, b.2))
}

/// Java `guildSection`: you and the guildmates who use Loot Gobbler in this dungeon, four facts and a podium. No
/// server yet: you alone, under the note asking to log in.
fn guild(
    ui: &mut Ui,
    p: &Palette,
    icons: &mut crate::icons::Icons,
    app: &lg_app::App,
    v: &Visit,
    run: Option<&lg_app::dps_history::Run>,
    fights: &[crate::damage_graph::Fight],
    current: bool,
    now: i64,
    r: Rect,
) -> f32 {
    let painter = ui.painter().clone();
    let data = app.world.data();
    let f = text(18.0);
    let fh = jtext::height(&painter, &f);
    let big = text(21.0);
    let mut y = r.top();
    jtext::draw(
        &painter,
        pos2(r.left(), y),
        Align2::LEFT_TOP,
        "Log in to a Loot Gobbler server to compare with your guildmates.",
        &f,
        fixed::MUTED,
    );
    y += fh + 6.0;
    // you: as the run shows you, else your look saved with the visit
    let me =
        run.and_then(|run| run.bosses.iter().find_map(|b| b.players.iter().find(|x| x.me))).map(|m| (m.class_type, m.skin, m.tex1, m.tex2));
    let look = me.or_else(|| v.look.as_ref().filter(|l| l.class_type > 0).map(|l| (l.class_type, l.skin, l.tex1, l.tex2)));
    let name = app.characters.player_name.clone().unwrap_or_else(|| "You".into());
    // the time fighting bosses, overlapping fights joined
    let mut spans: Vec<(i64, i64)> = Vec::new();
    for fi in fights {
        match spans.last_mut() {
            Some(l) if fi.from <= l.1 => l.1 = l.1.max(fi.to),
            _ => spans.push((fi.from, fi.to)),
        }
    }
    let during = |t: i64| spans.iter().any(|s| t >= s.0 && t <= s.1);
    let all = spans.is_empty();
    let counted: i64 = spans.iter().map(|s| s.1 - s.0).sum();
    let scope = if all {
        "No boss fought: these count the whole visit.".to_string()
    } else {
        format!("Counted during the boss fights only ({}).", duration(counted))
    };
    jtext::draw(&painter, pos2(r.left(), y), Align2::LEFT_TOP, &scope, &f, fixed::MUTED);
    y += fh + 8.0;
    let dur = if current { now - v.start } else { v.duration_ms };
    let minutes = (if all { dur } else { counted }).max(1000) as f64 / 60_000.0;
    let mut hits: Vec<(i64, i64)> = (0..v.hit_time.len().min(v.hit_damage.len()))
        .map(|k| (i64::from(v.hit_time[k]), i64::from(v.hit_damage[k])))
        .filter(|h| all || during(h.0))
        .collect();
    hits.sort_by_key(|h| h.0);
    let thr = if v.max_hp > 0 { 300.min((f64::from(v.max_hp) * 0.30).round() as i64) } else { 300 };
    let mut slices: HashMap<i64, i64> = HashMap::new();
    for h in &hits {
        *slices.entry(h.0.div_euclid(2000)).or_default() += h.1;
    }
    let critical = slices.values().filter(|d| **d >= thr).count() as f64;
    let (mut hurt, mut sum, mut a) = (0i64, 0i64, 0usize);
    for b in 0..hits.len() {
        sum += hits[b].1;
        while hits[b].0 - hits[a].0 >= 1000 {
            sum -= hits[a].1;
            a += 1;
        }
        hurt = hurt.max(sum);
    }
    let hpm = hits.len() as f64 / minutes;
    let baseline = 50.0;
    let dodged = (100.0 - if hpm <= 0.0 { 0.0 } else { 100.0 * hpm / (hpm + baseline) }).round();
    let items: Vec<i32> = (0..v.quick_item.len())
        .filter(|k| all || v.quick_time.len() != v.quick_item.len() || during(i64::from(v.quick_time[*k])))
        .map(|k| v.quick_item[k])
        .collect();
    // the four facts side by side, wrapping when the column is narrow
    let look_icon = |icons: &mut crate::icons::Icons, size: u32| {
        look.and_then(|(class, skin, t1, t2)| icons.skin(ui.ctx(), data, if skin > 0 { skin } else { class }, t1, t2, size))
    };
    let row_h = 36.0f32.max(jtext::height(&painter, &big)) + 2.0;
    let mut facts: Vec<(&str, String, Color32)> = vec![
        ("Most Critical Hits", format!("{}", critical as i64), p.ink),
        ("Biggest Slap", format!("{} HP", num(hurt)), p.ink),
        ("Shots Dodged", format!("{dodged:.0}%"), dodge_color(dodged)),
    ];
    facts.push(("Quickslots Used", String::new(), p.ink));
    let (mut x, mut line_h) = (r.left(), 0.0f32);
    let title_h = jtext::height(&painter, &big);
    for (title, value, color) in &facts {
        // the quick slot items: each as "3x" and its icon, the most used first; "none" if none
        let mut counts: Vec<(i32, usize)> = Vec::new();
        for it in &items {
            match counts.iter_mut().find(|c| c.0 == *it) {
                Some(c) => c.1 += 1,
                None => counts.push((*it, 1)),
            }
        }
        counts.sort_by_key(|c| std::cmp::Reverse(c.1));
        let quick = title.starts_with("Quick");
        let vw = if quick {
            if counts.is_empty() {
                jtext::bold_width(&painter, "none", &big)
            } else {
                counts.iter().map(|c| jtext::bold_width(&painter, &format!("{}x", c.1), &f) + 2.0 + 24.0).sum::<f32>()
                    + 6.0 * (counts.len() as f32 - 1.0)
            }
        } else {
            jtext::bold_width(&painter, value, &big)
        };
        let fw = jtext::bold_width(&painter, title, &big).max(8.0 + 36.0 + 8.0 + vw + 8.0);
        let fh_all = title_h + 4.0 + row_h;
        if x > r.left() && x + fw > r.right() {
            x = r.left();
            y += line_h + 14.0;
            line_h = 0.0;
        }
        bold(&painter, pos2(x, y), Align2::LEFT_TOP, title, &big, fixed::GOLD);
        let ry = y + title_h + 4.0;
        let cy = ry + row_h / 2.0;
        match look_icon(icons, 36) {
            Some(t) => {
                painter.image(t.id(), Rect::from_min_size(pos2(x + 8.0, cy - 18.0), vec2(36.0, 36.0)), uv(), Color32::WHITE);
            }
            None => {
                painter.rect_filled(
                    Rect::from_min_size(pos2(x + 8.0, cy - 18.0), vec2(36.0, 36.0)),
                    0.0,
                    Color32::from_rgb(0xE5, 0xB2, 0x3A),
                );
            }
        }
        let vx = x + 8.0 + 36.0 + 8.0;
        if quick && !counts.is_empty() {
            let mut qx = vx;
            for (it, n) in &counts {
                let t = bold(&painter, pos2(qx, cy), Align2::LEFT_CENTER, &format!("{n}x"), &f, p.ink);
                qx = t.right() + 2.0;
                if let Some(tx) = icons.object(ui.ctx(), data, *it, 24) {
                    painter.image(tx.id(), Rect::from_min_size(pos2(qx, cy - 12.0), vec2(24.0, 24.0)), uv(), Color32::WHITE);
                }
                qx += 24.0 + 6.0;
            }
        } else {
            bold(&painter, pos2(vx, cy), Align2::LEFT_CENTER, if quick { "none" } else { value }, &big, *color);
        }
        line_h = line_h.max(fh_all);
        x += fw + 36.0;
    }
    y += line_h + 20.0;
    // the podium: you alone, on the gold block in the middle
    let (col, gap, name_h) = (96.0f32, 6.0f32, 18.0f32);
    let block = 64.0;
    let look_h = 56.0;
    let base = y + block + look_h + name_h + 4.0;
    let x0 = r.left() + col + gap;
    let top = base - block;
    let medal = Color32::from_rgb(0xE5, 0xB2, 0x3A);
    let darker = Color32::from_rgb((0xE5 as f32 * 0.7) as u8, (0xB2 as f32 * 0.7) as u8, (0x3A as f32 * 0.7) as u8);
    painter.rect_filled(Rect::from_min_size(pos2(x0, top), vec2(col, block)), 0.0, darker);
    painter.rect_filled(Rect::from_min_size(pos2(x0, top), vec2(col, 6.0)), 0.0, medal);
    bold(
        &painter,
        pos2(x0 + col / 2.0, top + 6.0 + ((block - 6.0) / 2.0).floor()),
        Align2::CENTER_CENTER,
        "1",
        &text(26.0),
        Color32::from_white_alpha(200),
    );
    match look_icon(icons, 56) {
        Some(t) => {
            painter.image(t.id(), Rect::from_min_size(pos2(x0 + (col - 56.0) / 2.0, top - 56.0), vec2(56.0, 56.0)), uv(), Color32::WHITE);
        }
        None => {
            painter.rect_filled(Rect::from_min_size(pos2(x0 + (col - 56.0) / 2.0, top - 56.0), vec2(56.0, 56.0)), 0.0, medal);
        }
    }
    let shown = widgets::fit(&painter, &name, &f, col - 4.0);
    bold(&painter, pos2(x0 + col / 2.0, top - 56.0 - name_h / 2.0), Align2::CENTER_CENTER, &shown, &f, Color32::WHITE);
    base - r.top()
}

/// Java `deathRecap`: what killed you, and every hit in the 10 s before, the killing one last and bold.
fn death(ui: &mut Ui, p: &Palette, icons: &mut crate::icons::Icons, data: &lg_gamedata::GameData, v: &Visit, r: Rect) -> f32 {
    let painter = ui.painter().clone();
    let f = text(18.0);
    let fh = jtext::height(&painter, &f);
    let killer = v.killed_by.as_deref().filter(|k| !k.trim().is_empty()).unwrap_or("?");
    let head = format!("Killed by {killer} after {}", duration(i64::from(v.died_at)));
    bold(&painter, r.min, Align2::LEFT_TOP, &head, &f, p.ink);
    let mut ty = r.top() + fh;
    let hits: Vec<(i32, i32, i32)> = (0..v.hit_time.len())
        .filter_map(|i| {
            let before = v.died_at - v.hit_time[i];
            (0..=10_000).contains(&before).then(|| (before, v.hit_damage[i], v.hit_enemy[i]))
        })
        .collect();
    let total: i64 = hits.iter().map(|h| i64::from(h.1)).sum();
    let sum = if hits.is_empty() {
        "No hits recorded in the last 10 s.".to_string()
    } else {
        format!(
            "{} hits, {} damage in the last 10 s{}",
            hits.len(),
            num(total),
            if v.max_hp > 0 { format!(" ({:.0}% of your life)", 100.0 * total as f64 / f64::from(v.max_hp)) } else { String::new() }
        )
    };
    jtext::draw(&painter, pos2(r.left(), ty), Align2::LEFT_TOP, &sum, &f, fixed::MUTED);
    ty += fh + 6.0;
    let names: Vec<String> = hits.iter().map(|h| hit_source(data, h.2)).collect();
    let when_w = hits.iter().map(|h| jtext::width(&painter, &format!("-{:.1} s", f64::from(h.0) / 1000.0), &f)).fold(0.0, f32::max);
    let who_w = names.iter().zip(&hits).map(|(n, h)| jtext::width(&painter, n, &f) + if h.2 != 0 { 28.0 } else { 0.0 }).fold(0.0, f32::max);
    for (k, h) in hits.iter().enumerate() {
        let last = k == hits.len() - 1;
        let rh = fh.max(24.0) + 2.0;
        let cy = ty + rh / 2.0;
        jtext::draw(&painter, pos2(r.left(), cy), Align2::LEFT_CENTER, &format!("-{:.1} s", f64::from(h.0) / 1000.0), &f, fixed::MUTED);
        let mut x = r.left() + when_w + 12.0;
        if h.2 > 0 {
            if let Some(t) = icons.object(ui.ctx(), data, h.2, 24) {
                painter.image(t.id(), Rect::from_min_size(pos2(x, cy - 12.0), vec2(24.0, 24.0)), uv(), Color32::WHITE);
            }
            x += 28.0;
        } else if h.2 < 0 {
            x += 28.0;
        }
        let dmg = format!(
            "{}{}",
            num(i64::from(h.1)),
            if v.max_hp > 0 { format!("  ({:.0}%)", 100.0 * f64::from(h.1) / f64::from(v.max_hp)) } else { String::new() }
        );
        let dx = r.left() + when_w + 12.0 + who_w + 12.0;
        let draw = if last { jtext::draw_bold } else { jtext::draw };
        draw(&painter, pos2(x, cy), Align2::LEFT_CENTER, &names[k], &f, p.ink);
        draw(&painter, pos2(dx, cy), Align2::LEFT_CENTER, &dmg, &f, p.ink);
        ty += rh;
    }
    ty - r.top()
}
