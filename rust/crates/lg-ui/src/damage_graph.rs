//! Java `DamageGraph`: damage in a dungeon per 2 s slice, mirrored around one line: above it the damage you dealt
//! (gold), below it the damage you took (the bottom being your whole life). Slices taking 30% of your life or 300
//! are orange with the enemy behind them on the floor; boss fights framed in red with a lane under the time axis;
//! dangerous slices outside the window as teardrops at that edge. Shown at its latest end.

use crate::icons::Icons;
use crate::jtext;
use crate::theme::{Palette, fixed};
use crate::widgets::text;
use egui::{Align2, Color32, Painter, Pos2, Rect, Shape, Stroke, pos2, vec2};
use lg_app::dps_history::Run;
use lg_app::dungeon_visits::Visit;
use lg_gamedata::GameData;
use std::collections::HashMap;

pub const BIG_SHARE: f64 = 0.30;
pub const BIG_DAMAGE: i64 = 300;
pub const WINDOW_MS: i64 = 2000;
const HEADROOM: f64 = 1.08;
const PAD_LEFT: f32 = 44.0;
const PAD_RIGHT: f32 = 10.0;
const PAD_TOP: f32 = 28.0;
const PAD_BOTTOM: f32 = 20.0;
const ICON: f32 = 28.0;
const SLICE_PX: f32 = 14.0;
const BUBBLE: f32 = 13.0;
const BUBBLE_ICON: f32 = 18.0;
pub const HEIGHT: f32 = 230.0;
const LANE: f32 = 30.0;
const LANE_BAR: f32 = 6.0;
const LANE_ICON: f32 = 22.0;
const JOIN_MS: i64 = 3000;
const BAR: Color32 = Color32::from_rgb(0xE5, 0x63, 0x6E);
pub const BIG: Color32 = Color32::from_rgb(0xFF, 0x8A, 0x2A);
const DEALT: Color32 = Color32::from_rgb(0xE8, 0xB8, 0x3A);
const AMULET: Color32 = Color32::from_rgb(0x3A, 0x9B, 0xFF);
const FIGHT: Color32 = Color32::from_rgb(190, 51, 45);
const BUBBLE_LINE: Color32 = Color32::from_rgb(0xC9, 0xC2, 0xB4);

/// One boss fight: its boss, from its first to its last hit (ms after entering).
#[derive(Clone, Debug)]
pub struct Fight {
    pub boss: String,
    pub object_type: i32,
    pub from: i64,
    pub to: i64,
}

/// The visit's boss fights in the order they began, same-named ones close together joined (Java `fights`).
pub fn fights(v: &Visit, run: Option<&Run>) -> Vec<Fight> {
    let Some(run) = run.filter(|r| !r.guild_hall && r.dungeon == v.dungeon) else { return Vec::new() };
    let mut all: Vec<Fight> = run
        .bosses
        .iter()
        .filter(|b| b.fight_start > 0 && b.fight_start >= v.start)
        .map(|b| {
            let from = b.fight_start - v.start;
            Fight { boss: b.name.clone().unwrap_or_default(), object_type: b.object_type, from, to: from + b.fight_ms.max(1) }
        })
        .collect();
    all.sort_by_key(|f| f.from);
    let mut out: Vec<Fight> = Vec::new();
    for f in all {
        match (0..out.len()).rev().find(|&i| out[i].boss == f.boss && f.from <= out[i].to + JOIN_MS) {
            Some(i) => out[i].to = out[i].to.max(f.to),
            None => out.push(f),
        }
    }
    out
}

/// Damage at which a slice counts as dangerous: 30% of your life or 300, whichever is less.
pub fn threshold(v: &Visit) -> i64 {
    if v.max_hp > 0 { BIG_DAMAGE.min((f64::from(v.max_hp) * BIG_SHARE).round() as i64) } else { BIG_DAMAGE }
}

fn duration(v: &Visit, now: i64) -> i64 {
    if v.duration_ms > 0 { v.duration_ms } else { now - v.start }
}

fn span(v: &Visit, now: i64) -> i64 {
    let last = v.hit_time.last().map_or(0, |t| i64::from(*t));
    1000.max(duration(v, now).max(last + 1))
}

/// The hits added up per slice: damage, and the enemy that dealt the most (enemies before ground before unknown).
pub struct Slices {
    pub damage: Vec<i64>,
    pub enemy: Vec<i32>,
}

fn source_rank(t: i32) -> i32 {
    if t > 0 {
        2
    } else if t < 0 {
        1
    } else {
        0
    }
}

pub fn slices(v: &Visit, window: i64, now: i64) -> Slices {
    let n = ((span(v, now) + window - 1) / window).max(1) as usize;
    let mut damage = vec![0i64; n];
    let mut enemy = vec![0i32; n];
    let mut enemy_damage = vec![0i64; n];
    // per slice and enemy in the order first hit (Java walks a HashMap; ties only matter for equal damage)
    let mut by: Vec<HashMap<i32, i64>> = vec![HashMap::new(); n];
    let mut order: Vec<Vec<i32>> = vec![Vec::new(); n];
    for i in 0..v.hit_time.len().min(v.hit_damage.len()).min(v.hit_enemy.len()) {
        let s = ((i64::from(v.hit_time[i]) / window) as usize).min(n - 1);
        damage[s] += i64::from(v.hit_damage[i]);
        let e = by[s].entry(v.hit_enemy[i]).or_insert_with(|| {
            order[s].push(v.hit_enemy[i]);
            0
        });
        *e += i64::from(v.hit_damage[i]);
    }
    for s in 0..n {
        for t in &order[s] {
            let d = by[s][t];
            let first = enemy[s] == 0 && enemy_damage[s] == 0;
            let (rank, was) = (source_rank(*t), source_rank(enemy[s]));
            if first || rank > was || (rank == was && rank > 0 && d > enemy_damage[s]) {
                enemy[s] = *t;
                enemy_damage[s] = d;
            }
        }
    }
    Slices { damage, enemy }
}

/// How many slices reach the danger mark.
pub fn dangerous(v: &Visit, window: i64, now: i64) -> usize {
    let t = threshold(v);
    slices(v, window, now).damage.iter().filter(|d| **d >= t).count()
}

pub fn dealt_slices(v: &Visit, window: i64) -> Vec<i64> {
    let per = (window / 1000).max(1) as usize;
    let mut out = vec![0i64; v.dealt.len().div_ceil(per)];
    for (s, d) in v.dealt.iter().enumerate() {
        out[s / per] += i64::from(*d);
    }
    out
}

/// The graph's height: the plot and the time axis, and the boss lane when a boss was fought.
pub fn height(fights: &[Fight]) -> f32 {
    HEIGHT + if fights.is_empty() { 0.0 } else { LANE }
}

fn grouped(n: i64) -> String {
    crate::profile::num(n)
}

/// Paints the graph into `r` (its full size, as Java lays it out) at its latest end; gives whether the scrollbar
/// shows and where its thumb is (left, width as fractions).
pub fn paint(
    painter: &Painter,
    ctx: &egui::Context,
    icons: &mut Icons,
    data: &GameData,
    p: &Palette,
    v: &Visit,
    fights: &[Fight],
    now: i64,
    r: Rect,
    view: Option<i64>,
) -> Option<(f32, f32)> {
    let window = WINDOW_MS;
    let (w, h) = (r.width(), r.height());
    let lane = if fights.is_empty() { 0.0 } else { LANE };
    let pad_bottom = PAD_BOTTOM + lane;
    let base = h - PAD_BOTTOM - lane;
    let fit = (((w - PAD_LEFT - PAD_RIGHT + SLICE_PX - 1.0) / SLICE_PX).floor()).max(1.0) as i64;
    let view_ms = fit * window;
    let total = (span(v, now) as f64 / 1000.0).ceil() as i64;
    let extent = view_ms / 1000;
    let max = total.max(extent);
    let view_start = start_of(view, total, extent);
    let at = |x: f32, y: f32| pos2(r.left() + x, r.top() + y);
    let x_of = |t: i64| PAD_LEFT + ((t - view_start) as f64 * f64::from(SLICE_PX) / window as f64).round() as f32;
    let small = text(16.0);
    let asc = (small.size * 1050.0 / 1350.0).ceil();
    let plot = Rect::from_min_max(at(PAD_LEFT, PAD_TOP), at(w - PAD_RIGHT, base));
    painter.rect_filled(plot, 0.0, Color32::from_black_alpha(60));
    // time along the bottom: every minute within the window
    let mut t = (view_start + 59_999) / 60_000 * 60_000;
    while t <= view_start + view_ms {
        let s = crate::battles::duration(t);
        let sw = jtext::width(painter, &s, &small);
        let x = (x_of(t) - (sw / 2.0).floor()).max(PAD_LEFT).min(w - PAD_RIGHT - sw);
        jtext::draw(painter, at(x, h - lane - 4.0 - asc), Align2::LEFT_TOP, &s, &small, fixed::MUTED);
        t += 60_000;
    }
    let dealt = dealt_slices(v, window);
    let dealt_peak = dealt.iter().copied().max().unwrap_or(0);
    if v.hit_time.is_empty() && dealt_peak == 0 {
        let s = "No damage taken or dealt";
        let sw = jtext::width(painter, s, &small);
        jtext::draw(
            painter,
            at(PAD_LEFT + ((w - PAD_LEFT - PAD_RIGHT - sw) / 2.0).floor(), ((PAD_TOP + base) / 2.0).floor() - asc),
            Align2::LEFT_TOP,
            s,
            &small,
            fixed::MUTED,
        );
        return None;
    }
    let mid = PAD_TOP + ((h - pad_bottom - PAD_TOP) * 2.0 / 5.0).floor();
    let bottom = h - pad_bottom;
    let up = |d: i64, scale: i64| mid - ((d as f64 / scale as f64).min(1.0) * f64::from(mid - PAD_TOP)).round() as f32;
    let down = |d: i64, scale: i64| mid + ((d as f64 / scale as f64).min(1.0) * f64::from(bottom - mid)).round() as f32;
    let sl = slices(v, window, now);
    let scale = if v.max_hp > 0 {
        i64::from(v.max_hp)
    } else {
        1.max((sl.damage.iter().copied().max().unwrap_or(0).max(1) as f64 * HEADROOM).round() as i64)
    };
    for val in [scale / 3, scale * 2 / 3, scale] {
        let s = grouped(val);
        let sw = jtext::width(painter, &s, &small);
        jtext::draw(
            painter,
            at(PAD_LEFT - 6.0 - sw, down(val, scale) + (asc / 2.0).floor() - 1.0 - asc),
            Align2::LEFT_TOP,
            &s,
            &small,
            fixed::MUTED,
        );
    }
    let dealt_scale = 1.max((dealt_peak as f64 * HEADROOM).round() as i64);
    if dealt_peak > 0 {
        let s = grouped(dealt_peak);
        let sw = jtext::width(painter, &s, &small);
        jtext::draw(
            painter,
            at(PAD_LEFT - 6.0 - sw, up(dealt_peak, dealt_scale) + (asc / 2.0).floor() - 1.0 - asc),
            Align2::LEFT_TOP,
            &s,
            &small,
            DEALT,
        );
    }
    let thr = threshold(v);
    let clip = painter.with_clip_rect(Rect::from_min_max(at(PAD_LEFT, 0.0), at(w - PAD_RIGHT, h)).intersect(painter.clip_rect()));
    for f in fights {
        let x0 = x_of(f.from);
        let x1 = (x0 + 1.0).max(x_of(f.to));
        clip.rect_filled(Rect::from_min_max(at(x0, PAD_TOP), at(x1, base)), 0.0, Color32::from_rgba_unmultiplied(190, 51, 45, 40));
        let line = Color32::from_rgba_unmultiplied(190, 51, 45, 170);
        clip.rect_filled(Rect::from_min_max(at(x0, PAD_TOP), at(x0 + 1.0, base)), 0.0, line);
        clip.rect_filled(Rect::from_min_max(at(x1, PAD_TOP), at(x1 + 1.0, base)), 0.0, line);
    }
    let first = view_start / window;
    let last = (view_start + view_ms) / window;
    let mut last_icon = -10_000.0f32;
    let mut floor: Vec<(Look, f32)> = Vec::new();
    let count = sl.damage.len().max(dealt.len()) as i64;
    for i in first.max(0)..=last.min(count - 1) {
        let x0 = x_of(i * window);
        let x1 = x_of((i + 1) * window);
        let bw = (x1 - x0 - if x1 - x0 >= 4.0 { 1.0 } else { 0.0 }).max(1.0);
        let iu = i as usize;
        if iu < dealt.len() && dealt[iu] > 0 {
            let y = up(dealt[iu], dealt_scale);
            clip.rect_filled(Rect::from_min_size(at(x0, y), vec2(bw, (mid - y).max(1.0))), 0.0, DEALT);
        }
        if iu >= sl.damage.len() || sl.damage[iu] == 0 {
            continue;
        }
        let y = down(sl.damage[iu], scale);
        let big = sl.damage[iu] >= thr;
        clip.rect_filled(Rect::from_min_size(at(x0, mid + 1.0), vec2(bw, (y - mid - 1.0).max(1.0))), 0.0, if big { BIG } else { BAR });
        if big && x0 - last_icon >= ICON {
            let t = i * window + window / 2;
            let during = fights.iter().find(|f| t >= f.from && t <= f.to);
            let ix = x0 + (bw / 2.0).floor() - ICON / 2.0;
            floor.push((enemy_look(ctx, icons, data, sl.enemy[iu], during, ICON as u32), ix));
            last_icon = x0;
        }
    }
    for t in &v.amulet_saves {
        let x = x_of(i64::from(*t));
        clip.rect_filled(Rect::from_min_max(at(x - 1.0, mid + 1.0), at(x + 1.0, base)), 0.0, AMULET);
    }
    if v.died_at >= 0 {
        let xd = x_of(i64::from(v.died_at));
        let xr = x_of(i64::from(v.died_at) - 10_000);
        clip.rect_filled(Rect::from_min_max(at(xr, PAD_TOP), at(xd, base)), 0.0, Color32::from_white_alpha(28));
        clip.rect_filled(Rect::from_min_max(at(xd - 1.0, PAD_TOP), at(xd + 1.0, base)), 0.0, Color32::WHITE);
    }
    let fy = base - ICON - 2.0;
    for (look, x) in &floor {
        draw_look(&clip, look, Rect::from_min_size(at(*x, fy), vec2(ICON, ICON)));
    }
    boss_lane(&clip, ctx, icons, data, p, fights, &x_of, r, h - lane, w - PAD_RIGHT, &small);
    painter.rect_filled(Rect::from_min_size(at(PAD_LEFT, mid), vec2(w - PAD_RIGHT - PAD_LEFT, 1.0)), 0.0, fixed::MUTED);
    // the teardrops: dangerous slices outside the window
    let (mut lt, mut rt, mut lc, mut rc) = (None, None, 0, 0);
    for (i, d) in sl.damage.iter().enumerate() {
        if *d < thr {
            continue;
        }
        if (i as i64) < first {
            lt = Some(i);
            lc += 1;
        } else if i as i64 > last {
            if rt.is_none() {
                rt = Some(i);
            }
            rc += 1;
        }
    }
    let cy = ((mid + base) / 2.0).floor();
    if let Some(i) = lt {
        teardrop(painter, ctx, icons, data, at(PAD_LEFT + 4.0, cy), true, sl.enemy[i], lc, &small);
    }
    if let Some(i) = rt {
        teardrop(painter, ctx, icons, data, at(w - PAD_RIGHT - 4.0, cy), false, sl.enemy[i], rc, &small);
    }
    (total > extent).then(|| ((view_start / 1000) as f32 / max as f32, extent as f32 / max as f32))
}

/// What stands for a hit's source (Java `enemyIcon`).
enum Look {
    Sprite(egui::TextureHandle),
    /// A damaging tile as the game draws it, in an orange frame.
    Tile(egui::TextureHandle),
    Ground,
    Warning,
}

/// The tile that hurt you, else the ground; the enemy, else the boss fought then, else a warning badge.
fn enemy_look(ctx: &egui::Context, icons: &mut Icons, data: &GameData, t: i32, during: Option<&Fight>, size: u32) -> Look {
    if t < 0
        && let Some(tex) = icons.tile(ctx, data, -t, size)
    {
        return Look::Tile(tex);
    }
    if t <= 0 {
        return Look::Ground;
    }
    match icons.enemy(ctx, data, t, size).or_else(|| during.and_then(|f| icons.enemy(ctx, data, f.object_type, size))) {
        Some(tex) => Look::Sprite(tex),
        None => Look::Warning,
    }
}

fn draw_look(painter: &Painter, look: &Look, r: Rect) {
    match look {
        Look::Sprite(t) => {
            painter.image(t.id(), r, crate::detail::uv(), Color32::WHITE);
        }
        Look::Tile(t) => {
            let size = r.width();
            let pad = (size / 10.0).floor().max(2.0);
            let side = size - 2.0 * pad;
            let tr = Rect::from_min_size(r.min + vec2(pad, size - pad - side), vec2(side, side));
            painter.image(t.id(), tr, crate::detail::uv(), Color32::WHITE);
            painter.rect_stroke(tr, 2.0, Stroke::new((size / 14.0).max(1.5), BIG), egui::StrokeKind::Middle);
        }
        Look::Ground => ground(painter, r),
        Look::Warning => warning(painter, r),
    }
}

/// Damaging ground: a dark floor tile with glowing cracks and heat rising off it (Java `groundIcon`).
fn ground(painter: &Painter, r: Rect) {
    let size = r.width();
    let pad = (size / 8.0).floor().max(2.0);
    let tile = size - 2.0 * pad;
    let top = size - pad - (tile / 2.0).floor();
    let o = r.min;
    painter.rect_filled(Rect::from_min_size(o + vec2(pad, top), vec2(tile, (tile / 2.0).floor())), 1.5, BIG);
    let stone = Color32::from_rgb(0x3A, 0x24, 0x18);
    let half = (tile / 2.0).floor();
    let gap = (size / 16.0).floor().max(1.0);
    let row = (tile / 4.0).floor();
    for (x, y, w, h) in [
        (pad + gap, top + gap, half - 2.0 * gap, row - gap),
        (pad + half + gap, top + gap, half - 2.0 * gap, row - gap),
        (pad + gap, top + row + gap, (tile / 3.0).floor() - gap, row - 2.0 * gap),
        (pad + (tile / 3.0).floor() + gap, top + row + gap, (tile * 2.0 / 3.0).floor() - 2.0 * gap, row - 2.0 * gap),
    ] {
        painter.rect_filled(Rect::from_min_size(o + vec2(x, y), vec2(w, h)), 0.0, stone);
    }
    let (wt, wb) = (pad, top - gap * 2.0);
    let stroke = Stroke::new((size / 14.0).max(1.5), BIG);
    for k in 0..3 {
        let x = pad + tile * (k as f32 + 1.0) / 4.0;
        let hh = wb - wt;
        let amp = size / 12.0;
        let pts = [o + vec2(x, wb), o + vec2(x - amp, wb - hh / 3.0), o + vec2(x + amp, wb - hh * 2.0 / 3.0), o + vec2(x, wt)];
        painter.add(egui::epaint::CubicBezierShape::from_points_stroke(pts, false, Color32::TRANSPARENT, stroke));
    }
}

/// A round "!" badge for a dangerous slice without a recognisable enemy.
fn warning(painter: &Painter, r: Rect) {
    let d = r.width() - 6.0;
    let c = r.min + vec2(3.0 + d / 2.0, 3.0 + d / 2.0);
    painter.circle(c, d / 2.0, Color32::from_black_alpha(170), Stroke::new(2.0, BIG));
    painter.text(c, Align2::CENTER_CENTER, "!", egui::FontId::proportional((r.width() / 2.0).max(10.0)), BIG);
}

fn boss_lane(
    painter: &Painter,
    ctx: &egui::Context,
    icons: &mut Icons,
    data: &GameData,
    p: &Palette,
    fights: &[Fight],
    x_of: &dyn Fn(i64) -> f32,
    r: Rect,
    top: f32,
    right: f32,
    font: &egui::FontId,
) {
    let bar_y = top + ((LANE - LANE_BAR) / 2.0).floor() + 4.0;
    let at = |x: f32, y: f32| pos2(r.left() + x, r.top() + y);
    for f in fights {
        let x0 = x_of(f.from);
        let x1 = (x0 + 2.0).max(x_of(f.to));
        if x1 < PAD_LEFT || x0 > right {
            continue;
        }
        painter.rect_filled(Rect::from_min_max(at(x0, bar_y), at(x1, bar_y + LANE_BAR)), 2.0, FIGHT);
    }
    let asc = (font.size * 1050.0 / 1350.0).ceil();
    let mut taken = f32::MIN / 2.0;
    for f in fights {
        let x0 = x_of(f.from);
        let x1 = (x0 + 2.0).max(x_of(f.to));
        let (from, to) = (x0.max(PAD_LEFT), x1.min(right));
        if to <= from {
            continue;
        }
        let icon = icons.enemy(ctx, data, f.object_type, LANE_ICON as u32);
        let secs = (f.to - f.from) / 1000;
        let len = format!("({:02}:{:02})", secs / 60, secs % 60);
        let iw = if icon.is_some() { LANE_ICON } else { 0.0 };
        let gap = if icon.is_some() { 3.0 } else { 0.0 };
        let tw = jtext::width(painter, &len, font) + 6.0;
        let width = iw + gap + tw;
        let half = (width / 2.0).floor();
        let cx =
            if to - from >= width { (from + half).max((to - half).min(((x0 + x1) / 2.0).floor())) } else { ((from + to) / 2.0).floor() };
        let left = (cx - half).max(taken + 4.0);
        let cy = bar_y + LANE_BAR / 2.0;
        taken = left + width;
        if let Some(t) = icon {
            painter.image(
                t.id(),
                Rect::from_min_size(at(left, cy - LANE_ICON / 2.0), vec2(LANE_ICON, LANE_ICON)),
                crate::detail::uv(),
                Color32::WHITE,
            );
        }
        let tx = left + iw + gap;
        let th = asc + 2.0;
        painter.rect_filled(Rect::from_min_size(at(tx, cy - (th / 2.0).floor()), vec2(tw, th)), 3.0, Color32::from_black_alpha(170));
        jtext::draw(painter, at(tx + 3.0, cy + (asc / 2.0).floor() - 1.0 - asc), Align2::LEFT_TOP, &len, font, p.ink);
    }
}

fn teardrop(
    painter: &Painter,
    ctx: &egui::Context,
    icons: &mut Icons,
    data: &GameData,
    tip: Pos2,
    left: bool,
    enemy: i32,
    count: usize,
    font: &egui::FontId,
) {
    let r = BUBBLE;
    let cx = if left { tip.x + (r * 1.9).floor() } else { tip.x - (r * 1.9).floor() };
    let c = pos2(cx, tip.y);
    // the outline: the tip, then around the circle from one side's tangent point to the other
    let mut pts = vec![tip];
    let start = if left { 180.0f32 - 40.0 } else { 40.0 };
    let sweep = if left { -(360.0f32 - 80.0) } else { 360.0 - 80.0 };
    let n = 40;
    for k in 0..=n {
        let deg = (start + sweep * k as f32 / n as f32).to_radians();
        pts.push(pos2(cx + r * deg.cos(), tip.y - r * deg.sin()));
    }
    painter.add(Shape::convex_polygon(pts.clone(), Color32::from_black_alpha(170), Stroke::NONE));
    painter.add(Shape::closed_line(pts, Stroke::new(2.0, BUBBLE_LINE)));
    let ir = Rect::from_center_size(c, vec2(BUBBLE_ICON, BUBBLE_ICON));
    let look = enemy_look(ctx, icons, data, enemy, None, BUBBLE_ICON as u32);
    draw_look(&painter.with_clip_rect(ir.shrink(0.0).intersect(painter.clip_rect())), &look, ir);
    if count > 1 {
        let s = format!("\u{00D7}{count}");
        let asc = (font.size * 1050.0 / 1350.0).ceil();
        jtext::draw(painter, pos2(cx, tip.y + r + 1.0 + asc), Align2::CENTER_BOTTOM, &s, font, BUBBLE_LINE);
    }
}

/// Java `getToolTipText` of the graph painted in `r`, for the pointer at `pos`: a teardrop's dangerous moments,
/// a boss fight in the lane, your death, your amulet, else the number of the slice (dealt above the line, taken
/// below it).
pub fn tip(data: &GameData, v: &Visit, fights: &[Fight], now: i64, r: Rect, pos: Pos2, view: Option<i64>) -> Option<String> {
    let window = WINDOW_MS;
    let (w, h) = (r.width(), r.height());
    let lane = if fights.is_empty() { 0.0 } else { LANE };
    let pad_bottom = PAD_BOTTOM + lane;
    let base = h - PAD_BOTTOM - lane;
    let fit = (((w - PAD_LEFT - PAD_RIGHT + SLICE_PX - 1.0) / SLICE_PX).floor()).max(1.0) as i64;
    let view_ms = fit * window;
    let total = (span(v, now) as f64 / 1000.0).ceil() as i64;
    let extent = view_ms / 1000;
    let view_start = start_of(view, total, extent);
    let x_of = |t: i64| PAD_LEFT + ((t - view_start) as f64 * f64::from(SLICE_PX) / window as f64).round() as f32;
    let (x, y) = (pos.x - r.left(), pos.y - r.top());
    let mid = PAD_TOP + ((h - pad_bottom - PAD_TOP) * 2.0 / 5.0).floor();
    let sl = slices(v, window, now);
    // the teardrops at the edges
    let thr = threshold(v);
    let (first, last) = (view_start / window, (view_start + view_ms) / window);
    let (mut lt, mut rt, mut lc, mut rc) = (None, None, 0, 0);
    for (i, d) in sl.damage.iter().enumerate() {
        if *d < thr {
            continue;
        }
        if (i as i64) < first {
            lt = Some(i);
            lc += 1;
        } else if i as i64 > last {
            rt.get_or_insert(i);
            rc += 1;
        }
    }
    let cy = ((mid + base) / 2.0).floor();
    let reach = (BUBBLE * 1.9).floor() + BUBBLE;
    let bubble = |target: usize, count: usize, side: &str| {
        let t = sl.enemy[target];
        let who = if t == 0 { "the ground or area effects".to_string() } else { crate::battles::hit_source(data, t) };
        let n = if count == 1 { "A dangerous moment".to_string() } else { format!("{count} dangerous moments") };
        format!("{n} to the {side}\nnearest: {}, {who}\nclick to scroll there", crate::battles::duration(target as i64 * window))
    };
    if let Some(i) = lt
        && x >= PAD_LEFT + 4.0
        && x <= PAD_LEFT + 4.0 + reach
        && (y - cy).abs() <= BUBBLE
    {
        return Some(bubble(i, lc, "left"));
    }
    if let Some(i) = rt
        && x <= w - PAD_RIGHT - 4.0
        && x >= w - PAD_RIGHT - 4.0 - reach
        && (y - cy).abs() <= BUBBLE
    {
        return Some(bubble(i, rc, "right"));
    }
    if x < PAD_LEFT {
        return None;
    }
    if !fights.is_empty() && y >= h - LANE {
        let f = fights.iter().find(|f| x >= x_of(f.from) - 4.0 && x <= x_of(f.to) + 4.0)?;
        let d = crate::battles::duration;
        return Some(format!("{}\n{} to {}  \u{00B7}  {}", f.boss, d(f.from), d(f.to), d(f.to - f.from)));
    }
    if v.died_at >= 0 && (x_of(i64::from(v.died_at)) - x).abs() <= 3.0 {
        let by = v.killed_by.as_ref().map_or(String::new(), |k| format!(" ({k})"));
        return Some(format!("{}:  you died{by}", crate::battles::duration(i64::from(v.died_at))));
    }
    if y > mid
        && let Some(t) = v.amulet_saves.iter().find(|t| (x_of(i64::from(**t)) - x).abs() <= 3.0)
    {
        return Some(format!("{}:  your Amulet of Restoration fired", crate::battles::duration(i64::from(*t))));
    }
    let i = ((view_start + ((x - PAD_LEFT) as f64 * window as f64 / f64::from(SLICE_PX)) as i64) / window) as usize;
    let value = if y < mid { dealt_slices(v, window).get(i).copied().unwrap_or(0) } else { sl.damage.get(i).copied().unwrap_or(0) };
    (value != 0).then(|| crate::profile::num(value))
}

/// A tooltip at the pointer, at once (the graph's: Java sets the delay to 0 over it).
pub fn show_tip(ctx: &egui::Context, p: &Palette, pos: Pos2, t: &str) {
    let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Tooltip, egui::Id::unique("graph-tip")));
    let f = text(18.0);
    let lh = jtext::height(&painter, &f);
    let w = t.lines().map(|l| jtext::width(&painter, l, &f)).fold(0.0, f32::max) + 12.0;
    let hh = t.lines().count() as f32 * lh + 8.0;
    let mut at = pos + vec2(12.0, 18.0);
    let screen = ctx.content_rect();
    at.x = at.x.min(screen.right() - w - 2.0);
    at.y = if at.y + hh > screen.bottom() { pos.y - hh - 4.0 } else { at.y };
    let r = Rect::from_min_size(at, vec2(w, hh));
    painter.rect(r, 0.0, p.paper, Stroke::new(1.0, p.line), egui::StrokeKind::Inside);
    for (k, l) in t.lines().enumerate() {
        jtext::draw(&painter, pos2(r.left() + 6.0, r.top() + 4.0 + k as f32 * lh), Align2::LEFT_TOP, l, &f, p.ink);
    }
}

/// Where the window starts (ms): the second scrolled to (None: the latest end), kept within the visit.
fn start_of(view: Option<i64>, total: i64, extent: i64) -> i64 {
    let last = total.max(extent) - extent;
    view.map_or(last, |v| v.clamp(0, last)) * 1000
}

/// A visit's graph scrolled to (its start second), kept while the app runs; None: following its latest end.
pub fn view_of(ctx: &egui::Context, visit: i64) -> Option<i64> {
    ctx.data(|d| d.get_temp::<i64>(egui::Id::unique(("graph-view", visit))))
}

pub fn set_view(ctx: &egui::Context, visit: i64, view: Option<i64>) {
    ctx.data_mut(|d| match view {
        Some(s) => {
            d.insert_temp(egui::Id::unique(("graph-view", visit)), s);
        }
        None => d.remove::<i64>(egui::Id::unique(("graph-view", visit))),
    });
}

/// The graph's seconds: the visit's, and how many the window shows.
pub fn seconds(v: &Visit, now: i64, r: Rect) -> (i64, i64) {
    let fit = (((r.width() - PAD_LEFT - PAD_RIGHT + SLICE_PX - 1.0) / SLICE_PX).floor()).max(1.0) as i64;
    ((span(v, now) as f64 / 1000.0).ceil() as i64, fit * WINDOW_MS / 1000)
}

/// A click on a teardrop: the second to scroll to, so its slice is in the middle (Java's bubble click).
pub fn teardrop_click(v: &Visit, fights: &[Fight], now: i64, r: Rect, pos: Pos2, view: Option<i64>) -> Option<i64> {
    let (total, extent) = seconds(v, now, r);
    let view_start = start_of(view, total, extent);
    let view_ms = extent * 1000;
    let lane = if fights.is_empty() { 0.0 } else { LANE };
    let h = r.height();
    let base = h - PAD_BOTTOM - lane;
    let mid = PAD_TOP + ((h - PAD_BOTTOM - lane - PAD_TOP) * 2.0 / 5.0).floor();
    let cy = ((mid + base) / 2.0).floor();
    let (x, y) = (pos.x - r.left(), pos.y - r.top());
    if (y - cy).abs() > BUBBLE {
        return None;
    }
    let sl = slices(v, WINDOW_MS, now);
    let thr = threshold(v);
    let (first, last) = (view_start / WINDOW_MS, (view_start + view_ms) / WINDOW_MS);
    let reach = (BUBBLE * 1.9).floor() + BUBBLE;
    let danger = |i: &usize| sl.damage[*i] >= thr;
    let target = if x >= PAD_LEFT + 4.0 && x <= PAD_LEFT + 4.0 + reach {
        (0..sl.damage.len()).filter(danger).rfind(|i| (*i as i64) < first)
    } else if x <= r.width() - PAD_RIGHT - 4.0 && x >= r.width() - PAD_RIGHT - 4.0 - reach {
        (0..sl.damage.len()).filter(danger).find(|i| *i as i64 > last)
    } else {
        None
    }?;
    Some(((target as i64 * WINDOW_MS - view_ms / 2) / 1000).max(0))
}
