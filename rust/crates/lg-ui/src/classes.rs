//! Characters > Classes (Java: `ClassesView`): a card per class with its exaltations and your characters of it, most
//! exalted first; beside them the account's exaltation progress, in total and per stat.

use crate::icons::Icons;
use crate::jtext;
use crate::profile::{Ctx, num};
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::exalt_store::{THRESHOLDS, level, total};

const CARD_MIN_WIDTH: f32 = 400.0;
const GAP: f32 = 8.0;
const PORTRAIT: f32 = 72.0;
const ICON: f32 = 24.0;
const STAT_NAMES: [&str; 8] = ["Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"];
/// Each shown stat's index in the exaltation data (life, mana, attack, defense, speed, dexterity, vitality, wisdom).
const STAT_INDEX: [usize; 8] = [7, 6, 5, 4, 1, 0, 2, 3];
const STAT_POTION: [i32; 8] = [0xae9, 0xaea, 0xa1f, 0xa20, 0xa21, 0xa4c, 0xa34, 0xa35];
const PIP_ON: Color32 = Color32::from_rgb(0xFF, 0xD2, 0x1F);
const PIP_W: f32 = 8.0;
const PIP_H: f32 = 11.0;
const PIP_STEP: f32 = 10.0;

/// Java `Palette.CLASS_COLORS`.
pub fn class_color(name: &str) -> Color32 {
    let c = match name {
        "Rogue" => 0x6E7B8B,
        "Archer" => 0x5FA343,
        "Wizard" => 0xB36BE0,
        "Priest" => 0xE0C36B,
        "Warrior" => 0xC0503C,
        "Knight" => 0xE88A3A,
        "Paladin" => 0xF2C230,
        "Assassin" => 0x49A07A,
        "Necromancer" => 0x8C5AD6,
        "Huntress" => 0xE0679C,
        "Mystic" => 0x3FC4D6,
        "Trickster" => 0xE85AB4,
        "Sorcerer" => 0x4C8FE0,
        "Ninja" => 0x7A8899,
        "Samurai" => 0xD64545,
        "Bard" => 0xD9B44A,
        "Summoner" => 0x57B894,
        "Kensei" => 0xB5543A,
        "Druid" => 0x6B8E3A,
        _ => return fixed::STONE,
    };
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

struct Info {
    id: i32,
    name: String,
    progress: Option<Vec<i32>>,
    total: i32,
    sum: i32,
    chars: usize,
    best: i64,
}

/// A progress bar as FlatLaf draws it: the line colour, gold for the part done.
fn bar(painter: &egui::Painter, p: &Palette, r: Rect, share: f32) {
    painter.rect_filled(r, 0.0, p.line);
    painter.rect_filled(Rect::from_min_size(r.min, vec2((r.width() * share.clamp(0.0, 1.0)).round(), r.height())), 0.0, p.gold);
}

pub fn show(ui: &mut Ui, c: &mut Ctx, body: Rect) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let max_class = 8 * THRESHOLDS.len() as i32;
    let mut infos: Vec<Info> = data
        .classes
        .iter()
        .map(|k| {
            let pr = app.exalts.progress(k.id).cloned();
            let mine: Vec<_> = app.characters.all().filter(|x| x.alive && x.class_type == k.id).collect();
            Info {
                id: k.id,
                name: k.name.clone(),
                total: total(pr.as_deref()),
                sum: pr.as_ref().map_or(0, |v| v.iter().take(8).sum()),
                progress: pr,
                chars: mine.len(),
                best: mine.iter().map(|x| x.fame).max().unwrap_or(0),
            }
        })
        .collect();
    infos.sort_by(|a, b| b.total.cmp(&a.total).then(b.sum.cmp(&a.sum)).then(a.name.cmp(&b.name)));

    // the split: the side 280 px, the main area snapped down to whole columns of cards (Java `SideSplit.setSnap`)
    let main_w = body.width() - 280.0 - 8.0;
    let overhead = 2.0 + 12.0;
    let cols = (((main_w - overhead - 20.0 + GAP) / (CARD_MIN_WIDTH + GAP)).floor()).max(1.0);
    let main_w = overhead + cols * (CARD_MIN_WIDTH + GAP) - GAP + 20.0;
    let main = Rect::from_min_size(body.min, vec2(main_w, body.height()));
    let side = Rect::from_min_max(pos2(main.right() + 8.0, body.top()), body.max);

    let icons = &mut *c.icons;
    widgets::section(
        ui,
        &p,
        main,
        "Classes",
        fixed::GOLD,
        |_, _| {},
        |ui, r| {
            egui::ScrollArea::vertical().id_salt("classes").show(ui, |ui| {
                let top = ui.cursor().min;
                let inner_w = r.width() - 12.0 - 20.0;
                let cw = ((inner_w - (cols - 1.0) * GAP) / cols).floor();
                let painter = ui.painter().clone();
                let ch = card_height(&painter);
                for (i, info) in infos.iter().enumerate() {
                    let (col, row) = ((i as f32 % cols), (i as f32 / cols).floor());
                    let cr = Rect::from_min_size(pos2(top.x + 10.0 + col * (cw + GAP), top.y + 10.0 + row * (ch + GAP)), vec2(cw, ch));
                    card(ui, &p, icons, data, info, cr);
                }
                let rows = (infos.len() as f32 / cols).ceil();
                ui.allocate_exact_size(vec2(r.width() - 12.0, 20.0 + rows * (ch + GAP) - GAP), Sense::hover());
            });
        },
    );

    // the side: exaltation progress, then per stat
    let painter = ui.painter().clone();
    let all: i32 = infos.iter().map(|i| i.total).sum();
    let max = infos.len() as i32 * max_class;
    let pct = if max == 0 { 0.0 } else { 100.0 * f64::from(all) / f64::from(max) };
    let full = infos.iter().filter(|i| i.total == max_class).count();
    let lh = jtext::height(&painter, &text(18.0));
    let prog_h = jtext::height(&painter, &small()) + 10.0 + lh + 6.0 + lh;
    let head = widgets::section_head_height(&painter);
    let prog = Rect::from_min_size(side.min, vec2(side.width(), head + 2.0 + 20.0 + prog_h));
    widgets::section(
        ui,
        &p,
        prog,
        "Exaltation Progress",
        fixed::GREEN,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter();
            let x = r.left() + 10.0;
            let w = r.width() - 20.0;
            let mut y = r.top() + 10.0;
            let t = jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, "EXALTATIONS", &small(), p.ink_muted);
            y = t.bottom();
            bar(painter, &p, Rect::from_min_size(pos2(x, y), vec2(w, 10.0)), (pct / 100.0) as f32);
            y += 10.0;
            let t = jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, &format!("{all} / {max}   {pct:.1}%"), &text(18.0), p.ink);
            y = t.bottom() + 6.0;
            let note = if app.exalts.progress.is_empty() {
                "Exaltations load when you log in with the sniffer running.".to_string()
            } else {
                format!("{full} of {} classes fully exalted", infos.len())
            };
            jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, &note, &text(18.0), fixed::MUTED);
        },
    );
    let stats = Rect::from_min_max(pos2(side.left(), prog.bottom() + 8.0), side.max);
    let icons = &mut *c.icons;
    widgets::section(
        ui,
        &p,
        stats,
        "Per Stat",
        fixed::PURPLE,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter().clone();
            let x = r.left() + 10.0;
            let w = r.width() - 20.0;
            let mut y = r.top() + 8.0;
            let stat_max = infos.len() as i32 * THRESHOLDS.len() as i32;
            for s in 0..8 {
                let have: i32 =
                    infos.iter().filter_map(|i| i.progress.as_ref()).map(|pr| level(pr.get(STAT_INDEX[s]).copied().unwrap_or(0))).sum();
                let rh = lh.max(18.0);
                if let Some(t) = icons.object(ui.ctx(), data, STAT_POTION[s], 18) {
                    painter.image(
                        t.id(),
                        Rect::from_min_size(pos2(x, y + (rh - 18.0) / 2.0), vec2(18.0, 18.0)),
                        crate::detail::uv(),
                        Color32::WHITE,
                    );
                }
                jtext::draw(&painter, pos2(x + 26.0, y + rh / 2.0), Align2::LEFT_CENTER, STAT_NAMES[s], &text(18.0), p.ink);
                jtext::draw(&painter, pos2(x + w, y + rh / 2.0), Align2::RIGHT_CENTER, &format!("{have} / {stat_max}"), &text(18.0), p.ink);
                y += rh + 2.0;
                bar(
                    &painter,
                    &p,
                    Rect::from_min_size(pos2(x, y), vec2(w, 5.0)),
                    if stat_max == 0 { 0.0 } else { have as f32 / stat_max as f32 },
                );
                y += 5.0 + 5.0;
            }
        },
    );
}

fn card_height(painter: &egui::Painter) -> f32 {
    let head = jtext::height(painter, &text(22.0)).max(jtext::height(painter, &text(20.0)));
    let body = head + 4.0 + 2.0 * ICON + 4.0 + 4.0 + jtext::height(painter, &text(18.0));
    body.max(PORTRAIT) + 12.0
}

/// The "Exalted <Class>" skin (Java `exaltedSkin`), or -1.
fn exalted_skin(data: &lg_gamedata::GameData, class: &str) -> i32 {
    let name = format!("Exalted {class}");
    let mut ids: Vec<i32> = data.objects.values().filter(|o| o.id_name == name && o.class == "Skin").map(|o| o.id).collect();
    ids.sort_unstable();
    ids.last().copied().unwrap_or(-1)
}

/// One class's card (Java `Card`): its sprite (the Exalted skin once fully exalted), name and exaltations, the eight
/// stats with a pip per exaltation, your characters of it.
fn card(ui: &Ui, p: &Palette, icons: &mut Icons, data: &lg_gamedata::GameData, info: &Info, r: Rect) {
    let painter = ui.painter();
    let max_class = 8 * THRESHOLDS.len() as i32;
    let done = info.total == max_class;
    painter.rect_filled(r, 0.0, if done { mix(p.gold, p.paper, 0.45) } else { p.header });
    if done {
        painter.rect_stroke(r, 0.0, Stroke::new(2.0, p.gold), StrokeKind::Inside);
    } else {
        painter.rect_stroke(r, 0.0, Stroke::new(1.0, Color32::BLACK), StrokeKind::Inside);
    }
    let skin = if done { exalted_skin(data, &info.name) } else { -1 };
    let sprite = if skin > 0 { skin } else { info.id };
    if let Some(t) = icons.object(ui.ctx(), data, sprite, (PORTRAIT - 8.0) as u32) {
        let pr = Rect::from_min_size(pos2(r.left() + 6.0, r.center().y - PORTRAIT / 2.0), vec2(PORTRAIT, PORTRAIT));
        painter.image(
            t.id(),
            Rect::from_center_size(pr.center(), vec2(PORTRAIT - 8.0, PORTRAIT - 8.0)),
            crate::detail::uv(),
            Color32::WHITE,
        );
    }
    let bx = r.left() + 6.0 + PORTRAIT + 10.0;
    let bw = r.right() - 10.0 - bx;
    let mut y = r.top() + 6.0;
    let hh = jtext::height(painter, &text(22.0)).max(jtext::height(painter, &text(20.0)));
    painter.rect_filled(Rect::from_min_size(pos2(bx, y + hh / 2.0 - 4.0), vec2(8.0, 8.0)), 0.0, class_color(&info.name));
    jtext::draw(painter, pos2(bx + 16.0, y + hh / 2.0), Align2::LEFT_CENTER, &info.name, &text(22.0), p.ink);
    let t = if info.progress.is_none() { "-".to_string() } else { format!("{} / {max_class}", info.total) };
    jtext::draw(painter, pos2(bx + bw, y + hh / 2.0), Align2::RIGHT_CENTER, &t, &text(20.0), if done { p.gold } else { p.ink });
    y += hh + 4.0;
    // two rows of four: potion, then five pips
    let cell_w = ICON + 6.0 + 5.0 * PIP_STEP;
    let cw = ((bw - 3.0 * 8.0) / 4.0).min(cell_w);
    for s in 0..8 {
        let (col, row) = ((s % 4) as f32, (s / 4) as f32);
        let cx = bx + col * (cw + 8.0);
        let cy = y + row * (ICON + 4.0);
        if let Some(t) = icons.object(ui.ctx(), data, STAT_POTION[s], ICON as u32) {
            painter.image(t.id(), Rect::from_min_size(pos2(cx, cy), vec2(ICON, ICON)), crate::detail::uv(), Color32::WHITE);
        }
        let lv = level(info.progress.as_ref().and_then(|v| v.get(STAT_INDEX[s]).copied()).unwrap_or(0));
        let px0 = cx + ICON + 6.0;
        let step = ((cw - ICON - 6.0) / 5.0).floor().clamp(3.0, PIP_STEP);
        let w = (step - (PIP_STEP - PIP_W)).max(2.0);
        let py = cy + ((ICON - PIP_H) / 2.0).floor();
        for i in 0..5 {
            let px = px0 + i as f32 * step;
            if i < lv {
                painter.rect_filled(Rect::from_min_size(pos2(px, py), vec2(w, PIP_H)), 0.0, Color32::BLACK);
                painter.rect_filled(Rect::from_min_size(pos2(px + 1.0, py + 1.0), vec2(w - 2.0, PIP_H - 2.0)), 0.0, PIP_ON);
            } else {
                painter.rect_filled(
                    Rect::from_min_size(pos2(px + 1.0, py + 1.0), vec2(w - 2.0, PIP_H - 2.0)),
                    0.0,
                    Color32::from_black_alpha(50),
                );
            }
        }
    }
    y += 2.0 * ICON + 4.0 + 4.0;
    let who = if info.chars == 0 {
        "No characters".to_string()
    } else {
        format!("{} character{}  ·  best {} fame", info.chars, if info.chars == 1 { "" } else { "s" }, num(info.best))
    };
    jtext::draw(painter, pos2(bx, y), Align2::LEFT_TOP, &who, &text(18.0), fixed::MUTED);
}
