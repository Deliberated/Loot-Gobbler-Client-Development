//! The overlays as windows of their own (Java `Overlay`): above all others, no frame, see-through as set, never
//! taking the focus; dragged anywhere, where they are let go is kept. Each shows what is live, or a short note when
//! it has nothing to show.

use crate::icons::Icons;
use crate::overlay_page::{key_of, opacity};
use crate::overlays::{self, Badge, Cooldown, DpsLine};
use crate::window::Ask;
use egui::{Color32, Pos2, Rect, Stroke, StrokeKind, Vec2, pos2, vec2};
use lg_app::App;
use lg_app::properties::Properties;
use std::collections::HashMap;

const OVERLAYS: [(&str, &str); 4] =
    [("dungeon", "Not in a dungeon"), ("mana", "No character"), ("dps", "No boss fight"), ("buffs", "No cooldowns running")];
const MATRIX_COOLDOWN_MS: i64 = 180_000;

/// What each overlay window last was: its size (to keep its middle, for the centred one).
#[derive(Default)]
pub struct OverlayWindows {
    sizes: HashMap<&'static str, Vec2>,
}

/// The note box of an overlay with nothing to show (Java's `RoundBox` with the idle text).
fn idle(painter: &egui::Painter, at: Pos2, t: &str) -> Vec2 {
    let f = crate::widgets::text(18.0);
    let w = crate::jtext::width(painter, t, &f) + 32.0;
    let h = crate::jtext::height(painter, &f) + 18.0;
    let r = Rect::from_min_size(at, vec2(w, h) - vec2(1.0, 1.0));
    painter.rect(r, 5.0, Color32::from_rgb(0x2A, 0x26, 0x20), Stroke::new(1.0, Color32::from_rgb(0x5A, 0x54, 0x4A)), StrokeKind::Middle);
    crate::jtext::draw(painter, r.center(), egui::Align2::CENTER_CENTER, t, &f, Color32::from_rgb(0xF2, 0xEC, 0xDC));
    vec2(w, h)
}

/// Paints one overlay's live content at the window's corner; gives its size.
fn content(id: &str, painter: &egui::Painter, ctx: &egui::Context, icons: &mut Icons, app: &App, now: i64) -> Option<Vec2> {
    let data = app.world.data();
    let at = pos2(0.0, 0.0);
    match id {
        "dungeon" => {
            let v = app.dungeon_visits.current.as_ref()?;
            let run = app.dps_history.live.as_ref().filter(|r| !r.guild_hall && r.dungeon == v.dungeon);
            let rank = run
                .and_then(|r| r.main_boss())
                .and_then(|b| b.players.iter().position(|x| x.me).map(|i| format!("#{}/{}", i + 1, b.players.len())));
            let last = app.amulet_saves.saves.iter().rev().find(|s| !s.removed).map_or(0, |s| s.t);
            let amulet = last >= v.start && now - last < lg_app::amulet_saves::RECHARGE_MS;
            let c = &app.cooldowns;
            let matrix =
                (c.matrix_fired >= v.start && now - c.matrix_fired < MATRIX_COOLDOWN_MS && c.matrix_type >= 0).then_some(c.matrix_type);
            let moonlight = (v.dungeon == "Moonlight Village").then(|| moonlight(v));
            let b = Badge { dungeon: &v.dungeon, rank, amulet, matrix, moonlight };
            Some(overlays::badge(painter, ctx, icons, data, &b, at))
        }
        "mana" => app.vitals.fresh(now).then(|| overlays::mana_bar(painter, at, app.vitals.mp, app.vitals.max_mp)),
        "dps" => {
            // your latest boss in the dungeon you are in
            let v = app.dungeon_visits.current.as_ref()?;
            let run = app.dps_history.live.as_ref().filter(|r| !r.guild_hall && r.dungeon == v.dungeon)?;
            let b = run.bosses.iter().rev().find(|b| b.players.iter().any(|x| x.me))?;
            let total: i64 = b.players.iter().map(|x| i64::from(x.damage)).sum();
            let share = |d: i32| if total == 0 { 0.0 } else { 100.0 * f64::from(d) / total as f64 };
            let me = b.players.iter().position(|x| x.me)?;
            let from = (me as i64 - 1).min(b.players.len() as i64 - 3).max(0) as usize;
            let lines: Vec<DpsLine> = b.players[from..(from + 3).min(b.players.len())]
                .iter()
                .enumerate()
                .map(|(k, x)| DpsLine {
                    place: from + k + 1,
                    name: x.name.clone(),
                    damage: i64::from(x.damage),
                    share: share(x.damage),
                    me: x.me,
                })
                .collect();
            let max_share = b.players.first().map_or(1.0, |x| share(x.damage).max(0.001));
            Some(overlays::dps_overlay(painter, at, b.name.as_deref().unwrap_or(""), &lines, max_share))
        }
        _ => {
            let mut tiles = Vec::new();
            let last = app.amulet_saves.saves.iter().rev().find(|s| !s.removed).map_or(0, |s| s.t);
            if last > 0 && now - last < lg_app::amulet_saves::RECHARGE_MS {
                tiles.push(Cooldown { item: data.amulets.restoration, start: last, duration_ms: lg_app::amulet_saves::RECHARGE_MS });
            }
            for r in app.cooldowns.running(now) {
                tiles.push(Cooldown { item: r.item, start: r.start, duration_ms: r.duration_ms });
            }
            Some(overlays::buffs(painter, ctx, icons, data, at, &tiles, now))
        }
    }
}

/// Java `OverlayBadge.refresh` in Moonlight Village: the flames of the fight you are in (the three dancers'
/// phases, or once Umi starts only hers), whether they are the dancers', the latest phase's flames ("..." while
/// it runs, counting up at a lantern) and whether that phase ended (held, shown with FINAL).
fn moonlight(v: &lg_app::dungeon_visits::Visit) -> (i32, bool, String, bool) {
    const DANCERS: [i32; 3] = [20450, 20451, 20452];
    const MAX_FLAMES: i32 = 8;
    let phases = &v.mv_phases;
    let dancers = phases.iter().rev().find(|ph| ph.boss != 0).map(|ph| DANCERS.contains(&ph.boss));
    let now = phases.last();
    let held = now.is_some_and(|n| n.duration_ms > 0);
    let live = now.is_some_and(|n| !held && n.lantern && n.beats_max > 0);
    let so_far = |ph: &lg_app::dungeon_visits::MvPhase| {
        if !ph.lantern || ph.beats_max <= 0 { 0 } else { MAX_FLAMES.min(ph.beats * MAX_FLAMES / ph.beats_max) }
    };
    let mut flames = 0;
    for (i, ph) in phases.iter().enumerate() {
        let of_dancers = if ph.boss == 0 { dancers.is_none_or(|d| d) } else { DANCERS.contains(&ph.boss) };
        if dancers.is_none() || Some(of_dancers) == dancers {
            flames += if i + 1 == phases.len() && live { so_far(ph) } else { ph.flames };
        }
    }
    let phase = match now {
        None => "0".to_string(),
        Some(n) if held => n.flames.to_string(),
        Some(n) if live => so_far(n).to_string(),
        Some(_) => "...".to_string(),
    };
    (flames, dancers.unwrap_or(true), phase, held)
}

impl OverlayWindows {
    /// The overlays switched on, each in its window, this frame.
    pub fn show(&mut self, ctx: &egui::Context, icons: &mut Icons, app: &App, props: &Properties, now: i64, asks: &mut Vec<Ask>) {
        for (k, (id, idle_text)) in OVERLAYS.iter().enumerate() {
            if props.get(&key_of(id, "On")) != Some("true") {
                self.sizes.remove(id);
                continue;
            }
            let centred = *id == "buffs";
            let size = self.sizes.get(id).copied().unwrap_or(vec2(200.0, 40.0));
            let num = |what: &str| props.get(&key_of(id, what)).and_then(|v| v.trim().parse::<f32>().ok());
            // where it was let go; else near the top right, one below the other (the centred one: under you)
            let screen = ctx.input(|i| i.viewport().monitor_size).unwrap_or(vec2(1920.0, 1080.0));
            let x = if centred {
                num("CX").unwrap_or((screen.x - screen.y / 3.0) / 2.0) - size.x / 2.0
            } else {
                num("X").unwrap_or(screen.x - size.x - 40.0)
            };
            let y = num("Y").unwrap_or(if centred { (screen.y * 0.70).round() } else { 80.0 + 70.0 * k as f32 });
            let op = opacity(props, id) as f32 / 100.0;
            let vid = egui::ViewportId::from_hash_of(("overlay", id));
            let builder = egui::ViewportBuilder::default()
                .with_title(format!("Loot Gobbler {id} overlay"))
                .with_decorations(false)
                .with_transparent(true)
                .with_always_on_top()
                .with_taskbar(false)
                .with_active(false)
                .with_resizable(false)
                .with_position(pos2(x, y))
                .with_inner_size(size);
            let mut new_size = size;
            let mut released: Option<Pos2> = None;
            ctx.show_viewport_immediate(vid, builder, |ui, _| {
                let vctx = ui.ctx().clone();
                let mut painter = ui.painter().clone();
                painter.set_opacity(op);
                let got = content(id, &painter, &vctx, icons, app, now);
                new_size = match got {
                    Some(s) => s,
                    None => idle(&painter, pos2(0.0, 0.0), idle_text),
                };
                // dragging anywhere moves it; where it is let go is kept
                let resp =
                    ui.interact(Rect::from_min_size(Pos2::ZERO, new_size), ui.scope_id().with("drag"), egui::Sense::click_and_drag());
                if resp.drag_started() {
                    vctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
                }
                if resp.drag_stopped() || resp.clicked() {
                    released = vctx.input(|i| i.viewport().outer_rect).map(|r| r.min);
                }
                if resp.hovered() {
                    vctx.set_cursor_icon(egui::CursorIcon::Move);
                }
                if (new_size - size).length() > 0.5 {
                    vctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(new_size));
                    if centred {
                        vctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(pos2(x + size.x / 2.0 - new_size.x / 2.0, y)));
                    }
                }
            });
            self.sizes.insert(id, new_size);
            if let Some(p) = released {
                if centred {
                    asks.push(Ask::Set(key_of(id, "CX"), format!("{}", (p.x + new_size.x / 2.0).round())));
                } else {
                    asks.push(Ask::Set(key_of(id, "X"), format!("{}", p.x.round())));
                }
                asks.push(Ask::Set(key_of(id, "Y"), format!("{}", p.y.round())));
            }
        }
    }
}
