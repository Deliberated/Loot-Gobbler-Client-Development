//! Battles > Overlay (Java `OverlaysPage`): one part per overlay with what it looks like (sample values, as see-through
//! as it is set), whether it is on, and how see-through it is.

use crate::jtext;
use crate::overlays::{self, Badge, Cooldown, DpsLine};
use crate::profile::Ctx;
use crate::theme::fixed;
use crate::widgets::{self, text};
use egui::{Align2, Color32, Rect, Sense, Ui, pos2, vec2};

struct Overlay {
    id: &'static str,
    title: &'static str,
    description: &'static str,
}

const OVERLAYS: [Overlay; 4] = [
    Overlay {
        id: "dungeon",
        title: "Dungeon",
        description: "The dungeon you are in: its portal, your rank once you hit the boss, the amulet while it recharges from a save, the Protective Matrix armor that broke (heavy, robe or light) while it recharges, and in Moonlight Village the flames of the fight (the three dancers, then Umi), the loot tier they reach with the flames the next tier needs, and the latest phase's flames.",
    },
    Overlay { id: "mana", title: "Mana", description: "Your mana as a bar, with the points you have and your maximum." },
    Overlay {
        id: "dps",
        title: "DPS",
        description: "The boss you are fighting: your place, damage and share as under DPS, with the player above you and the one below.",
    },
    Overlay {
        id: "buffs",
        title: "Buffs",
        description: "Your item cooldowns while they run: the Coral Juice Refinery, the Miniature Alien Generator, the Protective Matrix and the Amulet of Restoration after it saved you. Behind each item a golden circle runs out until it can be used again, with the time left on it. While none runs, an empty grey circle.",
    },
];

const MIN_OPACITY: i32 = 30;
const DEFAULT_OPACITY: i32 = 85;
const REFINERY: i32 = 44495;
const ALIEN_GENERATOR: i32 = 48164;
const MAGIC_MATRIX: i32 = 0x0484;
const HEAVY_MATRIX: i32 = 0x0485;

/// Its setting's name; the dungeon overlay keeps the names it had when it was the only one.
pub fn key_of(id: &str, what: &str) -> String {
    if id == "dungeon" { format!("overlay{what}") } else { format!("overlay.{id}.{what}") }
}

fn key(o: &Overlay, what: &str) -> String {
    if o.id == "dungeon" { format!("overlay{what}") } else { format!("overlay.{}.{what}", o.id) }
}

pub fn opacity(props: &lg_app::properties::Properties, id: &str) -> i32 {
    let o = OVERLAYS.iter().find(|o| o.id == id).expect("overlay");
    props.get(&key(o, "Opacity")).and_then(|v| v.trim().parse::<i32>().ok()).map_or(DEFAULT_OPACITY, |v| v.clamp(MIN_OPACITY, 100))
}

pub fn show(ui: &mut Ui, c: &mut Ctx, body: Rect) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let props = c.props;
    let asks = std::cell::RefCell::new(Vec::new());
    let now = c.now;
    let icons = &mut *c.icons;
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(body));
    egui::ScrollArea::vertical().id_salt("overlays").show(&mut child, |ui| {
        let top = ui.cursor().min;
        let w = ui.available_width() - 6.0;
        let painter = ui.painter().clone();
        let f = text(18.0);
        let fh = jtext::height(&painter, &f);
        let mut y = top.y + 4.0;
        jtext::draw(
            &painter,
            pos2(top.x + 4.0, y),
            Align2::LEFT_TOP,
            "Small windows above the game, each switched on here on its own. Drag one where you want it; it stays there. One that has nothing to show says so.",
            &f,
            fixed::MUTED,
        );
        y += fh + 10.0;
        for o in &OVERLAYS {
            let on = props.get(&key(o, "On")) == Some("true");
            let op = opacity(props, o.id);
            let title = format!("{} overlay", o.title);
            y += widgets::section_box(
                ui,
                &p,
                pos2(top.x, y),
                w,
                &title,
                fixed::BLUE,
                |ui, head| {
                    // ON / OFF right after the title, the switch at the right
                    let painter = ui.painter();
                    let tx = head.left() + 10.0 - 1.0 + 8.0 + 8.0 + jtext::width(painter, &title.to_uppercase(), &crate::theme::head(17.0)) + 14.0;
                    jtext::draw_bold(painter, pos2(tx, head.center().y), Align2::LEFT_CENTER, if on { "ON" } else { "OFF" }, &f, if on { Color32::from_rgb(0x4C, 0xAF, 0x50) } else { fixed::MUTED });
                    let sw = widgets::segmented_width(painter, &["Off", "On"], true);
                    if let Some(i) = widgets::segmented(ui, &p, pos2(head.right() - 6.0 - sw, head.top()), head.height(), &["Off", "On"], usize::from(on), true, &[]) {
                        asks.borrow_mut().push(crate::window::Ask::Set(key(o, "On"), (i == 1).to_string()));
                    }
                },
                |ui, r| {
                    let painter = ui.painter().clone();
                    let mut y = r.top() - 10.0 + 8.0;
                    let x = r.left() - 11.0 + 1.0 + 10.0;
                    let iw = r.width();
                    let about = widgets::fit(&painter, o.description, &f, iw);
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, &about, &f, p.ink);
                    y += fh + 8.0;
                    // the stage: a dark tiled floor, the samples on it as see-through as set
                    let stage_h = stage_height(&painter, o.id);
                    let stage = Rect::from_min_size(pos2(x, y), vec2(iw, stage_h));
                    let clip = painter.with_clip_rect(stage.intersect(painter.clip_rect()));
                    let t = 24.0;
                    let mut ty = 0.0;
                    while ty < stage_h {
                        let mut tx = 0.0;
                        while tx < iw {
                            let even = ((tx / t) as i32 + (ty / t) as i32) % 2 == 0;
                            clip.rect_filled(Rect::from_min_size(pos2(x + tx, y + ty), vec2(t, t)), 0.0, if even { Color32::from_rgb(0x2E, 0x3A, 0x2C) } else { Color32::from_rgb(0x28, 0x34, 0x26) });
                            tx += t;
                        }
                        ty += t;
                    }
                    let mut faded = clip.clone();
                    faded.set_opacity(op as f32 / 100.0);
                    samples(&faded, ui.ctx(), icons, data, o.id, pos2(x + 24.0, y + 16.0), now);
                    y += stage_h + 8.0;
                    // the opacity: "Opacity", a slider, the value
                    let row_h = 30.0;
                    let cy = y + row_h / 2.0;
                    let lr = jtext::draw(&painter, pos2(x, cy), Align2::LEFT_CENTER, "Opacity", &f, p.ink);
                    let sx = lr.right() + 12.0;
                    let sw = 300.0;
                    let frac = (op - MIN_OPACITY) as f32 / (100 - MIN_OPACITY) as f32;
                    let track = Rect::from_min_max(pos2(sx + 15.0, cy - 1.5), pos2(sx + sw - 15.0, cy + 1.5));
                    painter.rect_filled(track, 1.5, p.line);
                    let tx = track.left() + frac * track.width();
                    painter.rect_filled(Rect::from_min_max(track.min, pos2(tx, track.bottom())), 1.5, Color32::from_rgb(0xE8, 0xB8, 0x3A));
                    painter.circle_filled(pos2(tx, cy), 9.0, Color32::from_rgb(0xE8, 0xB8, 0x3A));
                    jtext::draw(&painter, pos2(sx + sw + 6.0, cy), Align2::LEFT_CENTER, &format!("{op}%"), &f, p.ink);
                    let resp = ui.interact(Rect::from_min_size(pos2(sx, y), vec2(sw, row_h)), ui.scope_id().with(("opacity", o.id)), Sense::click_and_drag());
                    if (resp.clicked() || resp.dragged() || resp.drag_stopped())
                        && let Some(pos) = resp.interact_pointer_pos()
                    {
                        // follows while dragging, as in Java
                        let f = ((pos.x - track.left()) / track.width()).clamp(0.0, 1.0);
                        let v = MIN_OPACITY + (f * (100 - MIN_OPACITY) as f32).round() as i32;
                        if v != op {
                            asks.borrow_mut().push(crate::window::Ask::Set(key(o, "Opacity"), v.to_string()));
                        }
                    }
                    y += row_h;
                    y - r.top() + 10.0 - 10.0
                },
            );
        }
        ui.allocate_exact_size(vec2(w, y - top.y), Sense::hover());
    });
    c.asks.extend(asks.take());
}

fn stage_height(painter: &egui::Painter, id: &str) -> f32 {
    let look = match id {
        "dungeon" => jtext::height(painter, &text(17.0)) + 4.0 + 2.0 * jtext::height(painter, &text(15.5)) + 8.0,
        "mana" => 22.0,
        "dps" => 100.0,
        _ => 46.0,
    };
    look + 32.0
}

/// The sample looks of one overlay, from the stage's first place on.
fn samples(
    painter: &egui::Painter,
    ctx: &egui::Context,
    icons: &mut crate::icons::Icons,
    data: &lg_gamedata::GameData,
    id: &str,
    at: egui::Pos2,
    now: i64,
) {
    match id {
        "dungeon" => {
            let cap = text(17.0);
            let ch = jtext::height(painter, &cap) + 4.0;
            let caption = Color32::from_rgb(0xC8, 0xC2, 0xB4);
            let any =
                Badge { dungeon: "Ocean Trench", rank: Some("#3/8".into()), amulet: true, matrix: Some(HEAVY_MATRIX), moonlight: None };
            let mv = Badge {
                dungeon: "Moonlight Village",
                rank: Some("#2/6".into()),
                amulet: true,
                matrix: Some(HEAVY_MATRIX),
                moonlight: Some((54, true, "6".into(), true)),
            };
            // both stand on the stage's row, centred on the tallest
            let mv_h = mv_height(painter);
            let any_h = 34.0;
            let row_h = ch + mv_h;
            let a_top = at.y + (row_h - (ch + any_h)) / 2.0;
            jtext::draw_bold(painter, pos2(at.x, a_top), Align2::LEFT_TOP, "Any dungeon", &cap, caption);
            let s = overlays::badge(painter, ctx, icons, data, &any, pos2(at.x, a_top + ch));
            let mx = at.x + jtext::bold_width(painter, "Any dungeon", &cap).max(s.x) + 24.0;
            jtext::draw_bold(painter, pos2(mx, at.y), Align2::LEFT_TOP, "Moonlight Village", &cap, caption);
            overlays::badge(painter, ctx, icons, data, &mv, pos2(mx, at.y + ch));
        }
        "mana" => {
            overlays::mana_bar(painter, at, 182, 255);
        }
        "dps" => {
            let lines = [
                DpsLine { place: 3, name: "Brightvale".into(), damage: 412_880, share: 18.2, me: false },
                DpsLine { place: 4, name: "Kestrel".into(), damage: 371_045, share: 16.4, me: true },
                DpsLine { place: 5, name: "Moru".into(), damage: 298_114, share: 13.1, me: false },
            ];
            overlays::dps_overlay(painter, at, "Main boss", &lines, 24.0);
        }
        _ => {
            let tiles = [
                Cooldown { item: REFINERY, start: now - 2_000, duration_ms: 15_000 },
                Cooldown { item: ALIEN_GENERATOR, start: now - 27_000, duration_ms: 45_000 },
                Cooldown { item: MAGIC_MATRIX, start: now - 70_000, duration_ms: 180_000 },
                Cooldown { item: data.amulets.restoration, start: now - 1_500_000, duration_ms: 1_800_000 },
            ];
            overlays::buffs(painter, ctx, icons, data, at, &tiles, now);
        }
    }
}

fn mv_height(painter: &egui::Painter) -> f32 {
    2.0 * jtext::height(painter, &text(15.5)) + 8.0
}
