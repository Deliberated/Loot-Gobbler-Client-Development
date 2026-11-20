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
    pub st_filter: usize,
    pub st_class: Option<String>,
}

/// What you hold and how much, as the Items page counts it (Java `collected`, `held`, `bestEnchants`).
struct Owned {
    /// The collectible items you hold, in the collection's order.
    items: Vec<i32>,
    set: std::collections::HashSet<i32>,
    best: HashMap<i32, i32>,
}

fn owned(app: &lg_app::App, upe_char: i32) -> Owned {
    let data = app.world.data();
    if upe_char != 0 {
        let g = gathered(app, upe_char);
        let items: Vec<i32> = g.keys().copied().collect();
        return Owned { set: items.iter().copied().collect(), items, best: g.iter().map(|(k, v)| (*k, v.1)).collect() };
    }
    let living = lg_app::loadout::living(app);
    let time = app.zone.local_date_time(app.world.now());
    let hctx = lg_app::holdings::Ctx { data, now: app.world.now(), time: &time };
    let known = app.holdings.known();
    let (counts, best) = if known { app.holdings.counts_and_best(&hctx, &living) } else { (HashMap::new(), HashMap::new()) };
    let mut best_of = HashMap::new();
    let mut items = Vec::new();
    for (id, e) in app.collection.all() {
        let n = if known { counts.get(&id).copied().unwrap_or(0) } else { e.held() };
        if n == 0 || !data.items.collectible.contains(&id) {
            continue;
        }
        items.push(id);
        best_of.insert(
            id,
            match best.get(&id) {
                Some(b) if known && *b >= 0 => *b,
                _ => e.max_enchants(),
            },
        );
    }
    Owned { set: items.iter().copied().collect(), items, best: best_of }
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
        match self.page {
            1 => self.st_sets(ui, c, rarity, body),
            2 => self.log(ui, c, body),
            _ => self.items(ui, c, rarity, body),
        }
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
        // Java `matching`: name, type (slot), UT / ST / T14, "reusable" / "quick slot", or an enchantment found on it
        let has_enchant = |id: i32| {
            if self.upe_char != 0 && !owned_set.contains(&id) {
                return false;
            }
            app.collection.entry(id).filter(|_| owned_set.contains(&id)).is_some_and(|e| {
                e.history
                    .iter()
                    .flatten()
                    .filter_map(|f| f.enchant_ids.as_ref())
                    .flatten()
                    .any(|eid| *eid >= 0 && data.enchant_looks.get(eid).is_some_and(|l| l.name.to_lowercase().contains(&q)))
            })
        };
        let grid: Vec<i32> = universe
            .into_iter()
            .filter(|id| {
                q.is_empty()
                    || data.items.item(*id).is_some_and(|i| {
                        i.name.to_lowercase().contains(&q)
                            || i.slot_name().to_lowercase().contains(&q)
                            || i.tier_tag().to_lowercase().replace(['(', ')'], "") == q
                            || (!i.tier.is_empty() && q == format!("t{}", i.tier))
                            || (i.quickslot_only
                                && ("reusable".starts_with(&q) || "quick slot".starts_with(&q) || "quickslot".starts_with(&q)))
                    })
                    || has_enchant(*id)
            })
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
        let mut search = self.search.clone();
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
                // the search box
                let sr = Rect::from_min_size(pos2(fx - 8.0 - 220.0, head.top() + 3.0), vec2(220.0, h - 6.0));
                crate::widgets::search_field(ui, &p, sr, &mut search, "Search items or enchants", "collection-search");
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
        self.search = search;

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
        let mut clicked: Option<usize> = None;
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
                    if button(ui, &p, Rect::from_min_size(pos2(x, y), vec2(w, bh)), label, *danger, &format!("inv-{i}")) {
                        clicked = Some(i);
                    }
                    y += bh + 6.0;
                }
            },
        );
        match clicked {
            // Java `copyText`: "- Name (Shiny) xN" per item held, sorted, under a head line
            Some(0) => {
                let mut lines: Vec<String> = owned
                    .iter()
                    .map(|id| {
                        let item = data.items.item(*id);
                        let mut line = item
                            .filter(|i| !i.name.is_empty())
                            .map_or_else(|| data.object_name(*id).map_or(format!("Item #{id}"), str::to_string), |i| i.name.clone());
                        if item.is_some_and(|i| i.is_shiny()) && !line.contains("Shiny") {
                            line += " (Shiny)";
                        }
                        let n = held(*id);
                        if n > 1 {
                            line += &format!(" x{n}");
                        }
                        line
                    })
                    .collect();
                lines.sort_by_key(|l| l.to_lowercase());
                let mut t = format!("ROTMG Collection \u{2014} {} items\n", owned.len());
                for l in lines {
                    t += &format!("- {l}\n");
                }
                ui.ctx().copy_text(t);
                c.asks.push(crate::window::Ask::Confirm {
                    title: "Copy as text".into(),
                    text: "Collection copied to the clipboard as text.".into(),
                    yes: "OK".into(),
                    no: String::new(),
                    then: Box::new(crate::window::Ask::Nothing),
                });
            }
            Some(2) => c.asks.push(crate::window::Ask::Confirm {
                title: "Clear collection".into(),
                text: "Delete your whole collection history? This cannot be undone.".into(),
                yes: "Yes".into(),
                no: "No".into(),
                then: Box::new(crate::window::Ask::ClearCollection),
            }),
            _ => {}
        }
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

impl InventoryView {
    /// The Log page: every item you hold, newest find first: when first found, what, how many (Java `logView`).
    fn log(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let o = owned(app, self.upe_char);
        let mut rows: Vec<(i32, String, String, i32)> = o
            .items
            .iter()
            .filter_map(|id| {
                app.collection.entry(*id).map(|e| {
                    (
                        *id,
                        e.last.clone().unwrap_or_else(|| "null".into()),
                        e.first.as_deref().map_or(String::new(), |f| f[..f.len().min(10)].to_string()),
                        e.count,
                    )
                })
            })
            .collect();
        rows.sort_by(|a, b| b.1.cmp(&a.1));
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            body,
            "Collection · Log",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let f = text(18.0);
                // the table header, then the rows (36 px, every second one in the header colour)
                let hh = jtext::height(&painter, &small()) + 8.0;
                let head = Rect::from_min_size(r.min, vec2(r.width(), hh));
                painter.rect_filled(head, 0.0, p.header);
                painter.rect_filled(Rect::from_min_size(pos2(head.left(), head.bottom() - 1.0), vec2(head.width(), 1.0)), 0.0, p.line);
                let w = r.width() - 12.0;
                let cols = [(0.0, 120.0, "Date"), (120.0, 44.0, ""), (164.0, w - 164.0 - 80.0, "Item"), (w - 80.0, 80.0, "Count")];
                for (x, cw, t) in cols {
                    jtext::draw(&painter, pos2(r.left() + x + 10.0, head.center().y), Align2::LEFT_CENTER, t, &small(), p.ink_soft);
                    if x > 0.0 {
                        painter.rect_filled(Rect::from_min_size(pos2(r.left() + x, head.top() + 4.0), vec2(1.0, hh - 8.0)), 0.0, p.line);
                    }
                    let _ = cw;
                }
                let rr = Rect::from_min_max(pos2(r.left(), head.bottom()), r.max);
                let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rr));
                child.set_clip_rect(rr.intersect(ui.clip_rect()));
                egui::ScrollArea::vertical().id_salt("collection-log").show(&mut child, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    for (i, (id, _, first, count)) in rows.iter().enumerate() {
                        let y = top.y + i as f32 * 36.0;
                        let row = Rect::from_min_size(pos2(top.x, y), vec2(w, 36.0));
                        painter.rect_filled(row, 0.0, if i % 2 == 1 { p.header } else { p.paper });
                        jtext::draw(&painter, pos2(row.left() + 10.0, row.center().y), Align2::LEFT_CENTER, first, &f, p.ink);
                        if let Some(t) = icons.object(ui.ctx(), data, *id, ICON as u32) {
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(row.left() + 120.0 + 10.0, row.center().y - ICON / 2.0), vec2(ICON, ICON)),
                                uv(),
                                Color32::WHITE,
                            );
                        }
                        let name = data.items.item(*id).map_or(format!("Item {id}"), |it| it.name.clone());
                        jtext::draw(&painter, pos2(row.left() + 164.0 + 10.0, row.center().y), Align2::LEFT_CENTER, &name, &f, p.ink);
                        jtext::draw(
                            &painter,
                            pos2(row.right() - 10.0, row.center().y),
                            Align2::RIGHT_CENTER,
                            &format!("x{count}"),
                            &f,
                            fixed::GOLD,
                        );
                    }
                    // the table fills the view (Java `setFillsViewportHeight`)
                    let h = (rows.len() as f32 * 36.0).max(rr.height());
                    painter.rect_filled(
                        Rect::from_min_max(pos2(top.x, top.y + rows.len() as f32 * 36.0), pos2(top.x + w, top.y + h)),
                        0.0,
                        p.paper,
                    );
                    ui.allocate_exact_size(vec2(w, rows.len() as f32 * 36.0), Sense::hover());
                });
            },
        );
    }

    /// The ST Sets page (Java `StSetsView`): a card per set with its four pieces, beside the progress, the sets one
    /// piece away and the sets per class.
    fn st_sets(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &Rarity, body: Rect) {
        const CARD_MIN_WIDTH: f32 = 330.0;
        const GAP: f32 = 8.0;
        const TILE: f32 = 44.0;
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let o = owned(app, self.upe_char);
        let has = |id: i32| o.set.contains(&id);
        let sets = &data.st_sets;
        let n_of = |s: &lg_gamedata::st_sets::StSet| s.owned(&has);
        let token_of =
            |s: &lg_gamedata::st_sets::StSet| s.pieces.iter().find_map(|id| data.set_tokens.get(id).map(|t| t.token)).unwrap_or(0);
        let mut shown: Vec<&lg_gamedata::st_sets::StSet> = sets
            .iter()
            .filter(|s| {
                let n = n_of(s);
                let ok = match self.st_filter {
                    1 => n == 4,
                    2 => n < 4,
                    3 => token_of(s) != 0,
                    _ => true,
                };
                ok && self.st_class.as_ref().is_none_or(|k| *k == s.class_name)
            })
            .collect();
        shown.sort_by(|a, b| n_of(b).cmp(&n_of(a)).then(a.title.cmp(&b.title)));
        // the split: the side 280 px, the cards snapped down to whole columns
        let overhead = 2.0 + 12.0;
        let main_w = body.width() - 280.0 - 8.0;
        let cols = (((main_w - overhead - 20.0 + GAP) / (CARD_MIN_WIDTH + GAP)).floor()).max(1.0);
        let main = Rect::from_min_size(body.min, vec2(overhead + cols * (CARD_MIN_WIDTH + GAP) - GAP + 20.0, body.height()));
        let side = Rect::from_min_max(pos2(main.right() + 8.0, body.top()), body.max);
        let filters = ["All", "Complete", "In progress", "Tokens"];
        let mut filter = self.st_filter;
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            main,
            "Collection · ST Sets",
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter().clone();
                let h = head.height();
                let fw = widgets::segmented_width(&painter, &filters, true);
                let fx = head.right() - fw;
                if let Some(i) = widgets::segmented(ui, &p, pos2(fx, head.top()), h, &filters, filter, true, &[]) {
                    filter = i;
                }
                // the class box (a drop-down in the window)
                let cr = Rect::from_min_size(pos2(fx - 4.0 - 140.0, head.top() + 3.0), vec2(140.0, h - 6.0));
                painter.rect(cr, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
                jtext::draw(&painter, pos2(cr.left() + 8.0, cr.center().y), Align2::LEFT_CENTER, "All classes", &text(18.0), p.ink);
                let (ax, ay) = (cr.right() - 14.0, cr.center().y);
                painter.line_segment([pos2(ax - 4.0, ay - 2.0), pos2(ax, ay + 2.0)], Stroke::new(1.5, p.ink));
                painter.line_segment([pos2(ax + 4.0, ay - 2.0), pos2(ax, ay + 2.0)], Stroke::new(1.5, p.ink));
            },
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("st-sets").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let cw = ((r.width() - 12.0 - 20.0 - (cols - 1.0) * GAP) / cols).floor();
                    let lh = jtext::height(&painter, &text(18.0));
                    let ch = 6.0 + 72.0 + 2.0 + lh + 2.0 + 6.0;
                    if shown.is_empty() {
                        jtext::draw(
                            &painter,
                            top + vec2(10.0, 10.0),
                            Align2::LEFT_TOP,
                            if sets.is_empty() { "Loading sets..." } else { "No sets match." },
                            &text(18.0),
                            fixed::MUTED,
                        );
                    }
                    for (i, s) in shown.iter().enumerate() {
                        let (col, row) = (i as f32 % cols, (i as f32 / cols).floor());
                        let cr = Rect::from_min_size(pos2(top.x + 10.0 + col * (cw + GAP), top.y + 10.0 + row * (ch + GAP)), vec2(cw, ch));
                        let n = n_of(s);
                        let done = n == 4;
                        painter.rect_filled(cr, 0.0, if done { mix(p.gold, p.paper, 0.45) } else { p.header });
                        painter.rect_stroke(
                            cr,
                            0.0,
                            Stroke::new(if done { 2.0 } else { 1.0 }, if done { p.gold } else { Color32::BLACK }),
                            StrokeKind::Inside,
                        );
                        if s.skin != 0
                            && let Some(t) = icons.object(ui.ctx(), data, s.skin, 64)
                        {
                            painter.image(
                                t.id(),
                                Rect::from_center_size(pos2(cr.left() + 6.0 + 36.0, cr.top() + 6.0 + 36.0), vec2(64.0, 64.0)),
                                uv(),
                                Color32::WHITE,
                            );
                        }
                        // the four pieces beside the skin
                        let tx = cr.left() + 6.0 + 72.0 + 10.0;
                        let ty = cr.top() + 6.0 + (72.0 - TILE) / 2.0;
                        for (k, id) in s.pieces.iter().enumerate() {
                            let tr = Rect::from_min_size(pos2(tx + k as f32 * (TILE + 6.0), ty), vec2(TILE, TILE));
                            let have = has(*id);
                            painter.rect_filled(
                                tr,
                                0.0,
                                if have { mix(p.gold, Color32::from_rgb(0xFF, 0xF6, 0xC0), 0.7) } else { Color32::from_rgb(5, 5, 5) },
                            );
                            if let Some(t) = icons.object(ui.ctx(), data, *id, (TILE - 10.0) as u32) {
                                painter.image(
                                    t.id(),
                                    Rect::from_center_size(tr.center(), vec2(TILE - 10.0, TILE - 10.0)),
                                    uv(),
                                    if have { Color32::WHITE } else { Color32::from_white_alpha(64) },
                                );
                            }
                            if have && app.collection.entry(*id).is_some() {
                                enchant_badge(
                                    &painter,
                                    rarity,
                                    pos2(tr.right() - 3.0, tr.bottom() - 3.0),
                                    o.best.get(id).copied().unwrap_or(0).max(0) as usize,
                                    5.0,
                                );
                            }
                            painter.rect_stroke(tr, 0.0, Stroke::new(1.0, Color32::BLACK), StrokeKind::Inside);
                        }
                        let token = token_of(s);
                        if token != 0
                            && let Some(t) = icons.object(ui.ctx(), data, token, 30)
                        {
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(cr.right() - 30.0 - 10.0, cr.top() + 8.0), vec2(30.0, 30.0)),
                                uv(),
                                Color32::WHITE,
                            );
                        }
                        // along the bottom: the name (underlined, smaller to fit), the class and count
                        let by = cr.bottom() - 6.0 - lh / 2.0 - 1.0;
                        let cls = format!("{}{}", s.class_name, if done { String::new() } else { format!("   {n} / 4") });
                        let cls_w = 8.0 + 6.0 + jtext::width(&painter, &cls, &text(18.0));
                        let cx = cr.right() - 10.0 - cls_w;
                        painter.rect_filled(
                            Rect::from_min_size(pos2(cx, by - 4.0), vec2(8.0, 8.0)),
                            0.0,
                            crate::classes::class_color(&s.class_name),
                        );
                        jtext::draw(&painter, pos2(cx + 14.0, by), Align2::LEFT_CENTER, &cls, &text(18.0), p.ink);
                        let room = cx - 10.0 - (cr.left() + 6.0);
                        let mut size = 18.0;
                        while jtext::width(&painter, &s.title, &text(size)) > room && size > 12.0 {
                            size -= 1.0;
                        }
                        let t = widgets::fit(&painter, &s.title, &text(size), room);
                        let tr = jtext::draw(&painter, pos2(cr.left() + 6.0, by), Align2::LEFT_CENTER, &t, &text(size), p.ink);
                        painter.rect_filled(Rect::from_min_size(pos2(tr.left(), tr.bottom() - 1.0), vec2(tr.width(), 1.0)), 0.0, p.ink);
                    }
                    let rows = (shown.len() as f32 / cols).ceil();
                    ui.allocate_exact_size(vec2(r.width() - 12.0, 20.0 + rows * (ch + GAP)), Sense::hover());
                });
            },
        );
        self.st_filter = filter;

        // the side: set progress, almost complete, by class
        let painter = ui.painter().clone();
        let lh = jtext::height(&painter, &text(18.0));
        let sh = jtext::height(&painter, &small());
        let head = widgets::section_head_height(&painter);
        let complete = sets.iter().filter(|s| n_of(s) == 4).count();
        let pieces: usize = sets.iter().map(&n_of).sum();
        let prog = Rect::from_min_size(side.min, vec2(side.width(), head + 2.0 + 20.0 + 2.0 * (sh + 10.0 + lh) + 6.0));
        widgets::section(
            ui,
            &p,
            prog,
            "Set Progress",
            fixed::GREEN,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter();
                let (x, w) = (r.left() + 10.0, r.width() - 20.0);
                let mut y = r.top() + 10.0;
                for (k, (cap, have, total, color)) in
                    [("SETS COMPLETE", complete, sets.len(), p.gold), ("PIECES COLLECTED", pieces, sets.len() * 4, fixed::GREEN)]
                        .iter()
                        .enumerate()
                {
                    if k > 0 {
                        y += 6.0;
                    }
                    let t = jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, cap, &small(), p.ink_muted);
                    y = t.bottom();
                    let pct = if *total == 0 { 0.0 } else { 100.0 * *have as f64 / *total as f64 };
                    painter.rect_filled(Rect::from_min_size(pos2(x, y), vec2(w, 10.0)), 0.0, p.line);
                    painter.rect_filled(Rect::from_min_size(pos2(x, y), vec2((w * pct as f32 / 100.0).round(), 10.0)), 0.0, *color);
                    y += 10.0;
                    let t =
                        jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, &format!("{have} / {total}   {pct:.1}%"), &text(18.0), p.ink);
                    y = t.bottom();
                }
            },
        );
        let mut close: Vec<&lg_gamedata::st_sets::StSet> = sets.iter().filter(|s| n_of(s) == 3).collect();
        close.sort_by(|a, b| a.title.cmp(&b.title));
        close.truncate(8);
        let row_h = lh.max(22.0);
        let almost_h = if close.is_empty() { lh } else { close.len() as f32 * row_h };
        let almost = Rect::from_min_size(pos2(side.left(), prog.bottom() + 8.0), vec2(side.width(), head + 2.0 + 16.0 + almost_h));
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            almost,
            "Almost Complete",
            fixed::BLUE,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let (x, mut y) = (r.left() + 10.0, r.top() + 8.0);
                if close.is_empty() {
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, "No set is one piece away.", &text(18.0), fixed::MUTED);
                }
                for s in &close {
                    let missing = s.pieces.iter().copied().find(|id| !has(*id)).unwrap_or(0);
                    if let Some(t) = icons.object(ui.ctx(), data, missing, ICON as u32) {
                        painter.image(
                            t.id(),
                            Rect::from_min_size(pos2(x, y + (row_h - 22.0) / 2.0), vec2(22.0, 22.0)),
                            uv(),
                            Color32::WHITE,
                        );
                    }
                    jtext::draw(&painter, pos2(x + 30.0, y + row_h / 2.0), Align2::LEFT_CENTER, &s.title, &text(18.0), p.ink);
                    y += row_h;
                }
            },
        );
        let by_class = Rect::from_min_max(pos2(side.left(), almost.bottom() + 8.0), side.max);
        widgets::section(
            ui,
            &p,
            by_class,
            "By Class",
            fixed::PURPLE,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter();
                let mut per: std::collections::BTreeMap<&str, (usize, usize)> = std::collections::BTreeMap::new();
                for s in sets {
                    let v = per.entry(s.class_name.as_str()).or_default();
                    v.1 += 1;
                    if n_of(s) == 4 {
                        v.0 += 1;
                    }
                }
                let (x, w) = (r.left() + 10.0, r.width() - 20.0 - 12.0);
                let mut y = r.top() + 8.0;
                for (cls, (done, total)) in per {
                    painter.rect_filled(
                        Rect::from_min_size(pos2(x, y + lh / 2.0 - 4.0), vec2(8.0, 8.0)),
                        0.0,
                        crate::classes::class_color(cls),
                    );
                    jtext::draw(painter, pos2(x + 16.0, y + lh / 2.0), Align2::LEFT_CENTER, cls, &text(18.0), p.ink);
                    jtext::draw(painter, pos2(x + w, y + lh / 2.0), Align2::RIGHT_CENTER, &format!("{done} / {total}"), &text(18.0), p.ink);
                    y += lh + 2.0;
                    painter.rect_filled(Rect::from_min_size(pos2(x, y), vec2(w, 5.0)), 0.0, p.line);
                    painter.rect_filled(
                        Rect::from_min_size(pos2(x, y), vec2(if total == 0 { 0.0 } else { w * done as f32 / total as f32 }, 5.0)),
                        0.0,
                        p.gold,
                    );
                    y += 5.0 + 5.0;
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
