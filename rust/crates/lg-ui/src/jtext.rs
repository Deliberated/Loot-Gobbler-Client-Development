//! Text as Java 2D draws it: each letter's advance rounded to whole pixels (no fractional metrics), no kerning.
//! So widths (`FontMetrics.stringWidth`) and letter positions are the Java app's.

use egui::{Align2, Color32, FontId, Painter, Pos2, Rect, pos2, vec2};

/// The rounded advance of one letter.
fn advance(painter: &Painter, c: char, font: &FontId) -> f32 {
    let g = painter.layout_no_wrap(c.to_string(), font.clone(), Color32::WHITE);
    g.size().x.round()
}

/// Java `FontMetrics.stringWidth`.
pub fn width(painter: &Painter, text: &str, font: &FontId) -> f32 {
    text.chars().map(|c| advance(painter, c, font)).sum()
}

/// Java `FontMetrics.getHeight` (ascent + descent + leading).
pub fn height(painter: &Painter, font: &FontId) -> f32 {
    painter.layout_no_wrap("X".into(), font.clone(), Color32::WHITE).size().y
}

/// Draws `text` aligned at `pos` as Java would; gives the box it takes.
pub fn draw(painter: &Painter, pos: Pos2, align: Align2, text: &str, font: &FontId, color: Color32) -> Rect {
    let size = vec2(width(painter, text, font), height(painter, font));
    let r = align.anchor_size(pos, size);
    let mut x = r.left().round();
    let top = r.top().round();
    for c in text.chars() {
        let g = painter.layout_no_wrap(c.to_string(), font.clone(), color);
        let a = g.size().x.round();
        painter.galley(pos2(x, top), g, color);
        x += a;
    }
    r
}

/// Java's derived bold (synthetic emboldening): each letter's advance grows by a 24th of the size before rounding,
/// and the letter is drawn twice, a pixel apart.
fn bold_advance(painter: &Painter, c: char, font: &FontId) -> f32 {
    let g = painter.layout_no_wrap(c.to_string(), font.clone(), Color32::WHITE);
    (g.size().x + font.size / 24.0).round()
}

pub fn bold_width(painter: &Painter, text: &str, font: &FontId) -> f32 {
    text.chars().map(|c| bold_advance(painter, c, font)).sum()
}

/// Draws `text` in Java's derived bold (see [`bold_advance`]); gives the box it takes.
pub fn draw_bold(painter: &Painter, pos: Pos2, align: Align2, text: &str, font: &FontId, color: Color32) -> Rect {
    let size = vec2(bold_width(painter, text, font), height(painter, font));
    let r = align.anchor_size(pos, size);
    let mut x = r.left().round();
    let top = r.top().round();
    for c in text.chars() {
        let g = painter.layout_no_wrap(c.to_string(), font.clone(), color);
        let a = (g.size().x + font.size / 24.0).round();
        painter.galley(pos2(x + 1.0, top), g.clone(), color);
        painter.galley(pos2(x, top), g, color);
        x += a;
    }
    r
}
