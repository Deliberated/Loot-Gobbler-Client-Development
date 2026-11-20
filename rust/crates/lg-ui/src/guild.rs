//! The Guild tab (Java `GuildPanel`): Loot Caravan, Quest Map, Leaderboard, Recent Drops, Member, Quests. All of it
//! comes from the Loot Gobbler server; until the app talks to one, each page says what it says logged out (the
//! caravan's night road empty).

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Stroke, StrokeKind, Ui, pos2, vec2};

const PAGES: [&str; 6] = ["Loot Caravan", "Quest Map", "Leaderboard", "Recent Drops", "Member", "Quests"];
const LOGGED_OUT: &str = "Log in to a server in the Server tab to see your guild.";

#[derive(Default)]
pub struct GuildView {
    pub page: usize,
}

fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

/// A plain button with its text as written (Java `JButton` without upper-casing).
fn plain_button(ui: &Ui, p: &Palette, head: Rect, label: &str) {
    let painter = ui.painter();
    let f = small();
    let w = jtext::width(painter, label, &f) + 40.0;
    let r = Rect::from_min_size(pos2(head.right() - 5.0 - w, head.top()), vec2(w, head.height()));
    painter.rect(r, 0.0, p.header, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
    jtext::draw(painter, r.center(), Align2::CENTER_CENTER, label, &f, p.ink);
}

impl GuildView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx) {
        let p = *c.p;
        let all = ui.max_rect().shrink(8.0);
        if let Some(i) = widgets::segmented(ui, &p, pos2(all.left() + 7.0, all.top()), 36.0, &PAGES, self.page, false, &[]) {
            self.page = i;
        }
        let body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        let f = text(18.0);
        let note = |ui: &mut Ui, r: Rect, t: &str| {
            jtext::draw(ui.painter(), pos2(r.left() + 14.0, r.top() + 12.0), Align2::LEFT_TOP, t, &f, fixed::MUTED);
        };
        match self.page {
            0 => caravan(ui, &p, body, c.now),
            1 => widgets::section(
                ui,
                &p,
                body,
                "Quest Map",
                fixed::GOLD,
                |ui, head| plain_button(ui, &p, head, "Refresh"),
                |ui, r| note(ui, r, "Log in to a server in the Server tab to see your guild's quest map."),
            ),
            2 => widgets::section(
                ui,
                &p,
                body,
                "Leaderboard",
                fixed::GOLD,
                |ui, head| plain_button(ui, &p, head, "REFRESH"),
                |ui, r| note(ui, r, LOGGED_OUT),
            ),
            3 => widgets::section(
                ui,
                &p,
                body,
                "Recent Drops",
                fixed::PURPLE,
                |ui, head| plain_button(ui, &p, head, "REFRESH"),
                |ui, r| note(ui, r, LOGGED_OUT),
            ),
            4 => widgets::section(
                ui,
                &p,
                body,
                "Member",
                fixed::BLUE,
                |_, _| {},
                |ui, r| note(ui, r, "Click a player in the leaderboard or in the recent drops."),
            ),
            _ => {
                // under construction: the notice, the empty section greyed out
                let painter = ui.painter().clone();
                let h = jtext::height(&painter, &f) + 20.0;
                jtext::draw_bold(
                    &painter,
                    pos2(body.center().x, body.top() + h / 2.0),
                    Align2::CENTER_CENTER,
                    "Guild quests are under construction.",
                    &f,
                    fixed::GOLD,
                );
                let pr = Rect::from_min_max(pos2(body.left(), body.top() + h), body.max);
                widgets::section(ui, &p, pr, "Quests", fixed::GOLD, |_, _| {}, |_, _| {});
                let bg = p.sand;
                painter.rect_filled(pr, 0.0, Color32::from_rgba_unmultiplied(bg.r(), bg.g(), bg.b(), 170));
            }
        }
    }
}

/// Java `CaravanView`: the night road in a frame, the status line under it.
fn caravan(ui: &mut Ui, p: &Palette, body: Rect, now: i64) {
    let f = text(18.0);
    widgets::section(
        ui,
        p,
        body,
        "Loot Caravan",
        fixed::GOLD,
        |ui, head| {
            let painter = ui.painter();
            // the note right after the title
            let tx = head.left() + 10.0 - 1.0 + 8.0 + 8.0 + jtext::width(painter, "LOOT CARAVAN", &crate::theme::head(17.0)) + 12.0;
            jtext::draw(painter, pos2(tx, head.center().y + 1.0), Align2::LEFT_CENTER, "Ctrl + mouse wheel zooms", &f, fixed::MUTED);
            plain_button(ui, p, head, "Refresh");
        },
        |ui, r| {
            let painter = ui.painter().clone();
            let x = r.left() + 12.0;
            let y = r.top() + 12.0;
            let w = r.right() - 12.0 - x;
            // the road's frame: 2 px, the scene and an empty scroll bar row under it
            let frame = Rect::from_min_size(pos2(x, y), vec2(w, 440.0 + 18.0 + 4.0));
            painter.rect_stroke(frame, 0.0, Stroke::new(2.0, p.line), StrokeKind::Inside);
            let scene = Rect::from_min_size(frame.min + vec2(2.0, 2.0), vec2(w - 4.0, 440.0));
            painter.rect_filled(Rect::from_min_max(pos2(scene.left(), scene.bottom()), frame.max - vec2(2.0, 2.0)), 0.0, p.paper);
            backdrop(&painter.with_clip_rect(scene.intersect(painter.clip_rect())), scene, now / 70);
            jtext::draw(&painter, pos2(x, frame.bottom() + 8.0), Align2::LEFT_TOP, LOGGED_OUT, &f, fixed::MUTED);
        },
    );
}

/// The sky with its stars and moon, the hills, the road with its moving lines (Java `Scene.paintBackdrop`).
fn backdrop(painter: &egui::Painter, r: Rect, phase: i64) {
    const HEIGHT: f32 = 440.0;
    let w = r.width().floor();
    let at = |x: f32, y: f32| pos2(r.left() + x, r.top() + y);
    let star = rgb(0xF4E6C6);
    painter.rect_filled(Rect::from_min_size(r.min, vec2(w, HEIGHT)), 0.0, rgb(0x141B2B));
    let mut rnd = lg_app::java::JavaRandom::new(7);
    for i in 0..40 {
        let sx = i64::from(rnd.next_int(10_000));
        let sy = 8 + rnd.next_int(150);
        let dim = (phase / 12 + i) % 7 == 0;
        // Color.darker() twice: 0.7 * 0.7
        let c = if dim {
            Color32::from_rgb(
                ((0xF4 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
                ((0xE6 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
                ((0xC6 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
            )
        } else {
            star
        };
        painter.rect_filled(Rect::from_min_size(at((sx * w as i64 / 10_000) as f32, sy as f32), vec2(4.0, 4.0)), 0.0, c);
    }
    let mx = w - 120f32.max((w / 7.0).floor());
    painter.rect_filled(Rect::from_min_size(at(mx - 6.0, 28.0), vec2(60.0, 60.0)), 0.0, Color32::from_rgba_unmultiplied(244, 230, 198, 30));
    painter.rect_filled(Rect::from_min_size(at(mx, 34.0), vec2(48.0, 48.0)), 0.0, star);
    painter.rect_filled(Rect::from_min_size(at(mx + 8.0, 44.0), vec2(10.0, 10.0)), 0.0, rgb(0xD2B988));
    painter.rect_filled(Rect::from_min_size(at(mx + 28.0, 62.0), vec2(6.0, 6.0)), 0.0, rgb(0xD2B988));
    let hills = |top: f32, height: f32, color: Color32, peaks: &[i32]| {
        let n = peaks.len() as i64;
        let mut pts: Vec<egui::Pos2> = peaks
            .iter()
            .enumerate()
            .map(|(i, pk)| at((i as i64 * w as i64 / (n - 1)) as f32, top + height - (height as i64 * i64::from(100 - pk) / 100) as f32))
            .collect();
        pts.push(at(w, top + height));
        pts.push(at(0.0, top + height));
        // not convex: a triangle strip between each top point and the band's bottom
        let mut mesh = egui::Mesh::default();
        let base = top + height;
        for k in 0..peaks.len() - 1 {
            let (a, b) = (pts[k], pts[k + 1]);
            let i = mesh.vertices.len() as u32;
            mesh.colored_vertex(a, color);
            mesh.colored_vertex(b, color);
            mesh.colored_vertex(pos2(b.x, r.top() + base), color);
            mesh.colored_vertex(pos2(a.x, r.top() + base), color);
            mesh.add_triangle(i, i + 1, i + 2);
            mesh.add_triangle(i, i + 2, i + 3);
        }
        painter.add(egui::Shape::mesh(mesh));
    };
    hills(HEIGHT - 150.0, 120.0, rgb(0x1E2A2A), &[70, 40, 55, 20, 50, 35, 60, 25, 50, 30, 55]);
    hills(HEIGHT - 110.0, 90.0, rgb(0x24301F), &[50, 30, 60, 35, 55, 30, 60, 40, 60]);
    painter.rect_filled(Rect::from_min_size(at(0.0, HEIGHT - 112.0), vec2(w, 112.0)), 0.0, rgb(0x4A3522));
    painter.rect_filled(Rect::from_min_size(at(0.0, HEIGHT - 112.0), vec2(w, 4.0)), 0.0, rgb(0x6B4E2E));
    let mut x = -52 + (phase * 2) % 52;
    while (x as f32) < w {
        painter.rect_filled(Rect::from_min_size(at(x as f32, HEIGHT - 50.0), vec2(26.0, 4.0)), 0.0, rgb(0x6B4E2E));
        x += 52;
    }
    let mut x = -40 + (phase * 2) % 40;
    while (x as f32) < w {
        painter.rect_filled(Rect::from_min_size(at(x as f32, HEIGHT - 22.0), vec2(14.0, 4.0)), 0.0, rgb(0x3A2818));
        x += 40;
    }
}
