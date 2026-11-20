//! Characters > Pets (Java: `PetsView`): every pet as a tile, sortable; the selected one on the right with its
//! abilities and saddlebags.

use crate::jtext;
use crate::profile::{Ctx, num};
use crate::theme::{fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, FontId, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::pet_store::{Pet, RARITY, ability_name};

const TILE_MIN_WIDTH: f32 = 300.0;
const GAP: f32 = 8.0;
const SPRITE: f32 = 56.0;
const BIG_SPRITE: f32 = 96.0;
const SORTS: [&str; 4] = ["Rarity", "Abilities", "Name", "Last seen"];
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

fn rarity_color(r: i32) -> Color32 {
    [fixed::STONE, fixed::GREEN, fixed::BLUE, fixed::PURPLE, fixed::GOLD][r.clamp(0, 4) as usize]
}

fn name(p: &Pet) -> String {
    if p.name.trim().is_empty() { "Pet".into() } else { p.name.clone() }
}

fn levels(p: &Pet) -> i32 {
    p.abilities.iter().map(|a| a.power).sum()
}

fn max_power(p: &Pet) -> i32 {
    if p.max_power > 0 { p.max_power } else { 100 }
}

fn look(p: &Pet) -> i32 {
    if p.skin > 0 { p.skin } else { p.kind }
}

/// Bold as Java derives it: the text twice, a pixel apart.
fn bold(painter: &egui::Painter, pos: egui::Pos2, align: Align2, t: &str, f: &FontId, c: Color32) -> Rect {
    jtext::draw(painter, pos + vec2(1.0, 0.0), align, t, f, c);
    jtext::draw(painter, pos, align, t, f, c)
}

#[derive(Default)]
pub struct PetsView {
    pub sort: usize,
    pub reverse: bool,
    pub selected: i32,
}

impl PetsView {
    fn sorted<'a>(&self, pets: &'a [(i32, Pet)]) -> Vec<&'a Pet> {
        let mut v: Vec<&Pet> = pets.iter().map(|(_, p)| p).collect();
        let by_name = |a: &&Pet, b: &&Pet| name(a).to_lowercase().cmp(&name(b).to_lowercase());
        v.sort_by(|a, b| {
            let o = match self.sort {
                1 => levels(b).cmp(&levels(a)).then(b.rarity.cmp(&a.rarity)),
                2 => by_name(a, b).then(b.rarity.cmp(&a.rarity)),
                3 => b.last_seen.cmp(&a.last_seen),
                _ => b.rarity.cmp(&a.rarity).then(levels(b).cmp(&levels(a))),
            };
            o.then(by_name(a, b))
        });
        if self.reverse {
            v.reverse();
        }
        v
    }

    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let pets = self.sorted(&app.pets.pets);
        if !pets.iter().any(|x| x.instance_id == self.selected) {
            self.selected = pets.first().map_or(-1, |x| x.instance_id);
        }
        // the split: the side 460 px, the tiles snapped down to whole columns
        let overhead = 2.0 + 12.0;
        let main_w = body.width() - 460.0 - 8.0;
        let cols = (((main_w - overhead - 20.0 + GAP) / (TILE_MIN_WIDTH + GAP)).floor()).max(1.0);
        let main = Rect::from_min_size(body.min, vec2(overhead + cols * (TILE_MIN_WIDTH + GAP) - GAP + 20.0, body.height()));
        let side = Rect::from_min_max(pos2(main.right() + 8.0, body.top()), body.max);
        let (mut sort, mut reverse) = (self.sort, self.reverse);
        let mut picked = None;
        let icons = &mut *c.icons;
        let selected = self.selected;
        widgets::section(
            ui,
            &p,
            main,
            "Pets",
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter().clone();
                let h = head.height();
                let dw = 10.0 + 30.0;
                let dr = Rect::from_min_size(pos2(head.right() - dw, head.top()), vec2(dw, h));
                let hov = ui.interact(dr, ui.scope_id().with("pet-dir"), Sense::click());
                painter.rect(dr, 0.0, if hov.hovered() { p.sand } else { p.header }, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
                // the arrow: down for the usual order, up reversed
                let (cx, cy) = (dr.center().x, dr.center().y);
                let s = if reverse { -1.0 } else { 1.0 };
                let stroke = Stroke::new(2.0, p.ink);
                painter.line_segment([pos2(cx, cy - 7.0 * s), pos2(cx, cy + 7.0 * s)], stroke);
                painter.line_segment([pos2(cx - 5.0, cy + 2.0 * s), pos2(cx, cy + 7.0 * s)], stroke);
                painter.line_segment([pos2(cx + 5.0, cy + 2.0 * s), pos2(cx, cy + 7.0 * s)], stroke);
                if hov.clicked() {
                    reverse = !reverse;
                }
                let sw = widgets::segmented_width(&painter, &SORTS, true);
                let x = dr.left() - 4.0 - sw;
                if let Some(i) = widgets::segmented(ui, &p, pos2(x, head.top()), h, &SORTS, sort, true, &[]) {
                    sort = i;
                    reverse = false;
                }
                jtext::draw(&painter, pos2(x - 4.0, head.center().y), Align2::RIGHT_CENTER, "SORT BY", &small(), p.ink_muted);
            },
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("pets").show(ui, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    if pets.is_empty() {
                        jtext::draw(
                            &painter,
                            top + vec2(10.0, 10.0),
                            Align2::LEFT_TOP,
                            "Log in once so your character list is loaded, or visit your Pet Yard.",
                            &text(18.0),
                            fixed::MUTED,
                        );
                    }
                    let inner_w = r.width() - 12.0 - 20.0;
                    let tw = ((inner_w - (cols - 1.0) * GAP) / cols).floor();
                    let lh = jtext::height(&painter, &text(18.0));
                    let rows_in_tile = pets.iter().map(|x| x.abilities.len().max(1)).max().unwrap_or(1) as f32;
                    let th = 6.0 + (jtext::height(&painter, &text(24.0)) + 4.0 + rows_in_tile * lh).max(SPRITE + 8.0) + 6.0;
                    for (i, pet) in pets.iter().enumerate() {
                        let (col, row) = (i as f32 % cols, (i as f32 / cols).floor());
                        let tr = Rect::from_min_size(pos2(top.x + 10.0 + col * (tw + GAP), top.y + 10.0 + row * (th + GAP)), vec2(tw, th));
                        let sel = pet.instance_id == selected;
                        painter.rect_filled(tr, 0.0, if sel { mix(p.gold, p.header, 0.3) } else { p.header });
                        painter.rect_stroke(
                            tr,
                            0.0,
                            Stroke::new(if sel { 2.0 } else { 1.0 }, if sel { p.gold } else { Color32::BLACK }),
                            StrokeKind::Inside,
                        );
                        if let Some(t) = icons.object(ui.ctx(), data, look(pet), SPRITE as u32) {
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(tr.left() + 6.0 + 4.0, tr.top() + 6.0), vec2(SPRITE, SPRITE)),
                                crate::detail::uv(),
                                Color32::WHITE,
                            );
                        }
                        let bx = tr.left() + 6.0 + SPRITE + 8.0 + 10.0;
                        let bw = tr.right() - 10.0 - bx;
                        let hh = jtext::height(&painter, &text(24.0));
                        jtext::draw(
                            &painter,
                            pos2(bx, tr.top() + 6.0 + hh / 2.0),
                            Align2::LEFT_CENTER,
                            &widgets::fit(&painter, &name(pet), &text(24.0), bw - 90.0),
                            &text(24.0),
                            p.ink,
                        );
                        let r = pet.rarity.clamp(0, 4);
                        bold(
                            &painter,
                            pos2(bx + bw - 1.0, tr.top() + 6.0 + hh / 2.0),
                            Align2::RIGHT_CENTER,
                            &RARITY[r as usize].to_uppercase(),
                            &text(15.0),
                            rarity_color(r),
                        );
                        let mut y = tr.top() + 6.0 + hh + 4.0;
                        if pet.abilities.is_empty() {
                            jtext::draw(&painter, pos2(bx, y), Align2::LEFT_TOP, "Abilities unknown", &text(18.0), fixed::MUTED);
                        }
                        for a in &pet.abilities {
                            let cy = y + lh / 2.0;
                            jtext::draw(&painter, pos2(bx, cy), Align2::LEFT_CENTER, &ability_name(a.kind), &text(18.0), p.ink);
                            jtext::draw(&painter, pos2(bx + bw, cy), Align2::RIGHT_CENTER, &a.power.to_string(), &text(18.0), p.ink);
                            let (b0, b1) = (bx + 100.0 + 8.0, bx + bw - 28.0 - 8.0);
                            let br = Rect::from_min_size(pos2(b0, cy - 2.5), vec2(b1 - b0, 5.0));
                            painter.rect_filled(br, 0.0, p.line);
                            let full = a.power >= pet.max_power && pet.max_power > 0;
                            painter.rect_filled(
                                Rect::from_min_size(br.min, vec2(br.width() * a.power.min(100) as f32 / 100.0, 5.0)),
                                0.0,
                                if full { fixed::GOLD } else { fixed::GREEN },
                            );
                            y += lh;
                        }
                        if ui.interact(tr, ui.scope_id().with(("pet", pet.instance_id)), Sense::click()).clicked() {
                            picked = Some(pet.instance_id);
                        }
                    }
                    let rows = (pets.len() as f32 / cols).ceil();
                    ui.allocate_exact_size(vec2(r.width() - 12.0, 20.0 + rows * (th + GAP)), Sense::hover());
                });
            },
        );
        self.sort = sort;
        self.reverse = reverse;
        if let Some(id) = picked {
            self.selected = id;
        }
        if let Some(pet) = pets.iter().find(|x| x.instance_id == self.selected) {
            let mut side_ui = ui.new_child(egui::UiBuilder::new().max_rect(side));
            detail(&mut side_ui, c, pet, side);
        }
    }
}

/// The selected pet's boxes (Java `showPet`).
fn detail(ui: &mut Ui, c: &mut Ctx, pet: &Pet, side: Rect) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let icons = &mut *c.icons;
    let w = side.width() - 12.0;
    let mut y = side.top();
    y += widgets::section_box(
        ui,
        &p,
        pos2(side.left(), y),
        w,
        "Pet",
        fixed::GOLD,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter().clone();
            if let Some(t) = icons.object(ui.ctx(), data, look(pet), BIG_SPRITE as u32) {
                painter.image(t.id(), Rect::from_min_size(r.min, vec2(BIG_SPRITE, BIG_SPRITE)), crate::detail::uv(), Color32::WHITE);
            }
            let x = r.left() + BIG_SPRITE + 14.0;
            let mut ty = r.top();
            let t = jtext::draw(&painter, pos2(x, ty), Align2::LEFT_TOP, &name(pet), &text(32.0), p.ink);
            ty = t.bottom();
            let rr = pet.rarity.clamp(0, 4);
            let t = bold(&painter, pos2(x, ty), Align2::LEFT_TOP, &RARITY[rr as usize].to_uppercase(), &text(18.0), rarity_color(rr));
            ty = t.bottom();
            let kind = data.objects.get(&pet.kind).map(|o| o.name().to_string()).filter(|k| *k != pet.name);
            if let Some(k) = kind {
                let t = jtext::draw(&painter, pos2(x, ty), Align2::LEFT_TOP, &k, &text(18.0), fixed::MUTED);
                ty = t.bottom();
            }
            if pet.last_seen > 0 {
                ty += 4.0;
                let l = app.zone.local_date_time(pet.last_seen);
                let when = format!(
                    "last seen {} {} {}, {}",
                    l[8..10].trim_start_matches('0'),
                    MONTHS[l[5..7].parse::<usize>().unwrap_or(1) - 1],
                    &l[0..4],
                    &l[11..16]
                );
                let t = jtext::draw(&painter, pos2(x, ty), Align2::LEFT_TOP, &when, &text(18.0), fixed::MUTED);
                ty = t.bottom();
            }
            (ty - r.top()).max(BIG_SPRITE)
        },
    );
    y += widgets::section_box(
        ui,
        &p,
        pos2(side.left(), y),
        w,
        "Abilities",
        fixed::GREEN,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter().clone();
            let lh = jtext::height(&painter, &text(18.0));
            let mut ty = r.top();
            if pet.abilities.is_empty() {
                jtext::draw(&painter, r.min, Align2::LEFT_TOP, "Abilities unknown - visit your Pet Yard.", &text(18.0), fixed::MUTED);
                return lh;
            }
            let row_w = r.width().min(560.0);
            for a in &pet.abilities {
                let cy = ty + lh / 2.0;
                jtext::draw(&painter, pos2(r.left(), cy), Align2::LEFT_CENTER, &ability_name(a.kind), &text(18.0), p.ink);
                jtext::draw(
                    &painter,
                    pos2(r.left() + row_w - 70.0, cy),
                    Align2::LEFT_CENTER,
                    &format!("{} / {}", a.power, max_power(pet)),
                    &text(18.0),
                    p.ink,
                );
                let room = row_w - 130.0 - 70.0 - 20.0;
                let bw = room.clamp(0.0, 320.0);
                let bx = r.left() + 130.0 + 10.0 + (room - bw) / 2.0;
                let br = Rect::from_min_size(pos2(bx, cy - 4.0), vec2(bw, 8.0));
                painter.rect_filled(br, 0.0, p.line);
                let full = a.power >= pet.max_power && pet.max_power > 0;
                painter.rect_filled(
                    Rect::from_min_size(br.min, vec2(bw * a.power.min(100) as f32 / 100.0, 8.0)),
                    0.0,
                    if full { fixed::GOLD } else { fixed::GREEN },
                );
                ty += lh;
            }
            ty += 6.0;
            let t = jtext::draw(
                &painter,
                pos2(r.left(), ty),
                Align2::LEFT_TOP,
                &format!("Total level {} / {}", levels(pet), pet.abilities.len() as i32 * max_power(pet)),
                &text(18.0),
                fixed::MUTED,
            );
            t.bottom() - r.top()
        },
    );
    let _ = num;
    widgets::section_box(
        ui,
        &p,
        pos2(side.left(), y),
        w,
        "Saddlebags",
        fixed::PURPLE,
        |_, _| {},
        |ui, r| {
            let painter = ui.painter().clone();
            let mut ty = r.top();
            let bags: Vec<(&str, &lg_app::pet_store::Saddlebag)> =
                [("Saddlebag", pet.saddlebag.as_ref()), ("Seasonal saddlebag", pet.seasonal_saddlebag.as_ref())]
                    .into_iter()
                    .filter_map(|(t, b)| b.map(|b| (t, b)))
                    .collect();
            if bags.is_empty() {
                let t = jtext::draw(
                    &painter,
                    r.min,
                    Align2::LEFT_TOP,
                    "No saddlebag seen - it is read from your character list at login.",
                    &text(18.0),
                    fixed::MUTED,
                );
                return t.height();
            }
            for (k, (title, bag)) in bags.iter().enumerate() {
                if k > 0 {
                    ty += 8.0;
                }
                let n = bag.items.len();
                let cap = format!("{title}  ·  {n} {}", if n == 1 { "slot" } else { "slots" }).to_uppercase();
                let t = jtext::draw(&painter, pos2(r.left(), ty), Align2::LEFT_TOP, &cap, &small(), p.ink_muted);
                ty = t.bottom() + 2.0;
                for (i, &id) in bag.items.iter().enumerate() {
                    let sr = Rect::from_min_size(pos2(r.left() + 4.0 + i as f32 * 58.0, ty), vec2(54.0, 54.0));
                    match (id > 0).then(|| icons.object(ui.ctx(), data, id, 48)).flatten() {
                        Some(t) => {
                            painter.image(
                                t.id(),
                                Rect::from_center_size(sr.center(), vec2(48.0, 48.0)),
                                crate::detail::uv(),
                                Color32::WHITE,
                            );
                        }
                        None => {
                            painter.rect_filled(sr.shrink(6.0), 0.0, Color32::from_black_alpha(40));
                        }
                    }
                }
                ty += 54.0;
            }
            ty - r.top()
        },
    );
}
