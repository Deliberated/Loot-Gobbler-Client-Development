//! The overlays' looks (Java `OverlayBadge`, `ManaBar`, `DpsOverlay`, `BuffOverlay`), painted at a place: the
//! Battles > Overlay page shows them with sample values; the overlay windows will paint them live.

use crate::icons::Icons;
use crate::jtext;
use egui::{Align2, Color32, Painter, Pos2, Rect, Shape, Stroke, StrokeKind, pos2, vec2};
use lg_gamedata::GameData;

const TEXT: Color32 = Color32::from_rgb(0xF2, 0xEC, 0xDC);
const FALLBACK: Color32 = Color32::from_rgb(0x3A, 0x2C, 0x08);

fn font(px: f32) -> egui::FontId {
    crate::widgets::text(px)
}

/// Java `Color.RGBtoHSB`.
fn rgb_to_hsb(r: u8, g: u8, b: u8) -> [f32; 3] {
    let (r, g, b) = (i32::from(r), i32::from(g), i32::from(b));
    let cmax = r.max(g).max(b);
    let cmin = r.min(g).min(b);
    let bri = cmax as f32 / 255.0;
    let sat = if cmax != 0 { (cmax - cmin) as f32 / cmax as f32 } else { 0.0 };
    let hue = if sat == 0.0 {
        0.0
    } else {
        let d = (cmax - cmin) as f32;
        let (rc, gc, bc) = ((cmax - r) as f32 / d, (cmax - g) as f32 / d, (cmax - b) as f32 / d);
        let mut h = if r == cmax {
            bc - gc
        } else if g == cmax {
            2.0 + rc - bc
        } else {
            4.0 + gc - rc
        } / 6.0;
        if h < 0.0 {
            h += 1.0;
        }
        h
    };
    [hue, sat, bri]
}

/// Java `Color.HSBtoRGB`.
fn hsb_to_rgb(h: f32, s: f32, b: f32) -> Color32 {
    if s == 0.0 {
        let v = (b * 255.0 + 0.5) as u8;
        return Color32::from_rgb(v, v, v);
    }
    let h6 = (h - h.floor()) * 6.0;
    let f = h6 - h6.floor();
    let p = b * (1.0 - s);
    let q = b * (1.0 - s * f);
    let t = b * (1.0 - s * (1.0 - f));
    let (r, g, bl) = match h6 as i32 {
        0 => (b, t, p),
        1 => (q, b, p),
        2 => (p, b, t),
        3 => (p, q, b),
        4 => (t, p, b),
        _ => (b, p, q),
    };
    let c = |v: f32| (v * 255.0 + 0.5) as u8;
    Color32::from_rgb(c(r), c(g), c(bl))
}

/// Java `ActiveDungeonBadge.tint`: the portal's main colour (opaque pixels weighted by how colourful they are),
/// dark enough for light text.
pub fn tint(img: Option<&lg_gamedata::sprites::Image>) -> Color32 {
    let Some(img) = img else { return FALLBACK };
    let (mut r, mut g, mut b, mut weight) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for px in img.rgba.chunks(4) {
        if px[3] < 200 {
            continue;
        }
        let hsb = rgb_to_hsb(px[0], px[1], px[2]);
        let w = 0.05 + f64::from(hsb[1]) * f64::from(hsb[2]);
        r += f64::from(px[0]) * w;
        g += f64::from(px[1]) * w;
        b += f64::from(px[2]) * w;
        weight += w;
    }
    if weight == 0.0 {
        return FALLBACK;
    }
    let hsb = rgb_to_hsb((r / weight) as u8, (g / weight) as u8, (b / weight) as u8);
    hsb_to_rgb(hsb[0], (hsb[1] * 1.15).min(1.0), 0.28)
}

/// Java `ActiveDungeonBadge.shade`: the colour with its brightness scaled.
pub fn shade(c: Color32, factor: f32) -> Color32 {
    let hsb = rgb_to_hsb(c.r(), c.g(), c.b());
    hsb_to_rgb(hsb[0], hsb[1], (hsb[2] * factor).min(1.0))
}

/// A rounded rectangle filled with a gradient from top to bottom.
pub fn gradient_round_rect(painter: &Painter, r: Rect, radius: f32, top: Color32, bottom: Color32) {
    let mut pts: Vec<Pos2> = Vec::new();
    let corners = [
        (r.right() - radius, r.top() + radius, -90.0f32),
        (r.right() - radius, r.bottom() - radius, 0.0),
        (r.left() + radius, r.bottom() - radius, 90.0),
        (r.left() + radius, r.top() + radius, 180.0),
    ];
    for (cx, cy, start) in corners {
        for k in 0..=6 {
            let a = (start + 90.0 * k as f32 / 6.0).to_radians();
            pts.push(pos2(cx + radius * a.cos(), cy + radius * a.sin()));
        }
    }
    let color_at = |y: f32| {
        let t = ((y - r.top()) / r.height().max(1.0)).clamp(0.0, 1.0);
        let l = |a: u8, b: u8| (f32::from(a) + (f32::from(b) - f32::from(a)) * t).round() as u8;
        Color32::from_rgba_unmultiplied(l(top.r(), bottom.r()), l(top.g(), bottom.g()), l(top.b(), bottom.b()), l(top.a(), bottom.a()))
    };
    let mut mesh = egui::Mesh::default();
    let c = r.center();
    mesh.colored_vertex(c, color_at(c.y));
    for p in &pts {
        mesh.colored_vertex(*p, color_at(p.y));
    }
    let n = pts.len() as u32;
    for i in 0..n {
        mesh.add_triangle(0, 1 + i, 1 + (i + 1) % n);
    }
    painter.add(Shape::mesh(mesh));
}

/// What the dungeon box shows (Java `OverlayBadge`); all optional parts as the sample shows them.
pub struct Badge<'a> {
    pub dungeon: &'a str,
    pub rank: Option<String>,
    pub amulet: bool,
    /// The matrix armor that fired, while it charges.
    pub matrix: Option<i32>,
    /// Moonlight Village: total flames, whether of the dancers, the latest phase's flames, held (FINAL).
    pub moonlight: Option<(i32, bool, String, bool)>,
}

const BADGE_ICON: f32 = 26.0;
const BADGE_AMULET: f32 = 22.0;
const GAP: f32 = 10.0;
const FLAME: i32 = 20586;
const HELD: Color32 = Color32::from_rgb(0xC9, 0xBF, 0xE6);
const DANCER_TIERS: [i32; 4] = [2, 40, 58, 78];
const UMI_TIERS: [i32; 4] = [2, 24, 36, 48];
const TIER_COLORS: [u32; 5] = [0x5D5547, 0x4CD15A, 0x3FB6F2, 0xB05CF0, 0xF2C232];

fn tier_of(flames: i32, dancers: bool) -> usize {
    (if dancers { DANCER_TIERS } else { UMI_TIERS }).iter().filter(|s| flames >= **s).count()
}

fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

/// A loot chest in its tier's colour: lid, body, a dark band and the lock (22 x 20).
fn chest(painter: &Painter, at: Pos2, color: Color32) {
    let ink = Color32::from_rgb(0x14, 0x0E, 0x08);
    let mut lid = vec![pos2(2.0, 10.0)];
    for k in 1..=12 {
        // the cubic from (2,10) over (2,2) and (20,2) to (20,10)
        let t = k as f32 / 12.0;
        let u = 1.0 - t;
        let x = u * u * u * 2.0 + 3.0 * u * u * t * 2.0 + 3.0 * u * t * t * 20.0 + t * t * t * 20.0;
        let y = u * u * u * 10.0 + 3.0 * u * u * t * 2.0 + 3.0 * u * t * t * 2.0 + t * t * t * 10.0;
        lid.push(pos2(x, y));
    }
    let lid: Vec<Pos2> = lid.into_iter().map(|p| at + p.to_vec2()).collect();
    painter.add(Shape::convex_polygon(lid.clone(), color, Stroke::NONE));
    let body = Rect::from_min_size(at + vec2(2.0, 10.0), vec2(18.0, 9.0));
    painter.rect_filled(body, 0.0, color);
    painter.add(Shape::closed_line(lid, Stroke::new(1.2, ink)));
    painter.rect_stroke(body, 0.0, Stroke::new(1.2, ink), StrokeKind::Middle);
    painter.rect_filled(Rect::from_min_size(at + vec2(2.0, 9.0), vec2(18.0, 2.0)), 0.0, Color32::from_rgba_unmultiplied(20, 14, 8, 115));
    painter.rect_filled(Rect::from_min_size(at + vec2(9.0, 9.0), vec2(4.0, 5.0)), 1.0, ink);
    painter.rect_filled(Rect::from_min_size(at + vec2(10.0, 10.0), vec2(2.0, 3.0)), 0.0, TEXT);
}

/// The dungeon box at `at`; gives its size.
pub fn badge(painter: &Painter, ctx: &egui::Context, icons: &mut Icons, data: &GameData, b: &Badge, at: Pos2) -> egui::Vec2 {
    let bold = font(19.0);
    let small_bold = font(15.5);
    let fin_font = font(15.5);
    let portal = data.dungeons.portal_by_name.get(b.dungeon).copied().unwrap_or(-1);
    let img = icons.fitted_image(data, portal, 64, BADGE_ICON as u32);
    let tint = tint(img.as_ref());
    let rank_w = jtext::bold_width(painter, "#50/50", &bold) + 2.0;
    let next_w = jtext::bold_width(painter, "to T4", &small_bold) + 4.0;
    let next_h = 2.0 * jtext::height(painter, &small_bold);
    let sep = 2.0 * GAP + 1.0;
    let flame_h = (19.0f32 * 1050.0 / 1350.0).ceil();
    let total_w = |n: i32| {
        jtext::width(painter, "TOTAL", &font(18.0 * 0.85))
            + jtext::width(painter, " ", &font(18.0))
            + jtext::bold_width(painter, &n.to_string(), &font(18.0))
    };
    // the parts that show, left to right: (width, height, what)
    enum Part {
        Portal,
        Sep,
        Rank(String),
        Item(i32),
        Total(i32),
        Chest(usize),
        Next(String, String),
        Phase(String, bool),
        Final,
    }
    let mut parts: Vec<(f32, f32, Part)> = vec![(BADGE_ICON, BADGE_ICON, Part::Portal)];
    if let Some(r) = &b.rank {
        parts.push((1.0, 18.0, Part::Sep));
        parts.push((rank_w, jtext::height(painter, &bold), Part::Rank(r.clone())));
    }
    if b.amulet && data.amulets.restoration >= 0 {
        parts.push((1.0, 18.0, Part::Sep));
        parts.push((BADGE_AMULET, BADGE_AMULET, Part::Item(data.amulets.restoration)));
    }
    if let Some(m) = b.matrix {
        parts.push((1.0, 18.0, Part::Sep));
        parts.push((BADGE_AMULET, BADGE_AMULET, Part::Item(m)));
    }
    let mut fixed_w = 14.0 + GAP + BADGE_ICON + sep + jtext::bold_width(painter, "#50/50", &bold) + sep + BADGE_AMULET + sep + BADGE_AMULET;
    if let Some((total, dancers, phase, held)) = &b.moonlight {
        let starts = if *dancers { DANCER_TIERS } else { UMI_TIERS };
        let t = tier_of(*total, *dancers);
        parts.push((1.0, 18.0, Part::Sep));
        parts.push((total_w(*total), jtext::height(painter, &font(18.0)), Part::Total(*total)));
        parts.push((22.0, 20.0, Part::Chest(t)));
        let (a, bb) = if t >= starts.len() {
            ("MAX".to_string(), String::new())
        } else {
            (format!("+{}", starts[t] - total), format!("to T{}", t + 1))
        };
        parts.push((next_w, next_h, Part::Next(a, bb)));
        parts.push((1.0, 18.0, Part::Sep));
        parts.push((
            flame_h + 4.0 + jtext::bold_width(painter, phase, &bold),
            jtext::height(painter, &bold),
            Part::Phase(phase.clone(), *held),
        ));
        if *held {
            parts.push((jtext::bold_width(painter, "FINAL", &fin_font), jtext::height(painter, &fin_font), Part::Final));
        }
        fixed_w += sep
            + total_w(888)
            + GAP
            + 22.0
            + GAP
            + next_w
            + sep
            + flame_h
            + 4.0
            + jtext::bold_width(painter, "88", &bold)
            + GAP
            + jtext::bold_width(painter, "FINAL", &fin_font);
    }
    let flow_w = 14.0 + GAP + parts.iter().map(|p| p.0 + GAP).sum::<f32>();
    let inner_h = parts.iter().map(|p| p.1).fold(0.0, f32::max);
    let size = vec2(flow_w.max(fixed_w), (inner_h + 8.0).max(BADGE_ICON + 8.0));
    let r = Rect::from_min_size(at, size);
    let box_r = Rect::from_min_size(at, size - vec2(1.0, 1.0));
    gradient_round_rect(painter, box_r, 5.0, shade(tint, 1.2), shade(tint, 0.8));
    painter.rect_stroke(box_r, 5.0, Stroke::new(1.0, shade(tint, 1.9)), StrokeKind::Middle);
    let cy = r.top() + 4.0 + inner_h / 2.0;
    let mut x = r.left() + 2.0 + GAP;
    for (w, h, part) in &parts {
        let top = (cy - h / 2.0).floor();
        match part {
            Part::Portal => {
                if let Some(t) = icons.fitted(ctx, data, portal, 64, BADGE_ICON as u32) {
                    painter.image(
                        t.id(),
                        Rect::from_min_size(pos2(x, top), vec2(BADGE_ICON, BADGE_ICON)),
                        crate::detail::uv(),
                        Color32::WHITE,
                    );
                }
            }
            Part::Sep => {
                painter.rect_filled(
                    Rect::from_min_size(pos2(x, top), vec2(1.0, 18.0)),
                    0.0,
                    Color32::from_rgba_unmultiplied(242, 236, 220, 64),
                );
            }
            Part::Rank(t) => {
                jtext::draw_bold(painter, pos2(x + w / 2.0, cy), Align2::CENTER_CENTER, t, &bold, TEXT);
            }
            Part::Item(id) => {
                if let Some(t) = icons.fitted(ctx, data, *id, 40, BADGE_AMULET as u32) {
                    painter.image(
                        t.id(),
                        Rect::from_min_size(pos2(x, top), vec2(BADGE_AMULET, BADGE_AMULET)),
                        crate::detail::uv(),
                        Color32::WHITE,
                    );
                }
            }
            Part::Total(n) => {
                let tr = jtext::draw(
                    painter,
                    pos2(x, cy + 1.0),
                    Align2::LEFT_CENTER,
                    "TOTAL",
                    &font(18.0 * 0.85),
                    Color32::from_rgb(0xC8, 0xC2, 0xB4),
                );
                jtext::draw_bold(
                    painter,
                    pos2(tr.right() + jtext::width(painter, " ", &font(18.0)), cy),
                    Align2::LEFT_CENTER,
                    &n.to_string(),
                    &font(18.0),
                    TEXT,
                );
            }
            Part::Chest(t) => chest(painter, pos2(x, top), rgb(TIER_COLORS[*t])),
            Part::Next(a, bb) => {
                let lh = jtext::height(painter, &small_bold);
                jtext::draw_bold(painter, pos2(x, top), Align2::LEFT_TOP, a, &small_bold, TEXT);
                jtext::draw_bold(painter, pos2(x, top + lh), Align2::LEFT_TOP, bb, &small_bold, Color32::from_rgb(0xC8, 0xC2, 0xB4));
            }
            Part::Phase(t, held) => {
                if let Some(tx) = icons.fitted(ctx, data, FLAME, 40, flame_h as u32) {
                    painter.image(
                        tx.id(),
                        Rect::from_min_size(pos2(x, cy - flame_h / 2.0), vec2(flame_h, flame_h)),
                        crate::detail::uv(),
                        Color32::WHITE,
                    );
                }
                let color = if *held { HELD } else { TEXT };
                jtext::draw_bold(painter, pos2(x + flame_h + 4.0, cy), Align2::LEFT_CENTER, t, &bold, color);
            }
            Part::Final => {
                jtext::draw_bold(painter, pos2(x, cy), Align2::LEFT_CENTER, "FINAL", &fin_font, HELD);
            }
        }
        x += w + GAP;
    }
    size
}

/// The mana bar (Java `ManaBar`): 170 x 22, filled as far as your mana goes, "MP points / maximum" on it.
pub fn mana_bar(painter: &Painter, at: Pos2, mp: i32, max: i32) -> egui::Vec2 {
    let size = vec2(170.0, 22.0);
    let r = Rect::from_min_size(at, size - vec2(1.0, 1.0));
    painter.rect_filled(r, 3.0, Color32::from_rgb(0x16, 0x1B, 0x30));
    let share = if max <= 0 { 0.0 } else { (f64::from(mp) / f64::from(max)).clamp(0.0, 1.0) };
    let fw = (f64::from(r.width()) * share).round() as f32;
    if fw > 0.0 {
        gradient_round_rect(
            painter,
            Rect::from_min_size(r.min, vec2(fw, r.height())),
            3.0,
            Color32::from_rgb(0x4C, 0x86, 0xF0),
            Color32::from_rgb(0x1E, 0x4C, 0xB8),
        );
    }
    let t = format!("MP {} / {}", mp.max(0), max.max(0));
    let f = font(18.0);
    let c = at + size / 2.0;
    jtext::draw_bold(painter, c + vec2(1.0, 1.0), Align2::CENTER_CENTER, &t, &f, Color32::from_black_alpha(150));
    jtext::draw_bold(painter, c, Align2::CENTER_CENTER, &t, &f, TEXT);
    size
}

/// One player in the DPS overlay.
pub struct DpsLine {
    pub place: usize,
    pub name: String,
    pub damage: i64,
    pub share: f64,
    pub me: bool,
}

/// The DPS overlay (Java `DpsOverlay`): the boss and three players around you, your row in gold.
pub fn dps_overlay(painter: &Painter, at: Pos2, boss: &str, lines: &[DpsLine], max_share: f64) -> egui::Vec2 {
    let (w, pad, head, row) = (320.0f32, 8.0f32, 18.0f32, 22.0f32);
    let size = vec2(w, 2.0 * pad + head + 3.0 * row);
    let r = Rect::from_min_size(at, size - vec2(1.0, 1.0));
    painter.rect(r, 5.0, Color32::from_rgb(0x2A, 0x26, 0x20), Stroke::new(1.0, Color32::from_rgb(0x5A, 0x54, 0x4A)), StrokeKind::Middle);
    let muted = Color32::from_rgb(0xA3, 0x9A, 0x86);
    let me_c = Color32::from_rgb(0xE8, 0xC5, 0x52);
    let (plain, small) = (font(18.0), font(16.5));
    let left = at.x + pad + 4.0;
    let right = at.x + w - pad - 4.0;
    let dmg_right = right - 58.0;
    let name_x = left + 30.0;
    let hy = at.y + pad;
    let title = crate::widgets::fit(painter, &boss.to_uppercase(), &small, dmg_right - 70.0 - left);
    jtext::draw_bold(painter, pos2(left, hy), Align2::LEFT_TOP, &title, &small, muted);
    jtext::draw_bold(painter, pos2(dmg_right, hy), Align2::RIGHT_TOP, "DMG", &small, muted);
    jtext::draw_bold(painter, pos2(right, hy), Align2::RIGHT_TOP, "%", &small, muted);
    let mut y = at.y + pad + head;
    for l in lines {
        let bw = ((right - left + 8.0) as f64 * (l.share / max_share).min(1.0)) as i32 as f32;
        painter.rect_filled(
            Rect::from_min_size(pos2(left - 4.0, y + 2.0), vec2(bw, row - 4.0)),
            0.0,
            Color32::from_rgba_unmultiplied(232, 197, 82, 48),
        );
        let draw = if l.me { jtext::draw_bold } else { jtext::draw };
        let cy = y + row / 2.0;
        let c = if l.me { me_c } else { TEXT };
        draw(painter, pos2(left, cy), Align2::LEFT_CENTER, &format!("#{}", l.place), &plain, c);
        let name = crate::widgets::fit(painter, &l.name, &plain, dmg_right - 80.0 - name_x);
        draw(painter, pos2(name_x, cy), Align2::LEFT_CENTER, &name, &plain, c);
        draw(painter, pos2(dmg_right, cy), Align2::RIGHT_CENTER, &crate::profile::num(l.damage), &plain, c);
        draw(painter, pos2(right, cy), Align2::RIGHT_CENTER, &format!("{:.1}%", l.share), &plain, me_c);
        y += row;
    }
    size
}

/// One running cooldown in the buffs overlay.
pub struct Cooldown {
    pub item: i32,
    pub start: i64,
    pub duration_ms: i64,
}

/// "12", "2:45", "29m".
fn left_text(c: &Cooldown, now: i64) -> String {
    let s = ((c.start + c.duration_ms - now).max(0) as f64 / 1000.0).ceil() as i64;
    if s < 60 {
        s.to_string()
    } else if s < 600 {
        format!("{}:{:02}", s / 60, s % 60)
    } else {
        format!("{}m", (s + 59) / 60)
    }
}

/// The buffs overlay (Java `BuffOverlay`): a round tile per running cooldown, a golden circle running out behind the
/// item with the time left on it; one empty grey circle while none runs.
pub fn buffs(
    painter: &Painter,
    ctx: &egui::Context,
    icons: &mut Icons,
    data: &GameData,
    at: Pos2,
    tiles: &[Cooldown],
    now: i64,
) -> egui::Vec2 {
    let (tile, gap, sprite) = (46.0f32, 6.0f32, 24u32);
    let disc = Color32::from_rgba_unmultiplied(0x14, 0x10, 0x0C, 190);
    let n = tiles.len().max(1) as f32;
    let size = vec2(n * tile + (n - 1.0) * gap, tile);
    let rad = (tile - 1.0) / 2.0;
    if tiles.is_empty() {
        let c = at + vec2(rad, rad);
        painter.circle_filled(c, rad, disc);
        painter.circle_stroke(c, rad - 1.0, Stroke::new(2.0, Color32::from_rgb(0x8A, 0x82, 0x72)));
    }
    let mut x = at.x;
    for t in tiles {
        let c = pos2(x + rad, at.y + rad);
        painter.circle_filled(c, rad, disc);
        let share = if t.duration_ms <= 0 { 0.0 } else { ((t.start + t.duration_ms - now) as f64 / t.duration_ms as f64).clamp(0.0, 1.0) };
        if share > 0.0 {
            // what is left, from 12 o'clock counter-clockwise (the part gone by opens clockwise)
            let steps = (64.0 * share).ceil().max(2.0) as usize;
            let mut pts = vec![c];
            for k in 0..=steps {
                let a = (90.0 + 360.0 * share * k as f64 / steps as f64).to_radians();
                pts.push(pos2(c.x + rad * a.cos() as f32, c.y - rad * a.sin() as f32));
            }
            let mut mesh = egui::Mesh::default();
            let gold = Color32::from_rgb(0xFF, 0xC2, 0x0E);
            for p in &pts {
                mesh.colored_vertex(*p, gold);
            }
            for k in 1..pts.len() as u32 - 1 {
                mesh.add_triangle(0, k, k + 1);
            }
            painter.add(Shape::mesh(mesh));
            let mut outline = pts.clone();
            outline.push(c);
            painter.add(Shape::line(outline, Stroke::new(1.0, Color32::from_rgb(0xB8, 0x86, 0x0B))));
        }
        if let Some(tx) = icons.fitted(ctx, data, t.item, 40, sprite) {
            let s = sprite as f32;
            painter.image(
                tx.id(),
                Rect::from_min_size(pos2(x + ((tile - s) / 2.0).floor(), at.y + ((tile - s) / 2.0).floor()), vec2(s, s)),
                crate::detail::uv(),
                Color32::WHITE,
            );
        }
        let text = left_text(t, now);
        let f = font(17.0);
        let br = pos2(x + tile - 1.0, at.y + tile);
        jtext::draw_bold(painter, br + vec2(1.0, 1.0), Align2::RIGHT_BOTTOM, &text, &f, Color32::from_black_alpha(200));
        jtext::draw_bold(painter, br, Align2::RIGHT_BOTTOM, &text, &f, TEXT);
        x += tile + gap;
    }
    size
}
