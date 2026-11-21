//! Guild > Loot Caravan (Java `CaravanView`): a night road where every guild member drives a wagon piled with the
//! items they found, sitting up front as their most played character. The highest score leads, right behind the
//! pack ox; members with fewer than five items walk behind with a backpack. Clicking a member shows their items per
//! bag colour and their best finds below the picture. Ctrl + mouse wheel zooms (kept in `caravanZoom`).

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::fixed;
use crate::widgets::{self, text};
use crate::window::{Ask, ServerAsk};
use egui::{Align2, Color32, Pos2, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::guild::{CaravanItem, CaravanMember};
use lg_gamedata::GameData;
use std::collections::HashMap;

pub const HEIGHT: f32 = 440.0;
const PAD: f32 = 28.0;
const OX_W: f32 = 120.0;
const BAR: f32 = 28.0;
const STEP_W: f32 = 46.0;
const WAGON_W: f32 = 186.0;
const BED_H: f32 = 34.0;
const WALKER_W: f32 = 80.0;
const DRIVER: f32 = 64.0;
const ITEM: f32 = 24.0;
const ITEM_GAP: f32 = 2.0;
const PILE_COLS: usize = 5;
const WALK_BELOW: i64 = 5;
const MIN_ZOOM: f64 = 0.4;
const MAX_ZOOM: f64 = 3.0;
const MAX_FIT: f32 = 1.7;
const FIT_MARGIN: f32 = 120.0;
const POLL_MS: i64 = 60_000;

/// Bag colours, rarest first: the order of the bag counts, and the pile order from the top down.
const BAG_ORDER: [&str; 11] = ["White", "Orange", "Red", "Gold", "Teal", "Blue", "Purple", "Egg Basket", "Pink", "Brown", "Other"];

const fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

const SKY: Color32 = rgb(0x141B2B);
const WOOD: Color32 = rgb(0x8C6C47);
const WOOD_DARK: Color32 = rgb(0x6B4E2E);
const INK: Color32 = rgb(0x2C1D0E);
const BONE: Color32 = rgb(0xE9D8B8);
const BLANKET: Color32 = rgb(0xB8364A);
const FLAG_2: Color32 = rgb(0xD2B988);
const FLAG_3: Color32 = rgb(0xC8814A);
const TAG: Color32 = rgb(0x2E2116);
const ROAD_EDGE: Color32 = rgb(0x6B4E2E);
const DARK: Color32 = rgb(0x1A120A);
const GOLD_BODY: Color32 = rgb(0xE5B23A);
const GOLD_BAND: Color32 = rgb(0xB0306E);
const STONE: [Color32; 5] = [Color32::BLACK, rgb(0x4CD15A), rgb(0x3FB6F2), rgb(0xB05CF0), rgb(0xF2C232)];

/// The caravan page's own state: the member shown below, the zoom, when it was last asked for.
#[derive(Default)]
pub struct CaravanView {
    picked: i64,
    zoom: Option<f64>,
    asked_at: i64,
}

/// Java `Color.darker`.
fn darker(c: Color32) -> Color32 {
    Color32::from_rgb((c.r() as f32 * 0.7) as u8, (c.g() as f32 * 0.7) as u8, (c.b() as f32 * 0.7) as u8)
}

/// Java `Color.brighter`.
fn brighter(c: Color32) -> Color32 {
    let i = 3u8;
    let (r, g, b) = (c.r(), c.g(), c.b());
    if r == 0 && g == 0 && b == 0 {
        return Color32::from_rgb(i, i, i);
    }
    let up = |v: u8| {
        let v = if v > 0 && v < i { i } else { v };
        ((v as f32 / 0.7) as u32).min(255) as u8
    };
    Color32::from_rgb(up(r), up(g), up(b))
}

fn rarity(it: &CaravanItem) -> usize {
    BAG_ORDER.iter().position(|b| *b == it.bag).unwrap_or(BAG_ORDER.len())
}

fn points(data: &GameData, w: &lg_app::server::ScoreWeights, it: &CaravanItem) -> f64 {
    lg_app::guild::score(data, w, it.item_id, &it.r#where, it.enchants)
}

/// Java `score`: a member's points: their tally, or the items listed if the server sends no tally.
pub fn score(data: &GameData, w: &lg_app::server::ScoreWeights, m: &CaravanMember) -> f64 {
    if !m.tally.is_empty() {
        return lg_app::guild::total(data, w, &m.tally);
    }
    let sum: f64 = m.items.iter().map(|it| points(data, w, it)).sum();
    (sum * 10.0 + 0.5).floor() / 10.0
}

/// Java `pile`: what a wagon carries, rarest first: every item once, with its copies.
fn pile(m: &CaravanMember) -> (Vec<&CaravanItem>, HashMap<i32, i32>) {
    let mut loot: Vec<&CaravanItem> = m.items.iter().collect();
    loot.sort_by_key(|it| rarity(it));
    let mut once: Vec<&CaravanItem> = Vec::new();
    let mut copies = HashMap::new();
    for it in loot {
        if !once.iter().any(|o| o.item_id == it.item_id) {
            once.push(it);
        }
        *copies.entry(it.item_id).or_insert(0) += 1;
    }
    (once, copies)
}

/// The caravan's coordinates (unzoomed, as Java draws them) on screen.
struct T {
    o: Pos2,
    start: f32,
    ground: f32,
    f: f32,
}

impl T {
    fn p(&self, x: f32, y: f32) -> Pos2 {
        pos2(self.o.x + self.start + (x - PAD) * self.f, self.o.y + self.ground + (y - self.ground) * self.f)
    }

    fn r(&self, x: f32, y: f32, w: f32, h: f32) -> Rect {
        Rect::from_min_max(self.p(x, y), self.p(x + w, y + h))
    }
}

/// A filled block with a dark pixel outline.
fn block(painter: &egui::Painter, t: &T, x: f32, y: f32, w: f32, h: f32, fill: Color32) {
    painter.rect_filled(t.r(x, y, w, h), 0.0, INK);
    painter.rect_filled(t.r(x + 3.0, y + 3.0, w - 6.0, h - 6.0), 0.0, fill);
}

/// What was drawn where: the wagons and walkers, the pile items.
#[derive(Default)]
struct Hits {
    members: Vec<(Rect, i64)>,
    items: Vec<(Rect, i32, f64, bool)>,
}

struct Scene<'a> {
    data: &'a GameData,
    weights: &'a lg_app::server::ScoreWeights,
    riders: Vec<&'a CaravanMember>,
    walkers: Vec<&'a CaravanMember>,
    phase: i64,
    picked: i64,
    me: i64,
    gold: Color32,
    gold_hover: Color32,
}

impl Scene<'_> {
    fn plate(&self, m: &CaravanMember) -> String {
        lg_app::guild::whole(score(self.data, self.weights, m))
    }

    fn walker_tag(&self, m: &CaravanMember) -> String {
        format!("{} · {}", m.ign, self.plate(m))
    }

    fn walker_width(&self, painter: &egui::Painter, m: &CaravanMember) -> f32 {
        WALKER_W.max(jtext::width(painter, &self.walker_tag(m), &text(17.0)) + 24.0)
    }

    fn content_width(&self, painter: &egui::Painter) -> f32 {
        let mut w = OX_W + self.riders.len() as f32 * (BAR + WAGON_W + STEP_W) + if self.walkers.is_empty() { 0.0 } else { 46.0 };
        for m in &self.walkers {
            w += self.walker_width(painter, m);
        }
        w
    }

    fn content_height(&self) -> f32 {
        let rows = self.riders.iter().map(|m| pile(m).0.len().div_ceil(PILE_COLS)).max().unwrap_or(0);
        150f32.max(32.0 + BED_H + rows as f32 * (ITEM + ITEM_GAP) + 12.0)
    }

    /// Java `fit`: enlarged up to MAX_FIT while it fits the width (with a margin) and the sky.
    fn fit(&self, painter: &egui::Painter, width: f32) -> f32 {
        let by_width = (width - 2.0 * FIT_MARGIN) / self.content_width(painter);
        let by_height = (HEIGHT - 34.0 - 16.0) / self.content_height();
        by_height.min(1f32.max(MAX_FIT.min(by_width.min(by_height))))
    }

    fn paint_ox(&self, p: &egui::Painter, t: &T, x: f32, ground: f32) {
        let half = (self.phase / 4 % 2) as f32;
        let y = ground - 96.0 - half * 2.0;
        let step = if self.phase / 4 % 2 == 0 { 3.0 } else { -3.0 };
        block(p, t, x + 22.0, y + 22.0, 76.0, 40.0, WOOD);
        block(p, t, x, y + 10.0, 30.0, 28.0, WOOD);
        p.rect_filled(t.r(x + 6.0, y + 18.0, 5.0, 5.0), 0.0, DARK);
        block(p, t, x + 4.0, y + 2.0, 6.0, 10.0, BONE);
        block(p, t, x + 20.0, y + 2.0, 6.0, 10.0, BONE);
        block(p, t, x + 30.0 + step, y + 62.0, 10.0, 30.0, WOOD_DARK);
        block(p, t, x + 80.0 - step, y + 62.0, 10.0, 30.0, WOOD_DARK);
        block(p, t, x + 38.0, y + 18.0, 40.0, 10.0, BLANKET);
        p.rect_filled(t.r(x + 98.0, y + 40.0, 22.0, 4.0), 0.0, INK);
    }

    fn wheel(&self, p: &egui::Painter, t: &T, cx: f32, cy: f32) {
        let c = t.p(cx, cy);
        p.circle_filled(c, 19.0 * t.f, WOOD_DARK);
        p.circle_stroke(c, 17.0 * t.f, Stroke::new(5.0 * t.f, INK));
        let a = -(self.phase as f64) * 0.18;
        for i in 0..2 {
            let s = a + f64::from(i) * std::f64::consts::FRAC_PI_2;
            let (dx, dy) = ((s.cos() * 14.0).round() as f32, (s.sin() * 14.0).round() as f32);
            p.line_segment([t.p(cx - dx, cy - dy), t.p(cx + dx, cy + dy)], Stroke::new(4.0 * t.f, INK));
        }
    }

    /// A faceted gem around (cx, cy), turned by angle.
    fn gem(p: &egui::Painter, t: &T, cx: f32, cy: f32, c: Color32, angle: f32) {
        let r = 5.0;
        let rot = |x: f32, y: f32| {
            let (s, co) = angle.sin_cos();
            t.p(cx + x * co - y * s, cy + x * s + y * co)
        };
        let d = vec![rot(0.0, -r), rot(r, 0.0), rot(0.0, r), rot(-r, 0.0)];
        p.add(egui::Shape::convex_polygon(d.clone(), c, Stroke::NONE));
        p.add(egui::Shape::convex_polygon(vec![rot(0.0, 0.0), rot(r, 0.0), rot(0.0, r)], darker(c), Stroke::NONE));
        p.add(egui::Shape::closed_line(d, Stroke::new(t.f, INK)));
        let shine = rot(-2.0, -3.0);
        p.rect_filled(Rect::from_min_size(shine, vec2(2.0 * t.f, 2.0 * t.f)), 0.0, brighter(brighter(c)));
    }

    /// Java `paintPile`: gems heaped on (cx, bottom), rows narrowing upwards, each turned and nudged a little.
    fn gem_pile(p: &egui::Painter, t: &T, gems: &[usize], seed: i64, cx: f32, bottom: f32, width: f32) {
        let mut rnd = lg_app::java::JavaRandom::new(seed.wrapping_mul(31).wrapping_add(gems.len() as i64));
        let step = 11.0 - 4.0;
        let per_row = 1.max((width / step) as usize);
        let (mut row, mut i) = (0usize, 0usize);
        let mut placed = Vec::new();
        while i < gems.len() {
            let in_row = 1.max(per_row.saturating_sub(row)).min(gems.len() - i);
            let row_w = (in_row - 1) as f32 * step;
            for k in 0..in_row {
                let gx = (cx - (row_w / 2.0).floor() + k as f32 * step) as i32 + rnd.next_int(3) - 1;
                let gy = (bottom - 5.0 - row as f32 * 6.0) as i32 + rnd.next_int(3) - 1;
                let a = rnd.next_int(360);
                placed.push((gx as f32, gy as f32, gems[i], a));
                i += 1;
            }
            row += 1;
        }
        for (x, y, r, a) in placed {
            Scene::gem(p, t, x, y, STONE[r], (a as f32).to_radians());
        }
    }

    /// Java `paintStep` and `paintHoard`: the step at the back with a stone per enchanted item (a chest when many).
    fn step(&self, p: &egui::Painter, t: &T, m: &CaravanMember, x: f32, y: f32) {
        let mut stones: Vec<usize> = m.items.iter().filter(|it| it.enchants > 0).map(|it| it.enchants.min(4) as usize).collect();
        stones.sort_unstable();
        block(p, t, x, y, STEP_W, 7.0, WOOD);
        p.rect_filled(t.r(x + 2.0, y + 6.0, 3.0, 8.0), 0.0, INK);
        p.rect_filled(t.r(x + STEP_W - 5.0, y + 6.0, 3.0, 8.0), 0.0, INK);
        if stones.is_empty() {
            return;
        }
        let cx = x + (STEP_W / 2.0).floor();
        let per = if stones.len() <= 12 {
            1
        } else if stones.len() <= 50 {
            4
        } else {
            10
        };
        let mut count = [0usize; 5];
        for s in &stones {
            count[*s] += 1;
        }
        let mut shown = Vec::new();
        for (r, n) in count.iter().enumerate().skip(1) {
            let k = if *n == 0 { 0 } else { 1.max(n / per) };
            shown.extend(std::iter::repeat_n(r, k));
        }
        if per == 1 {
            return Scene::gem_pile(p, t, &shown, m.id, cx, y, 40.0);
        }
        let gold = per == 10;
        let (body, band, lock) = if gold { (GOLD_BODY, GOLD_BAND, BONE) } else { (WOOD, INK, GOLD_BODY) };
        let (w, h) = (38.0, 20.0);
        let (bx, rim) = (cx - w / 2.0, y - h);
        block(p, t, bx + 2.0, rim - 15.0, w - 4.0, 15.0, if gold { GOLD_BAND } else { WOOD_DARK });
        p.rect_filled(t.r(bx + 8.0, rim - 12.0, 3.0, 12.0), 0.0, band);
        p.rect_filled(t.r(bx + w - 11.0, rim - 12.0, 3.0, 12.0), 0.0, band);
        p.rect_filled(t.r(bx + 3.0, rim - 2.0, w - 6.0, 6.0), 0.0, rgb(0x1E140A));
        Scene::gem_pile(p, t, &shown, m.id, cx, rim + 3.0, 30.0);
        block(p, t, bx, rim, w, h, body);
        p.rect_filled(t.r(bx + 8.0, rim + 3.0, 3.0, h - 6.0), 0.0, band);
        p.rect_filled(t.r(bx + w - 11.0, rim + 3.0, 3.0, h - 6.0), 0.0, band);
        p.rect_filled(t.r(bx + 3.0, rim + 3.0, w - 6.0, 2.0), 0.0, band);
        p.rect_filled(t.r(cx - 4.0, rim + 4.0, 8.0, 9.0), 0.0, INK);
        p.rect_filled(t.r(cx - 2.0, rim + 6.0, 4.0, 5.0), 0.0, lock);
    }

    /// The member's most played character, facing left.
    fn driver(&self, ui: &Ui, icons: &mut crate::icons::Icons, p: &egui::Painter, m: &CaravanMember, r: Rect, tint: Color32) -> bool {
        let Some(c) = &m.character else { return false };
        let skin = if c.skin > 0 { c.skin } else { c.class_type };
        let Some(tex) = icons.skin(ui.ctx(), self.data, skin, c.tex1, c.tex2, DRIVER as u32) else { return false };
        p.image(tex.id(), r, Rect::from_min_max(pos2(1.0, 0.0), pos2(0.0, 1.0)), tint);
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn wagon(
        &self,
        ui: &Ui,
        icons: &mut crate::icons::Icons,
        p: &egui::Painter,
        t: &T,
        m: &CaravanMember,
        index: usize,
        x: f32,
        ground: f32,
        plate_w: f32,
        hits: &mut Hits,
    ) {
        let bed_y = ground - 32.0 - BED_H;
        let bob = (2.0 * (self.phase as f64 * 0.25 + index as f64).sin()).round() as f32;
        let loot_bob = (2.0 * (self.phase as f64 * 0.06 + index as f64).sin()).round() as f32;
        let mine = m.id == self.me;
        let (mut pile_items, copies) = pile(m);
        pile_items.reverse(); // drawn from the bottom up
        let pile_x = x + 52.0;
        let pile_w = PILE_COLS as f32 * (ITEM + ITEM_GAP);
        let mut top = bed_y;
        for (i, it) in pile_items.iter().enumerate() {
            let (row, col) = (i / PILE_COLS, i % PILE_COLS);
            let in_row = PILE_COLS.min(pile_items.len() - row * PILE_COLS);
            let row_x = pile_x + ((pile_w - in_row as f32 * (ITEM + ITEM_GAP)) / 2.0).floor();
            let ix = row_x + col as f32 * (ITEM + ITEM_GAP);
            let iy = bed_y - (row as f32 + 1.0) * (ITEM + ITEM_GAP) + loot_bob;
            top = top.min(iy);
            let ir = t.r(ix, iy, ITEM, ITEM);
            hits.items.push((ir, it.item_id, points(self.data, self.weights, it), mine));
            if let Some(tex) = icons.object(ui.ctx(), self.data, it.item_id, ITEM as u32) {
                p.image(tex.id(), ir, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
            }
            let c = copies.get(&it.item_id).copied().unwrap_or(1);
            if c > 1 {
                let f = text(13.0 * t.f);
                let at = t.p(ix + ITEM + 2.0, iy + ITEM + 4.0);
                for dx in -1..=1 {
                    for dy in -1..=1 {
                        jtext::draw(p, at + vec2(dx as f32, dy as f32), Align2::RIGHT_BOTTOM, &c.to_string(), &f, INK);
                    }
                }
                jtext::draw(p, at, Align2::RIGHT_BOTTOM, &c.to_string(), &f, Color32::WHITE);
            }
        }
        self.step(p, t, m, x + WAGON_W, bed_y + BED_H - 4.0);
        self.driver(ui, icons, p, m, t.r(x - 4.0, bed_y - DRIVER + 14.0 + bob, DRIVER, DRIVER), Color32::WHITE);
        // the bed with the score plate and the name
        block(p, t, x, bed_y, WAGON_W, BED_H, WOOD);
        p.rect_filled(t.r(x + 3.0, bed_y + BED_H - 9.0, WAGON_W - 6.0, 6.0), 0.0, WOOD_DARK);
        let pf = text(19.0);
        let sf = text(19.0 * t.f);
        let wid = |s: &str| jtext::width(p, s, &pf);
        let rank = self.plate(m);
        let plate = match index {
            0 => self.gold,
            1 => FLAG_2,
            2 => FLAG_3,
            _ => BONE,
        };
        let rw = plate_w.max(wid(&rank) + 10.0);
        p.rect_filled(t.r(x + 6.0, bed_y + 5.0, rw, 21.0), 0.0, plate);
        p.rect_stroke(t.r(x + 6.0, bed_y + 5.0, rw - 1.0, 20.0), 0.0, Stroke::new(t.f, INK), StrokeKind::Inside);
        jtext::draw(p, t.p(x + 6.0 + rw / 2.0, bed_y + 15.5), Align2::CENTER_CENTER, &rank, &sf, DARK);
        let free = WAGON_W - rw - 20.0;
        let mut name: Vec<char> = m.ign.chars().collect();
        while wid(&name.iter().collect::<String>()) > free && name.len() > 3 {
            name.pop();
        }
        let name: String = name.into_iter().collect();
        jtext::draw(p, t.p(x + rw + 10.0 + free / 2.0, bed_y + 15.5), Align2::CENTER_CENTER, &name, &sf, DARK);
        if m.id == self.picked {
            p.rect_stroke(
                t.r(x - 3.0, bed_y - 3.0, WAGON_W + 5.0, BED_H + 5.0),
                0.0,
                Stroke::new(3.0 * t.f, self.gold_hover),
                StrokeKind::Middle,
            );
        }
        self.wheel(p, t, x + 15.0 + 19.0, ground - 19.0);
        self.wheel(p, t, x + WAGON_W - 15.0 - 19.0, ground - 19.0);
        hits.members.push((t.r(x, top, WAGON_W, ground - top), m.id));
    }

    #[allow(clippy::too_many_arguments)]
    fn walker(
        &self,
        ui: &Ui,
        icons: &mut crate::icons::Icons,
        p: &egui::Painter,
        t: &T,
        m: &CaravanMember,
        index: usize,
        x: f32,
        slot: f32,
        ground: f32,
        hits: &mut Hits,
    ) {
        let step = ((self.phase / 4 + index as i64) % 2) as f32 * 2.0;
        let top = ground - DRIVER + 2.0 - step;
        let fx = x + ((slot - DRIVER) / 2.0).floor();
        block(p, t, fx + 40.0, top + 22.0, 18.0, 24.0, WOOD);
        p.rect_filled(t.r(fx + 44.0, top + 38.0, 10.0, 6.0), 0.0, WOOD_DARK);
        if !self.driver(ui, icons, p, m, t.r(fx, top, DRIVER, DRIVER), Color32::WHITE) {
            block(p, t, fx + 12.0, top + 10.0, 24.0, 34.0, fixed::MUTED);
        }
        let tag = self.walker_tag(m);
        let tw = jtext::width(p, &tag, &text(17.0)) + 10.0;
        let (tx, ty) = (x + ((slot - tw) / 2.0).floor(), ground - 2.0);
        p.rect_filled(t.r(tx, ty, tw, 20.0), 0.0, TAG);
        let edge = if m.id == self.picked { self.gold_hover } else { ROAD_EDGE };
        p.rect_stroke(t.r(tx, ty, tw - 1.0, 19.0), 0.0, Stroke::new(t.f, edge), StrokeKind::Inside);
        jtext::draw(p, t.p(tx + 5.0, ty + 10.0), Align2::LEFT_CENTER, &tag, &text(17.0 * t.f), FLAG_2);
        hits.members.push((t.r(x, top, slot, ground + 20.0 - top), m.id));
    }
}

/// "12 min ago" from a find's local time.
fn ago(zone: &lg_app::localtime::Zone, now: i64, time: &str) -> String {
    zone.parse_local(time).map_or(time.to_string(), |t| crate::profile::ago(now, t))
}

impl CaravanView {
    /// The page: the road in its frame, the status line, the picked member's card and best finds.
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let weights = &app.server.weights;
        let guild = &app.server.guild;
        let online = app.server.user.is_some() && app.server.state == lg_app::server::State::Online;
        let zoom = *self.zoom.get_or_insert_with(|| {
            c.props.get("caravanZoom").and_then(|v| v.trim().parse::<f64>().ok()).map_or(1.0, |z| z.clamp(MIN_ZOOM, MAX_ZOOM))
        });
        // the minute's check while on screen, and a reload once your drops reached the server
        if online && (c.now - self.asked_at >= POLL_MS || guild.caravan_stale) {
            if self.asked_at != 0 || guild.caravan_stale {
                c.asks.push(Ask::Server(ServerAsk::CaravanLoad));
            }
            self.asked_at = c.now;
        }
        let (caravan, message) = match &guild.caravan {
            _ if !online => (None, "Log in to a server in the Server tab to see your guild.".to_string()),
            None => (None, "Open this tab while logged in to a server to see your guild.".to_string()),
            Some(Err(e)) => (None, crate::guild::load_error_text(&app.profile.guild, e)),
            Some(Ok(c)) => (Some(c), String::new()),
        };
        // highest score first, then most items, then by name
        let mut members: Vec<&CaravanMember> = caravan.map(|c| c.members.iter().collect()).unwrap_or_default();
        let scores: HashMap<i64, f64> = members.iter().map(|m| (m.id, score(data, weights, m))).collect();
        members.sort_by(|a, b| {
            scores[&b.id]
                .total_cmp(&scores[&a.id])
                .then(b.total.cmp(&a.total))
                .then_with(|| a.ign.to_lowercase().cmp(&b.ign.to_lowercase()))
        });
        if !members.iter().any(|m| m.id == self.picked) {
            self.picked = members.first().map_or(-1, |m| m.id);
        }
        let me = app.server.user.as_ref().map_or(-1, |u| u.id);
        let title = caravan.map_or("Loot Caravan".to_string(), |c| format!("Loot Caravan - {}", c.guild));
        let refresh = std::cell::Cell::new(false);
        let mut picked = self.picked;
        let mut new_zoom = None;
        let icons = &mut *c.icons;
        let mut open_item = None;
        let now = c.now;
        widgets::section(
            ui,
            &p,
            body,
            &title,
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter();
                let f = text(18.0);
                let tx =
                    head.left() + 10.0 - 1.0 + 8.0 + 8.0 + jtext::width(painter, &title.to_uppercase(), &crate::theme::head(17.0)) + 12.0;
                jtext::draw(painter, pos2(tx, head.center().y + 1.0), Align2::LEFT_CENTER, "Ctrl + mouse wheel zooms", &f, fixed::MUTED);
                let w = jtext::width(painter, "Refresh", &widgets::small()) + 40.0;
                let r = Rect::from_min_size(pos2(head.right() - 5.0 - w, head.top()), vec2(w, head.height()));
                painter.rect(r, 0.0, p.header, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
                jtext::draw(painter, r.center(), Align2::CENTER_CENTER, "Refresh", &widgets::small(), p.ink);
                if ui.interact(r, ui.scope_id().with("caravan-refresh"), Sense::click()).clicked() {
                    refresh.set(true);
                }
            },
            |ui, r| {
                let f = text(18.0);
                let mut outer = ui.new_child(egui::UiBuilder::new().max_rect(r));
                egui::ScrollArea::vertical().id_salt("caravan-page").show(&mut outer, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let x = top.x + 12.0;
                    let w = r.width() - 24.0 - 8.0;
                    let mut y = top.y + 12.0;
                    let riders: Vec<&CaravanMember> = members.iter().copied().filter(|m| m.total >= WALK_BELOW).collect();
                    let walkers: Vec<&CaravanMember> = members.iter().copied().filter(|m| m.total < WALK_BELOW).collect();
                    let scene =
                        Scene { data, weights, riders, walkers, phase: now / 70, picked, me, gold: p.gold, gold_hover: p.gold_hover };
                    let view_w = w - 4.0;
                    let has = !scene.riders.is_empty() || !scene.walkers.is_empty();
                    let f_scale = if has { scene.fit(&painter, view_w) * zoom as f32 } else { 1.0 };
                    let scene_h = if has { HEIGHT.max((scene.content_height() * f_scale).ceil() + 34.0 + 20.0) } else { HEIGHT };
                    let content_w = if has { 400f32.max(PAD + (scene.content_width(&painter) * f_scale).ceil() + PAD) } else { 400.0 };
                    let frame = Rect::from_min_size(pos2(x, y), vec2(w, scene_h + 18.0 + 4.0));
                    painter.rect_stroke(frame, 0.0, Stroke::new(2.0, p.line), StrokeKind::Inside);
                    let vp = Rect::from_min_size(frame.min + vec2(2.0, 2.0), vec2(view_w, scene_h + 18.0));
                    let mut road = ui.new_child(egui::UiBuilder::new().max_rect(vp));
                    let mut hits = Hits::default();
                    egui::ScrollArea::horizontal().id_salt("caravan-road").show(&mut road, |ui| {
                        let sw = content_w.max(view_w);
                        let (sr, _) = ui.allocate_exact_size(vec2(sw, scene_h), Sense::hover());
                        let p2 = ui.painter().with_clip_rect(sr.intersect(ui.clip_rect()));
                        let extra = scene_h - HEIGHT;
                        if extra > 0.0 {
                            p2.rect_filled(Rect::from_min_size(sr.min, vec2(sw, extra)), 0.0, SKY);
                        }
                        crate::guild::backdrop(&p2, Rect::from_min_size(sr.min + vec2(0.0, extra), vec2(sw, HEIGHT)), scene.phase);
                        if !has {
                            return;
                        }
                        let ground = HEIGHT - 34.0;
                        let drawn = scene.content_width(&p2) * f_scale;
                        let start = if drawn + 2.0 * PAD > sw { PAD } else { (sw - drawn) / 2.0 };
                        let t = T { o: sr.min + vec2(0.0, extra), start, ground, f: f_scale };
                        let mut xx = PAD;
                        scene.paint_ox(&p2, &t, xx, ground);
                        xx += OX_W;
                        let plate_w =
                            scene.riders.iter().map(|m| jtext::width(&p2, &scene.plate(m), &text(19.0)) + 10.0).fold(0.0, f32::max);
                        for (i, m) in scene.riders.iter().enumerate() {
                            let bar = if i == 0 { BAR } else { STEP_W + BAR };
                            p2.rect_filled(t.r(xx, ground - 40.0, bar, 4.0), 0.0, INK);
                            xx += bar;
                            scene.wagon(ui, icons, &p2, &t, m, i, xx, ground, plate_w, &mut hits);
                            xx += WAGON_W;
                        }
                        if !scene.walkers.is_empty() {
                            xx += 46.0;
                        }
                        for (i, m) in scene.walkers.iter().enumerate() {
                            let slot = scene.walker_width(&p2, m);
                            scene.walker(ui, icons, &p2, &t, m, i, xx, slot, ground, &mut hits);
                            xx += slot;
                        }
                        // clicks and tips: a pile item first, then a member
                        let resp = ui.interact(sr, ui.scope_id().with("caravan-scene"), Sense::click());
                        if let Some(pos) = resp.hover_pos() {
                            let zd = ui.input(|i| if i.modifiers.ctrl || i.modifiers.command { i.zoom_delta() } else { 1.0 });
                            if (zd - 1.0).abs() > 1e-3 {
                                new_zoom = Some((zoom * f64::from(zd)).clamp(MIN_ZOOM, MAX_ZOOM));
                            }
                            if let Some((_, id, pts, mine)) = hits.items.iter().find(|h| h.0.contains(pos)) {
                                let name =
                                    data.items.item(*id).filter(|i| !i.name.is_empty()).map_or(format!("Item {id}"), |i| i.name.clone());
                                let tip = format!(
                                    "{name}  ·  {} pts{}",
                                    lg_app::guild::format(*pts),
                                    if *mine { "  (click: show in Inventory)" } else { "" }
                                );
                                let resp = resp.clone().on_hover_text(tip);
                                if *mine {
                                    ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                                    if resp.clicked() {
                                        open_item = Some(*id);
                                    }
                                }
                            } else if let Some((_, id)) = hits.members.iter().find(|h| h.0.contains(pos))
                                && let Some(m) = members.iter().find(|m| m.id == *id)
                            {
                                let cls = m.character.as_ref().map_or(String::new(), |c| format!(" ({})", c.class_name));
                                let n = if m.total == 1 { "item" } else { "items" };
                                let tip = format!("{}{cls}: {} points, {} {n}", m.ign, lg_app::guild::whole(scores[&m.id]), m.total);
                                ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                                if resp.clone().on_hover_text(tip).clicked() {
                                    picked = m.id;
                                }
                            }
                        }
                    });
                    y = frame.bottom() + 8.0;
                    // the status line
                    let status = match caravan {
                        None => message.clone(),
                        Some(cv) => {
                            let found: i64 = cv.members.iter().map(|m| m.total).sum();
                            let mine: i64 = cv.members.iter().filter(|m| m.id == me).map(|m| m.total).sum();
                            format!(
                                "{} items  ·  you: {}  ·  click a member for details",
                                crate::profile::num(found),
                                crate::profile::num(mine)
                            )
                        }
                    };
                    let fh = jtext::height(&painter, &f);
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, &status, &f, fixed::MUTED);
                    y += fh + 12.0;
                    // the picked member: their card and their best finds, side by side
                    if let Some(m) = members.iter().find(|m| m.id == picked) {
                        let cw = (w - 16.0) / 2.0;
                        let place = members.iter().position(|x| x.id == m.id).map_or(0, |i| i + 1);
                        let h = self_card_height(&painter);
                        let left = Rect::from_min_size(pos2(x, y), vec2(cw, h));
                        let right = Rect::from_min_size(pos2(x + cw + 16.0, y), vec2(cw, h));
                        for r in [left, right] {
                            painter.rect_filled(r, 0.0, p.paper);
                            painter.rect_stroke(r, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                            painter.rect_stroke(r.shrink(1.0), 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                        }
                        // the card
                        let (cx, mut cy) = (left.left() + 18.0, left.top() + 14.0);
                        let small = text(16.0);
                        let n = if m.total == 1 { "ITEM" } else { "ITEMS" };
                        let wh = if m.total >= WALK_BELOW { format!("WAGON #{place}") } else { "ON FOOT".into() };
                        jtext::draw(
                            &painter,
                            pos2(cx, cy),
                            Align2::LEFT_TOP,
                            &format!("{wh}  ·  {} {n}  ·  ALL TIME", crate::profile::num(m.total)),
                            &small,
                            fixed::MUTED,
                        );
                        cy += jtext::height(&painter, &small);
                        let big = crate::theme::head(44.0);
                        jtext::draw(&painter, pos2(cx, cy), Align2::LEFT_TOP, &m.ign, &big, p.gold);
                        cy += jtext::height(&painter, &big);
                        let h30 = crate::theme::head(30.0);
                        let pr = jtext::draw(
                            &painter,
                            pos2(cx, cy),
                            Align2::LEFT_TOP,
                            &format!("{} points", lg_app::guild::whole(scores[&m.id])),
                            &h30,
                            p.ink,
                        );
                        widgets::tip(ui, pr, "caravan-points", "The points of all items found");
                        cy += jtext::height(&painter, &h30);
                        let played = m.character.as_ref().map_or("Most played: unknown".to_string(), |ch| {
                            format!("Most played: {}  ·  {} fame", ch.class_name, crate::profile::num(ch.fame))
                        });
                        jtext::draw(&painter, pos2(cx, cy), Align2::LEFT_TOP, &played, &f, p.ink);
                        cy += fh;
                        let last =
                            m.last_drop.as_ref().map_or("No drops yet".to_string(), |t| format!("Last drop {}", ago(&app.zone, now, t)));
                        jtext::draw(&painter, pos2(cx, cy), Align2::LEFT_TOP, &last, &f, fixed::MUTED);
                        cy += fh + 10.0;
                        let mut bx = cx;
                        let mut any = false;
                        for bag in BAG_ORDER {
                            let Some((_, n)) = m.by_bag.iter().find(|(b, n)| b == bag && *n > 0) else { continue };
                            any = true;
                            let ir = Rect::from_min_size(pos2(bx, cy + 2.0), vec2(28.0, 28.0));
                            if let Some(tex) = crate::profile::bag_id(bag).and_then(|id| icons.object(ui.ctx(), data, id, 28)) {
                                painter.image(tex.id(), ir, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
                            }
                            let tr = jtext::draw(
                                &painter,
                                pos2(ir.right() + 4.0, ir.center().y),
                                Align2::LEFT_CENTER,
                                &crate::profile::num(*n),
                                &h30,
                                p.ink,
                            );
                            let what = if *n == 1 { "item" } else { "items" };
                            widgets::tip(ui, ir.union(tr), ("caravan-bag", bag), &format!("{n} {what} from {} bags", bag.to_lowercase()));
                            bx = tr.right() + 18.0;
                        }
                        if !any {
                            jtext::draw(&painter, pos2(cx, cy), Align2::LEFT_TOP, "No items yet.", &f, fixed::MUTED);
                        }
                        // the best finds: the most points, newest first among equals
                        let (fx, mut fy) = (right.left() + 18.0, right.top() + 14.0);
                        jtext::draw(&painter, pos2(fx, fy), Align2::LEFT_TOP, "Best finds", &h30, p.gold);
                        fy += jtext::height(&painter, &h30) + 4.0;
                        let mut best: Vec<&CaravanItem> = m.items.iter().collect();
                        best.sort_by(|a, b| points(data, weights, b).total_cmp(&points(data, weights, a)));
                        if best.is_empty() {
                            jtext::draw(&painter, pos2(fx, fy), Align2::LEFT_TOP, "Nothing found yet.", &f, fixed::MUTED);
                        }
                        let mine = m.id == me;
                        for (k, it) in best.iter().take(4).enumerate() {
                            let ir = Rect::from_min_size(pos2(fx, fy), vec2(36.0, 36.0));
                            if let Some(tex) = icons.object(ui.ctx(), data, it.item_id, 36) {
                                painter.image(tex.id(), ir, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
                            }
                            let name = data
                                .items
                                .item(it.item_id)
                                .filter(|i| !i.name.is_empty())
                                .map_or(format!("Item {}", it.item_id), |i| i.name.clone());
                            let nr = jtext::draw(&painter, pos2(ir.right() + 12.0, ir.center().y), Align2::LEFT_CENTER, &name, &f, p.ink);
                            fy += 36.0;
                            let pts = format!("{} pts", lg_app::guild::format(points(data, weights, it)));
                            let place = if it.r#where.trim().is_empty() { String::new() } else { format!("  ·  {}", it.r#where) };
                            let pr = jtext::draw(&painter, pos2(fx + 48.0, fy), Align2::LEFT_TOP, &pts, &f, fixed::MUTED);
                            let rest = format!("  ·  {} bag{place}  ·  {}", it.bag, ago(&app.zone, now, &it.time));
                            let rr = jtext::draw(&painter, pos2(pr.right(), fy), Align2::LEFT_TOP, &rest, &f, fixed::MUTED);
                            let tip: String = lg_app::guild::breakdown(data, it.item_id, &it.r#where, it.enchants)
                                .iter()
                                .map(|(k, v)| format!("{k}  {v}"))
                                .collect::<Vec<_>>()
                                .join("\n");
                            widgets::tip(ui, pr, ("best-pts", k), &tip);
                            if mine {
                                // click: the item in Inventory
                                let zone = ir.union(nr).union(rr);
                                let resp = ui.interact(zone, ui.scope_id().with(("best", k)), Sense::click());
                                if resp.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                                    open_item = Some(it.item_id);
                                }
                            }
                            fy += fh + 6.0;
                        }
                        y += h;
                    }
                    ui.allocate_exact_size(vec2(w, y + 12.0 - top.y), Sense::hover());
                });
            },
        );
        self.picked = picked;
        if refresh.get() {
            c.asks.push(Ask::Server(ServerAsk::CaravanLoad));
        }
        if let Some(z) = new_zoom {
            self.zoom = Some(z);
            c.asks.push(Ask::Set("caravanZoom".into(), format!("{z:.3}")));
        }
        if let Some(id) = open_item {
            crate::window::open_later(ui.ctx(), format!("Inventory:item:{id}"));
        }
    }
}

/// The member card's height: the small line, the name, the points, two lines, the bag counts.
fn self_card_height(painter: &egui::Painter) -> f32 {
    let f = text(18.0);
    14.0 + jtext::height(painter, &text(16.0))
        + jtext::height(painter, &crate::theme::head(44.0))
        + jtext::height(painter, &crate::theme::head(30.0))
        + 2.0 * jtext::height(painter, &f)
        + 10.0
        + jtext::height(painter, &crate::theme::head(30.0)).max(32.0)
        + 14.0
}

#[cfg(test)]
mod tests {
    #[test]
    fn colours_as_java() {
        use super::*;
        assert_eq!(darker(rgb(0xF4E6C6)), Color32::from_rgb(170, 161, 138));
        assert_eq!(brighter(rgb(0x4CD15A)), Color32::from_rgb(108, 255, 128));
    }
}
