//! The Suggested Loadout box (Java: `CharacterDetail.ideal` and its parts): the scope, deleted-enchantment and second
//! chance switches, the enemy DEF, the stat boxes (each a toggle for what you aim for), and per slot the suggested
//! item with where its copy is, its stats, and the enchantments planned for it.

use crate::detail::{Rarity, enchant_badge, uv};
use crate::icons::Icons;
use crate::jtext;
use crate::profile::num;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::App;
use lg_app::characters::Char;
use lg_app::loadout::{Enchantments, Pick, STATS, Settings, Summary};

const STAT_SHORT: [&str; 8] = ["HP", "MP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"];
const SCOPES: [&str; 3] = ["current", "local", "global"];
const SECOND: [&str; 3] = ["off", "ami", "ami + armor"];
const SLOT_NAMES: [&str; 4] = ["Weapon", "Ability", "Armor", "Ring"];
const UP: Color32 = fixed::GREEN;
const DOWN: Color32 = Color32::from_rgb(0xFF, 0x8A, 0x2A);
const OTHER_STAT: Color32 = Color32::from_rgb(0x3A, 0x9B, 0xFF);

/// A click in the box: what it changes in the settings.
#[derive(Debug, Clone, PartialEq)]
pub enum Change {
    Toggle(String),
    Scope,
    ShowReplaced,
    SecondChance,
}

fn signed(n: i64) -> String {
    format!("{}{n}", if n >= 0 { "+" } else { "" })
}

/// Java `ThreeWaySwitch`: a recessed pill, gold as the knob moves on, dots for the stops, a metal knob.
fn switch(painter: &egui::Painter, r: Rect, stops: usize, stop: usize, p: &Palette) {
    let h = r.height() - 1.0;
    let track = r.shrink2(vec2(0.5, 0.5));
    painter.rect_filled(track, h / 2.0, Color32::from_rgb(0x42, 0x3D, 0x35));
    if stop > 0 {
        let level = (stop as f32 / (stops - 1) as f32).min(1.0);
        let g = p.gold;
        painter.rect_filled(track, h / 2.0, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), (150.0 * level) as u8));
    }
    painter.rect_stroke(track, h / 2.0, Stroke::new(1.2, Color32::from_black_alpha(120)), StrokeKind::Inside);
    let pad = 3.0;
    let d = h - 2.0 * pad;
    let span = r.width() - 1.0 - 2.0 * pad - d;
    for i in 0..stops {
        let cx = r.left() + pad + span * i as f32 / (stops - 1) as f32 + d / 2.0;
        painter.circle_filled(pos2(cx, r.center().y), 2.0, if i == stop { p.gold } else { Color32::from_white_alpha(70) });
    }
    let x = r.left() + pad + span * stop as f32 / (stops - 1) as f32;
    let c = pos2(x + d / 2.0, r.top() + pad + 0.5 + d / 2.0);
    painter.circle_filled(c + vec2(1.0, 2.0), d / 2.0, Color32::from_black_alpha(90));
    painter.circle_filled(c, d / 2.0, Color32::from_rgb(0xCF, 0xCF, 0xCF));
    painter.circle_filled(c - vec2(d * 0.12, d * 0.18), d * 0.28, Color32::from_rgb(0xF2, 0xF2, 0xF2));
    painter.circle_stroke(c, d / 2.0, Stroke::new(1.2, Color32::from_rgb(0x6E, 0x6E, 0x6E)));
}

/// A label with a switch at the right end of the column; true when clicked.
fn switch_row(ui: &Ui, p: &Palette, at: egui::Pos2, w: f32, label: &str, stops: usize, stop: usize, id: &str) -> (f32, bool) {
    let painter = ui.painter();
    let sw = if stops == 2 { 58.0 } else { 92.0 };
    let h = 30.0;
    let r = Rect::from_min_size(at, vec2(w, h));
    jtext::draw(painter, pos2(at.x, r.center().y), Align2::LEFT_CENTER, label, &text(18.0), p.ink);
    switch(painter, Rect::from_min_size(pos2(r.right() - sw, r.top()), vec2(sw, h)), stops, stop, p);
    let clicked = ui.interact(r, ui.scope_id().with(("switch", id)), Sense::click()).clicked();
    (h, clicked)
}

/// Java `StatBox`: a dark framed box (gold-tinted when aimed for), the name above "x (+y)" (green up, orange down).
fn stat_box(ui: &Ui, p: &Palette, r: Rect, name: &str, value: &str, ench: &str, trend: i32, chosen: bool) -> bool {
    let painter = ui.painter();
    let g = p.gold;
    painter.rect_filled(
        r.shrink(3.0),
        0.0,
        if chosen { Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 55) } else { Color32::from_black_alpha(70) },
    );
    let frame = if chosen { g } else { Color32::from_white_alpha(40) };
    let (w, h, c) = (r.width(), r.height(), 8.0);
    for (x, y, ww, hh) in
        [(c, 0.0, w - 2.0 * c, 3.0), (c, h - 3.0, w - 2.0 * c, 3.0), (0.0, c, 3.0, h - 2.0 * c), (w - 3.0, c, 3.0, h - 2.0 * c)]
    {
        painter.rect_filled(Rect::from_min_size(pos2(r.left() + x, r.top() + y), vec2(ww, hh)), 0.0, frame);
    }
    let f = text(18.0);
    let big = text((18.0f32 * 1.15).floor());
    jtext::draw(painter, pos2(r.center().x, r.top() + 6.0), Align2::CENTER_TOP, name, &f, if chosen { g } else { fixed::MUTED });
    let tw = jtext::width(painter, value, &big) + jtext::width(painter, ench, &big);
    let x = r.center().x - (tw / 2.0).floor();
    let y = r.top() + 6.0 + jtext::height(painter, &f);
    let a = jtext::draw(
        painter,
        pos2(x, y),
        Align2::LEFT_TOP,
        value,
        &big,
        if trend > 0 {
            UP
        } else if trend < 0 {
            DOWN
        } else {
            Color32::WHITE
        },
    );
    if !ench.is_empty() {
        jtext::draw(painter, pos2(a.right(), y), Align2::LEFT_TOP, ench, &big, OTHER_STAT);
    }
    ui.interact(r, ui.scope_id().with(("stat", name)), Sense::click()).clicked()
}

/// The box's content; gives its height and what was clicked.
pub fn suggested(
    ui: &mut Ui,
    p: &Palette,
    app: &App,
    icons: &mut Icons,
    rarity: &Rarity,
    en: &Enchantments,
    c: &Char,
    settings: &Settings,
    picks: &[Pick; 4],
    s: &Summary,
    r: Rect,
) -> (f32, Option<Change>) {
    let painter = ui.painter().clone();
    let ctx = ui.ctx().clone();
    let data = app.world.data();
    let chosen = |k: &str| settings.chosen.iter().any(|x| x == k);
    let mut change = None;
    // the stats column: as wide as two boxes for the widest value
    let big = text((18.0f32 * 1.15).floor());
    let box_w = jtext::width(&painter, "9,999 (+999) (+99)", &big) + 30.0;
    let box_h = jtext::height(&painter, &text(18.0)) + jtext::height(&painter, &big) + 14.0;
    let col_w = 2.0 * box_w + 6.0;
    let x = r.left();
    let mut y = r.top();
    let (h, clicked) = switch_row(ui, p, pos2(x, y), col_w, &format!("Scope: {}", SCOPES[settings.scope]), 3, settings.scope, "scope");
    if clicked {
        change = Some(Change::Scope);
    }
    y += h + 4.0;
    let shown = settings.chosen.is_empty() || false;
    let _ = shown;
    let (h, clicked) = switch_row(ui, p, pos2(x, y), col_w, "Show deleted enchants", 2, 0, "replaced");
    if clicked {
        change = Some(Change::ShowReplaced);
    }
    y += h + 4.0;
    let (h, clicked) = switch_row(
        ui,
        p,
        pos2(x, y),
        col_w,
        &format!("Second chance: {}", SECOND[settings.second_chance]),
        3,
        settings.second_chance,
        "second",
    );
    if clicked {
        change = Some(Change::SecondChance);
    }
    y += h;
    if s.has_weapon {
        y += 4.0;
        let row_h = 24.0;
        let n = jtext::draw(&painter, pos2(x, y + row_h / 2.0), Align2::LEFT_CENTER, "Enemy DEF", &small(), fixed::MUTED);
        let vw = jtext::width(&painter, "100", &text(18.0)) + 2.0;
        let def = settings.enemy_def as i32;
        jtext::draw(&painter, pos2(x + col_w - vw, y + row_h / 2.0), Align2::LEFT_CENTER, &def.to_string(), &text(18.0), p.ink);
        let (t0, t1) = (n.right() + 6.0 + 8.0, x + col_w - vw - 6.0 - 8.0);
        let g = p.gold;
        painter.line_segment(
            [pos2(t0, y + row_h / 2.0), pos2(t1, y + row_h / 2.0)],
            Stroke::new(4.0, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 128)),
        );
        let kx = t0 + (t1 - t0) * def as f32 / 100.0;
        painter.line_segment([pos2(t0, y + row_h / 2.0), pos2(kx, y + row_h / 2.0)], Stroke::new(4.0, g));
        painter.circle_filled(pos2(kx, y + row_h / 2.0), 8.0, g);
        y += row_h;
    }
    y += 6.0;
    let g = p.gold;
    painter.rect_filled(Rect::from_min_size(pos2(x, y), vec2(col_w, 2.0)), 0.0, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 170));
    y += 2.0 + 18.0;
    // HP/s | MP/s
    let one = |v: f64| format!("{v:.1}");
    let trend = |d: f64| {
        if d > 1e-9 {
            1
        } else if d < -1e-9 {
            -1
        } else {
            0
        }
    };
    for (k, (name, key)) in [("HP/s", lg_app::loadout::HP_REGEN), ("MP/s", lg_app::loadout::MP_REGEN)].iter().enumerate() {
        let d = s.regen[k] - s.regen_now[k];
        let v = format!("{} ({}{})", one(s.regen[k]), if d >= 0.0 { "+" } else { "" }, one(d));
        if stat_box(
            ui,
            p,
            Rect::from_min_size(pos2(x + k as f32 * (box_w + 6.0), y), vec2(box_w, box_h)),
            name,
            &v,
            "",
            trend(d),
            chosen(key),
        ) {
            change = Some(Change::Toggle(key.to_string()));
        }
    }
    y += box_h;
    if s.has_weapon {
        y += 6.0;
        let d = (s.dps.round() - s.dps_now.round()) as i64;
        let v = format!("{} ({})", num(s.dps.round() as i64), signed(d));
        if stat_box(
            ui,
            p,
            Rect::from_min_size(pos2(x, y), vec2(col_w, box_h)),
            "DPS",
            &v,
            "",
            trend(d as f64),
            chosen(lg_app::loadout::DPS),
        ) {
            change = Some(Change::Toggle(lg_app::loadout::DPS.into()));
        }
        y += box_h;
    }
    y += 14.0;
    for i in 0..8 {
        let (col, row) = (i % 2, i / 2);
        let items = s.suggested[i];
        let ench = (s.enchanted[i] + 1e-9).floor() as i64;
        let total = i64::from(s.max[i]) + i64::from(items) + ench;
        let v = format!("{} ({})", num(total), signed(i64::from(items)));
        let e = if ench == 0 { String::new() } else { format!(" ({})", signed(ench)) };
        let br = Rect::from_min_size(pos2(x + col as f32 * (box_w + 6.0), y + row as f32 * (box_h + 6.0)), vec2(box_w, box_h));
        if stat_box(ui, p, br, STAT_SHORT[i], &v, &e, items.signum(), chosen(STATS[i])) {
            change = Some(Change::Toggle(STATS[i].into()));
        }
    }
    y += 4.0 * box_h + 3.0 * 6.0 + 14.0;
    let d = s.loot - s.loot_now;
    let v = format!("+{}% ({}{})", one(s.loot), if d >= 0.0 { "+" } else { "" }, one(d));
    if stat_box(ui, p, Rect::from_min_size(pos2(x, y), vec2(box_w, box_h)), "LOOT", &v, "", trend(d), chosen(lg_app::loadout::LOOT)) {
        change = Some(Change::Toggle(lg_app::loadout::LOOT.into()));
    }
    y += box_h;
    let stats_h = y - r.top();

    // the items column
    let ix = x + col_w + 12.0;
    let iw = r.right() - ix;
    let mut iy = r.top();
    let small_f = small();
    let name_f = text(18.0);
    for (slot, pk) in picks.iter().enumerate() {
        if slot > 0 {
            iy += 6.0;
        }
        let it = data.items.item(pk.id);
        // the text first, to centre it on the icon's height
        let mut lines: Vec<Vec<(String, Color32, Option<i32>)>> = Vec::new();
        let name = if pk.id > 0 {
            it.map_or(format!("Item {}", pk.id), |i| i.name.clone())
        } else {
            format!("No {}", SLOT_NAMES[slot].to_lowercase())
        };
        let mut first = vec![(name, p.ink, None)];
        if !pk.r#where.is_empty() && pk.id > 0 {
            first.push((format!(" ({})", pk.r#where), fixed::MUTED, None));
        }
        lines.push(first);
        if let Some(it) = it {
            let (mut wanted, mut other) = (Vec::new(), Vec::new());
            for st in &it.on_equip {
                let Some(si) = STATS.iter().position(|x| *x == st.stat) else { continue };
                let want = chosen(&st.stat) || (chosen(lg_app::loadout::DPS) && (st.stat == "ATT" || st.stat == "DEX"));
                let t = format!("{}{} {}  ", if st.amount > 0 { "+" } else { "" }, st.amount, STAT_SHORT[si]);
                if want { wanted.push(t) } else { other.push(t) }
            }
            if !wanted.is_empty() || !other.is_empty() {
                lines.push(vec![(wanted.concat(), fixed::GREEN, Some(-1)), (other.concat(), OTHER_STAT, Some(-1))]);
            }
        }
        // the enchantments: planned (green: it has it, gold: reroll for it), then what it keeps, then free slots
        let mut chips: Vec<(Option<usize>, String, Color32)> = Vec::new();
        if it.is_some_and(|i| i.enchant_slots.is_some()) {
            let n = pk.slots(data);
            if pk.enchants.is_none() {
                if !settings.chosen.is_empty() {
                    chips.push((None, "Rarity not known yet: open the vault or play the character that has it".into(), fixed::MUTED));
                }
            } else if n == 0 {
                if !settings.chosen.is_empty() {
                    chips.push((None, "Common: no enchantment slots".into(), fixed::MUTED));
                }
            } else {
                let has: Vec<i32> = pk.enchants.clone().unwrap_or_default();
                let shown = if settings.chosen.is_empty() { en.of(pk.enchants.as_deref()) } else { pk.plan.picks.clone() };
                for e in &shown {
                    let owned = has.contains(&en.get(*e).kind);
                    let color = if settings.chosen.is_empty() {
                        fixed::MUTED
                    } else if owned {
                        fixed::GREEN
                    } else {
                        p.gold
                    };
                    chips.push((Some(*e), en.get(*e).name.clone(), color));
                }
                if !settings.chosen.is_empty() {
                    for e in &s.finals[slot] {
                        if !shown.contains(e) {
                            chips.push((Some(*e), en.get(*e).name.clone(), fixed::MUTED));
                        }
                    }
                }
                let free = n as i64 - if settings.chosen.is_empty() { shown.len() } else { s.finals[slot].len() } as i64;
                if !settings.chosen.is_empty() && free > 0 {
                    chips.push((None, format!("{free} free slot{}", if free == 1 { "" } else { "s" }), fixed::MUTED));
                }
            }
        }
        let text_h = jtext::height(&painter, &name_f)
            + (lines.len() as f32 - 1.0) * jtext::height(&painter, &small_f)
            + chips.len() as f32 * jtext::height(&painter, &small_f)
            + if chips.is_empty() { 0.0 } else { 2.0 };
        let h = text_h.max(52.0);
        let icon_r = Rect::from_min_size(pos2(ix, iy + (h - 52.0) / 2.0), vec2(60.0, 52.0));
        if pk.id > 0 {
            let t = icons.object(&ctx, data, pk.id, 48);
            let ir = Rect::from_min_size(pos2(icon_r.right() - 48.0, icon_r.center().y - 24.0), vec2(48.0, 48.0));
            if let Some(t) = t {
                painter.image(t.id(), ir, uv(), Color32::WHITE);
            }
            enchant_badge(&painter, rarity, ir.right_bottom() - vec2(1.0, 1.0), pk.enchants.as_ref().map_or(0, Vec::len), 6.0);
        }
        let tx = ix + 60.0 + 10.0;
        let tw = iw - 70.0;
        let mut ty = iy + (h - text_h) / 2.0;
        for (k, line) in lines.iter().enumerate() {
            let f = if k == 0 { &name_f } else { &small_f };
            let mut lx = tx;
            for (t, color, _) in line {
                let fitted = widgets::fit(&painter, t, f, (tx + tw - lx).max(10.0));
                let a = jtext::draw(&painter, pos2(lx, ty), Align2::LEFT_TOP, &fitted, f, *color);
                lx = a.right();
            }
            ty += jtext::height(&painter, f);
        }
        if !chips.is_empty() {
            ty += 1.0;
        }
        for (e, t, color) in &chips {
            let lh = jtext::height(&painter, &small_f);
            let mut lx = tx;
            if let Some(e) = e
                && let Some((sheet, idx)) = data.enchant_looks.get(&en.get(*e).kind).and_then(|l| l.texture.clone())
            {
                if let Some(tex) = icons.sheet(&ctx, &sheet, idx, 16) {
                    painter.image(tex.id(), Rect::from_min_size(pos2(lx, ty + (lh - 16.0) / 2.0), vec2(16.0, 16.0)), uv(), Color32::WHITE);
                }
                lx += 19.0;
            }
            jtext::draw(&painter, pos2(lx, ty), Align2::LEFT_TOP, &widgets::fit(&painter, t, &small_f, tx + tw - lx), &small_f, *color);
            ty += lh;
        }
        iy += h;
    }
    let _ = c;
    (stats_h.max(iy - r.top()), change)
}
