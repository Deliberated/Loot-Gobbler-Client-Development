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
