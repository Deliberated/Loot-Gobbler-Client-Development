//! The Server tab (Java `ServerPanel`): the Loot Gobbler server this client connects to, and the account on it.
//! The app does not talk to a server yet: it shows the address kept in the settings, not connected.

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Stroke, StrokeKind, Ui, pos2, vec2};

/// A text field (Java `JTextField`): paper with a line border, the text or its placeholder.
pub fn text_field(painter: &egui::Painter, p: &Palette, r: Rect, value: &str, placeholder: &str) {
    painter.rect(r, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let f = text(18.0);
    let clip = painter.with_clip_rect(r.shrink(2.0).intersect(painter.clip_rect()));
    if value.is_empty() {
        jtext::draw(&clip, pos2(r.left() + 8.0, r.center().y), Align2::LEFT_CENTER, placeholder, &f, p.ink_muted);
    } else {
        jtext::draw(&clip, pos2(r.left() + 8.0, r.center().y), Align2::LEFT_CENTER, value, &f, p.ink);
    }
}

/// A button by its left edge; gives its rect (primary: gold).
fn button_at(ui: &Ui, p: &Palette, x: f32, cy: f32, label: &str, primary: bool) -> Rect {
    let painter = ui.painter();
    let w = jtext::width(painter, &label.to_uppercase(), &small()) + 32.0;
    let r = Rect::from_min_size(pos2(x, cy - 18.0), vec2(w, 36.0));
    if primary {
        painter.rect(r, 0.0, p.gold, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
        jtext::draw(painter, r.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &small(), p.on_accent);
    } else {
        crate::inventory::button(ui, p, r, label, false, label);
    }
    r
}

pub fn show(ui: &mut Ui, c: &mut Ctx) {
    let p = *c.p;
    let url = c.props.get("serverUrl").unwrap_or("").to_string();
    let all = ui.max_rect().shrink(8.0);
    let f = text(18.0);
    let mut y = all.top();
    y += widgets::section_box(
        ui,
        &p,
        pos2(all.left(), y),
        all.width(),
        "Connection",
        fixed::BLUE,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter().clone();
            // the address row: label, field, Connect, Test connection, Forget
            let cy = r.top() - 10.0 + 10.0 + 4.0 + 18.0;
            let lr = jtext::draw(&painter, pos2(r.left() - 11.0 + 12.0 + 6.0, cy), Align2::LEFT_CENTER, "Server address", &f, p.ink);
            let field = Rect::from_min_size(pos2(lr.right() + 6.0, cy - 17.0), vec2(330.0, 34.0));
            text_field(&painter, &p, field, &url, "loot.example.com");
            let b = button_at(ui, &p, field.right() + 6.0, cy, "Connect", true);
            let b = button_at(ui, &p, b.right() + 6.0, cy, "Test connection", false);
            if !url.is_empty() {
                button_at(ui, &p, b.right() + 6.0, cy, "Forget", false);
            }
            // not connected
            let sy = cy + 18.0 + 4.0 + 4.0;
            let fh = jtext::height(&painter, &f);
            let sq = Rect::from_min_size(pos2(r.left() - 11.0 + 12.0 + 6.0, sy + fh / 2.0 - 5.0), vec2(10.0, 10.0));
            painter.rect_filled(sq, 0.0, fixed::MUTED);
            jtext::draw(
                &painter,
                pos2(sq.right() + 10.0, sy + fh / 2.0),
                Align2::LEFT_CENTER,
                "Not connected. Enter the address the server's host gave you.",
                &f,
                fixed::MUTED,
            );
            // the address row, the status line, then the empty feedback line (6 above it), 12 below all
            44.0 + 4.0 + fh + 6.0 + 6.0 + fh + 2.0
        },
    );
    widgets::section_box(
        ui,
        &p,
        pos2(all.left(), y),
        all.width(),
        "Account",
        fixed::GOLD,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter();
            jtext::draw(
                painter,
                pos2(r.left() - 11.0 + 12.0, r.top()),
                Align2::LEFT_TOP,
                "Connect to a server to log in or register.",
                &f,
                fixed::MUTED,
            );
            // and the empty feedback line under it
            2.0 * jtext::height(painter, &f) + 6.0 + 2.0
        },
    );
}
