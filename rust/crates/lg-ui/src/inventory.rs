//! The Inventory tab (Java: `CollectionPanel`, `CollectionGrid`): pages Items, ST Sets, Log; on Items every
//! collectible item in a grid (yours bright with how many copies and the best rarity, the rest faded), the progress,
//! actions, and what you found lately. A column on the left picks whose finds are shown: all, or a game mode run's.

use crate::detail::{Rarity, enchant_badge, uv};
use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use std::collections::HashMap;

const CELL: f32 = 34.0;
const ICON: f32 = 28.0;
const PAD: f32 = 6.0;
const BG: Color32 = Color32::from_rgb(5, 5, 5);
const COUNT_GOLD: Color32 = Color32::from_rgb(0xF5, 0xC5, 0x42);
const FILTERS: [&str; 4] = ["All", "Shiny", "Normal", "Reusable"];
const WHITE_BAG: i32 = 1292;
const RECENT: usize = 8;
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

#[derive(Default)]
pub struct InventoryView {
    pub page: usize,
    pub filter: usize,
    pub search: String,
    /// The game mode character whose finds are shown (0: everything).
    pub upe_char: i32,
}

/// A button as FlatLaf draws it: header colour, black frame, the text in the button font (danger: red).
pub fn button(ui: &Ui, p: &Palette, r: Rect, label: &str, danger: bool, id: &str) -> bool {
    let painter = ui.painter();
    let resp = ui.interact(r, ui.scope_id().with(("button", id)), Sense::click());
    let (bg, fg, frame) =
        if danger { (p.paper, p.red, p.red) } else { (if resp.hovered() { p.sand } else { p.header }, p.ink, Color32::BLACK) };
    painter.rect(r, 0.0, bg, Stroke::new(1.5, frame), StrokeKind::Inside);
    jtext::draw(painter, r.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &widgets::small(), fg);
    resp.clicked()
}

impl InventoryView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &Rarity) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let all = ui.max_rect().shrink(8.0);
        let painter = ui.painter().clone();
        // the white bag (opens the column of whose finds), then the pages
        if let Some(t) = c.icons.object(ui.ctx(), data, WHITE_BAG, 34) {
            painter.image(t.id(), Rect::from_min_size(pos2(all.left() + 8.0, all.top() + 1.0), vec2(34.0, 34.0)), uv(), Color32::WHITE);
        }
        if let Some(i) = widgets::segmented(
            ui,
            &p,
            pos2(all.left() + 8.0 + 34.0 + 8.0, all.top()),
            36.0,
            &["Items", "ST Sets", "Log"],
            self.page,
            false,
            &[],
        ) {
            self.page = i;
        }
        let mut body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        let column = c.props.get("collectionUpeColumn") == Some("true");
        if column {
            let w = self.upe_column(ui, c, body);
            body = Rect::from_min_max(pos2(body.left() + w, body.top()), body.max);
        }
        if self.page != 0 {
            jtext::draw(&painter, body.min + vec2(8.0, 8.0), Align2::LEFT_TOP, "Coming soon.", &text(18.0), fixed::MUTED);
            return;
        }
        self.items(ui, c, rarity, body);
    }

    /// The column picking whose finds are shown: all, then per game mode its characters (Java `renderUpeColumn`).
    fn upe_column(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) -> f32 {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let painter = ui.painter().clone();
        let w = 4.0 + 96.0 + 4.0 + 6.0;
        let x = body.left() + 4.0;
        let mut y = body.top() + 4.0;
        let f = small();
        let mut entry = |y: &mut f32, tex: Option<egui::TextureHandle>, name: &str, id: i32, faded: bool| {
            let shown = self.upe_char == id;
            let lh = jtext::height(&painter, &f);
            let h = 4.0 + 40.0 + lh + 4.0 + 4.0;
            let r = Rect::from_min_size(pos2(x + (w - 8.0 - 96.0) / 2.0, *y + 3.0), vec2(96.0, h));
            if shown {
                painter.rect_filled(r, 0.0, mix(p.gold, p.paper, 0.25));
                painter.rect_stroke(r, 0.0, Stroke::new(2.0, p.gold), StrokeKind::Inside);
            }
            if let Some(t) = tex {
                let tint = if faded { Color32::from_white_alpha(110) } else { Color32::WHITE };
                painter.image(t.id(), Rect::from_min_size(pos2(r.center().x - 20.0, r.top() + 6.0), vec2(40.0, 40.0)), uv(), tint);
            }
            jtext::draw(
                &painter,
                pos2(r.center().x, r.top() + 6.0 + 40.0),
                Align2::CENTER_TOP,
                name,
                &f,
                if faded { fixed::MUTED } else { p.ink_soft },
            );
            if ui.interact(r, ui.scope_id().with(("upe-entry", id)), Sense::click()).clicked() {
                self.upe_char = id;
            }
            *y += h + 6.0;
        };
        let bag = c.icons.object(ui.ctx(), data, WHITE_BAG, 40);
        entry(&mut y, bag, "All", 0, false);
        let mut runs: Vec<&lg_app::upe::Run> = app.upe.runs.values().filter(|r| r.marked).collect();
        runs.sort_by_key(|r| r.char_id);
        for mode in lg_app::upe::MODES {
            let in_mode: Vec<_> = runs.iter().filter(|r| r.mode == mode && app.characters.get(r.char_id).is_some()).collect();
            if in_mode.is_empty() {
                continue;
            }
            y += 10.0;
            let t = jtext::draw(&painter, pos2(x + 6.0, y), Align2::LEFT_TOP, &lg_app::upe::label(mode), &f, p.gold);
            y = t.bottom() + 2.0;
            painter.rect_filled(Rect::from_min_size(pos2(x + 6.0, y), vec2(w - 8.0 - 12.0, 2.0)), 0.0, p.gold);
            y += 2.0 + 4.0;
            for r in in_mode {
                let ch = app.characters.get(r.char_id).expect("checked");
                let skin = if ch.skin > 0 { ch.skin } else { ch.class_type };
                let tex = c.icons.skin(ui.ctx(), data, skin, ch.tex1, ch.tex2, 40);
                entry(&mut y, tex, &ch.class_name, r.char_id, r.broken.is_some() || !ch.alive);
            }
        }
        if runs.is_empty() {
            jtext::draw(&painter, pos2(x + w / 2.0, y + 8.0), Align2::CENTER_TOP, "No game mode runs yet", &f, fixed::MUTED);
        }
        w
    }

    /// The Items page: the grid beside progress, actions and recent finds.
    fn items(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &Rarity, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let living = lg_app::loadout::living(app);
        // copies you hold and the best rarity per item: Holdings once it knows, else the collection's
        let time = app.zone.local_date_time(app.world.now());
        let hctx = lg_app::holdings::Ctx { data, now: app.world.now(), time: &time };
        let (counts, best) =
            if app.holdings.known() { app.holdings.counts_and_best(&hctx, &living) } else { (HashMap::new(), HashMap::new()) };
        let known = app.holdings.known();
        let upe_items: HashMap<i32, (i32, i32, String, String)> = gathered(app, self.upe_char);
        let held = |id: i32| -> i32 {
            if self.upe_char != 0 {
                return upe_items.get(&id).map_or(0, |e| e.0);
            }
            if known { counts.get(&id).copied().unwrap_or(0) } else { app.collection.entry(id).map_or(0, |e| e.held()) }
        };
        let best_of = |id: i32| -> i32 {
            if self.upe_char != 0 {
                return upe_items.get(&id).map_or(0, |e| e.1);
            }
            match best.get(&id) {
                Some(b) if known && *b >= 0 => *b,
                _ => app.collection.entry(id).map_or(0, |e| e.max_enchants()),
            }
        };
        // what you hold of the collectible items (Java `collected`), in the collection's order
        let owned: Vec<i32> = if self.upe_char != 0 {
            upe_items.keys().copied().collect()
        } else {
            app.collection
                .all()
                .map(|(id, _)| id)
                .filter(|id| data.items.item(*id).is_some_and(|_| data.items.collectible.contains(id)) && held(*id) > 0)
                .collect()
        };
        let owned_set: std::collections::HashSet<i32> = owned.iter().copied().collect();
        let universe: Vec<i32> = data
            .items
            .collectible
            .iter()
            .copied()
            .filter(|id| {
                let it = data.items.item(*id).expect("collectible items are known");
                match self.filter {
                    3 => it.quickslot_only,
                    1 => it.is_shiny(),
                    2 => !it.is_shiny(),
                    _ => true,
                }
            })
            .collect();
        let q = self.search.trim().to_lowercase();
        let grid: Vec<i32> = universe
            .into_iter()
            .filter(|id| q.is_empty() || data.items.item(*id).is_some_and(|i| i.name.to_lowercase().contains(&q)))
            .collect();

        // the split: the side 380 px, the grid snapped down to whole columns
        let overhead = 2.0 + 12.0;
        let main_w = body.width() - 380.0 - 8.0;
        let cols = ((main_w - overhead - 2.0 * PAD) / CELL).floor().max(1.0);
        let main = Rect::from_min_size(body.min, vec2(overhead + 2.0 * PAD + cols * CELL, body.height()));
        let side = Rect::from_min_max(pos2(main.right() + 8.0, body.top()), body.max);
        let title = if self.upe_char != 0 {
            let name = app.characters.get(self.upe_char).map_or("UPE".to_string(), |ch| format!("{} (UPE)", ch.class_name));
            format!("Collection · {name}")
        } else {
            "Collection".to_string()
        };
        let mut filter = self.filter;
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            main,
            &title,
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter().clone();
                let h = head.height();
                let fw = widgets::segmented_width(&painter, &FILTERS, true);
                let fx = head.right() - fw;
                if let Some(i) = widgets::segmented(ui, &p, pos2(fx, head.top()), h, &FILTERS, filter, true, &[]) {
                    filter = i;
                }
                // the search box (typing comes with the window)
                let sr = Rect::from_min_size(pos2(fx - 8.0 - 220.0, head.top() + 3.0), vec2(220.0, h - 6.0));
                painter.rect(sr, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
                jtext::draw(
                    &painter,
                    pos2(sr.left() + 8.0, sr.center().y),
                    Align2::LEFT_CENTER,
                    "Search items or enchants",
                    &text(18.0),
                    p.ink_muted,
                );
            },
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("collection-grid").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let gw = r.width() - 12.0;
                    let rows = (grid.len() as f32 / cols).ceil();
                    let gh = rows * CELL + 2.0 * PAD;
                    let painter = ui.painter().clone();
                    painter.rect_filled(Rect::from_min_size(top, vec2(gw, gh.max(r.height()))), 0.0, BG);
                    let count_f = text(16.0);
                    for (i, id) in grid.iter().enumerate() {
                        let (col, row) = (i as f32 % cols, (i as f32 / cols).floor());
                        let (x, y) = (top.x + PAD + col * CELL, top.y + PAD + row * CELL);
                        let have = owned_set.contains(id);
                        if let Some(t) = icons.object(ui.ctx(), data, *id, ICON as u32) {
                            let tint = if have { Color32::WHITE } else { Color32::from_white_alpha(56) };
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(x + (CELL - ICON) / 2.0, y + (CELL - ICON) / 2.0), vec2(ICON, ICON)),
                                uv(),
                                tint,
                            );
                        }
                        if have {
                            enchant_badge(&painter, rarity, pos2(x + CELL - 3.0, y + CELL - 3.0), best_of(*id).max(0) as usize, 5.0);
                            let n = held(*id);
                            if n > 0 {
                                let t = n.to_string();
                                let asc = (16.0f32 * 1050.0 / 1350.0).ceil();
                                let (tx, ty) = (x + 2.0, y + CELL - 3.0 - asc);
                                for dx in -1..=1 {
                                    for dy in -1..=1 {
                                        if dx != 0 || dy != 0 {
                                            jtext::draw(
                                                &painter,
                                                pos2(tx + dx as f32, ty + dy as f32),
                                                Align2::LEFT_TOP,
                                                &t,
                                                &count_f,
                                                Color32::BLACK,
                                            );
                                        }
                                    }
                                }
                                jtext::draw(&painter, pos2(tx, ty), Align2::LEFT_TOP, &t, &count_f, COUNT_GOLD);
                            }
                        }
                    }
                    ui.allocate_exact_size(vec2(gw, gh), Sense::hover());
                });
            },
        );
        self.filter = filter;

        // the side: progress and actions on top, recent finds below
        let painter = ui.painter().clone();
        let lh = jtext::height(&painter, &text(18.0));
        let head = widgets::section_head_height(&painter);
        let have = grid.iter().filter(|id| owned_set.contains(id)).count();
        let pct = if grid.is_empty() { 0.0 } else { 100.0 * have as f64 / grid.len() as f64 };
        let prog = Rect::from_min_size(
            side.min,
            vec2(side.width(), head + 2.0 + 20.0 + jtext::height(&painter, &small()) + 4.0 + 12.0 + 4.0 + lh),
        );
        widgets::section(
            ui,
            &p,
            prog,
            "Progress",
            fixed::GREEN,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter();
                let (x, w) = (r.left() + 10.0, r.width() - 20.0);
                let t = jtext::draw(painter, pos2(x, r.top() + 10.0), Align2::LEFT_TOP, "COLLECTED", &small(), p.ink_muted);
                let by = t.bottom() + 4.0;
                painter.rect_filled(Rect::from_min_size(pos2(x, by), vec2(w, 12.0)), 0.0, p.line);
                painter.rect_filled(Rect::from_min_size(pos2(x, by), vec2((w * pct as f32 / 100.0).round(), 12.0)), 0.0, p.gold);
                jtext::draw(
                    painter,
                    pos2(x, by + 12.0 + 4.0),
                    Align2::LEFT_TOP,
                    &format!("{have} / {}   {pct:.1}%", grid.len()),
                    &text(18.0),
                    p.ink,
                );
            },
        );
        let bh = 36.0;
        let acts =
            Rect::from_min_size(pos2(side.left(), prog.bottom() + 8.0), vec2(side.width(), head + 2.0 + 20.0 + 3.0 * bh + 2.0 * 6.0));
        let upe_shown = self.upe_char != 0;
        widgets::section(
            ui,
            &p,
            acts,
            "Actions",
            fixed::BLUE,
            |_, _| {},
            |ui, r| {
                let (x, w) = (r.left() + 10.0, r.width() - 20.0);
                let mut y = r.top() + 10.0;
                for (i, (label, danger)) in
                    [("Copy as text", false), ("Copy as image", false), ("Clear collection", true)].iter().enumerate()
                {
                    if *danger && upe_shown {
                        continue;
                    }
                    button(ui, &p, Rect::from_min_size(pos2(x, y), vec2(w, bh)), label, *danger, &format!("inv-{i}"));
                    y += bh + 6.0;
                }
            },
        );
        let recent = Rect::from_min_max(pos2(side.left(), acts.bottom() + 8.0), side.max);
        let icons = &mut *c.icons;
        let mut by_last: Vec<(i32, String, String, i32)> = if self.upe_char != 0 {
            upe_items.iter().map(|(id, e)| (*id, e.3.clone(), e.2.clone(), e.1)).collect()
        } else {
            owned
                .iter()
                .filter_map(|id| {
                    app.collection
                        .entry(*id)
                        .map(|e| (*id, e.last.clone().unwrap_or_else(|| "null".into()), e.last_date().to_string(), newest_enchants(e)))
                })
                .collect()
        };
        by_last.sort_by(|a, b| b.1.cmp(&a.1));
        let today = app.zone.local_date_time(app.world.now().max(c.now));
        widgets::section(
            ui,
            &p,
            recent,
            "Recent",
            fixed::PURPLE,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let (x, w) = (r.left() + 10.0, r.width() - 20.0);
                let mut y = r.top() + 8.0;
                if by_last.is_empty() {
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, "Nothing collected yet.", &text(18.0), p.ink);
                }
                let mut day: Option<String> = None;
                for (i, (id, _, date, ench)) in by_last.iter().take(RECENT).enumerate() {
                    if day.as_deref() != Some(date.as_str()) {
                        day = Some(date.clone());
                        if i > 0 {
                            y += 4.0;
                            painter.rect_filled(Rect::from_min_size(pos2(x, y), vec2(w, 1.0)), 0.0, p.line);
                            y += 2.0;
                        }
                        y += 4.0;
                        let t = jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, &day_label(date, &today), &small(), fixed::MUTED);
                        y = t.bottom() + 4.0;
                    }
                    let rh = 36.0;
                    if let Some(t) = icons.object(ui.ctx(), data, *id, 32) {
                        let ir = Rect::from_min_size(pos2(x, y + (rh - 32.0) / 2.0), vec2(32.0, 32.0));
                        painter.image(t.id(), ir, uv(), Color32::WHITE);
                        enchant_badge(&painter, rarity, ir.right_bottom() - vec2(1.0, 1.0), (*ench).max(0) as usize, 5.0);
                    }
                    let name = data.items.item(*id).map_or(format!("Item {id}"), |it| it.name.clone());
                    jtext::draw(
                        &painter,
                        pos2(x + 32.0 + 10.0, y + rh / 2.0),
                        Align2::LEFT_CENTER,
                        &widgets::fit(&painter, &name, &text(18.0), w - 42.0),
                        &text(18.0),
                        p.ink,
                    );
                    y += rh + 4.0;
                }
            },
        );
    }
}

/// The newest copy's enchantment count (Java `recentRow`).
fn newest_enchants(e: &lg_app::collection::Entry) -> i32 {
    for f in e.history.iter().flatten().rev() {
        if f.is_removal() {
            continue;
        }
        return f.enchant_ids.as_ref().map_or(f.enchants.max(0), |v| v.len() as i32);
    }
    0
}

/// "Today", "Yesterday" or "16 Nov" (Java `dayLine`), upper case.
fn day_label(date: &str, now_local: &str) -> String {
    if date.len() < 10 {
        return date.to_uppercase();
    }
    let today = &now_local[..10.min(now_local.len())];
    let days = |d: &str| -> Option<i64> {
        let (y, m, dd) = (d[0..4].parse::<i64>().ok()?, d[5..7].parse::<i64>().ok()?, d[8..10].parse::<i64>().ok()?);
        Some(y * 372 + m * 31 + dd)
    };
    let text = if date == today {
        "Today".to_string()
    } else if days(today).zip(days(date)).is_some_and(|(t, d)| t - d == 1) {
        "Yesterday".to_string()
    } else {
        let m: usize = date[5..7].parse().unwrap_or(1);
        format!("{} {}", date[8..10].trim_start_matches('0'), MONTHS[m.saturating_sub(1).min(11)])
    };
    text.to_uppercase()
}

/// A game mode run's finds as collection entries (Java `gathered`): per item its copies, the most enchantments,
/// the last find's time and date.
fn gathered(app: &lg_app::App, char_id: i32) -> HashMap<i32, (i32, i32, String, String)> {
    let mut out: HashMap<i32, (i32, i32, String, String)> = HashMap::new();
    let Some(run) = app.upe.runs.get(&char_id) else { return out };
    let data = app.world.data();
    for g in &run.gathered {
        if !data.items.collectible.contains(&g.item) {
            continue;
        }
        let time = app.zone.local_date_time(g.at);
        let e = out.entry(g.item).or_insert((0, 0, String::new(), String::new()));
        e.0 += 1;
        e.1 = e.1.max(g.enchants.as_ref().map_or(0, |v| v.len() as i32));
        e.2 = time[..10.min(time.len())].to_string();
        e.3 = time;
    }
    out
}
