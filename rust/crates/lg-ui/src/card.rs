//! A character's loadout as a picture to share (Java `LoadoutCard`): a box tinted with the theme's accent, its
//! top-right corner cut off (transparent), the character with its class and fame, the reusable quick-slot items,
//! the four gear slots below like the game's inventory and the backpack beside them. Drawn at twice the size over
//! the window for a moment, grabbed from the frame and put on the clipboard.

use crate::detail::{Rarity, enchant_badge, enchant_ids, uv};
use crate::icons::Icons;
use crate::jtext;
use crate::theme::{Palette, mix};
use egui::{Align2, Color32, Pos2, Rect, Shape, Stroke, vec2};
use lg_app::characters::Char;
use lg_gamedata::GameData;

pub const SCALE: f32 = 2.0;
const EDGE: f32 = 2.0;
const PAD: f32 = 14.0;
const CUT: f32 = 18.0;
const SKIN: f32 = 88.0;
const TILE: f32 = 56.0;
const TILE_GAP: f32 = 8.0;
const BAG: f32 = 50.0;
const BAG_GAP: f32 = 8.0;
const QUICK: f32 = 28.0;
const QUICK_GAP: f32 = 4.0;
const TEXT_W: f32 = 220.0;
const QUICK_W: f32 = 240.0;
const QUICK_ROWS: f32 = 3.0;
const BACKPACK: i32 = 3180;
const EXTENDER: i32 = 65280;

fn luminance(c: Color32) -> f64 {
    (0.2126 * f64::from(c.r()) + 0.7152 * f64::from(c.g()) + 0.0722 * f64::from(c.b())) / 255.0
}

/// Java's `mix(a, b, t)`: t of the way from a to b.
fn toward(a: Color32, b: Color32, t: f64) -> Color32 {
    mix(b, a, t)
}

/// The font made smaller until the text fits the width (not under 10).
fn fit(painter: &egui::Painter, font: egui::FontId, t: &str, w: f32) -> egui::FontId {
    let mut f = font;
    while f.size > 10.0 && jtext::width(painter, t, &f) > w {
        f.size -= 1.0;
    }
    f
}

/// The box around an image's visible pixels (x0, y0, x1, y1), or None.
fn shown(im: &lg_gamedata::sprites::Image) -> Option<(u32, u32, u32, u32)> {
    let (mut x0, mut y0, mut x1, mut y1) = (u32::MAX, u32::MAX, 0, 0);
    let mut any = false;
    for y in 0..im.h {
        for x in 0..im.w {
            if im.rgba[((y * im.w + x) * 4 + 3) as usize] != 0 {
                any = true;
                x0 = x0.min(x);
                y0 = y0.min(y);
                x1 = x1.max(x);
                y1 = y1.max(y);
            }
        }
    }
    any.then_some((x0, y0, x1, y1))
}

/// The card's size in card pixels (before SCALE).
pub fn size(painter: &egui::Painter, icons: &mut Icons, data: &GameData, c: &Char) -> egui::Vec2 {
    layout(painter, icons, data, c).size
}

struct Layout {
    size: egui::Vec2,
    name: String,
    fame: String,
    name_font: egui::FontId,
    fame_font: egui::FontId,
    quick: Vec<i32>,
    quick_w: f32,
    header_h: f32,
    skin_size: f32,
    big: bool,
    sx: f32,
    char_right: f32,
    row_x: f32,
    bag: i32,
}

fn layout(painter: &egui::Painter, icons: &mut Icons, data: &GameData, c: &Char) -> Layout {
    let name = if c.class_name.trim().is_empty() { "Character".to_string() } else { c.class_name.clone() };
    let fame = format!("{} Fame", crate::profile::num(c.fame));
    let quick: Vec<i32> =
        c.quickslots.iter().flatten().copied().filter(|id| *id > 0 && data.items.item(*id).is_some_and(|i| i.quickslot_only)).collect();
    let bag = if c.extender {
        EXTENDER
    } else if c.backpack {
        BACKPACK
    } else {
        -1
    };
    let quick_h = QUICK_ROWS * QUICK + (QUICK_ROWS - 1.0) * QUICK_GAP;
    let name_font = fit(painter, crate::widgets::head_px(30.0), &name, TEXT_W);
    let fame_font = fit(painter, crate::widgets::text(20.0), &fame, TEXT_W);
    let header_h = SKIN.max(quick_h).max(jtext::height(painter, &name_font) + jtext::height(painter, &fame_font));
    let small = crate::widgets::text(16.0);
    let mut quick_w: f32 = 0.0;
    for id in &quick {
        let label = data.items.item(*id).map_or(format!("Item {id}"), |i| i.name.clone());
        let f = fit(painter, small.clone(), &label, QUICK_W - QUICK - 6.0);
        quick_w = quick_w.max(QUICK + 6.0 + jtext::width(painter, &label, &f));
    }
    let sprite = if c.skin > 0 { c.skin } else { c.class_type };
    let native = icons.native(data, sprite);
    let native_size = native.as_ref().map_or(8, |im| im.w) as f32;
    let big = native_size > 8.0;
    let skin_size = if big { header_h + 14.0 + TILE } else { SKIN };
    let char_px = skin_size / native_size;
    // where the character shows in its icon (the outlined icon: the sprite scaled to the size less 2, outlined)
    let inner = skin_size - 2.0;
    let (shown_left, shown_right) = native.as_ref().and_then(shown).map_or((0.0, skin_size), |(x0, _, x1, _)| {
        let px = inner / native_size;
        ((x0 as f32 * px).floor(), ((x1 + 1) as f32 * px).ceil() + 2.0)
    });
    let leftmost = native.as_ref().map_or(0, |im| {
        (0..im.w).map(|x| (0..im.h).filter(|y| im.rgba[((y * im.w + x) * 4 + 3) as usize] != 0).count()).find(|n| *n > 0).unwrap_or(0)
    });
    let left_px = if leftmost <= 2 { 1.0 } else { 2.0 };
    let sx = EDGE + (char_px * left_px).round() - shown_left;
    let char_right = sx + shown_right + (char_px * 2.5).round();
    let row_x = if big { char_right } else { sx + shown_left };
    let text_w = jtext::width(painter, &name, &name_font).max(jtext::width(painter, &fame, &fame_font));
    let header_right = char_right + text_w + if quick_w > 0.0 { 32.0 + quick_w } else { 0.0 };
    let row_right = row_x + 4.0 * TILE + 3.0 * TILE_GAP + if bag > 0 { BAG_GAP + BAG } else { 0.0 };
    let w = header_right.max(row_right) + PAD + CUT / 2.0;
    let h = PAD * 2.0 + header_h + 14.0 + TILE;
    Layout {
        size: vec2(w.ceil(), h.ceil()),
        name,
        fame,
        name_font,
        fame_font,
        quick,
        quick_w,
        header_h,
        skin_size,
        big,
        sx,
        char_right,
        row_x,
        bag,
    }
}

/// The outline from (o, o) to (right, bottom), the top-right corner cut.
fn outline(at: Pos2, k: f32, o: f32, right: f32, bottom: f32, cut: f32) -> Vec<Pos2> {
    [(o, o), (right - cut, o), (right, o + cut), (right, bottom), (o, bottom)].iter().map(|(x, y)| at + vec2(x * k, y * k)).collect()
}

/// A soft shadow behind a texture: its shape in black, spread, a little down and right (Java blurs it).
fn shadow(painter: &egui::Painter, t: &egui::TextureHandle, r: Rect, radius: f32, k: f32, dark: bool) {
    let strength = if dark { 0.8 } else { 0.45 };
    let off = vec2(radius / 2.0, radius * 0.75) * k;
    for (d, a) in [(0.0, 0.5), (radius * 0.35, 0.25), (radius * 0.7, 0.12)] {
        for (dx, dy) in [(-d, 0.0), (d, 0.0), (0.0, -d), (0.0, d)] {
            let c = Color32::from_black_alpha((255.0 * strength * a / 4.0) as u8);
            painter.image(t.id(), r.translate(off + vec2(dx, dy) * k), uv(), c);
        }
    }
}

/// Text with a dark shadow on dark themes.
fn shadowed(painter: &egui::Painter, at: Pos2, t: &str, f: &egui::FontId, color: Color32, dark: bool, k: f32) {
    if dark {
        jtext::draw(painter, at + vec2(k, k), Align2::LEFT_TOP, t, f, Color32::BLACK);
    }
    jtext::draw(painter, at, Align2::LEFT_TOP, t, f, color);
}

/// Paints the card with its corner at `at`, `k` screen points per card pixel; gives its rect.
#[allow(clippy::too_many_arguments)]
pub fn paint(
    painter: &egui::Painter,
    ctx: &egui::Context,
    icons: &mut Icons,
    data: &GameData,
    rarity: &Rarity,
    p: &Palette,
    c: &Char,
    at: Pos2,
    k: f32,
) -> Rect {
    let l = layout(painter, icons, data, c);
    let (w, h) = (l.size.x, l.size.y);
    let dark = luminance(p.paper) < 0.5;
    let gold_light = p.gold_hover;
    let sc = |f: &egui::FontId| egui::FontId::new(f.size * k, f.family.clone());
    // the box: a gradient of the accent, its edge, a lighter inner line, the corner cut
    let pts = outline(at, k, 0.5, w - 0.5, h - 0.5, CUT);
    let (top, bottom) = (toward(p.paper, p.gold, 0.16), toward(p.paper, p.gold, 0.07));
    let mut mesh = egui::Mesh::default();
    for q in &pts {
        let t = ((q.y - at.y) / (h * k)).clamp(0.0, 1.0);
        let m = |a: u8, b: u8| (f32::from(a) + (f32::from(b) - f32::from(a)) * t).round() as u8;
        mesh.colored_vertex(*q, Color32::from_rgb(m(top.r(), bottom.r()), m(top.g(), bottom.g()), m(top.b(), bottom.b())));
    }
    for i in 1..pts.len() as u32 - 1 {
        mesh.add_triangle(0, i, i + 1);
    }
    painter.add(Shape::mesh(mesh));
    painter.add(Shape::closed_line(pts, Stroke::new(2.0 * k, p.gold)));
    let gl = Color32::from_rgba_unmultiplied(gold_light.r(), gold_light.g(), gold_light.b(), 80);
    painter.add(Shape::closed_line(outline(at, k, 3.0, w - 3.0, h - 3.0, CUT - 1.2), Stroke::new(k, gl)));
    // the character, its class and fame
    let y = PAD;
    let skin_y = if l.big { y } else { y + (l.header_h - SKIN) / 2.0 };
    let sprite = if c.skin > 0 { c.skin } else { c.class_type };
    if let Some(t) = icons.skin(ctx, data, sprite, c.tex1, c.tex2, (l.skin_size * k) as u32) {
        let r = Rect::from_min_size(at + vec2(l.sx, skin_y) * k, vec2(l.skin_size, l.skin_size) * k);
        shadow(painter, &t, r, if l.big { 5.0 } else { 4.0 }, k, dark);
        painter.image(t.id(), r, uv(), Color32::WHITE);
    }
    let (nf, ff) = (sc(&l.name_font), sc(&l.fame_font));
    let text_h = jtext::height(painter, &l.name_font) + jtext::height(painter, &l.fame_font);
    let ty = y + ((l.header_h - text_h) / 2.0).max(0.0);
    shadowed(painter, at + vec2(l.char_right, ty) * k, &l.name, &nf, p.ink, dark, k);
    shadowed(painter, at + vec2(l.char_right, ty + jtext::height(painter, &l.name_font)) * k, &l.fame, &ff, p.gold, dark, k);
    // the reusable quick-slot items, top right
    let rx = w - PAD - CUT / 2.0 - l.quick_w;
    let quick_h = QUICK_ROWS * QUICK + (QUICK_ROWS - 1.0) * QUICK_GAP;
    let mut qy = y + (l.header_h - quick_h) / 2.0;
    for id in &l.quick {
        if let Some(t) = icons.object(ctx, data, *id, (QUICK * k) as u32) {
            let r = Rect::from_min_size(at + vec2(rx, qy) * k, vec2(QUICK, QUICK) * k);
            shadow(painter, &t, r, 2.0, k, dark);
            painter.image(t.id(), r, uv(), Color32::WHITE);
        }
        let label = data.items.item(*id).map_or(format!("Item {id}"), |i| i.name.clone());
        let f = fit(painter, crate::widgets::text(16.0), &label, QUICK_W - QUICK - 6.0);
        let fh = jtext::height(painter, &f);
        shadowed(painter, at + vec2(rx + QUICK + 6.0, qy + (QUICK - fh) / 2.0) * k, &label, &sc(&f), p.ink, dark, k);
        qy += QUICK + QUICK_GAP;
    }
    // the gear as inventory slots, the backpack beside them
    let sy = y + l.header_h + 14.0;
    let tile_bg = if dark { toward(p.sand, Color32::BLACK, 0.3) } else { toward(p.sand, p.line, 0.25) };
    for slot in 0..4 {
        let x = l.row_x + slot as f32 * (TILE + TILE_GAP);
        let tr = Rect::from_min_size(at + vec2(x, sy) * k, vec2(TILE, TILE) * k);
        painter.rect(tr, 4.0 * k, tile_bg, Stroke::new(k, p.line), egui::StrokeKind::Inside);
        let id = c.equipment.as_ref().and_then(|e| e.get(slot)).copied().unwrap_or(-1);
        if id > 0
            && let Some(t) = icons.object(ctx, data, id, ((TILE - 10.0) * k) as u32)
        {
            painter.image(t.id(), Rect::from_center_size(tr.center(), vec2(TILE - 10.0, TILE - 10.0) * k), uv(), Color32::WHITE);
        }
        let n = enchant_ids(c, slot).map_or(0, |v| v.len());
        enchant_badge(painter, rarity, tr.min + vec2(TILE - 4.0, TILE - 4.0) * k, n, 6.0 * k);
    }
    if l.bag > 0
        && let Some(t) = icons.object(ctx, data, l.bag, (BAG * k) as u32)
    {
        let bx = l.row_x + 4.0 * (TILE + TILE_GAP) - TILE_GAP + BAG_GAP;
        let r = Rect::from_center_size(at + vec2(bx + BAG / 2.0, sy + TILE / 2.0) * k, vec2(BAG, BAG) * k);
        shadow(painter, &t, r, 3.0, k, dark);
        painter.image(t.id(), r, uv(), Color32::WHITE);
    }
    Rect::from_min_size(at, vec2(w, h) * k)
}

/// The grabbed card: its pixels from the frame (`r` in points, `ppp` pixels per point, `k` points per card
/// pixel), the cut corner made see-through.
pub fn crop(frame: &egui::ColorImage, r: Rect, ppp: f32, k: f32) -> egui::ColorImage {
    let (x0, y0) = ((r.left() * ppp).round() as usize, (r.top() * ppp).round() as usize);
    let w = ((r.width() * ppp).round() as usize).min(frame.size[0].saturating_sub(x0));
    let h = ((r.height() * ppp).round() as usize).min(frame.size[1].saturating_sub(y0));
    let cut = CUT * k * ppp;
    let mut px = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            // above the cut's diagonal: transparent
            let from_right = (w - 1 - x) as f32;
            let c = if (y as f32) + from_right < cut { Color32::TRANSPARENT } else { frame.pixels[(y0 + y) * frame.size[0] + x0 + x] };
            px.push(c);
        }
    }
    egui::ColorImage::new([w, h], px)
}
