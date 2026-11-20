//! The Social tab: Chat (the chat log with its channels), Daily Quests and Discord (both greyed out under a notice,
//! as in the Java app: under construction).

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::chat::Channel;

const CHANNELS: [Channel; 4] = [Channel::Guild, Channel::Party, Channel::Pm, Channel::Public];

fn channel_color(c: Channel) -> Color32 {
    match c {
        Channel::Guild => fixed::BLUE,
        Channel::Party => fixed::GREEN,
        Channel::Pm => fixed::GOLD,
        Channel::Public => fixed::STONE,
        Channel::System => fixed::RED,
    }
}

#[derive(Default)]
pub struct SocialView {
    pub page: usize,
}

/// Java `PixelCheckBoxIcon`: a square, gold with a darker core when ticked.
pub fn pixel_check(painter: &egui::Painter, p: &Palette, at: egui::Pos2, on: bool) -> Rect {
    let s = 19.0;
    let r = Rect::from_min_size(at, vec2(s, s));
    painter.rect_filled(r, 0.0, if on { p.gold } else { p.header });
    if on {
        painter.rect_filled(r.shrink(4.0), 0.0, p.gold_dark);
    }
    painter.rect_stroke(r, 0.0, Stroke::new(1.0, if on { p.gold_dark } else { p.line }), StrokeKind::Inside);
    r
}

/// Java `UnderConstruction`: the notice above a page that is drawn greyed out; gives the page's rect and draws
/// the wash once `page` has painted.
fn under_construction(ui: &mut Ui, p: &Palette, r: Rect, message: &str, page: impl FnOnce(&mut Ui, Rect)) {
    let painter = ui.painter().clone();
    let f = text(18.0);
    let h = jtext::height(&painter, &f) + 20.0;
    jtext::draw_bold(&painter, pos2(r.center().x, r.top() + h / 2.0), Align2::CENTER_CENTER, message, &f, fixed::GOLD);
    let pr = Rect::from_min_max(pos2(r.left(), r.top() + h), r.max);
    page(ui, pr);
    let bg = p.sand;
    painter.rect_filled(pr, 0.0, Color32::from_rgba_unmultiplied(bg.r(), bg.g(), bg.b(), 170));
}

impl SocialView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx) {
        let p = *c.p;
        let all = ui.max_rect().shrink(8.0);
        if let Some(i) =
            widgets::segmented(ui, &p, pos2(all.left() + 7.0, all.top()), 36.0, &["Chat", "Daily Quests", "Discord"], self.page, false, &[])
        {
            self.page = i;
        }
        let body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        match self.page {
            0 => chat(ui, c, body.shrink(8.0)),
            1 => under_construction(
                ui,
                &p,
                body,
                "Daily quests are under construction - they will come from the Loot Gobbler server.",
                |ui, r| {
                    let painter = ui.painter();
                    painter.rect_stroke(r, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                    jtext::draw(
                        painter,
                        pos2(r.center().x, r.top() + 6.0),
                        Align2::CENTER_TOP,
                        "Enter Daily Quest Room to see quests",
                        &text(18.0),
                        p.ink,
                    );
                },
            ),
            _ => under_construction(ui, &p, body, "Discord webhooks are under construction - nothing is posted for now.", |ui, r| {
                discord(ui, &p, r.shrink(8.0))
            }),
        }
    }
}

/// The chat log and the channels beside it (Java `ChatLogPanel`).
fn chat(ui: &mut Ui, c: &mut Ctx, r: Rect) {
    let p = *c.p;
    let app = c.app;
    let props = c.props;
    let asks = &mut *c.asks;
    let painter = ui.painter().clone();
    let f = text(18.0);
    let side_w = 216.0;
    let log = Rect::from_min_max(r.min, pos2(r.right() - side_w - 8.0, r.bottom()));
    let side = Rect::from_min_max(pos2(log.right() + 8.0, r.top()), r.max);
    let shown = |ch: Channel| ch == Channel::System || props.get(&format!("chatShow{}", ch.label())) != Some("false");
    widgets::section(
        ui,
        &p,
        log,
        "Chat Log",
        fixed::BLUE,
        |_, _| {},
        |ui, b| {
            let inner = b.shrink(6.0);
            ui.painter().rect_filled(inner, 0.0, p.paper);
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(inner));
            let row_h = jtext::height(&painter, &f) + 8.0;
            let lines: Vec<&lg_app::chat::Line> = app.chat.lines.iter().filter(|l| shown(l.channel)).collect();
            egui::ScrollArea::vertical().id_salt("chat").stick_to_bottom(true).show_rows(&mut child, row_h, lines.len(), |ui, range| {
                let top = ui.cursor().min;
                let w = ui.available_width();
                let painter = ui.painter();
                for i in range.clone() {
                    let l = lines[i];
                    let y = top.y + (i - range.start) as f32 * row_h;
                    let rr = Rect::from_min_size(pos2(top.x, y), vec2(w, row_h));
                    painter.rect_filled(rr, 0.0, if i % 2 == 1 { p.header } else { p.paper });
                    let cy = rr.center().y;
                    jtext::draw(painter, pos2(rr.left() + 10.0, cy), Align2::LEFT_CENTER, &l.time, &text(16.0), fixed::MUTED);
                    jtext::draw(
                        painter,
                        pos2(rr.left() + 80.0 + 10.0, cy),
                        Align2::LEFT_CENTER,
                        &l.channel.label().to_uppercase(),
                        &small(),
                        channel_color(l.channel),
                    );
                    let mx = rr.left() + 160.0 + 10.0;
                    let clip = painter.with_clip_rect(
                        Rect::from_min_max(pos2(mx, rr.top()), pos2(rr.right() - 10.0, rr.bottom())).intersect(painter.clip_rect()),
                    );
                    if l.name.is_empty() {
                        jtext::draw(&clip, pos2(mx, cy), Align2::LEFT_CENTER, &l.text, &f, p.ink);
                    } else {
                        let nr = jtext::draw_bold(&clip, pos2(mx, cy), Align2::LEFT_CENTER, &l.name, &f, p.ink);
                        jtext::draw(&clip, pos2(nr.right(), cy), Align2::LEFT_CENTER, &format!(": {}", l.text), &f, p.ink);
                    }
                }
                ui.allocate_exact_size(vec2(w, (range.end - range.start) as f32 * row_h), Sense::hover());
            });
        },
    );
    widgets::section(
        ui,
        &p,
        side,
        "Channels",
        fixed::PURPLE,
        |_, _| {},
        |ui, b| {
            let painter = ui.painter();
            let x = b.left() - 1.0 + 12.0;
            let mut y = b.top() - 1.0 + 10.0;
            jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, "Show channels:", &f, p.ink);
            y += jtext::height(painter, &f) + 8.0;
            let bw = jtext::width(painter, "CLEAR CHAT", &small()) + 32.0;
            if crate::inventory::button(ui, &p, Rect::from_min_size(pos2(x, y), vec2(bw, 36.0)), "Clear chat", true, "clear-chat") {
                asks.push(crate::window::Ask::ClearChat);
            }
            y += 36.0 + 12.0;
            let w = b.right() + 1.0 - 12.0 - x;
            for ch in CHANNELS {
                let rr = Rect::from_min_size(pos2(x, y), vec2(w, 42.0));
                painter.rect(rr, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
                let cb = pixel_check(painter, &p, pos2(rr.left() + 12.0, rr.center().y - 9.5), shown(ch));
                let sq = Rect::from_min_size(pos2(cb.right() + 12.0, rr.center().y - 4.0), vec2(8.0, 8.0));
                painter.rect_filled(sq, 0.0, channel_color(ch));
                jtext::draw(
                    painter,
                    pos2(sq.right() + 10.0, rr.center().y),
                    Align2::LEFT_CENTER,
                    &ch.label().to_uppercase(),
                    &crate::theme::head(17.0),
                    p.ink,
                );
                if ui.interact(rr, ui.scope_id().with(("channel", ch.label())), Sense::click()).clicked() {
                    asks.push(crate::window::Ask::Set(format!("chatShow{}", ch.label()), (!shown(ch)).to_string()));
                }
                y += 42.0 + 6.0;
            }
        },
    );
}

/// The Discord page (Java `DiscordPanel`) with no webhooks saved: the intro and "+ Add another webhook".
fn discord(ui: &mut Ui, p: &Palette, r: Rect) {
    widgets::section(
        ui,
        p,
        r,
        "Webhooks",
        fixed::PURPLE,
        |ui, head| {
            let painter = ui.painter();
            let aw = jtext::width(painter, "+ ADD WEBHOOK", &small()) + 32.0;
            let ar = Rect::from_min_size(pos2(head.right() - aw, head.top()), vec2(aw, head.height()));
            painter.rect(ar, 0.0, p.gold, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
            jtext::draw(painter, ar.center(), Align2::CENTER_CENTER, "+ ADD WEBHOOK", &small(), p.on_accent);
            let tw = jtext::width(painter, "SEND TEST LOOT", &small()) + 32.0;
            crate::inventory::button(
                ui,
                p,
                Rect::from_min_size(pos2(ar.left() - 6.0 - tw, head.top()), vec2(tw, head.height())),
                "Send test loot",
                false,
                "test-loot",
            );
        },
        |ui, b| {
            let painter = ui.painter();
            let f = text(18.0);
            let x = b.left() - 1.0 + 12.0;
            let mut y = b.top() - 1.0 + 10.0;
            for line in [
                "Each UT or ST item that drops for you is posted as a rendered loot card to every webhook below.",
                "In Discord: Edit Channel -> Integrations -> Webhooks -> New Webhook -> Copy Webhook URL, then paste it into a row and Save. Add an optional name to identify the Discord.",
            ] {
                jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, line, &f, p.ink);
                y += jtext::height(painter, &f);
            }
            y += 10.0;
            let w = b.right() + 1.0 - 12.0 - x;
            crate::inventory::button(ui, p, Rect::from_min_size(pos2(x, y), vec2(w, 36.0)), "+ Add another webhook", false, "add-hook");
        },
    );
}
