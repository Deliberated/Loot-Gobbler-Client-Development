//! The box in the menu bar after the menus while you are in a dungeon (Java `ActiveDungeonBadge`): the dungeon's
//! portal, "Duration: m:ss" (time in the dungeon), once you hit its main boss "Rank #x / y", and at the right edge
//! the game's difficulty rating (number and gravestone), in Moonlight Village with the flames of the current phase
//! left of it. Tinted in a darkened version of the portal's colours; hidden outside dungeons. A click opens the
//! dungeon under Battles > Dungeons.

use crate::icons::Icons;
use crate::jtext;
use crate::overlays::{gradient_round_rect, shade, tint};
use crate::widgets::text;
use egui::{Align2, Color32, Pos2, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};

const ICON: f32 = 26.0;
const GRAVESTONE: i32 = 1282;
const FLAME: i32 = 20586;
/// The same example as in Battles.
pub const PREVIEW_DUNGEON: &str = "Pirate Cave";
const TEXT: Color32 = Color32::from_rgb(0xF2, 0xEC, 0xDC);

fn duration(ms: i64) -> String {
    let s = (ms / 1000).max(0);
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

/// The badge at `at` in a bar `h` high, for the dungeon you are in, else (`preview`: the Newcomer's Guide shows
/// it) a sample in for 1:12. Gives where it is (None: hidden) and whether it was clicked.
pub fn show(ui: &mut Ui, icons: &mut Icons, app: &lg_app::App, now: i64, at: Pos2, h: f32, preview: Option<i64>) -> (Option<Rect>, bool) {
    let visit = app.dungeon_visits.current.as_ref();
    let sample = visit.is_none();
    let (name, start) = match (visit, preview) {
        (Some(v), _) => (v.dungeon.as_str(), v.start),
        (None, Some(since)) => (PREVIEW_DUNGEON, since),
        (None, None) => return (None, false),
    };
    let data = app.world.data();
    let ctx = ui.ctx().clone();
    let painter = ui.painter().clone();
    let f = text(18.0);
    let big = text((18.0f32 * 1.3).floor());
    // the rank only once you have one (you hit the main boss)
    let run = if sample { None } else { app.dps_history.live.as_ref().filter(|r| !r.guild_hall && r.dungeon == name) };
    let rank = run
        .and_then(|r| r.main_boss())
        .and_then(|b| b.players.iter().position(|x| x.me).map(|i| format!("Rank #{} / {}", i + 1, b.players.len())));
    let difficulty = data.difficulty.get(name).map(|d| if d.fract() == 0.0 { format!("{}", *d as i64) } else { format!("{d}") });
    let flames = visit.filter(|v| v.dungeon == "Moonlight Village").and_then(|v| v.mv_phases.last()).map(|ph| ph.flames.to_string());
    let asc = |font: &egui::FontId| (font.size * 1050.0 / 1350.0).ceil();
    let flame_w = flames.as_ref().map_or(0.0, |n| jtext::bold_width(&painter, n, &big) + 3.0 + asc(&big));
    // always the same width: enough for the longest it shows, so it does not grow and shrink as the time runs
    let w = 22.0
        + ICON
        + 30.0
        + jtext::width(&painter, "Duration: ", &f)
        + jtext::bold_width(&painter, "0:00:00", &f)
        + jtext::bold_width(&painter, "Rank #00 / 00", &f)
        + 12.0
        + jtext::bold_width(&painter, "10.5", &big)
        + 5.0
        + asc(&big)
        + 4.0
        + flame_w;
    let r = Rect::from_min_size(at, vec2(w.ceil(), h));
    let portal = data.dungeons.portal_by_name.get(name).copied().unwrap_or(-1);
    let img = icons.fitted_image(data, portal, 64, ICON as u32);
    let tint = tint(img.as_ref());
    let box_r = Rect::from_min_max(pos2(r.left() + 4.0, r.top() + 2.0), pos2(r.right() - 5.0, r.bottom() - 3.0));
    gradient_round_rect(&painter, box_r, 5.0, shade(tint, 1.2), shade(tint, 0.8));
    painter.rect_stroke(box_r, 5.0, Stroke::new(1.0, shade(tint, 1.9)), StrokeKind::Middle);
    let cy = r.center().y - 1.0;
    let mut x = r.left() + 22.0;
    let icon_r = Rect::from_min_size(pos2(x, (cy - ICON / 2.0).round()), vec2(ICON, ICON));
    if let Some(t) = icons.fitted(&ctx, data, portal, 64, ICON as u32) {
        painter.image(t.id(), icon_r, crate::detail::uv(), Color32::WHITE);
    }
    x += ICON + 10.0;
    let base = (cy + asc(&f) / 2.0).round();
    let t = jtext::draw(&painter, pos2(x, base - asc(&f)), Align2::LEFT_TOP, "Duration: ", &f, TEXT);
    let t = jtext::draw_bold(&painter, pos2(t.right(), base - asc(&f)), Align2::LEFT_TOP, &duration(now - start), &f, TEXT);
    x = t.right() + 10.0;
    if let Some(rank) = &rank {
        jtext::draw_bold(&painter, pos2(x, base - asc(&f)), Align2::LEFT_TOP, rank, &f, TEXT);
    }
    // at the right edge, as the game shows it: a big number and the gravestone, on the text's line
    let mut rx = r.right() - 10.0;
    if let Some(d) = &difficulty {
        let digits = (big.size * 0.62).ceil() + 1.0;
        let right = rx;
        if let Some(g) = icons.plain_cut(&ctx, data, GRAVESTONE, digits as u32) {
            let [gw, gh] = g.size();
            rx -= gw as f32;
            painter.image(
                g.id(),
                Rect::from_min_size(pos2(rx, base - gh as f32), vec2(gw as f32, gh as f32)),
                crate::detail::uv(),
                Color32::WHITE,
            );
            rx -= 5.0;
        }
        let tw = jtext::bold_width(&painter, d, &big);
        jtext::draw_bold(&painter, pos2(rx - tw, base - asc(&big)), Align2::LEFT_TOP, d, &big, TEXT);
        rx -= tw;
        crate::widgets::tip(
            ui,
            Rect::from_min_max(pos2(rx, base - asc(&big)), pos2(right, base)),
            "badge-difficulty",
            "Dungeon difficulty",
        );
    }
    if let Some(n) = &flames {
        rx -= 12.0;
        let tw = jtext::bold_width(&painter, n, &big);
        if let Some(t) = icons.fitted(&ctx, data, FLAME, 40, asc(&big) as u32) {
            rx -= asc(&big);
            painter.image(
                t.id(),
                Rect::from_min_size(pos2(rx, base - asc(&big)), vec2(asc(&big), asc(&big))),
                crate::detail::uv(),
                Color32::WHITE,
            );
            rx -= 3.0;
        }
        jtext::draw_bold(&painter, pos2(rx - tw, base - asc(&big)), Align2::LEFT_TOP, n, &big, TEXT);
    }
    let tip = if sample { format!("{name} (an example: this is how a dungeon you are in shows)") } else { name.to_string() };
    crate::widgets::tip(ui, icon_r, "badge-portal", &tip);
    let resp = ui.interact(r, ui.scope_id().with("title-dungeon"), Sense::click()).on_hover_cursor(egui::CursorIcon::PointingHand);
    let clicked = resp.clicked();
    if !icon_r.contains(ui.input(|i| i.pointer.hover_pos()).unwrap_or_default()) {
        resp.on_hover_text("Open this dungeon in Battles");
    }
    // the time moves on
    ctx.request_repaint_after(std::time::Duration::from_millis(1000));
    (Some(r), clicked)
}
