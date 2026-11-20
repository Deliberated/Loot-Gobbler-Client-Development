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
        if self.page != 0 {
            jtext::draw(ui.painter(), body.min + vec2(8.0, 8.0), Align2::LEFT_TOP, "Coming soon.", &text(18.0), fixed::MUTED);
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
        let tw = jtext::width(painter, &t, &bf);
        let color = Color32::from_rgb(0xF2, 0xEC, 0xDC);
        jtext::draw(painter, pos2(rx - tw + 1.0, base - asc), Align2::LEFT_TOP, &t, &bf, color);
        jtext::draw(painter, pos2(rx - tw, base - asc), Align2::LEFT_TOP, &t, &bf, color);
        rx -= tw;
    }
    let not_done = !current && dungeon_visits::completed(v, &app.dps_history) == Some(false);
    if not_done {
        rx -= 10.0;
        let red = Color32::from_rgb(0xD8, 0x5A, 0x4A);
        let ff = text(13.0);
        let tw = jtext::width(painter, "NOT COMPLETED", &ff) + 12.0 + 2.0;
        let h = jtext::height(painter, &ff) + 4.0;
        let br = Rect::from_min_size(pos2(rx - tw, name.center().y - h / 2.0), vec2(tw, h));
        painter.rect(br, 0.0, Color32::from_black_alpha(90), Stroke::new(1.0, red), StrokeKind::Inside);
        jtext::draw(painter, pos2(br.center().x + 0.5, br.center().y), Align2::CENTER_CENTER, "NOT COMPLETED", &ff, red);
        jtext::draw(painter, br.center(), Align2::CENTER_CENTER, "NOT COMPLETED", &ff, red);
    }
    ui.interact(r, ui.scope_id().with(("visit", v.start)), Sense::click()).clicked()
}
