//! One character's details on the Characters page (Java: `CharacterDetail`, `CharactersView.renderPanel`): its
//! loadout with every enchantment by name, its inventory with three highlights, its fame gain and sessions.

use crate::icons::Icons;
use crate::jtext;
use crate::profile::num;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Shape, Stroke, Ui, pos2, vec2};
use lg_app::App;
use lg_app::characters::Char;

pub type Rarity = [Option<egui::TextureHandle>; 5];

/// Rarity colours by enchantment count (Java `EnchantBadge.COLORS`).
const RARITY_COLORS: [Color32; 5] = [
    Color32::from_rgb(0x9C, 0xA3, 0xAF),
    Color32::from_rgb(0x4C, 0xD1, 0x5A),
    Color32::from_rgb(0x3F, 0xB6, 0xF2),
    Color32::from_rgb(0xB0, 0x5C, 0xF0),
    Color32::from_rgb(0xF2, 0xC2, 0x32),
];
const SLOT_NAMES: [&str; 4] = ["Weapon", "Ability", "Armor", "Ring"];
const UT: Color32 = Color32::from_rgb(0x80, 0x49, 0xA8);
const ST: Color32 = Color32::from_rgb(0xE0, 0x7B, 0x2A);
const TIER: Color32 = Color32::from_rgb(0xB0, 0x7D, 0x0A);
const AMULET_BLUE: Color32 = Color32::from_rgb(0x3A, 0x9B, 0xFF);

pub fn uv() -> Rect {
    Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))
}

fn image(painter: &egui::Painter, t: &Option<egui::TextureHandle>, r: Rect) {
    if let Some(t) = t {
        painter.image(t.id(), r, uv(), Color32::WHITE);
    }
}

/// Java `EnchantBadge.draw`: the rarity sprite for a count, its bottom right at `at`, at `size` / 5 scale.
pub fn enchant_badge(painter: &egui::Painter, rarity: &Rarity, at: egui::Pos2, count: usize, size: f32) {
    if count == 0 {
        return;
    }
    let Some(t) = &rarity[count.min(4)] else { return };
    let s = (size / 5.0).max(1.0);
    let [w, h] = t.size();
    let (w, h) = ((w as f32 * s).round(), (h as f32 * s).round());
    painter.image(t.id(), Rect::from_min_size(pos2(at.x - w + 1.0, at.y - h + 1.0), vec2(w, h)), uv(), Color32::WHITE);
}

/// A slot's enchantments (Java `enchantIds`): -2 for each counted before the ids were kept.
pub fn enchant_ids(c: &Char, slot: usize) -> Option<Vec<i32>> {
    if let Some(ids) = c.enchant_ids.as_ref().and_then(|v| v.get(slot)) {
        return Some(ids.clone());
    }
    let n = c.enchants.as_ref().and_then(|v| v.get(slot)).copied().unwrap_or(0);
    (n > 0).then(|| vec![-2; n as usize])
}

/// The loadout box's content (Java `CharacterDetail.loadout`): weapon and armor on top, ability and ring below, each
/// with its enchantments; past a gold line the reusables in the quick slots. Gives its height.
pub fn loadout(ui: &mut Ui, p: &Palette, app: &App, icons: &mut Icons, c: &Char, r: Rect) -> f32 {
    let painter = ui.painter().clone();
    let data = app.world.data();
    let ctx = ui.ctx().clone();
    let name_f = text(18.0);
    let ench_f = small();
    // each slot: (icon, name and its colour, the enchantment lines)
    struct Slot {
        id: i32,
        name: String,
        color: Color32,
        lines: Vec<(i32, String)>,
        note: Option<String>,
    }
    let mut slots = Vec::new();
    for (i, slot_name) in SLOT_NAMES.iter().enumerate() {
        let id = c.equipment.as_ref().and_then(|e| e.get(i)).copied().unwrap_or(-1);
        let ench = enchant_ids(c, i);
        let n = ench.as_ref().map_or(0, Vec::len);
        let name = if id > 0 {
            data.items.item(id).map_or(format!("Item {id}"), |it| it.name.clone())
        } else {
            format!("No {}", slot_name.to_lowercase())
        };
        let lines = ench
            .unwrap_or_default()
            .iter()
            .map(|&e| {
                let t = if e == -1 {
                    "empty slot".to_string()
                } else if e < 0 {
                    "refresh to see".to_string()
                } else {
                    data.enchant_looks.get(&e).map_or(format!("Enchantment #{e}"), |l| l.name.clone())
                };
                (e, t)
            })
            .collect();
        let note = if i == 3 { amulet_note(app, c, id) } else { None };
        slots.push(Slot { id, name, color: if n > 0 { RARITY_COLORS[n.min(4)] } else { p.ink }, lines, note });
    }
    // GridBag: two columns at their natural widths (the second 20 px off), the line and the reusables after; with
    // too little room the columns give up what they must, their texts cut
    let width_of = |s: &Slot| -> f32 {
        let mut w = jtext::width(&painter, &s.name, &name_f);
        for (_, l) in &s.lines {
            w = w.max(16.0 + 6.0 + jtext::width(&painter, l, &ench_f));
        }
        48.0 + 10.0 + w
    };
    let reusable: Vec<i32> =
        c.quickslots.iter().flatten().copied().filter(|id| *id > 0 && data.items.item(*id).is_some_and(|i| i.quickslot_only)).collect();
    let reuse_w = if reusable.is_empty() {
        jtext::width(&painter, if c.quickslots.is_none() { "Quick slots not seen yet" } else { "No reusables in quick slots" }, &ench_f)
    } else {
        reusable
            .iter()
            .map(|id| 32.0 + 8.0 + jtext::width(&painter, &data.items.item(*id).map_or(String::new(), |i| i.name.clone()), &name_f))
            .fold(0.0, f32::max)
    };
    let mut c0 = width_of(&slots[0]).max(width_of(&slots[1]));
    let mut c1 = width_of(&slots[2]).max(width_of(&slots[3]));
    let fixed_w = 20.0 + 18.0 + 7.0 + 16.0 + reuse_w;
    let room = r.width();
    if c0 + c1 + fixed_w > room {
        let over = c0 + c1 + fixed_w - room;
        let (a, b) = (c0 / (c0 + c1), c1 / (c0 + c1));
        c0 = (c0 - over * a).max(60.0);
        c1 = (c1 - over * b).max(60.0);
    }
    let row_h = |s: &Slot| -> f32 {
        let text_h = jtext::height(&painter, &name_f) + s.lines.len() as f32 * jtext::height(&painter, &ench_f);
        let icon_h = 48.0 + s.note.as_ref().map_or(0.0, |_| jtext::height(&painter, &text(15.0)));
        text_h.max(icon_h)
    };
    let h0 = row_h(&slots[0]).max(row_h(&slots[2]));
    let h1 = row_h(&slots[1]).max(row_h(&slots[3]));
    let at = [(0usize, 0usize), (0, 1), (1, 0), (1, 1)];
    for (i, s) in slots.iter().enumerate() {
        let (col, row) = at[i];
        let x = r.left() + if col == 0 { 0.0 } else { c0 + 20.0 };
        let y = r.top() + if row == 0 { 0.0 } else { h0 + 8.0 };
        let w = if col == 0 { c0 } else { c1 };
        if s.id > 0 {
            let t = icons.object(&ctx, data, s.id, 44);
            image(&painter, &t, Rect::from_min_size(pos2(x + 2.0, y), vec2(44.0, 44.0)));
            widgets::item_link(ui, data, Rect::from_min_size(pos2(x, y), vec2(48.0, 48.0)), s.id);
        }
        if let Some(n) = &s.note {
            jtext::draw(&painter, pos2(x + 24.0, y + 48.0), Align2::CENTER_TOP, n, &text(15.0), AMULET_BLUE);
        }
        let tx = x + 48.0 + 10.0;
        let tw = w - 58.0;
        let mut ty = y;
        jtext::draw(&painter, pos2(tx, ty), Align2::LEFT_TOP, &widgets::fit(&painter, &s.name, &name_f, tw), &name_f, s.color);
        ty += jtext::height(&painter, &name_f);
        for (e, l) in &s.lines {
            let lh = jtext::height(&painter, &ench_f);
            if *e >= 0
                && let Some((sheet, idx)) = data.enchant_looks.get(e).and_then(|l| l.texture.clone())
            {
                let t = icons.sheet(&ctx, &sheet, idx, 16);
                image(&painter, &t, Rect::from_min_size(pos2(tx, ty + (lh - 16.0) / 2.0), vec2(16.0, 16.0)));
            }
            let color = if *e < 0 { fixed::MUTED } else { p.ink };
            jtext::draw(&painter, pos2(tx + 22.0, ty), Align2::LEFT_TOP, &widgets::fit(&painter, l, &ench_f, tw - 22.0), &ench_f, color);
            ty += lh;
        }
    }
    let total_h = h0 + 8.0 + h1;
    // the gold line with caps, then the reusables over the full height in three rows
    let lx = r.left() + c0 + 20.0 + c1 + 18.0;
    section_line(&painter, Rect::from_min_size(pos2(lx, r.top()), vec2(7.0, total_h)));
    let rx = lx + 7.0 + 16.0;
    if reusable.is_empty() {
        let t = if c.quickslots.is_none() { "Quick slots not seen yet" } else { "No reusables in quick slots" };
        jtext::draw(&painter, pos2(rx, r.top() + total_h / 2.0), Align2::LEFT_CENTER, t, &ench_f, fixed::MUTED);
    } else {
        let row = (total_h - 8.0) / 3.0;
        for (k, id) in reusable.iter().enumerate() {
            let cy = r.top() + k as f32 * (row + 4.0) + row / 2.0;
            let t = icons.object(&ctx, data, *id, 32);
            image(&painter, &t, Rect::from_min_size(pos2(rx, cy - 16.0), vec2(32.0, 32.0)));
            widgets::item_link(ui, data, Rect::from_min_size(pos2(rx, cy - 16.0), vec2(32.0, 32.0)), *id);
            let name = data.items.item(*id).map_or(String::new(), |i| i.name.clone());
            jtext::draw(&painter, pos2(rx + 40.0, cy), Align2::LEFT_CENTER, &name, &name_f, p.ink);
        }
    }
    total_h
}

/// Java `amuletNote`: with an amulet that saves you, "at 182HP", the life it fires below.
fn amulet_note(app: &App, c: &Char, ring: i32) -> Option<String> {
    let a = app.world.data().amulets.of(ring)?;
    let mut life = c.max_hp;
    if life <= 0 {
        let data = app.world.data();
        let base = data.classes.iter().find(|k| k.id == c.class_type).map_or(0, |k| k.max_stats[0]);
        let bonus: i32 = c
            .equipment
            .iter()
            .flatten()
            .filter(|id| **id > 0)
            .filter_map(|id| data.items.item(*id))
            .flat_map(|it| it.on_equip.iter())
            .filter(|s| s.stat == "MAXHP")
            .map(|s| s.amount)
            .sum();
        life = base + bonus;
    }
    Some(format!("at {}HP", (f64::from(life) * a.threshold).round() as i64))
}

/// Java `SectionLine`: a vertical gold line with short caps, setting off a new part.
fn section_line(painter: &egui::Painter, r: Rect) {
    let (w, h, cap) = (7.0, r.height(), 2.0);
    let mid = (w / 2.0f32).floor();
    let f = |x: f32, y: f32, ww: f32, hh: f32, c: Color32| {
        painter.rect_filled(Rect::from_min_size(pos2(r.left() + x, r.top() + y), vec2(ww, hh)), 0.0, c)
    };
    f(mid - 1.0, 0.0, 3.0, h, fixed::GOLD_DARK);
    f(0.0, 0.0, w, cap + 1.0, fixed::GOLD_DARK);
    f(0.0, h - cap - 1.0, w, cap + 1.0, fixed::GOLD_DARK);
    f(mid, 0.0, 1.0, h, fixed::GOLD);
    f(1.0, 0.0, w - 2.0, cap, fixed::GOLD);
    f(1.0, h - cap, w - 2.0, cap, fixed::GOLD);
}

const STATS: [&str; 8] = ["MAXHP", "MAXMP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"];
const STAT_SHORT: [&str; 8] = ["HP", "MP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"];

/// A fact that makes a carried item stand out (Java `Fact`): its title, its measure (None: does not apply), how
/// its value is shown.
type Measure<'a> = Box<dyn Fn(i32) -> Option<f64> + 'a>;
type Shown<'a> = Box<dyn Fn(i32) -> String + 'a>;

/// The inventory box's content (Java `CharacterDetail.inventory`). Gives its height.
pub fn inventory(ui: &mut Ui, p: &Palette, app: &App, icons: &mut Icons, rarity: &Rarity, c: &Char, r: Rect) -> f32 {
    let painter = ui.painter().clone();
    let ctx = ui.ctx().clone();
    let data = app.world.data();
    let Some(slots) = c.slots.as_ref().filter(|s| s.len() > 4) else {
        jtext::draw(
            &painter,
            r.min,
            Align2::LEFT_TOP,
            "Log in with this character, or Refresh, to see its inventory.",
            &text(18.0),
            fixed::MUTED,
        );
        return jtext::height(&painter, &text(18.0));
    };
    let mut carried: Vec<i32> = Vec::new();
    for &id in &slots[4..] {
        if id > 0 && data.items.item(id).is_some() && !carried.contains(&id) {
            carried.push(id);
        }
    }
    let item = |id: i32| data.items.item(id).expect("carried items are known");
    let stat = |id: i32, s: &str| -> i32 { item(id).on_equip.iter().filter(|x| x.stat == s).map(|x| x.amount).sum() };
    let carried_enchants = |id: i32| -> usize {
        let mut best = 0;
        if let Some(e) = &c.slot_enchant_ids {
            for i in 4..slots.len().min(e.len()) {
                if slots[i] == id {
                    best = best.max(e[i].len());
                }
            }
        }
        best
    };
    let time = |id: i32, last: bool| -> Option<f64> {
        let e = app.collection.entry(id)?;
        let t = if last { e.last.as_deref() } else { e.first.as_deref() }?;
        if t.len() < 10 {
            return None;
        }
        lg_app::localtime::Zone::default().parse_local(t).map(|ms| (ms / 1000) as f64)
    };
    let day = |t: Option<&str>| -> String {
        let Some(t) = t.filter(|t| t.len() >= 10) else { return String::new() };
        const M: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        let (y, m, d) = (&t[0..4], t[5..7].parse::<usize>().unwrap_or(1), t[8..10].parse::<u32>().unwrap_or(1));
        format!("{d} {} {y}", M[m.saturating_sub(1).min(11)])
    };
    let living: std::collections::HashMap<i32, bool> = app.characters.living().iter().map(|l| (l.char_id, l.seasonal)).collect();
    let now_text = app.zone.local_date_time(app.world.now());
    let hctx = lg_app::holdings::Ctx { data, now: app.world.now(), time: &now_text };
    let held = |id: i32| -> i32 {
        if app.holdings.known() { app.holdings.count(&hctx, &living, id) } else { app.collection.entry(id).map_or(0, |e| e.held()) }
    };
    let mut facts: Vec<(String, Measure, Shown)> = vec![
        (
            "Highest feed power".into(),
            Box::new(|id| (item(id).feed_power > 0).then(|| f64::from(item(id).feed_power))),
            Box::new(|id| format!("{} FP", num(i64::from(item(id).feed_power)))),
        ),
        (
            "Lowest feed power".into(),
            Box::new(|id| (item(id).feed_power > 0).then(|| -f64::from(item(id).feed_power))),
            Box::new(|id| format!("{} FP", num(i64::from(item(id).feed_power)))),
        ),
        (
            "Highest damage".into(),
            Box::new(|id| (item(id).max_damage > 0).then(|| f64::from(item(id).max_damage))),
            Box::new(|id| format!("{}-{} damage", item(id).min_damage, item(id).max_damage)),
        ),
    ];
    for (s, title) in [
        ("WIS", "Most wisdom"),
        ("DEF", "Most defense"),
        ("ATT", "Most attack"),
        ("SPD", "Most speed"),
        ("DEX", "Most dexterity"),
        ("VIT", "Most vitality"),
        ("MAXHP", "Most life"),
        ("MAXMP", "Most mana"),
    ] {
        let short = STAT_SHORT[STATS.iter().position(|x| *x == s).expect("stat")];
        facts.push((
            title.into(),
            Box::new(move |id| (stat(id, s) > 0).then(|| f64::from(stat(id, s)))),
            Box::new(move |id| format!("+{} {short}", stat(id, s))),
        ));
    }
    facts.push((
        "Most enchantments".into(),
        Box::new(|id| (carried_enchants(id) > 0).then(|| carried_enchants(id) as f64)),
        Box::new(|id| format!("{} enchantment{}", carried_enchants(id), if carried_enchants(id) == 1 { "" } else { "s" })),
    ));
    facts.push((
        "Most recently found".into(),
        Box::new(|id| time(id, true)),
        Box::new(|id| day(app.collection.entry(id).and_then(|e| e.last.as_deref()))),
    ));
    facts.push((
        "Oldest find".into(),
        Box::new(|id| time(id, false).map(|t| -t)),
        Box::new(|id| day(app.collection.entry(id).and_then(|e| e.first.as_deref()))),
    ));
    facts.push(("Your only copy".into(), Box::new(|id| (held(id) == 1).then_some(1.0)), Box::new(|_| "1 copy".into())));
    facts.push((
        "Most copies".into(),
        Box::new(|id| (held(id) > 1).then(|| f64::from(held(id)))),
        Box::new(|id| format!("{} copies", held(id))),
    ));
    lg_app::java::shuffle(&mut facts, &mut lg_app::java::JavaRandom::new(i64::from(c.char_id)));
    let types: Vec<i32> =
        data.classes.iter().find(|k| k.id == c.class_type).and_then(|k| k.slot_types).map(|t| t.to_vec()).unwrap_or_default();
    let wearable: Vec<i32> = carried.iter().copied().filter(|id| types.contains(&item(*id).slot_type)).collect();
    let mut shown: Vec<(usize, i32)> = Vec::new();
    let mut used: Vec<i32> = Vec::new();
    'passes: for pass in 0..2 {
        for (fi, f) in facts.iter().enumerate() {
            if shown.len() == 3 {
                break 'passes;
            }
            if shown.iter().any(|s| s.0 == fi) {
                continue;
            }
            let values: Vec<(i32, f64)> = carried.iter().filter_map(|&id| (f.1)(id).map(|v| (id, v))).collect();
            let Some(best_v) = values.iter().map(|v| v.1).fold(None, |a: Option<f64>, v| Some(a.map_or(v, |a| if v > a { v } else { a })))
            else {
                continue;
            };
            let best = carried.iter().copied().find(|id| {
                values.iter().any(|(i, v)| i == id && *v == best_v) && !used.contains(id) && (pass == 1 || wearable.contains(id))
            });
            let Some(best) = best else { continue };
            used.push(best);
            shown.push((fi, best));
        }
    }
    let mut y = r.top();
    if !shown.is_empty() {
        let cw = ((r.width() - 24.0) / 3.0).floor();
        let lh = jtext::height(&painter, &text(18.0));
        for (k, (fi, id)) in shown.iter().enumerate() {
            let x = r.left() + k as f32 * (cw + 12.0);
            let t = icons.object(&ctx, data, *id, 40);
            image(&painter, &t, Rect::from_min_size(pos2(x, y + lh - 20.0), vec2(40.0, 40.0)));
            widgets::item_link(ui, data, Rect::from_min_size(pos2(x, y + lh - 20.0), vec2(40.0, 40.0)), *id);
            let tx = x + 50.0;
            jtext::draw(
                &painter,
                pos2(tx, y),
                Align2::LEFT_TOP,
                &widgets::fit(&painter, &facts[*fi].0, &text(18.0), cw - 50.0),
                &text(18.0),
                p.gold,
            );
            jtext::draw(
                &painter,
                pos2(tx, y + lh),
                Align2::LEFT_TOP,
                &widgets::fit(&painter, &(facts[*fi].2)(*id), &text(18.0), cw - 50.0),
                &text(18.0),
                fixed::MUTED,
            );
        }
        y += (2.0 * lh).max(40.0) + 10.0;
    }
    // the packs side by side: inventory, then backpack and extender if there
    let has_items = |from: usize| (from..(from + 8).min(slots.len())).any(|i| slots[i] > 0);
    let packs = 1 + usize::from(c.backpack || slots.len() > 12 && has_items(12)) + usize::from(c.extender || has_items(20));
    const GAP: f32 = 6.0;
    const DIVIDER: f32 = 22.0;
    const PAD: f32 = 10.0;
    // 64 px slots, smaller when the packs would not fit the box (as Java shows them)
    let n = packs as f32;
    let fit = ((r.width() - 4.0 - (n - 1.0) * DIVIDER - n * 3.0 * GAP) / (4.0 * n)).floor();
    let s_px = fit.min(64.0);
    #[allow(non_snake_case)]
    let S = s_px;
    let pack_w = 4.0 * S + 3.0 * GAP;
    let all_w = packs as f32 * pack_w + (packs as f32 - 1.0) * DIVIDER;
    let left = r.left() + ((r.width() - all_w) / 2.0).floor();
    let cols = 4 * packs;
    let cell = |row: usize, col: usize| -> Rect {
        let (pack, k) = (col / 4, col % 4);
        Rect::from_min_size(
            pos2(left + pack as f32 * (pack_w + DIVIDER) + k as f32 * (S + GAP), y + PAD + row as f32 * (S + GAP)),
            vec2(S, S),
        )
    };
    let g = p.gold;
    let cut = (S / 5.0).floor().max(6.0);
    for row in 0..2 {
        for col in 0..cols {
            let b = cell(row, col).expand(2.0);
            let (tl, tr) = (col == 0 && row == 0, col == cols - 1 && row == 0);
            let mut pts = Vec::new();
            let (x0, y0, x1, y1) = (b.left(), b.top(), b.right() - 1.0, b.bottom() - 1.0);
            if tl {
                pts.push(pos2(x0, y0 + cut));
                pts.push(pos2(x0 + cut, y0));
            } else {
                pts.push(pos2(x0, y0));
            }
            if tr {
                pts.push(pos2(x1 - cut, y0));
                pts.push(pos2(x1, y0 + cut));
            } else {
                pts.push(pos2(x1, y0));
            }
            pts.push(pos2(x1, y1));
            pts.push(pos2(x0, y1));
            let below: Vec<_> = pts.iter().map(|q| *q + vec2(2.0, 3.0)).collect();
            painter.add(Shape::convex_polygon(below, Color32::from_black_alpha(70), Stroke::NONE));
            painter.add(Shape::convex_polygon(pts.clone(), Color32::from_black_alpha(55), Stroke::NONE));
            let mut closed = pts.clone();
            closed.push(pts[0]);
            painter.add(Shape::line(closed, Stroke::new(1.5, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 80))));
        }
    }
    let (y0, y1) = (y + PAD - 2.0 - 7.0, y + PAD + 2.0 * S + GAP + 2.0 + 7.0);
    let line = Stroke::new(2.0, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 170));
    for pack in 1..packs {
        let x = cell(0, pack * 4).left() - DIVIDER / 2.0;
        painter.line_segment([pos2(x, y0), pos2(x, y1)], line);
        painter.line_segment([pos2(x - 3.0, y0), pos2(x + 3.0, y0)], line);
        painter.line_segment([pos2(x - 3.0, y1), pos2(x + 3.0, y1)], line);
    }
    // the items: each pack fills its 4 x 2 slots row by row; a rarity marker and UT / ST / tier tag on each
    for row in 0..2 {
        for pack in 0..packs {
            for k in 0..4 {
                let i = 4 + pack * 8 + row * 4 + k;
                let id = slots.get(i).copied().unwrap_or(-1);
                let Some(it) = (id > 0).then(|| data.items.item(id)).flatten() else { continue };
                let b = cell(row, pack * 4 + k);
                let t = icons.object(&ctx, data, id, (S - 10.0) as u32);
                image(&painter, &t, Rect::from_center_size(b.center(), vec2(S - 10.0, S - 10.0)));
                widgets::item_link(ui, data, b, id);
                let ne = c.slot_enchant_ids.as_ref().and_then(|e| e.get(i)).map_or(0, Vec::len);
                enchant_badge(&painter, rarity, pos2(b.right() - 3.0, b.bottom() - 3.0), ne, if S >= 64.0 { 8.0 } else { 5.0 });
                let (tag, color) = if it.labels.contains("UT") {
                    ("UT".to_string(), UT)
                } else if it.labels.contains("ST") {
                    ("ST".to_string(), ST)
                } else if it.tier.is_empty() {
                    continue;
                } else {
                    (format!("T{}", it.tier), TIER)
                };
                let px = (S / 4.0).floor().max(13.0);
                let f = text(px);
                let asc = (px * 1050.0 / 1350.0).ceil();
                jtext::draw(&painter, pos2(b.left() + 4.0, b.bottom() - 3.0 - asc), Align2::LEFT_TOP, &tag, &f, Color32::BLACK);
                jtext::draw(&painter, pos2(b.left() + 3.0, b.bottom() - 4.0 - asc), Align2::LEFT_TOP, &tag, &f, color);
            }
        }
    }
    y + 2.0 * PAD + 2.0 * S + GAP - r.top()
}

/// The fame gain box's content for one character (Java `renderPanel`): KPIs, the chart, the sessions.
pub fn fame(ui: &mut Ui, p: &Palette, app: &App, icons: &mut Icons, c: &Char, since: i64, r: Rect, marks: &[crate::charts::Mark]) -> f32 {
    let _ = icons;
    let painter = ui.painter().clone();
    let sessions = lg_app::characters::Characters::sessions(c, since);
    let gained: i64 = sessions.iter().map(|s| s.gained).sum();
    let played: i64 = sessions.iter().map(|s| s.duration_ms()).sum();
    let kh = widgets::kpi_height(&painter);
    let kw = ((r.width() - 16.0) / 3.0).floor();
    let hour = 3_600_000.0;
    let per_hour = if played > 60_000 { num((gained as f64 / (played as f64 / hour)).round() as i64) } else { "-".into() };
    let values = [
        ("Fame gained", format!("+{}", num(gained))),
        ("Fame / hour", per_hour),
        ("Time played", format!("{:.1} h", played as f64 / hour)),
    ];
    let mut y = r.top();
    for (i, (cap, v)) in values.iter().enumerate() {
        widgets::kpi(ui, p, Rect::from_min_size(pos2(r.left() + i as f32 * (kw + 8.0), y), vec2(kw, kh)), cap, v);
    }
    y += kh + 8.0;
    let points: Vec<(i64, i64)> = c.samples.iter().map(|s| (s.t, s.fame)).collect();
    crate::charts::fame_chart(
        &painter,
        p,
        Rect::from_min_size(pos2(r.left(), y), vec2(r.width(), 200.0)),
        &points,
        since,
        app.world.now().max(1),
        &app.zone,
        marks,
    );
    y += 208.0;
    let t = jtext::draw(&painter, pos2(r.left(), y), Align2::LEFT_TOP, "SESSIONS", &small(), p.ink_muted);
    y = t.bottom() + 4.0;
    let f = text(18.0);
    if sessions.is_empty() {
        let t = jtext::draw(&painter, pos2(r.left(), y), Align2::LEFT_TOP, "No sessions recorded in this range.", &f, fixed::MUTED);
        y = t.bottom();
    }
    const DAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
    const M: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    for (i, s) in sessions.iter().enumerate() {
        let lh = jtext::height(&painter, &f) + 6.0;
        let rr = Rect::from_min_size(pos2(r.left(), y), vec2(r.width(), lh));
        painter.rect_filled(rr, 0.0, if i % 2 == 0 { p.header } else { p.sand });
        let a = app.zone.local_date_time(s.start);
        let b = app.zone.local_date_time(s.end);
        let local_days = (s.start + i64::from(app.zone.offset(s.start / 1000)) * 1000).div_euclid(86_400_000);
        let when = format!(
            "{} {} {}, {} – {}",
            DAYS[local_days.rem_euclid(7) as usize],
            a[8..10].trim_start_matches('0'),
            M[a[5..7].parse::<usize>().unwrap_or(1) - 1],
            &a[11..16],
            &b[11..16]
        );
        jtext::draw(&painter, pos2(rr.left() + 8.0, rr.center().y), Align2::LEFT_CENTER, &when, &f, p.ink);
        jtext::draw(
            &painter,
            pos2(rr.right() - 8.0, rr.center().y),
            Align2::RIGHT_CENTER,
            &format!("{}{} fame", if s.gained >= 0 { "+" } else { "" }, num(s.gained)),
            &f,
            fixed::GREEN,
        );
        y += lh;
    }
    y - r.top()
}
