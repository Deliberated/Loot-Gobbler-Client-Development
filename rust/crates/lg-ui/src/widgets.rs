//! Building blocks the Java views share: text as Java's labels draw it, cards (paper with a double line border),
//! rows of equal cards as tall as the tallest, captions, headings.

use crate::jtext;
use crate::theme::{self, Palette};
use egui::{Align2, Color32, FontId, Rect, Sense, Shape, Stroke, StrokeKind, Ui, pos2, vec2};

/// A label: one line of text at the cursor, Java's spacing; gives its response.
pub fn label(ui: &mut Ui, text: &str, font: &FontId, color: Color32) -> egui::Response {
    let p = ui.painter().clone();
    let size = vec2(jtext::width(&p, text, font), jtext::height(&p, font));
    let (r, resp) = ui.allocate_exact_size(size, Sense::click());
    jtext::draw(&p, r.min, Align2::LEFT_TOP, text, font, color);
    resp
}

/// Label at the right edge of the available width.
pub fn label_right(ui: &mut Ui, text: &str, font: &FontId, color: Color32, inset: f32) -> egui::Response {
    let p = ui.painter().clone();
    let w = ui.available_width();
    let size = vec2(w, jtext::height(&p, font));
    let (r, resp) = ui.allocate_exact_size(size, Sense::hover());
    jtext::draw(&p, pos2(r.right() - inset, r.top()), Align2::RIGHT_TOP, text, font, color);
    resp
}

/// Text font of the Java labels (Jersey 15) at a pixel size.
pub fn text(px: f32) -> FontId {
    theme::body(px)
}

/// The `small` style: Jersey 10 at 16, scaled.
pub fn small() -> FontId {
    theme::head(16.0)
}

/// Jersey 10 at a pixel size as it is (Java `new Font(HEAD_FONT, PLAIN, px)`).
pub fn head_px(px: f32) -> FontId {
    FontId::new(px, egui::FontFamily::Name(theme::HEAD.into()))
}

/// Java `card`: paper, a two-pixel line border, 14/18 padding; content at the top. The background is drawn after
/// the content (in a reserved slot) at `min_height` or the content's height.
pub fn card<R>(ui: &mut Ui, p: &Palette, width: f32, min_height: f32, add: impl FnOnce(&mut Ui) -> R) -> (Rect, R) {
    let slot = ui.painter().add(Shape::Noop);
    let top = ui.cursor().min;
    let inner = Rect::from_min_size(pos2(top.x + 18.0, top.y + 14.0), vec2(width - 36.0, f32::INFINITY));
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(inner).layout(egui::Layout::top_down(egui::Align::Min)));
    child.spacing_mut().item_spacing = vec2(0.0, 0.0);
    let r = add(&mut child);
    let h = (child.min_rect().height() + 28.0).max(min_height);
    let rect = Rect::from_min_size(top, vec2(width, h));
    paint_card(ui, slot, rect, p);
    (rect, r)
}

pub fn paint_card(ui: &Ui, slot: egui::layers::ShapeIdx, rect: Rect, p: &Palette) {
    ui.painter().set(
        slot,
        Shape::Vec(vec![
            Shape::rect_filled(rect, 0.0, p.paper),
            Shape::rect_stroke(rect, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside),
            Shape::rect_stroke(rect.shrink(1.0), 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside),
        ]),
    );
}

/// A card's content, given what the cards of a row share (e.g. the icons), one card after the other.
pub type Card<'a, S> = Box<dyn FnOnce(&mut Ui, &mut S) + 'a>;

/// Java `row`: cards of equal width side by side (gap between), all as tall as the tallest; the cursor moves below.
pub fn row<S>(ui: &mut Ui, p: &Palette, gap: f32, state: &mut S, cards: Vec<Card<'_, S>>) {
    let n = cards.len() as f32;
    let left = ui.cursor().min;
    let w = ((ui.available_width() - gap * (n - 1.0)) / n).floor();
    let mut slots = Vec::new();
    let mut tallest: f32 = 0.0;
    for (i, add) in cards.into_iter().enumerate() {
        let x = left.x + i as f32 * (w + gap);
        let slot = ui.painter().add(Shape::Noop);
        let inner = Rect::from_min_size(pos2(x + 18.0, left.y + 14.0), vec2(w - 36.0, f32::INFINITY));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(inner).layout(egui::Layout::top_down(egui::Align::Min)));
        child.spacing_mut().item_spacing = vec2(0.0, 0.0);
        add(&mut child, state);
        tallest = tallest.max(child.min_rect().height() + 28.0);
        slots.push((slot, x));
    }
    for (slot, x) in slots {
        paint_card(ui, slot, Rect::from_min_size(pos2(x, left.y), vec2(w, tallest)), p);
    }
    ui.allocate_exact_size(vec2(ui.available_width(), tallest), Sense::hover());
}

/// The muted caption above a value (Java `caption`).
pub fn caption(ui: &mut Ui, p: &Palette, t: &str) -> egui::Response {
    label(ui, t, &small(), p.ink_muted)
}

/// A gold heading in the head font at 30 (Java `heading`).
pub fn heading(ui: &mut Ui, p: &Palette, t: &str) -> egui::Response {
    label(ui, t, &head_px(30.0), p.gold)
}

pub fn soft(ui: &mut Ui, p: &Palette, t: &str) -> egui::Response {
    label(ui, t, &text(18.0), p.ink_soft)
}

pub fn muted(ui: &mut Ui, t: &str) -> egui::Response {
    label(ui, t, &text(18.0), theme::fixed::MUTED)
}

/// An icon at its size at the cursor.
pub fn icon(ui: &mut Ui, tex: Option<&egui::TextureHandle>, size: f32) -> egui::Response {
    let (r, resp) = ui.allocate_exact_size(vec2(size, size), Sense::click());
    if let Some(t) = tex {
        ui.painter().image(t.id(), r, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
    }
    resp
}

/// Java's `Section`: a line-bordered panel with a header strip (a coloured square, the upper-case title in the tab
/// font, controls at the right) above a paper body. `actions` draws right-to-left from the header's right end and
/// gives back how wide they were; `body` fills the rest. Gives the whole rect.
pub fn section(
    ui: &mut Ui,
    p: &Palette,
    rect: Rect,
    title: &str,
    accent: Color32,
    actions: impl FnOnce(&mut Ui, Rect),
    body: impl FnOnce(&mut Ui, Rect),
) {
    let painter = ui.painter().clone();
    let font = theme::head(17.0);
    let th = jtext::height(&painter, &font);
    let head_h = th + 12.0 + 1.0;
    painter.rect_filled(rect, 0.0, p.paper);
    let head = Rect::from_min_size(rect.min + vec2(1.0, 1.0), vec2(rect.width() - 2.0, head_h - 1.0));
    painter.rect_filled(head, 0.0, p.header);
    painter.rect_filled(Rect::from_min_size(pos2(rect.left(), head.bottom()), vec2(rect.width(), 1.0)), 0.0, p.line);
    let sq = Rect::from_min_size(pos2(head.left() + 10.0, head.center().y - 4.0), vec2(8.0, 8.0));
    painter.rect_filled(sq, 0.0, accent);
    jtext::draw(&painter, pos2(sq.right() + 8.0, head.center().y), Align2::LEFT_CENTER, &title.to_uppercase(), &font, p.ink_soft);
    let mut a = ui.new_child(egui::UiBuilder::new().max_rect(head));
    actions(&mut a, Rect::from_min_max(pos2(head.left(), head.top() + 2.0), pos2(head.right() - 4.0, head.bottom() - 2.0)));
    let body_r = Rect::from_min_max(pos2(rect.left() + 1.0, head.bottom() + 1.0), pos2(rect.right() - 1.0, rect.bottom() - 1.0));
    let mut b = ui.new_child(egui::UiBuilder::new().max_rect(body_r).layout(egui::Layout::top_down(egui::Align::Min)));
    b.set_clip_rect(body_r.intersect(ui.clip_rect()));
    b.spacing_mut().item_spacing = vec2(0.0, 0.0);
    body(&mut b, body_r);
    painter.rect_stroke(rect, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
}

/// The height of a section's header strip.
pub fn section_head_height(painter: &egui::Painter) -> f32 {
    jtext::height(painter, &theme::head(17.0)) + 12.0
}

/// The width a row of buttons takes (see [`segmented`]).
pub fn segmented_width(painter: &egui::Painter, names: &[&str], joined: bool) -> f32 {
    let font = small();
    let w: f32 = names.iter().map(|n| jtext::width(painter, &n.to_uppercase(), &font) + 30.0).sum();
    w + if joined { 0.0 } else { 8.0 * (names.len() as f32 - 1.0) }
}

/// Java `Segmented`: toggle buttons in a row (`joined`: touching, as one control), the selected one gold. Drawn with
/// its left top at `at`, `h` high; gives the clicked one.
pub fn segmented(
    ui: &Ui,
    p: &Palette,
    at: egui::Pos2,
    h: f32,
    names: &[&str],
    selected: usize,
    joined: bool,
    disabled: &[usize],
) -> Option<usize> {
    let font = small();
    let painter = ui.painter().clone();
    let mut x = at.x;
    let mut clicked = None;
    for (i, n) in names.iter().enumerate() {
        let t = n.to_uppercase();
        let w = jtext::width(&painter, &t, &font) + 30.0;
        let r = Rect::from_min_size(pos2(x, at.y), vec2(w, h));
        let off = disabled.contains(&i);
        let resp =
            ui.interact(r, ui.scope_id().with(("seg", n, at.x as i32, at.y as i32)), if off { Sense::hover() } else { Sense::click() });
        let on = selected == i;
        let (bg, fg) = if on {
            (p.gold, p.on_accent)
        } else if off {
            (p.paper, p.ink_muted)
        } else if resp.hovered() {
            (p.header, p.ink_soft)
        } else {
            (p.paper, p.ink_soft)
        };
        painter.rect(r, 0.0, bg, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
        jtext::draw(&painter, r.center(), Align2::CENTER_CENTER, &t, &font, fg);
        if resp.clicked() {
            clicked = Some(i);
        }
        x += w + if joined { 0.0 } else { 8.0 };
    }
    clicked
}

/// Java's banner (SEASONAL / CRUCIBLE / UPE): a slab with notched corners, a dark bottom side, white text with a
/// grey outline; faded when off. Gives its size for a text.
pub fn banner_size(painter: &egui::Painter, text: &str) -> egui::Vec2 {
    let f = head_px(24.0);
    // Java's FontMetrics.getAscent (rounded up)
    let asc: f32 = (24.0f32 * 1125.0 / 1400.0).ceil();
    let h = asc + 18.0;
    let u = (h / 5.6).round().max(3.0);
    vec2(jtext::width(painter, &text.to_uppercase(), &f) + 2.0 * u + 12.0, h)
}

pub fn banner(painter: &egui::Painter, r: Rect, text: &str, bg: Color32, alpha: f32) {
    let f = head_px(24.0);
    let (w, h) = (r.width(), r.height());
    let u = (h / 5.6).round().max(3.0);
    let fade = |c: Color32| c.gamma_multiply(alpha);
    let rect = |x: f32, y: f32, ww: f32, hh: f32, c: Color32| {
        painter.rect_filled(Rect::from_min_size(pos2(r.left() + x, r.top() + y), vec2(ww, hh)), 0.0, fade(c))
    };
    let dark = crate::theme::mix(bg, Color32::BLACK, 0.76);
    rect(u, 0.0, w - 2.0 * u, h, Color32::BLACK);
    rect(0.0, u, w, h - 2.0 * u, Color32::BLACK);
    rect(u + 1.0, 1.0, w - 2.0 * u - 2.0, h - 2.0, dark);
    rect(1.0, u + 1.0, w - 2.0, h - 2.0 * u - 2.0, dark);
    rect(u + 1.0, 1.0, w - 2.0 * u - 2.0, h - 2.0 - u, bg);
    rect(1.0, u + 1.0, w - 2.0, h - 2.0 * u - 2.0 - ((u + 1.0) / 2.0).floor(), bg);
    let t = text.to_uppercase();
    let face = h - u;
    let tw = jtext::width(painter, &t, &f);
    let asc: f32 = (24.0f32 * 1125.0 / 1400.0).ceil();
    let base = ((face + asc) / 2.0).floor() - 1.0;
    let top = r.top() + base - asc;
    let x = r.left() + ((w - tw) / 2.0).floor();
    for dx in -1..=1 {
        for dy in -1..=1 {
            if dx != 0 || dy != 0 {
                jtext::draw(
                    painter,
                    pos2(x + dx as f32, top + dy as f32),
                    Align2::LEFT_TOP,
                    &t,
                    &f,
                    fade(Color32::from_rgb(0x5C, 0x5C, 0x5C)),
                );
            }
        }
    }
    jtext::draw(painter, pos2(x, top), Align2::LEFT_TOP, &t, &f, fade(Color32::WHITE));
}

/// Java `OutlinedText`: pixel-font text with a 2 px black outline and a shadow below; its size.
pub fn outlined_size(painter: &egui::Painter, text: &str, px: f32) -> egui::Vec2 {
    let f = head_px(px);
    vec2(jtext::width(painter, text, &f) + 6.0, (px * 1125.0 / 1400.0).ceil() + 6.0)
}

pub fn outlined_text(painter: &egui::Painter, r: Rect, text: &str, px: f32, color: Color32) {
    let f = head_px(px);
    let asc = (px * 1125.0 / 1400.0).ceil();
    let base = ((r.height() + asc) / 2.0).floor() - 1.0;
    let (x, top) = (r.left() + 2.0, r.top() + base - asc);
    for dx in -2..=2 {
        for dy in -2..=3 {
            if dx != 0 || dy != 0 {
                jtext::draw(painter, pos2(x + dx as f32, top + dy as f32), Align2::LEFT_TOP, text, &f, Color32::BLACK);
            }
        }
    }
    jtext::draw(painter, pos2(x, top), Align2::LEFT_TOP, text, &f, color);
}

/// Java `kpi`: a header-coloured box with a line border, the caption above a gold value.
pub fn kpi(ui: &mut Ui, p: &Palette, r: Rect, caption_text: &str, value: &str) {
    let painter = ui.painter();
    painter.rect(r, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let c = jtext::draw(painter, pos2(r.left() + 9.0, r.top() + 5.0), Align2::LEFT_TOP, &caption_text.to_uppercase(), &small(), p.ink_soft);
    jtext::draw(painter, pos2(r.left() + 9.0, c.bottom()), Align2::LEFT_TOP, value, &text(24.0), crate::theme::fixed::GOLD);
}

/// A KPI box's height.
pub fn kpi_height(painter: &egui::Painter) -> f32 {
    jtext::height(painter, &small()) + jtext::height(painter, &text(24.0)) + 10.0
}

/// A [`section`] as tall as its content (Java `CharactersView.box`: the content inside a 10 px border, 8 px below the
/// box): `body` draws from the given top left within the width and gives back how tall it was. Gives the box's
/// height with the gap.
pub fn section_box(
    ui: &mut Ui,
    p: &Palette,
    at: egui::Pos2,
    w: f32,
    title: &str,
    accent: Color32,
    actions: impl FnOnce(&mut Ui, Rect),
    body: impl FnOnce(&mut Ui, Rect) -> f32,
) -> f32 {
    let painter = ui.painter().clone();
    let bg = painter.add(Shape::Noop);
    let font = theme::head(17.0);
    let head_h = jtext::height(&painter, &font) + 12.0;
    let head = Rect::from_min_size(at + vec2(1.0, 1.0), vec2(w - 2.0, head_h));
    let sq = Rect::from_min_size(pos2(head.left() + 10.0, head.center().y - 4.0), vec2(8.0, 8.0));
    painter.rect_filled(sq, 0.0, accent);
    jtext::draw(&painter, pos2(sq.right() + 8.0, head.center().y), Align2::LEFT_CENTER, &title.to_uppercase(), &font, p.ink_soft);
    let mut a = ui.new_child(egui::UiBuilder::new().max_rect(head));
    actions(&mut a, Rect::from_min_max(pos2(head.left(), head.top() + 2.0), pos2(head.right() - 4.0, head.bottom() - 2.0)));
    let inner = Rect::from_min_size(pos2(at.x + 11.0, head.bottom() + 1.0 + 10.0), vec2(w - 22.0, f32::INFINITY));
    let mut b = ui.new_child(egui::UiBuilder::new().max_rect(inner).layout(egui::Layout::top_down(egui::Align::Min)));
    b.spacing_mut().item_spacing = vec2(0.0, 0.0);
    let content = body(&mut b, inner);
    let h = 1.0 + head_h + 1.0 + 10.0 + content + 10.0 + 1.0;
    let rect = Rect::from_min_size(at, vec2(w, h));
    painter.set(
        bg,
        Shape::Vec(vec![
            Shape::rect_filled(rect, 0.0, p.paper),
            Shape::rect_filled(head, 0.0, p.header),
            Shape::rect_filled(Rect::from_min_size(pos2(rect.left(), head.bottom()), vec2(w, 1.0)), 0.0, p.line),
        ]),
    );
    painter.rect_stroke(rect, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
    h + 8.0
}

/// Text cut to fit a width, ending in "..." (Java's label when it gets less room than it needs).
pub fn fit(painter: &egui::Painter, t: &str, font: &FontId, w: f32) -> String {
    if jtext::width(painter, t, font) <= w {
        return t.to_string();
    }
    let mut s: String = t.to_string();
    while !s.is_empty() && jtext::width(painter, &format!("{s}..."), font) > w {
        s.pop();
    }
    format!("{}...", s.trim_end())
}

/// Text wrapped at word boundaries to a width (Java `JTextArea` with word wrap): its lines.
pub fn wrap(painter: &egui::Painter, t: &str, font: &FontId, width: f32) -> Vec<String> {
    let mut lines = Vec::new();
    let mut line = String::new();
    for word in t.split(' ') {
        let candidate = if line.is_empty() { word.to_string() } else { format!("{line} {word}") };
        if !line.is_empty() && jtext::width(painter, &candidate, font) > width {
            lines.push(std::mem::take(&mut line));
            line = word.to_string();
        } else {
            line = candidate;
        }
    }
    lines.push(line);
    lines
}

/// Wrapped text (with Java's 2 px above and below) at the top left; gives its height.
pub fn wrapped(painter: &egui::Painter, at: egui::Pos2, t: &str, font: &FontId, width: f32, color: Color32) -> f32 {
    let lh = jtext::height(painter, font);
    let lines = wrap(painter, t, font, width);
    for (i, l) in lines.iter().enumerate() {
        jtext::draw(painter, pos2(at.x, at.y + 2.0 + i as f32 * lh), Align2::LEFT_TOP, l, font, color);
    }
    lines.len() as f32 * lh + 4.0
}

/// Java `Color.brighter`.
pub fn brighter(c: Color32) -> Color32 {
    let f = |v: u8| -> u8 {
        let v = if v == 0 { 3.0 } else { f32::from(v) };
        (v / 0.7).min(255.0) as u8
    };
    if c.r() == 0 && c.g() == 0 && c.b() == 0 {
        return Color32::from_rgb(3, 3, 3);
    }
    Color32::from_rgb(if c.r() > 0 { f(c.r()) } else { 0 }, if c.g() > 0 { f(c.g()) } else { 0 }, if c.b() > 0 { f(c.b()) } else { 0 })
}
