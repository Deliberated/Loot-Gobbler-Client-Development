//! Guild > Quest Map (Java `QuestMapView`): the guild's chain of dungeons. Any guildie's clear of the current stage
//! unlocks the next one; after the last stage the guild earns a medal (not there yet). The current map is on top,
//! past maps below it, each painted in its style (parchment, hex board, overworld, Oryx's tower, constellation, neon,
//! adventure, hunt, hellforge) and one of four colour variations, all chosen by the server.
// the boards are walked by index, as Java does
#![allow(clippy::needless_range_loop)]

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::fixed;
use crate::widgets::{self, text};
use crate::window::{Ask, ServerAsk};
use egui::{Align2, Color32, FontId, Painter, Pos2, Rect, Sense, Shape, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::guild::QuestMap;
use lg_app::java::JavaRandom;
use std::collections::HashMap;

const POLL_MS: i64 = 60_000;
const H: f32 = 380.0;

/// The page's own state: the stage picked on each map, when the maps were last asked for.
#[derive(Default)]
pub struct QuestMapView {
    selected: HashMap<i64, usize>,
    asked_at: i64,
}

const fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

fn alpha(c: Color32, a: u8) -> Color32 {
    Color32::from_rgba_unmultiplied(c.r(), c.g(), c.b(), a)
}

/// Java `Color.darker` / `brighter`.
fn darker(c: Color32) -> Color32 {
    Color32::from_rgb((c.r() as f32 * 0.7) as u8, (c.g() as f32 * 0.7) as u8, (c.b() as f32 * 0.7) as u8)
}

fn brighter(c: Color32) -> Color32 {
    let i = 3u8;
    if c.r() == 0 && c.g() == 0 && c.b() == 0 {
        return Color32::from_rgb(i, i, i);
    }
    let up = |v: u8| {
        let v = if v > 0 && v < i { i } else { v };
        ((v as f32 / 0.7) as u32).min(255) as u8
    };
    Color32::from_rgb(up(c.r()), up(c.g()), up(c.b()))
}

/// A map's colours (Java `Pal`).
#[derive(Clone, Copy)]
struct Pal {
    bg: Color32,
    bg2: Color32,
    frame: Color32,
    ink: Color32,
    road: Color32,
    walked: Color32,
    done: Color32,
    current: Color32,
    locked: Color32,
    text: Color32,
    deco1: Color32,
    deco2: Color32,
    deco3: Color32,
}

const fn pal(c: [u32; 13]) -> Pal {
    Pal {
        bg: rgb(c[0]),
        bg2: rgb(c[1]),
        frame: rgb(c[2]),
        ink: rgb(c[3]),
        road: rgb(c[4]),
        walked: rgb(c[5]),
        done: rgb(c[6]),
        current: rgb(c[7]),
        locked: rgb(c[8]),
        text: rgb(c[9]),
        deco1: rgb(c[10]),
        deco2: rgb(c[11]),
        deco3: rgb(c[12]),
    }
}

fn palette(style: &str, variant: i32) -> Pal {
    let v = variant.rem_euclid(4) as usize;
    let all: [[u32; 13]; 4] = match style {
        "hex" => [
            [
                0x101A20, 0x2E4A36, 0x0C1418, 0x0C1418, 0x34404A, 0xB08A3A, 0xE5B23A, 0x7FD0B0, 0x3A464E, 0xBFF0DC, 0x22402C, 0x3A464E,
                0x22465C,
            ],
            [
                0x2A1E10, 0x9A7A44, 0x1A1208, 0x1A1208, 0x5A4A30, 0xE0B060, 0xF0C060, 0xFF8A3A, 0x5A4A34, 0xFFE0B0, 0x7A5A2A, 0x6A5A4A,
                0x3A6A7A,
            ],
            [
                0x0E1A26, 0x9AB8C8, 0x081018, 0x081018, 0x4A6070, 0xE0F0FF, 0x9FD8F0, 0xFFFFFF, 0x4A5A68, 0xE0F0FF, 0x7A9AAA, 0x5A6A78,
                0x3A6A8A,
            ],
            [
                0x1A0A08, 0x3A2018, 0x0A0404, 0x0A0404, 0x4A2A20, 0xFFB040, 0xFFB040, 0xF0503A, 0x4A3028, 0xFFD9A8, 0x2A1410, 0x4A3A34,
                0xC8401A,
            ],
        ],
        "overworld" => [
            [
                0x7EC8F0, 0x6CC24A, 0x1A1A1A, 0x1A1A1A, 0x4FA83A, 0xFFF4C0, 0xE8483A, 0xF8D030, 0xA0A8A0, 0x1A1A1A, 0x4FA83A, 0x2E8B3A,
                0x3A8EE0,
            ],
            [
                0xF0C890, 0xE0B060, 0x1A1A1A, 0x1A1A1A, 0xC89A40, 0xFFF4D0, 0xE8483A, 0x3AA0E0, 0xB0A890, 0x1A1A1A, 0xC8903A, 0x3A8A3A,
                0x3A8EE0,
            ],
            [
                0xA8D0F0, 0xF4F8FF, 0x1A1A1A, 0x1A1A1A, 0xC8D8E8, 0x5A9AD0, 0xE8483A, 0xF8D030, 0xA0A8B0, 0x1A1A1A, 0xD0E0F0, 0x2E6A4A,
                0x6AB0E0,
            ],
            [
                0x2A1838, 0x4A3040, 0x0A0608, 0x0A0608, 0x3A2830, 0xFFB040, 0xE8483A, 0xF8D030, 0x6A6070, 0xF4E6C6, 0x3A2434, 0x1A1018,
                0xE8592A,
            ],
        ],
        "oryx" => [
            [
                0x120C1C, 0x2A1E3A, 0x07040C, 0x07040C, 0x4A4058, 0xE5B23A, 0xE5B23A, 0xB07BE0, 0x5A4E6A, 0xF4E6C6, 0xB8364A, 0x1E1430,
                0x8A1E2E,
            ],
            [
                0x1A0808, 0x3A1818, 0x0A0303, 0x0A0303, 0x5A3030, 0xF0C060, 0xF0C060, 0xE8483A, 0x6A4A4A, 0xF4E0D0, 0xF0E8E0, 0x2A1010,
                0x5A1A1A,
            ],
            [
                0x0C141E, 0x26384A, 0x04080C, 0x04080C, 0x4A5A6A, 0xE0F0FF, 0xE0F0FF, 0x9FD8F0, 0x4A5A68, 0xE0F0FF, 0xDCEBF2, 0x162636,
                0x2D7AB0,
            ],
            [
                0x1A140A, 0x3A2E1A, 0x0A0804, 0x0A0804, 0x5A4A30, 0xF0C060, 0xF0C060, 0xFFD870, 0x5A503A, 0xFFF0D0, 0xF0C060, 0x2A2014,
                0x6A1A2A,
            ],
        ],
        "constellation" => [
            [
                0x0B1026, 0x2A3060, 0x05081A, 0x05081A, 0x9FD8F0, 0xC9C4F0, 0xFFF2B0, 0x9FD8F0, 0x4A5080, 0xC9C4F0, 0x8049A8, 0xFFFFFF,
                0x8A90C0,
            ],
            [
                0x06141A, 0x1A4A44, 0x020A0C, 0x020A0C, 0x7FF0C0, 0xB0F0D8, 0xD0FFD8, 0x7FF0C0, 0x3A6058, 0xB0F0D8, 0x2A8A6A, 0xFFFFFF,
                0x6AA0A0,
            ],
            [
                0x1A0610, 0x4A1A34, 0x0A0206, 0x0A0206, 0xFF7A9A, 0xF0C0D0, 0xFFD0A0, 0xFF7A9A, 0x6A3048, 0xF0C0D0, 0xA8284A, 0xFFFFFF,
                0xA07090,
            ],
            [
                0x041A20, 0x1A4A5A, 0x020C10, 0x020C10, 0x6AD8F0, 0xC0E8F0, 0xF0F8FF, 0x6AD8F0, 0x2A5A6A, 0xC0E8F0, 0x1A6A8A, 0xFFFFFF,
                0x7AA8B8,
            ],
        ],
        "neon" => [
            [
                0x04070C, 0x0A1A24, 0x0E2A38, 0x000000, 0x1E4A5A, 0x6FF0FF, 0x6FF0FF, 0xFFFFFF, 0x2A4A58, 0xBFF8FF, 0x0F5A6E, 0xFF9A3A,
                0x6FF0FF,
            ],
            [
                0x0C0604, 0x241208, 0x3A1A08, 0x000000, 0x5A2A10, 0xFF9A3A, 0xFF9A3A, 0xFFFFFF, 0x5A3A28, 0xFFD8B0, 0x6E2E0F, 0x6FF0FF,
                0xFF9A3A,
            ],
            [
                0x0A040C, 0x1E0A24, 0x34103E, 0x000000, 0x4A1A5A, 0xFF5AE0, 0xFF5AE0, 0xFFFFFF, 0x4A2A58, 0xFFC8F4, 0x5A1A6E, 0x6FF0FF,
                0xFF5AE0,
            ],
            [
                0x06080C, 0x141A22, 0x2A3440, 0x000000, 0x3A4450, 0xE8F4FF, 0xE8F4FF, 0x9FD8F0, 0x3A4450, 0xE8F4FF, 0x2A3A4A, 0xFF9A3A,
                0xE8F4FF,
            ],
        ],
        "adventure" => [
            [
                0x5CA83A, 0xF0DCA0, 0x1A1A1A, 0x1A1A1A, 0xC8B078, 0xF4E4B0, 0xE5B23A, 0x3AA0E0, 0x6A6A6A, 0xF4F4F4, 0x2E7A2E, 0x8A6A40,
                0x3A7AD0,
            ],
            [
                0xD8B870, 0xF0E0B0, 0x1A1A1A, 0x1A1A1A, 0xB8986A, 0xFFF4D0, 0xE5B23A, 0xE8483A, 0x7A6A50, 0xF4F4F4, 0x6A8A3A, 0x9A6A3A,
                0x3A8AB0,
            ],
            [
                0xE8F0F8, 0xC8D8E8, 0x1A1A1A, 0x1A1A1A, 0xA8B8C8, 0x5A9AD0, 0xE5B23A, 0xE8483A, 0x8A9AAA, 0xF4F4F4, 0x2E5A4A, 0x8A9AAA,
                0x6AA0D0,
            ],
            [
                0x3A3A6A, 0x8A7AAA, 0x0A0A14, 0x0A0A14, 0x5A4A7A, 0xD8C8F0, 0xE5B23A, 0xF8D030, 0x5A5A6A, 0xF4F4F4, 0x2A2A4A, 0x5A4A6A,
                0x2A4A8A,
            ],
        ],
        "hunt" => [
            [
                0xC8B490, 0xE0CCA4, 0x4A3420, 0x2A1E12, 0x8A7050, 0x2A1E12, 0xB8282A, 0xE58A2A, 0x8A7A60, 0x2A1E12, 0x8AA060, 0xA89A80,
                0x7AA8C0,
            ],
            [
                0x4A3028, 0x6A4434, 0x1A0E08, 0x1A0E08, 0x8A5A3A, 0xFFB040, 0xE8483A, 0xFFB040, 0x6A5048, 0xF4E0C8, 0x8A2A1A, 0x5A4A44,
                0xD8501A,
            ],
            [
                0xD8E4EC, 0xEEF4F8, 0x2A3A4A, 0x1A2A3A, 0x7A8A9A, 0x2A4A6A, 0xB8282A, 0x2D7AB0, 0x9AAAB8, 0x1A2A3A, 0x9AB0A8, 0xB0B8C0,
                0x8AB8D8,
            ],
            [
                0x6A7A4A, 0x86965C, 0x2A3018, 0x1A2010, 0x4A5A2A, 0xD8E070, 0xB8282A, 0xD8E070, 0x4A5438, 0xF0F0D0, 0x3A5A2A, 0x7A7A5A,
                0x3A6A5A,
            ],
        ],
        "hellforge" => [
            [
                0x140808, 0x2A1414, 0x4A3A36, 0x000000, 0x3A2020, 0xE8483A, 0x3CC060, 0xFF3A2A, 0xE5B23A, 0xF4D8C8, 0x5A4A44, 0xFF6A2A,
                0x8A1A10,
            ],
            [
                0x0A120A, 0x1A2A18, 0x3A4A38, 0x000000, 0x1E3A1E, 0x7AF04A, 0x7AF04A, 0xB8FF5A, 0xE5B23A, 0xD8F4C8, 0x3A4A38, 0x7AF04A,
                0x2A5A1A,
            ],
            [
                0x0C1014, 0x1C242C, 0x4A5460, 0x000000, 0x2A3440, 0x6AB0FF, 0x3CC060, 0x6AB0FF, 0xE5B23A, 0xD8E4F0, 0x4A5460, 0x6AB0FF,
                0x2A3A4A,
            ],
            [
                0x140E04, 0x2A2010, 0x5A4A2A, 0x000000, 0x3A2A10, 0xFFC040, 0xFFC040, 0xFF7A2A, 0xE5B23A, 0xF4E4C0, 0x5A4A2A, 0xFF9A2A,
                0x6A3A0A,
            ],
        ],
        _ => [
            [
                0xE9D8B8, 0xD7BF94, 0x6B4E2E, 0x2C1D0E, 0x8C6C47, 0xB07D0A, 0xE5B23A, 0xE5B23A, 0x8C6C47, 0x4A3522, 0xB89A6A, 0x6E7F3E,
                0x9DB8B0,
            ],
            [
                0xD8DCC0, 0xBFC6A0, 0x4A5A30, 0x1E2814, 0x7A8A58, 0x5A8A2A, 0xA8C94A, 0xC8E070, 0x7A8A58, 0x2E3A1E, 0x9AA078, 0x3E6A2E,
                0x8FB8A8,
            ],
            [
                0xDCEBF2, 0xB6D4E3, 0x1B3A52, 0x0F2536, 0x7F9DB0, 0x2D7AB0, 0x5FB4DC, 0x9FD8F0, 0x7F9DB0, 0x1B3A52, 0x8FB3C8, 0x2F5A4A,
                0xA9D2E6,
            ],
            [
                0x3A1C14, 0x5A2A1A, 0x1A0805, 0x1A0805, 0x6A4A3A, 0xFFB040, 0xF07A3A, 0xFFB040, 0x5A3A2E, 0xFFD9A8, 0x241008, 0x2A1410,
                0xE8592A,
            ],
        ],
    };
    pal(all[v])
}

const PORTALS: [Color32; 8] =
    [rgb(0x8049A8), rgb(0x3C9347), rgb(0x2D7AB0), rgb(0xB8364A), rgb(0xE5B23A), rgb(0x3FC3C0), rgb(0xE0679C), rgb(0x8C6C47)];
const LIGHT: Color32 = rgb(0xF4E6C6);

fn portal(dungeon: &str) -> Color32 {
    PORTALS[lg_world::fight::java_string_hash(dungeon).rem_euclid(8) as usize]
}

/// The map's drawing surface: Java's Graphics2D calls on the canvas at `o`.
struct G<'a> {
    p: &'a Painter,
    o: Pos2,
}

impl G<'_> {
    fn at(&self, x: f32, y: f32) -> Pos2 {
        pos2(self.o.x + x, self.o.y + y)
    }

    fn r(&self, x: f32, y: f32, w: f32, h: f32) -> Rect {
        Rect::from_min_size(self.at(x, y), vec2(w, h))
    }

    fn fill(&self, x: f32, y: f32, w: f32, h: f32, c: Color32) {
        self.p.rect_filled(self.r(x, y, w, h), 0.0, c);
    }

    /// Java `drawRect`: the outline of w + 1 by h + 1 pixels, `width` wide around it.
    fn rect(&self, x: f32, y: f32, w: f32, h: f32, width: f32, c: Color32) {
        self.p.rect_stroke(self.r(x + 0.5, y + 0.5, w, h), 0.0, Stroke::new(width, c), StrokeKind::Middle);
    }

    fn round(&self, x: f32, y: f32, w: f32, h: f32, arc: f32, c: Color32) {
        self.p.rect_filled(self.r(x, y, w, h), arc / 2.0, c);
    }

    fn round_stroke(&self, x: f32, y: f32, w: f32, h: f32, arc: f32, width: f32, c: Color32) {
        self.p.rect_stroke(self.r(x + 0.5, y + 0.5, w, h), arc / 2.0, Stroke::new(width, c), StrokeKind::Middle);
    }

    fn oval(&self, x: f32, y: f32, w: f32, h: f32, c: Color32) {
        self.p.add(Shape::ellipse_filled(self.at(x + w / 2.0, y + h / 2.0), vec2(w / 2.0, h / 2.0), c));
    }

    fn oval_stroke(&self, x: f32, y: f32, w: f32, h: f32, width: f32, c: Color32) {
        self.p.add(Shape::ellipse_stroke(self.at(x + w / 2.0 + 0.5, y + h / 2.0 + 0.5), vec2(w / 2.0, h / 2.0), Stroke::new(width, c)));
    }

    /// The top half of an ellipse (Java `fillArc(x, y, w, h, 0, 180)`).
    fn half_oval(&self, x: f32, y: f32, w: f32, h: f32) -> Vec<Pos2> {
        let (cx, cy, rx, ry) = (x + w / 2.0, y + h / 2.0, w / 2.0, h / 2.0);
        (0..=24).map(|k| std::f32::consts::PI * k as f32 / 24.0).map(|a| self.at(cx + rx * a.cos(), cy - ry * a.sin())).collect()
    }

    fn half_oval_fill(&self, x: f32, y: f32, w: f32, h: f32, c: Color32) {
        self.p.add(Shape::convex_polygon(self.half_oval(x, y, w, h), c, Stroke::NONE));
    }

    fn half_oval_stroke(&self, x: f32, y: f32, w: f32, h: f32, width: f32, c: Color32) {
        self.p.add(Shape::line(self.half_oval(x, y, w, h), Stroke::new(width, c)));
    }

    /// A filled polygon, convex or not (ear clipping).
    fn poly(&self, pts: &[(f32, f32)], c: Color32) {
        let v: Vec<Pos2> = pts.iter().map(|(x, y)| self.at(*x, *y)).collect();
        fill_any(self.p, &v, c);
    }

    fn poly_stroke(&self, pts: &[(f32, f32)], width: f32, c: Color32) {
        let v: Vec<Pos2> = pts.iter().map(|(x, y)| self.at(*x, *y)).collect();
        self.p.add(Shape::closed_line(v, Stroke::new(width, c)));
    }

    fn line(&self, x1: f32, y1: f32, x2: f32, y2: f32, width: f32, c: Color32) {
        self.p.line_segment([self.at(x1, y1), self.at(x2, y2)], Stroke::new(width, c));
    }

    fn polyline(&self, pts: &[(f32, f32)], width: f32, c: Color32) {
        self.p.add(Shape::line(pts.iter().map(|(x, y)| self.at(*x, *y)).collect(), Stroke::new(width, c)));
    }

    fn dashed(&self, pts: &[Pos2], width: f32, c: Color32, dash: f32, gap: f32, offset: f32) {
        let v: Vec<Pos2> = pts.iter().map(|p| self.at(p.x, p.y)).collect();
        self.p.extend(Shape::dashed_line_with_offset(&v, Stroke::new(width, c), &[dash], &[gap], offset));
    }

    /// Java `drawString`: `y` is the baseline.
    fn text(&self, x: f32, y: f32, t: &str, f: &FontId, c: Color32) -> Rect {
        let asc = jtext::height(self.p, f) * 0.78;
        jtext::draw(self.p, self.at(x, y - asc), Align2::LEFT_TOP, t, f, c)
    }

    fn width(&self, t: &str, f: &FontId) -> f32 {
        jtext::width(self.p, t, f)
    }
}

/// A filled simple polygon (ear clipping), for the shapes that are not convex.
fn fill_any(p: &Painter, v: &[Pos2], c: Color32) {
    if v.len() < 3 {
        return;
    }
    let area: f32 = (0..v.len())
        .map(|i| {
            let (a, b) = (v[i], v[(i + 1) % v.len()]);
            a.x * b.y - b.x * a.y
        })
        .sum();
    let mut idx: Vec<usize> = (0..v.len()).collect();
    if area < 0.0 {
        idx.reverse();
    }
    let mut mesh = egui::Mesh::default();
    for q in v {
        mesh.colored_vertex(*q, c);
    }
    let cross = |a: Pos2, b: Pos2, c: Pos2| (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    let mut guard = 0;
    while idx.len() > 3 && guard < 10_000 {
        guard += 1;
        let n = idx.len();
        let mut cut = None;
        for i in 0..n {
            let (a, b, cc) = (idx[(i + n - 1) % n], idx[i], idx[(i + 1) % n]);
            if cross(v[a], v[b], v[cc]) <= 0.0 {
                continue;
            }
            let inside = idx.iter().any(|&k| {
                k != a
                    && k != b
                    && k != cc
                    && cross(v[a], v[b], v[k]) > 0.0
                    && cross(v[b], v[cc], v[k]) > 0.0
                    && cross(v[cc], v[a], v[k]) > 0.0
            });
            if !inside {
                cut = Some(i);
                mesh.add_triangle(a as u32, b as u32, cc as u32);
                break;
            }
        }
        match cut {
            Some(i) => {
                idx.remove(i);
            }
            None => break,
        }
    }
    if idx.len() == 3 {
        mesh.add_triangle(idx[0] as u32, idx[1] as u32, idx[2] as u32);
    }
    p.add(Shape::mesh(mesh));
}

/// One painted map (Java `MapCanvas`).
struct Canvas<'a> {
    g: G<'a>,
    m: &'a QuestMap,
    p: Pal,
    n: usize,
    cleared: usize,
    active: bool,
    selected: usize,
    phase: i64,
    w: f32,
    hits: Vec<Option<Rect>>,
}

fn head(size: f32) -> FontId {
    crate::theme::head(size)
}

fn body(size: f32) -> FontId {
    crate::theme::body(size)
}

impl Canvas<'_> {
    fn done(&self, i: usize) -> bool {
        i < self.cleared
    }

    fn current(&self, i: usize) -> bool {
        self.active && i == self.cleared
    }

    /// Stage positions (and the medal at index n) for the horizontal styles.
    fn points(&self, w: f32, h: f32, scatter: bool) -> Vec<Pos2> {
        let margin = 80.0;
        let mut r = JavaRandom::new(self.m.id.wrapping_mul(31).wrapping_add(7));
        (0..=self.n)
            .map(|i| {
                let x = margin + ((w - 2.0 * margin) as i64 * i as i64 / self.n.max(1) as i64) as f32;
                let f = if scatter {
                    0.24 + r.next_double() * 0.42
                } else if i % 2 == 0 {
                    0.66
                } else {
                    0.34
                };
                pos2(x, (f64::from(h) * f) as i32 as f32)
            })
            .collect()
    }

    /// Dash offset that moves the dashes forward as the animation runs.
    fn march(&self, pattern: i64, speed: i64) -> f32 {
        (pattern - 1 - (self.phase * speed).rem_euclid(pattern)) as f32
    }

    fn glow(&self, c: Pos2, size: f32, color: Color32) {
        let pulse = 0.5 + 0.5 * (self.phase as f64 * 0.2).sin();
        for k in (1..=3).rev() {
            let pad = (k as f64 * (6.0 + 4.0 * pulse)) as i32 as f32;
            self.g.oval(
                c.x - (size / 2.0).floor() - pad,
                c.y - (size / 2.0).floor() - pad,
                size + 2.0 * pad,
                size + 2.0 * pad,
                alpha(color, 40),
            );
        }
    }

    fn badge(&self, x: f32, y: f32, t: &str, bg: Color32, fg: Color32) {
        self.g.fill(x, y, 24.0, 24.0, bg);
        let f = body(18.0);
        self.g.text(x + 12.0 - (self.g.width(t, &f) / 2.0).floor(), y + 18.0, t, &f, fg);
    }

    fn check(&self, x: f32, y: f32) {
        self.g.fill(x - 2.0, y - 2.0, 24.0, 24.0, rgb(0x3C9347));
        self.g.rect(x - 2.0, y - 2.0, 24.0, 24.0, 2.0, self.p.ink);
        self.g.polyline(&[(x + 3.0, y + 10.0), (x + 8.0, y + 15.0), (x + 16.0, y + 5.0)], 3.0, Color32::WHITE);
    }

    fn padlock(&self, x: f32, y: f32) {
        self.g.fill(x - 2.0, y + 4.0, 20.0, 16.0, self.p.locked);
        self.g.rect(x - 2.0, y + 4.0, 20.0, 16.0, 3.0, self.p.ink);
        self.g.half_oval_stroke(x + 2.0, y - 6.0, 12.0, 18.0, 3.0, self.p.ink);
    }

    fn fit(&self, t: &str, f: &FontId, max: f32) -> String {
        if self.g.width(t, f) <= max {
            return t.to_string();
        }
        let mut s: Vec<char> = t.chars().collect();
        while s.len() > 3 && self.g.width(&format!("{}..", s.iter().collect::<String>()), f) > max {
            s.pop();
        }
        format!("{}..", s.into_iter().collect::<String>())
    }

    /// A name pill centred under a stage, shortened to fit between neighbours.
    fn label(&self, cx: f32, y: f32, t: &str, fg: Color32, bg: Color32, border: Color32) {
        let max = 70f32.max(((self.w - 160.0) / self.n.max(1) as f32).floor() - 4.0);
        self.label_max(cx, y, t, fg, bg, border, max);
    }

    #[allow(clippy::too_many_arguments)]
    fn label_max(&self, cx: f32, y: f32, t: &str, fg: Color32, bg: Color32, border: Color32, max: f32) {
        let f = body(18.0);
        let t = self.fit(t, &f, max);
        let tw = self.g.width(&t, &f) + 14.0;
        let x = cx - (tw / 2.0).floor();
        self.g.fill(x, y, tw, 24.0, bg);
        self.g.rect(x, y, tw, 24.0, 2.0, border);
        self.g.text(x + 7.0, y + 18.0, &t, &f, fg);
    }

    fn selection(&mut self, r: Rect, i: usize) {
        self.hits[i] = Some(r);
        if self.selected != i {
            return;
        }
        let pts = [
            pos2(r.left() - 4.0, r.top() - 4.0),
            pos2(r.right() + 4.0, r.top() - 4.0),
            pos2(r.right() + 4.0, r.bottom() + 4.0),
            pos2(r.left() - 4.0, r.bottom() + 4.0),
            pos2(r.left() - 4.0, r.top() - 4.0),
        ];
        self.g.dashed(&pts, 2.0, self.p.text, 5.0, 4.0, 0.0);
    }

    fn rect_at(x: f32, y: f32, w: f32, h: f32) -> Rect {
        Rect::from_min_size(pos2(x, y), vec2(w, h))
    }

    // -- parchment: a winding road between portal tiles, mountains and trees on old paper

    fn parchment(&mut self, w: f32, h: f32) {
        let (g, p) = (&self.g, self.p);
        g.fill(0.0, 0.0, w, h, p.bg);
        g.rect(8.0, 8.0, w - 16.0, h - 16.0, 4.0, p.bg2);
        let mut r = JavaRandom::new(self.m.id);
        for i in 0..6 {
            let x = r.next_int(1.max((w - 80.0) as i32)) as f32;
            let y = if i % 2 == 0 { 20.0 + r.next_int(30) as f32 } else { h - 90.0 + r.next_int(30) as f32 };
            g.poly(&[(x, y + 50.0), (x + 34.0, y), (x + 68.0, y + 50.0)], p.deco1);
            let tx = ((x as i32 + 220) % 1.max((w - 40.0) as i32)) as f32;
            g.poly(&[(tx, y + 34.0), (tx + 11.0, y + 4.0), (tx + 22.0, y + 34.0)], p.deco2);
            g.fill(tx + 9.0, y + 34.0, 4.0, 8.0, p.deco2);
        }
        g.oval(-60.0, h - 70.0, 260.0, 140.0, p.deco3);
        // the compass
        g.rect(w - 70.0, h - 70.0, 50.0, 50.0, 3.0, p.frame);
        g.text(w - 50.0, h - 38.0, "N", &body(20.0), p.frame);
        let pts = self.points(w, h - 40.0, false);
        for i in 0..self.n {
            let (a, b) = (pts[i], pts[i + 1]);
            let ctrl = pos2((a.x + b.x) / 2.0, if i % 2 == 0 { a.y.min(b.y) - 50.0 } else { a.y.max(b.y) + 50.0 });
            let curve: Vec<Pos2> = (0..=32)
                .map(|k| {
                    let t = k as f32 / 32.0;
                    let u = 1.0 - t;
                    pos2(u * u * a.x + 2.0 * u * t * ctrl.x + t * t * b.x, u * u * a.y + 2.0 * u * t * ctrl.y + t * t * b.y)
                })
                .collect();
            self.road(&curve, i);
        }
        for (i, c) in pts.iter().enumerate().take(self.n) {
            self.tile(*c, i, 56.0);
        }
        self.medal(pts[self.n], p.frame);
    }

    fn road(&self, pts: &[Pos2], i: usize) {
        let walked = i < self.cleared;
        let next = self.current(i + 1) || (self.active && i == self.cleared);
        if walked {
            self.g.polyline(&pts.iter().map(|q| (q.x, q.y)).collect::<Vec<_>>(), 7.0, self.p.walked);
        } else {
            self.g.dashed(pts, 6.0, self.p.road, 12.0, 12.0, if next { self.march(24, 2) } else { 0.0 });
        }
    }

    /// A square portal tile with its number, a check when cleared, a padlock while locked, and its name.
    fn tile(&mut self, c: Pos2, i: usize, size: f32) {
        let (d, cur) = (self.done(i), self.current(i));
        let p = self.p;
        let (x, y) = (c.x - size / 2.0, c.y - size / 2.0);
        if cur {
            self.glow(c, size, p.current);
        }
        let g = &self.g;
        g.fill(x, y, size, size, if d || cur { p.bg } else { p.bg2 });
        g.rect(
            x,
            y,
            size,
            size,
            4.0,
            if d {
                p.done
            } else if cur {
                p.current
            } else {
                p.locked
            },
        );
        let pc = if d || cur { portal(&self.m.dungeons[i]) } else { p.locked };
        g.rect(x + 12.0, y + 12.0, size - 24.0, size - 24.0, 5.0, pc);
        g.fill(c.x - 5.0, c.y - 5.0, 10.0, 10.0, pc);
        self.badge(x - 8.0, y - 8.0, &(i + 1).to_string(), p.ink, LIGHT);
        if d {
            self.check(x + size - 12.0, y + size - 12.0);
        }
        if !d && !cur {
            self.padlock(x + size - 10.0, y + size - 10.0);
        }
        let name = &self.m.dungeons[i];
        if d || cur {
            self.label(c.x, y + size + 8.0, name, if d { p.done } else { p.current }, p.ink, p.ink);
        } else {
            self.label(c.x, y + size + 8.0, name, p.text, p.bg2, p.ink);
        }
        self.selection(Canvas::rect_at(x - 6.0, y - 6.0, size + 12.0, size + 12.0), i);
    }

    fn medal(&self, c: Pos2, ink: Color32) {
        let (g, p) = (&self.g, self.p);
        let won = self.cleared == self.n;
        g.poly(&[(c.x - 16.0, c.y - 50.0), (c.x - 4.0, c.y - 50.0), (c.x - 10.0, c.y - 20.0), (c.x - 22.0, c.y - 20.0)], rgb(0xB8364A));
        g.poly(&[(c.x + 4.0, c.y - 50.0), (c.x + 16.0, c.y - 50.0), (c.x + 22.0, c.y - 20.0), (c.x + 10.0, c.y - 20.0)], rgb(0x2D7AB0));
        g.oval(c.x - 28.0, c.y - 26.0, 56.0, 56.0, if won { p.done } else { p.bg2 });
        g.oval_stroke(c.x - 28.0, c.y - 26.0, 56.0, 56.0, 4.0, ink);
        g.text(c.x - 6.0, c.y + 12.0, "?", &head(30.0), ink);
        self.label(c.x, c.y + 38.0, "Guild medal · soon", p.text, p.bg, ink);
    }

    // -- hex board: a trail of hexes across a honeycomb of terrain

    fn hexagon(&self, cx: f32, cy: f32, r: f32) -> Vec<(f32, f32)> {
        (0..6).map(|k| std::f32::consts::PI / 3.0 * k as f32).map(|a| (cx + r * a.cos(), cy + r * a.sin())).collect()
    }

    fn hex(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        self.g.fill(0.0, 0.0, w, h, p.bg);
        let r = 40.0f32;
        let step = 3f32.sqrt() * r;
        let cols = ((2 * n + 2) as i32).max(((w - 2.0 * r) / (1.5 * r)) as i32 + 1) as usize;
        let rows = (h / step) as usize + 1;
        let x0 = (w - (1.5 * r * (cols - 1) as f32 + 2.0 * r)) / 2.0 + r;
        let y0 = step / 2.0 + 6.0;
        let mid = 1.max(rows as i32 / 2 - 1) as usize;
        let mut stage_col = vec![0usize; n + 1];
        let mut stage_row = vec![0usize; n + 1];
        for i in 0..=n {
            stage_col[i] = 1 + (i as f64 * (cols as f64 - 2.0) / n.max(1) as f64).round() as usize;
            stage_row[i] = if i % 2 == 0 { mid } else { mid + 1 };
        }
        let mut trail = vec![vec![0usize; rows + 2]; cols + 2];
        trail[0][stage_row[0]] = 1;
        for i in 0..=n {
            trail[stage_col[i]][stage_row[i]] = 3 + i;
            if i == n {
                break;
            }
            let (from, to) = (stage_col[i] + 1, stage_col[i + 1].saturating_sub(1));
            let half = (from + to) / 2;
            for c in from..=to {
                trail[c][if c <= half { stage_row[i] } else { stage_row[i + 1] }] = if i < self.cleared { 1 } else { 2 };
            }
        }
        let mut rnd = JavaRandom::new(self.m.id);
        let terrain = [p.bg2, p.bg2, p.deco1, p.deco2, p.deco3];
        let mut centers: Vec<Option<Pos2>> = vec![None; n + 1];
        for c in 0..cols {
            for row in 0..rows {
                let cx = x0 + c as f32 * 1.5 * r;
                let cy = y0 + row as f32 * step + if c % 2 == 1 { step / 2.0 } else { 0.0 };
                let t = terrain[rnd.next_int(terrain.len() as i32) as usize];
                let kind = trail[c][row];
                if kind >= 3 {
                    centers[kind - 3] = Some(pos2(cx as i32 as f32, cy as i32 as f32));
                    continue;
                }
                let hx = self.hexagon(cx, cy, r - 1.5);
                self.g.poly(
                    &hx,
                    if kind == 1 {
                        p.walked
                    } else if kind == 2 {
                        p.road
                    } else {
                        t
                    },
                );
                self.g.poly_stroke(&hx, 2.0, p.ink);
                if kind == 2 {
                    self.g.oval(cx as i32 as f32 - 5.0, cy as i32 as f32 - 5.0, 10.0, 10.0, brighter(p.locked));
                }
            }
        }
        for i in 0..=n {
            let Some(c) = centers[i] else { continue };
            let (d, cur, medal) = (i < n && self.done(i), i < n && self.current(i), i == n);
            let won = medal && self.cleared == n;
            if cur {
                self.glow(c, (r * 1.8) as i32 as f32, p.current);
            }
            let edge = if d || won {
                p.done
            } else if cur {
                p.current
            } else {
                p.locked
            };
            self.g.poly(&self.hexagon(c.x, c.y, r - 1.0), edge);
            self.g.poly(&self.hexagon(c.x, c.y, r - 8.0), p.bg);
            let f = head((r * 0.9) as i32 as f32);
            let t = if medal { "?".to_string() } else { (i + 1).to_string() };
            let asc = jtext::height(self.g.p, &f) * 0.78;
            self.g.text(c.x - (self.g.width(&t, &f) / 2.0).floor(), c.y + (asc / 2.0).floor() - 2.0, &t, &f, edge);
            if medal {
                self.label(c.x, c.y + r, "Guild medal", p.ink, edge, edge);
            } else {
                self.label(c.x, c.y + r, &self.m.dungeons[i], p.ink, edge, edge);
                self.selection(Canvas::rect_at(c.x - r, c.y - r, 2.0 * r, 2.0 * r), i);
            }
        }
    }

    // -- overworld: an island with level dots on a dotted path, and a castle at the end

    fn overworld(&mut self, w: f32, h: f32) {
        let p = self.p;
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        let cloud = ((self.phase / 3) % 60) as f32;
        for (cx, cy, cw) in [((w / 4.0).floor(), 18.0, 110.0), ((w * 2.0 / 3.0).floor(), 34.0, 84.0)] {
            let x = cx + if self.active { cloud } else { 0.0 };
            g.round(x, cy, cw, 32.0, 22.0, Color32::WHITE);
            g.round_stroke(x, cy, cw, 32.0, 22.0, 4.0, p.ink);
        }
        g.round(14.0, 70.0, w - 28.0, h + 60.0, 160.0, p.bg2);
        g.round_stroke(14.0, 70.0, w - 28.0, h + 60.0, 160.0, 4.0, p.ink);
        let mut r = JavaRandom::new(self.m.id);
        for _ in 0..4 {
            let x = 40.0 + r.next_int(1.max((w - 160.0) as i32)) as f32;
            let y = 92.0 + r.next_int(30) as f32;
            g.half_oval_fill(x, y, 100.0, 80.0, p.deco1);
            g.half_oval_stroke(x, y, 100.0, 80.0, 4.0, p.ink);
            let tx = 60.0 + r.next_int(1.max((w - 120.0) as i32)) as f32;
            let ty = h - 70.0 - r.next_int(30) as f32;
            g.oval(tx, ty, 28.0, 28.0, p.deco2);
            g.oval_stroke(tx, ty, 28.0, 28.0, 4.0, p.ink);
            g.fill(tx + 11.0, ty + 28.0, 6.0, 12.0, p.ink);
        }
        let pts = self.points(w, h - 20.0, false);
        for i in 0..self.n {
            let (a, b) = (pts[i], pts[i + 1]);
            for t in 1..=5 {
                let x = a.x + ((b.x - a.x) as i32 * t / 6) as f32;
                let y = a.y + ((b.y - a.y) as i32 * t / 6) as f32;
                g.oval(x - 6.0, y - 6.0, 12.0, 12.0, if i < self.cleared { p.walked } else { p.road });
                g.oval_stroke(x - 6.0, y - 6.0, 12.0, 12.0, 3.0, p.ink);
            }
        }
        for i in 0..self.n {
            let c = pts[i];
            let (d, cur) = (self.done(i), self.current(i));
            let g = &self.g;
            g.oval(c.x - 26.0, c.y - 21.0, 52.0, 52.0, p.ink);
            g.oval(
                c.x - 26.0,
                c.y - 26.0,
                52.0,
                52.0,
                if d {
                    p.done
                } else if cur {
                    p.current
                } else {
                    p.locked
                },
            );
            g.oval_stroke(c.x - 26.0, c.y - 26.0, 52.0, 52.0, 5.0, p.ink);
            let f = head(28.0);
            let t = (i + 1).to_string();
            g.text(c.x - (g.width(&t, &f) / 2.0).floor(), c.y + 9.0, &t, &f, p.ink);
            if d {
                g.fill(c.x + 16.0, c.y - 60.0, 4.0, 40.0, p.ink);
                g.fill(c.x + 20.0, c.y - 60.0, 22.0, 15.0, p.done);
                g.rect(c.x + 20.0, c.y - 60.0, 22.0, 15.0, 3.0, p.ink);
            }
            if cur {
                let by = c.y - 72.0 - ((self.phase / 4) % 2) as f32 * 8.0;
                g.fill(c.x - 10.0, by, 20.0, 8.0, p.done);
                g.fill(c.x - 10.0, by + 8.0, 20.0, 14.0, rgb(0xF8C890));
                g.fill(c.x - 12.0, by + 22.0, 24.0, 16.0, rgb(0x3A6AE0));
                g.rect(c.x - 10.0, by, 20.0, 22.0, 3.0, p.ink);
                g.rect(c.x - 12.0, by + 22.0, 24.0, 16.0, 3.0, p.ink);
            }
            self.label(c.x, c.y + 34.0, &self.m.dungeons[i], p.ink, Color32::WHITE, p.ink);
            self.selection(Canvas::rect_at(c.x - 30.0, c.y - 30.0, 60.0, 60.0), i);
        }
        // the castle
        let c = pts[self.n];
        let won = self.cleared == self.n;
        let (x, y) = (c.x - 45.0, c.y - 50.0);
        let g = &self.g;
        g.fill(x, y + 20.0, 90.0, 70.0, rgb(0xC8C8C8));
        for k in 0..4 {
            g.fill(x + k as f32 * 26.0, y + 6.0, 14.0, 16.0, rgb(0xC8C8C8));
        }
        g.rect(x, y + 20.0, 90.0, 70.0, 4.0, p.ink);
        g.round(x + 28.0, y + 52.0, 34.0, 40.0, 30.0, p.ink);
        g.fill(x + 43.0, y - 30.0, 4.0, 36.0, p.ink);
        g.fill(x + 47.0, y - 30.0, 26.0, 18.0, if won { p.current } else { p.locked });
        g.text(x + 39.0, y + 82.0, "?", &head(26.0), if won { p.current } else { p.locked });
        self.label(c.x, y + 96.0, "Medal · soon", Color32::WHITE, p.ink, p.ink);
    }

    // -- Oryx's tower: a floor per stage, climbed from the bottom, his throne on top

    fn bricks(&self, x: f32, y: f32, w: f32, h: f32, fill: Color32) {
        let g = &self.g;
        g.fill(x, y, w, h, fill);
        let mut by = y;
        while by < y + h {
            g.fill(x, by, w, 3.0, self.p.frame);
            let off = if (((by - y) / 22.0) as i32) % 2 == 0 { 0.0 } else { 22.0 };
            let mut bx = x + off;
            while bx < x + w {
                g.fill(bx, by, 3.0, 22f32.min(y + h - by), self.p.frame);
                bx += 44.0;
            }
            by += 22.0;
        }
        g.rect(x, y, w, h, 4.0, self.p.frame);
    }

    fn oryx(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        g.oval(40.0, 30.0, 60.0, 60.0, p.deco1);
        let (w8, w5, w3) = ((w / 8.0).floor(), (w / 5.0).floor(), (w / 3.0).floor());
        g.poly(&[(0.0, h - 200.0), (w8, h - 260.0), (w5, h - 210.0), (w3, h - 280.0), (w3, h), (0.0, h)], p.deco2);
        let (a, b, c) = ((w * 7.0 / 8.0).floor(), (w * 4.0 / 5.0).floor(), (w * 2.0 / 3.0).floor());
        g.poly(&[(w, h - 180.0), (a, h - 250.0), (b, h - 200.0), (c, h - 270.0), (c, h), (w, h)], p.deco2);
        let tw = (w - 40.0).min(640.0);
        let tx = ((w - tw) / 2.0).floor();
        let (floor_h, throne_h, top) = (58.0, 130.0, 12.0);
        self.bricks(tx, top, tw, throne_h, p.bg2);
        let g = &self.g;
        for bx in [tx + (tw / 6.0).floor(), tx + (tw * 5.0 / 6.0).floor() - 34.0] {
            g.poly(
                &[(bx, top + 30.0), (bx + 34.0, top + 30.0), (bx + 34.0, top + 110.0), (bx + 17.0, top + 96.0), (bx, top + 110.0)],
                p.deco3,
            );
        }
        let mid = tx + (tw / 2.0).floor();
        g.fill(mid - 46.0, top + 26.0, 92.0, 100.0, p.frame);
        let won = self.cleared == n;
        g.oval(mid - 26.0, top + 50.0, 52.0, 52.0, if won { p.done } else { p.locked });
        g.text(mid - 7.0, top + 86.0, "?", &head(32.0), p.frame);
        self.hits[n] = Some(Canvas::rect_at(tx, top, tw, throne_h));
        self.label_max(mid, top + throne_h - 24.0, "THRONE · guild medal soon", p.text, p.frame, p.frame, tw);
        for i in 0..n {
            let fy = top + throne_h + (n - 1 - i) as f32 * floor_h;
            let (d, cur) = (self.done(i), self.current(i));
            self.bricks(
                tx,
                fy,
                tw,
                floor_h,
                if d {
                    brighter(p.bg2)
                } else if cur {
                    p.bg2
                } else {
                    darker(p.bg2)
                },
            );
            let g = &self.g;
            g.fill(tx + 10.0, fy + 8.0, 56.0, floor_h - 16.0, p.frame);
            let f = head(34.0);
            let num = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII"][i.min(7)];
            g.text(
                tx + 38.0 - (g.width(num, &f) / 2.0).floor(),
                fy + floor_h / 2.0 + 11.0,
                num,
                &f,
                if d {
                    p.done
                } else if cur {
                    p.current
                } else {
                    p.locked
                },
            );
            g.fill(tx + 76.0, fy + 8.0, tw - 76.0 - 120.0, floor_h - 16.0, Color32::from_rgba_unmultiplied(0, 0, 0, 170));
            let tc = if d || cur { p.text } else { p.locked };
            g.text(tx + 86.0, fy + 30.0, &self.m.dungeons[i], &body(22.0), tc);
            let what = if d {
                format!("cleared by {}", self.m.clears[i].ign)
            } else if cur {
                "any guildie can clear it".into()
            } else {
                "locked".into()
            };
            g.text(tx + 86.0, fy + 46.0, &what, &body(15.0), tc);
            let (gw, gh) = (40.0, floor_h - 10.0);
            let (gx, gy) = (tx + tw - 84.0, fy + floor_h - gh);
            if d || cur {
                let flick = (self.phase / 3 + i as i64) % 2 == 0;
                g.oval(gx - 16.0, gy + if flick { 2.0 } else { 4.0 }, 9.0, if flick { 15.0 } else { 12.0 }, rgb(0xF0A030));
                g.oval(gx + gw + 7.0, gy + if flick { 4.0 } else { 2.0 }, 9.0, if flick { 12.0 } else { 15.0 }, rgb(0xF0A030));
            }
            if cur {
                self.glow(pos2(gx + gw / 2.0, gy + (gh / 2.0).floor()), gh, p.current);
            }
            let g = &self.g;
            let gate = if cur { darker(p.current) } else { p.frame };
            g.oval(gx, gy, gw, gw, gate);
            g.fill(gx, gy + gw / 2.0, gw, gh - gw / 2.0, gate);
            if !d && !cur {
                let mut bb = gx + 5.0;
                while bb < gx + gw - 3.0 {
                    g.fill(bb, gy + 6.0, 3.0, gh - 6.0, p.locked);
                    bb += 8.0;
                }
            }
            if d {
                self.check(gx + gw + 4.0, gy + gh - 22.0);
            }
            let floor = Canvas::rect_at(tx, fy, tw, floor_h);
            self.hits[i] = Some(floor);
            if self.selected == i {
                self.g.rect(floor.left() + 2.0, floor.top() + 2.0, floor.width() - 4.0, floor.height() - 4.0, 3.0, p.current);
            }
        }
        let g = &self.g;
        let base_y = top + throne_h + n as f32 * floor_h;
        g.fill(tx, base_y, tw, h - base_y, p.frame);
        let f = body(15.0);
        let ent = "ENTRANCE · CLIMB FROM HERE";
        g.text(tx + ((tw - g.width(ent, &f)) / 2.0).floor(), base_y + 20.0, ent, &f, p.locked);
    }

    // -- constellation: stars that join into a figure as stages are cleared

    fn star(&self, cx: f32, cy: f32, r: f32, points: usize) -> Vec<(f32, f32)> {
        (0..points * 2)
            .map(|k| {
                let a = std::f32::consts::PI / points as f32 * k as f32 - std::f32::consts::FRAC_PI_2;
                let rr = if k % 2 == 0 { r } else { r * 0.32 };
                (cx + rr * a.cos(), cy + rr * a.sin())
            })
            .collect()
    }

    fn constellation(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        g.oval((w / 6.0).floor(), (h / 5.0).floor(), (w * 2.0 / 3.0).floor(), (h * 3.0 / 5.0).floor(), alpha(p.deco1, 40));
        let mut r = JavaRandom::new(self.m.id);
        for i in 0..90 {
            let x = r.next_int(1.max(w as i32)) as f32;
            let y = r.next_int(h as i32) as f32;
            let s = if i % 6 == 0 { 3.0 } else { 2.0 };
            let dim = self.active && (self.phase / 6 + i) % 9 == 0;
            g.fill(
                x,
                y,
                s,
                s,
                if dim {
                    darker(p.deco3)
                } else if i % 3 == 0 {
                    p.deco2
                } else {
                    p.deco3
                },
            );
        }
        g.text(18.0, 36.0, &self.m.name.to_uppercase(), &head(30.0), p.bg2);
        let pts = self.points(w, h - 30.0, true);
        for i in 0..n {
            let (a, b) = (pts[i], pts[i + 1]);
            if i + 1 < self.cleared || (i < self.cleared && i + 1 == n && self.cleared == n) {
                g.line(a.x, a.y, b.x, b.y, 3.0, p.walked);
            } else if self.active && self.cleared > 0 && i == self.cleared - 1 {
                g.dashed(&[a, b], 2.0, p.road, 6.0, 6.0, self.march(12, 1));
            }
        }
        for i in 0..n {
            let c = pts[i];
            let (d, cur) = (self.done(i), self.current(i));
            let size = if d {
                40.0
            } else if cur {
                46.0 + (6.0 * (self.phase as f64 * 0.25).sin()) as i32 as f32
            } else {
                18.0
            };
            if d || cur {
                self.glow(c, size, if d { p.done } else { p.current });
            }
            let cap = if d {
                p.done
            } else if cur {
                p.current
            } else {
                p.locked
            };
            self.g.poly(&self.star(c.x, c.y, size / 2.0, 4), cap);
            let f = body(18.0);
            let t = format!("{} · {}", i + 1, self.m.dungeons[i]);
            let t = self.fit(&t, &f, ((w - 160.0) / n.max(1) as f32).floor());
            self.g.text(c.x - (self.g.width(&t, &f) / 2.0).floor(), c.y + 40.0, &t, &f, cap);
            self.selection(Canvas::rect_at(c.x - 28.0, c.y - 28.0, 56.0, 56.0), i);
        }
        let mc = pts[n];
        let won = self.cleared == n;
        self.g.poly(&self.star(mc.x, mc.y, 44.0, 8), if won { p.done } else { p.bg2 });
        self.g.text(mc.x - 6.0, mc.y + 9.0, "?", &head(28.0), if won { p.ink } else { p.locked });
    }

    // -- neon grid: a light trail with right-angle turns across a glowing perspective grid

    fn neon_line(&self, x1: f32, y1: f32, x2: f32, y2: f32, c: Color32, width: f32) {
        self.g.line(x1, y1, x2, y2, width * 3.0, alpha(c, 60));
        self.g.line(x1, y1, x2, y2, width, c);
    }

    fn neon(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        let horizon = (h * 0.2).floor();
        let vx = (w / 2.0).floor();
        g.fill(0.0, horizon, w, h - horizon, p.bg2);
        let scroll = if self.active { self.phase % 12 } else { 0 };
        for k in 0..=12 {
            let t = (k as f64 + scroll as f64 / 12.0) / 12.0;
            let y = horizon + ((h - horizon) as f64 * t * t) as i32 as f32;
            g.line(0.0, y, w, y, 1.0, p.deco1);
        }
        for k in -16..=16 {
            g.line(vx, horizon, vx + (k * w as i32 / 8) as f32, h, 1.0, p.deco1);
        }
        self.neon_line(0.0, horizon, w, horizon, p.deco3, 2.0);
        let pts = self.points(w, h - 30.0, false);
        for i in 0..n {
            let (a, b) = (pts[i], pts[i + 1]);
            if i < self.cleared {
                self.neon_line(a.x, a.y, b.x, a.y, p.walked, 4.0);
                self.neon_line(b.x, a.y, b.x, b.y, p.walked, 4.0);
            } else {
                let next = self.active && i == self.cleared;
                self.g.dashed(&[a, pos2(b.x, a.y), b], 3.0, p.road, 10.0, 8.0, if next { self.march(18, 2) } else { 0.0 });
            }
        }
        for i in 0..n {
            let c = pts[i];
            let (d, cur) = (self.done(i), self.current(i));
            let col = if d {
                p.done
            } else if cur {
                p.current
            } else {
                p.locked
            };
            if cur {
                self.glow(c, 46.0, p.walked);
            }
            let dia = [(c.x, c.y - 26.0), (c.x + 26.0, c.y), (c.x, c.y + 26.0), (c.x - 26.0, c.y)];
            let g = &self.g;
            g.poly(&dia, p.bg);
            if d || cur {
                g.poly_stroke(&dia, 9.0, alpha(col, 70));
            }
            g.poly_stroke(&dia, 3.0, col);
            let f = head(24.0);
            let t = (i + 1).to_string();
            g.text(c.x - (g.width(&t, &f) / 2.0).floor(), c.y + 8.0, &t, &f, col);
            self.label(c.x, c.y + 34.0, &self.m.dungeons[i].to_uppercase(), col, p.bg, if d || cur { col } else { p.road });
            self.selection(Canvas::rect_at(c.x - 30.0, c.y - 30.0, 60.0, 60.0), i);
        }
        let mc = pts[n];
        let won = self.cleared == n;
        let disc = if won { p.done } else { p.locked };
        if won {
            self.glow(mc, 56.0, p.done);
        }
        let g = &self.g;
        g.oval(mc.x - 28.0, mc.y - 28.0, 56.0, 56.0, p.bg);
        g.oval_stroke(mc.x - 28.0, mc.y - 28.0, 56.0, 56.0, 4.0, disc);
        g.oval_stroke(mc.x - 14.0, mc.y - 14.0, 28.0, 28.0, 4.0, disc);
        g.text(mc.x - 5.0, mc.y + 8.0, "?", &head(22.0), disc);
        self.label(mc.x, mc.y + 36.0, "MEDAL · SOON", disc, p.bg, disc);
    }

    // -- hero's overworld: a top-down tile map, cave entrances, and fog over the land not yet explored

    fn adventure(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let t = 30.0f32;
        let cols = ((n + 7) as i32).max(w as i32 / 30 + 1) as usize;
        let rows = 6.max(h as i32 / 30 + 1) as usize;
        let mut r = JavaRandom::new(self.m.id);
        let mut sc = vec![0usize; n + 1];
        let mut sr = vec![0usize; n + 1];
        for i in 0..=n {
            sc[i] = 2 + (i as f64 * (cols as f64 - 6.0) / n.max(1) as f64).round() as usize;
            sr[i] = if i % 2 == 0 { (rows as f64 * 0.62) as usize } else { (rows as f64 * 0.3) as usize };
        }
        let mut path = vec![vec![0u8; rows + 1]; cols + 1];
        for i in 0..n {
            let kind = if i < self.cleared { 1 } else { 2 };
            for c in sc[i].min(sc[i + 1])..=sc[i].max(sc[i + 1]) {
                path[c][sr[i]] = kind;
            }
            for row in sr[i].min(sr[i + 1])..=sr[i].max(sr[i + 1]) {
                path[sc[i + 1]][row] = kind;
            }
        }
        let g = &self.g;
        for c in 0..cols {
            for row in 0..rows {
                let (x, y) = (c as f32 * t, row as f32 * t);
                g.fill(x, y, t, t, p.bg);
                if path[c][row] > 0 {
                    g.fill(x, y, t, t, if path[c][row] == 1 { p.walked } else { p.road });
                    continue;
                }
                let roll = r.next_int(100);
                let near = (0..=n).any(|i| (c as i32 - sc[i] as i32).abs() <= 1 && (row as i32 - sr[i] as i32).abs() <= 1);
                if near {
                    continue;
                }
                if roll < 14 {
                    g.oval(x + 3.0, y + 2.0, t - 6.0, t - 8.0, p.deco1);
                    g.oval_stroke(x + 3.0, y + 2.0, t - 6.0, t - 8.0, 2.0, p.ink);
                    g.fill(x + t / 2.0 - 2.0, y + t - 7.0, 4.0, 6.0, p.ink);
                } else if roll < 20 {
                    let tri = [(x, y + t), (x + t / 2.0, y + 2.0), (x + t, y + t)];
                    g.poly(&tri, p.deco2);
                    g.poly_stroke(&tri, 2.0, p.ink);
                } else if roll < 24 {
                    g.fill(x + 1.0, y + 1.0, t - 2.0, t - 2.0, p.deco3);
                }
            }
        }
        if self.active || self.cleared < n {
            let edge = (sc[self.cleared.min(n)] + 2) as f32 * t;
            g.fill(edge, 0.0, w - edge, h, Color32::from_rgba_unmultiplied(0, 0, 0, 120));
        }
        for i in 0..n {
            let c = pos2(sc[i] as f32 * t + t / 2.0, sr[i] as f32 * t + t / 2.0);
            let (d, cur) = (self.done(i), self.current(i));
            let (x, y) = (c.x - 26.0, c.y - 24.0);
            let g = &self.g;
            g.round(x, y, 52.0, 44.0, 14.0, p.deco2);
            g.round_stroke(x, y, 52.0, 44.0, 14.0, 3.0, p.ink);
            g.half_oval_fill(x + 13.0, y + 14.0, 26.0, 36.0, p.ink);
            g.fill(x + 13.0, y + 31.0, 26.0, 13.0, p.ink);
            let f = head(20.0);
            let num = (i + 1).to_string();
            g.text(
                c.x - (g.width(&num, &f) / 2.0).floor(),
                y + 40.0,
                &num,
                &f,
                if d {
                    p.done
                } else if cur {
                    p.current
                } else {
                    p.locked
                },
            );
            if d {
                self.check(x + 40.0, y - 8.0);
            }
            if cur && (self.phase / 5) % 4 != 3 {
                let g = &self.g;
                let hy = y - 30.0 - ((self.phase / 4) % 2) as f32 * 3.0;
                g.poly(&[(c.x - 9.0, hy + 8.0), (c.x + 9.0, hy + 8.0), (c.x + 3.0, hy - 4.0)], p.current);
                g.fill(c.x - 7.0, hy + 8.0, 14.0, 10.0, rgb(0xF8C890));
                g.fill(c.x - 8.0, hy + 18.0, 16.0, 12.0, darker(p.current));
                g.rect(c.x - 8.0, hy + 8.0, 16.0, 22.0, 2.0, p.ink);
            }
            self.label(c.x, y + 50.0, &self.m.dungeons[i], p.text, p.ink, p.ink);
            self.selection(Canvas::rect_at(x - 4.0, y - 4.0, 60.0, 52.0), i);
        }
        let ch = pos2(sc[n] as f32 * t + t / 2.0, sr[n] as f32 * t + t / 2.0);
        let won = self.cleared == n;
        let g = &self.g;
        g.fill(ch.x - 24.0, ch.y - 14.0, 48.0, 30.0, rgb(0x8A5A28));
        g.half_oval_fill(ch.x - 24.0, ch.y - 28.0, 48.0, 28.0, rgb(0x8A5A28));
        g.fill(ch.x - 24.0, ch.y - 6.0, 48.0, 6.0, if won { p.done } else { p.locked });
        g.fill(ch.x - 5.0, ch.y - 10.0, 10.0, 14.0, if won { p.done } else { p.locked });
        g.rect(ch.x - 24.0, ch.y - 14.0, 48.0, 30.0, 3.0, p.ink);
        g.half_oval_stroke(ch.x - 24.0, ch.y - 28.0, 48.0, 28.0, 3.0, p.ink);
        self.label(ch.x, ch.y + 22.0, "Medal · soon", p.text, p.ink, p.ink);
    }

    // -- hunting grounds: numbered zones on a survey map, a camp at the start, the apex monster at the end

    fn hunt(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        let mut r = JavaRandom::new(self.m.id);
        let regions = [p.deco1, p.deco2, p.deco3];
        for k in 0..7 {
            let c = regions[k % 3];
            let x = r.next_int(1.max(w as i32)) as f32 - 120.0;
            let y = r.next_int(h as i32) as f32 - 80.0;
            let ow = 220.0 + r.next_int(200) as f32;
            let oh = 140.0 + r.next_int(120) as f32;
            g.oval(x, y, ow, oh, alpha(c, 120));
        }
        g.rect(6.0, 6.0, w - 12.0, h - 12.0, 12.0, p.frame);
        g.rect(14.0, 14.0, w - 28.0, h - 28.0, 2.0, p.ink);
        let pts = self.points(w, h - 30.0, true);
        let camp = pos2(34f32.max(pts[0].x - 50.0), pts[0].y - 40.0);
        let tent = [(camp.x - 16.0, camp.y + 12.0), (camp.x, camp.y - 14.0), (camp.x + 16.0, camp.y + 12.0)];
        g.poly(&tent, rgb(0xD8C8A0));
        g.poly_stroke(&tent, 2.0, p.ink);
        g.line(camp.x, camp.y - 14.0, camp.x, camp.y + 12.0, 2.0, p.ink);
        for i in 0..n {
            let (a, b) = (pts[i], pts[i + 1]);
            let len = a.distance(b);
            let mut s = 44.0;
            while s < len - 44.0 {
                let x = (a.x + (b.x - a.x) * s / len) as i32 as f32;
                let y = (a.y + (b.y - a.y) * s / len) as i32 as f32;
                g.oval(x - 3.0, y - 3.0, 6.0, 6.0, if i < self.cleared { p.walked } else { alpha(p.road, 160) });
                s += 16.0;
            }
        }
        for i in 0..n {
            let c = pts[i];
            let (d, cur) = (self.done(i), self.current(i));
            let zone: Vec<(f32, f32)> = (0..7)
                .map(|k| {
                    let a = std::f64::consts::PI * 2.0 * k as f64 / 7.0;
                    let rr = 38.0 + ((self.m.id + i as i64 * 7 + k * 13).rem_euclid(9)) as f64;
                    ((c.x as f64 + rr * a.cos()) as i32 as f32, (c.y as f64 + rr * a.sin()) as i32 as f32)
                })
                .collect();
            if cur {
                self.glow(c, 70.0, p.current);
            }
            let g = &self.g;
            g.poly(&zone, if d || cur { p.bg2 } else { alpha(p.bg2, 150) });
            g.poly_stroke(&zone, if cur { 4.0 } else { 2.0 }, if cur { p.current } else { p.ink });
            let f = head(34.0);
            let num = (i + 1).to_string();
            g.text(c.x - (g.width(&num, &f) / 2.0).floor(), c.y + 11.0, &num, &f, if d || cur { p.ink } else { p.locked });
            if cur {
                for k in -1..=1 {
                    let kx = k as f32 * 8.0;
                    g.line(c.x + 20.0 + kx, c.y - 34.0, c.x + 32.0 + kx, c.y - 14.0, 4.0, p.current);
                }
            }
            if d {
                // a red "CLEARED" stamp, a little tilted
                let sc = pos2(c.x, c.y + 22.0);
                let col = alpha(p.done, 210);
                let rot = |x: f32, y: f32| {
                    let (s, co) = (-0.25f32).sin_cos();
                    let (dx, dy) = (x - sc.x, y - sc.y);
                    (sc.x + dx * co - dy * s, sc.y + dx * s + dy * co)
                };
                let corners = [
                    rot(sc.x - 40.0, sc.y - 12.0),
                    rot(sc.x + 40.0, sc.y - 12.0),
                    rot(sc.x + 40.0, sc.y + 12.0),
                    rot(sc.x - 40.0, sc.y + 12.0),
                ];
                g.poly_stroke(&corners, 3.0, col);
                let f = head(20.0);
                let galley = g.p.layout_no_wrap("CLEARED".into(), f, col);
                let gw = galley.size().x;
                let gh = galley.size().y;
                let (tx, ty) = rot(sc.x - gw / 2.0, sc.y - gh / 2.0);
                let mut ts = egui::epaint::TextShape::new(g.at(tx, ty), galley, col);
                ts.angle = -0.25;
                g.p.add(ts);
            }
            self.label(c.x, c.y + 44.0, &self.m.dungeons[i], p.text, p.bg2, p.ink);
            self.selection(Canvas::rect_at(c.x - 42.0, c.y - 42.0, 84.0, 84.0), i);
        }
        let ap = pts[n];
        let won = self.cleared == n;
        let g = &self.g;
        let horn = if won { p.done } else { p.locked };
        g.poly(&[(ap.x - 22.0, ap.y - 10.0), (ap.x - 34.0, ap.y - 40.0), (ap.x - 12.0, ap.y - 18.0)], horn);
        g.poly(&[(ap.x + 22.0, ap.y - 10.0), (ap.x + 34.0, ap.y - 40.0), (ap.x + 12.0, ap.y - 18.0)], horn);
        g.oval(ap.x - 24.0, ap.y - 24.0, 48.0, 44.0, p.bg2);
        g.oval_stroke(ap.x - 24.0, ap.y - 24.0, 48.0, 44.0, 3.0, p.ink);
        g.oval(ap.x - 14.0, ap.y - 8.0, 10.0, 10.0, p.ink);
        g.oval(ap.x + 4.0, ap.y - 8.0, 10.0, 10.0, p.ink);
        g.text(ap.x - 4.0, ap.y + 16.0, "?", &head(18.0), p.ink);
        self.label(ap.x, ap.y + 26.0, "Apex · medal soon", p.text, p.bg2, p.ink);
    }

    // -- hellforge: rooms of a burning facility joined by corridors, with status lights and hazard doors

    fn hazard(&self, x: f32, y: f32, w: f32, h: f32) {
        let r = self.g.r(x, y, w, h);
        let clip = G { p: &self.g.p.with_clip_rect(r.intersect(self.g.p.clip_rect())), o: self.g.o };
        clip.fill(x, y, w, h, rgb(0x1A1A1A));
        let mut k = x - h;
        while k < x + w + h {
            clip.poly(&[(k, y + h), (k + 8.0, y + h), (k + 8.0 + h, y), (k + h, y)], self.p.locked);
            k += 16.0;
        }
    }

    fn hellforge(&mut self, w: f32, h: f32) {
        let (n, p) = (self.n, self.p);
        let g = &self.g;
        g.fill(0.0, 0.0, w, h, p.bg);
        let mut x = 0.0;
        while x < w {
            g.line(x, 0.0, x, h, 1.0, p.deco1);
            x += 64.0;
        }
        let mut y = 0.0;
        while y < h {
            g.line(0.0, y, w, y, 1.0, p.deco1);
            y += 48.0;
        }
        let mut x = 6.0;
        while x < w {
            let mut y = 6.0;
            while y < h {
                g.oval(x, y, 4.0, 4.0, p.deco1);
                y += 48.0;
            }
            x += 64.0;
        }
        for k in 0..4 {
            g.fill(0.0, h - 70.0 + k as f32 * 16.0, w, 70.0 - k as f32 * 16.0, alpha(p.deco2, (22 + k * 8) as u8));
        }
        self.hazard(0.0, 0.0, w, 12.0);
        self.hazard(0.0, h - 12.0, w, 12.0);
        let pts = self.points(w, h - 40.0, false);
        let g = &self.g;
        for i in 0..n {
            let (a, b) = (pts[i], pts[i + 1]);
            let mx = ((a.x + b.x) / 2.0).floor();
            let line = [(a.x, a.y), (mx, a.y), (mx, b.y), (b.x, b.y)];
            g.polyline(&line, 16.0, p.road);
            if i < self.cleared {
                g.polyline(&line, 3.0, p.walked);
            }
        }
        for i in 0..n {
            let c = pts[i];
            let (d, cur) = (self.done(i), self.current(i));
            let (rw, rh) = (92.0, 62.0);
            let (x, y) = (c.x - rw / 2.0, c.y - rh / 2.0);
            let g = &self.g;
            g.fill(x, y, rw, rh, p.bg2);
            g.rect(x, y, rw, rh, 5.0, p.frame);
            g.text(x + 8.0, y + 18.0, &format!("SECTOR {}", i + 1), &body(14.0), p.text);
            g.text(x + 8.0, y + rh - 10.0, &format!("{:02}", i + 1), &head(28.0), p.text);
            let blink = !cur || (self.phase / 4) % 2 == 0;
            g.oval(
                x + rw - 22.0,
                y + 8.0,
                14.0,
                14.0,
                if d {
                    p.done
                } else if cur {
                    if blink { p.current } else { p.bg2 }
                } else {
                    p.locked
                },
            );
            if cur && blink {
                self.glow(pos2(x + rw - 15.0, y + 15.0), 14.0, p.current);
            }
            if !d && !cur {
                self.hazard(x + rw - 36.0, y + rh - 18.0, 30.0, 12.0);
            }
            self.label(
                c.x,
                y + rh + 8.0,
                &self.m.dungeons[i],
                p.text,
                p.bg,
                if d {
                    p.done
                } else if cur {
                    p.current
                } else {
                    p.frame
                },
            );
            self.selection(Canvas::rect_at(x - 4.0, y - 4.0, rw + 8.0, rh + 8.0), i);
        }
        let ex = pts[n];
        let won = self.cleared == n;
        let g = &self.g;
        g.fill(ex.x - 40.0, ex.y - 36.0, 80.0, 72.0, p.bg2);
        g.rect(ex.x - 40.0, ex.y - 36.0, 80.0, 72.0, 5.0, if won { p.done } else { p.deco3 });
        g.text(ex.x - 8.0, ex.y + 12.0, "?", &head(36.0), if won { p.done } else { p.deco3 });
        self.label(ex.x, ex.y + 44.0, "EXIT · medal soon", p.text, p.bg, if won { p.done } else { p.deco3 });
        self.g.text(22.0, h - 22.0, &format!("SECTORS {}/{}", self.cleared, n), &head(26.0), p.text);
    }

    fn paint(&mut self, w: f32, h: f32) {
        match self.m.style.as_str() {
            "hex" => self.hex(w, h),
            "overworld" => self.overworld(w, h),
            "oryx" => self.oryx(w, h),
            "constellation" => self.constellation(w, h),
            "neon" => self.neon(w, h),
            "adventure" => self.adventure(w, h),
            "hunt" => self.hunt(w, h),
            "hellforge" => self.hellforge(w, h),
            _ => self.parchment(w, h),
        }
        self.g.rect(2.0, 2.0, w - 4.0, h - 4.0, 4.0, self.p.frame);
    }
}

/// "d MMM" and "d MMM, HH:mm" in local time.
fn day(zone: &lg_app::localtime::Zone, ms: i64) -> String {
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let (_, m, d, _, _, _) = zone.fields(ms);
    format!("{d} {}", MONTHS[(m - 1) as usize])
}

fn day_time(zone: &lg_app::localtime::Zone, ms: i64) -> String {
    let (_, _, _, h, mi, _) = zone.fields(ms);
    format!("{}, {h:02}:{mi:02}", day(zone, ms))
}

fn left(ms: i64) -> String {
    let h = ms.max(0) / 3_600_000;
    if h >= 48 { format!("{} days", h / 24) } else { format!("{h} hours") }
}

/// Java `stageText`: what a stage is, for the line under the map and the tooltips.
fn stage_text(zone: &lg_app::localtime::Zone, m: &QuestMap, i: usize, now: i64) -> String {
    let (n, cleared) = (m.dungeons.len(), m.clears.len());
    if i >= n {
        return format!("The medal: earned by clearing all {n} stages (coming soon).");
    }
    let head = format!("Stage {}  ·  {}  -  ", i + 1, m.dungeons[i]);
    let active = m.completed_at.is_none() && now < m.ends_at;
    if i < cleared {
        let c = &m.clears[i];
        return format!("{head}cleared by {}, {}", c.ign, day_time(zone, c.at));
    }
    if i == cleared && active {
        return format!("{head}in progress: the first guildie to clear it moves everyone on");
    }
    if !active {
        return format!("{head}not reached");
    }
    format!("{head}locked until stage {i} is cleared")
}

impl QuestMapView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let guild = &app.server.guild;
        let online = app.server.user.is_some() && app.server.state == lg_app::server::State::Online;
        // the minute's check while on screen, and a reload once your run reached the server
        if online && (c.now - self.asked_at >= POLL_MS || guild.maps_stale) {
            if self.asked_at != 0 || guild.maps_stale {
                c.asks.push(Ask::Server(ServerAsk::MapsLoad));
            }
            self.asked_at = c.now;
        }
        let now = c.now + guild.skew;
        let (maps, message) = match &guild.quest_maps {
            _ if !online => (None, "Log in to a server in the Server tab to see your guild's quest map.".to_string()),
            None => (None, "Open this tab while logged in to a server to see your guild's quest map.".to_string()),
            Some(Err(e)) => (None, crate::guild::load_error_text(&app.profile.guild, e)),
            Some(Ok(m)) => (Some(m), String::new()),
        };
        let title = maps.map_or("Quest Map".to_string(), |m| format!("Quest Map - {}", m.guild));
        let refresh = std::cell::Cell::new(false);
        let selected = &mut self.selected;
        let zone = &app.zone;
        widgets::section(
            ui,
            &p,
            body,
            &title,
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter();
                let w = jtext::width(painter, "Refresh", &widgets::small()) + 40.0;
                let r = Rect::from_min_size(pos2(head.right() - 5.0 - w, head.top()), vec2(w, head.height()));
                painter.rect(r, 0.0, p.header, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
                jtext::draw(painter, r.center(), Align2::CENTER_CENTER, "Refresh", &widgets::small(), p.ink);
                if ui.interact(r, ui.scope_id().with("maps-refresh"), Sense::click()).clicked() {
                    refresh.set(true);
                }
            },
            |ui, r| {
                let f = text(18.0);
                let mut outer = ui.new_child(egui::UiBuilder::new().max_rect(r));
                egui::ScrollArea::vertical().id_salt("quest-maps").show(&mut outer, |ui| {
                    let top = ui.cursor().min;
                    let painter = ui.painter().clone();
                    let fh = jtext::height(&painter, &f);
                    let x = top.x + 12.0;
                    let w = r.width() - 24.0 - 8.0;
                    let mut y = top.y + 12.0;
                    let Some(maps) = maps else {
                        jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, &message, &f, fixed::MUTED);
                        ui.allocate_exact_size(vec2(w, fh + 24.0), Sense::hover());
                        return;
                    };
                    let mut card = |ui: &mut Ui, m: &QuestMap, current: bool, y: f32| -> f32 {
                        let n = m.dungeons.len();
                        let cleared = m.clears.len().min(n);
                        let complete = m.completed_at.is_some() || cleared == n;
                        let ended = now >= m.ends_at;
                        let active = !complete && !ended;
                        let canvas_h = if m.style == "oryx" { 150.0 + n as f32 * 58.0 + 40.0 } else { H };
                        let name_f = head(if current { 40.0 } else { 30.0 });
                        let log_lines = cleared as f32;
                        let h = 14.0
                            + jtext::height(&painter, &name_f)
                            + fh
                            + 8.0
                            + canvas_h
                            + 8.0
                            + fh
                            + if cleared > 0 { 4.0 + log_lines * fh } else { 0.0 }
                            + 14.0;
                        let cr = Rect::from_min_size(pos2(x, y), vec2(w, h));
                        painter.rect_filled(cr, 0.0, p.paper);
                        painter.rect_stroke(cr, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                        painter.rect_stroke(cr.shrink(1.0), 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                        let (ix, mut iy) = (cr.left() + 16.0, cr.top() + 14.0);
                        let iw = w - 32.0;
                        jtext::draw(&painter, pos2(ix, iy), Align2::LEFT_TOP, &m.name, &name_f, p.gold);
                        iy += jtext::height(&painter, &name_f);
                        let status = if complete {
                            let at = m.completed_at.unwrap_or_else(|| m.clears.get(n.saturating_sub(1)).map_or(0, |c| c.at));
                            format!("Completed on {} - the guild earns the medal (coming soon)", day_time(zone, at))
                        } else if ended {
                            format!("Ended {} with {cleared} of {n} stages cleared", day(zone, m.ends_at))
                        } else {
                            format!("Stage {} of {n}  ·  ends in {}", cleared + 1, left(m.ends_at - now))
                        };
                        let sub = format!("{} - {}  ·  {status}", day(zone, m.starts_at), day(zone, m.ends_at));
                        jtext::draw(&painter, pos2(ix, iy), Align2::LEFT_TOP, &sub, &f, if complete { fixed::GREEN } else { fixed::MUTED });
                        iy += fh + 8.0;
                        // the map
                        let sel = *selected.entry(m.id).or_insert(cleared.min(n));
                        let area = Rect::from_min_size(pos2(ix, iy), vec2(iw, canvas_h));
                        let clip = painter.with_clip_rect(area.intersect(painter.clip_rect()));
                        let mut cv = Canvas {
                            g: G { p: &clip, o: area.min },
                            m,
                            p: palette(&m.style, m.variant),
                            n,
                            cleared,
                            active,
                            selected: sel,
                            phase: if active { c_now_phase(now) } else { 0 },
                            w: iw,
                            hits: vec![None; n + 1],
                        };
                        cv.paint(iw, canvas_h);
                        let resp = ui.interact(area, ui.scope_id().with(("quest-map", m.id)), Sense::click());
                        if let Some(pos) = resp.hover_pos() {
                            let local = pos2(pos.x - area.left(), pos.y - area.top());
                            if let Some(i) = cv.hits.iter().position(|h| h.is_some_and(|h| h.contains(local))) {
                                ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                                if resp.on_hover_text(stage_text(zone, m, i, now)).clicked() {
                                    selected.insert(m.id, i);
                                }
                            }
                        }
                        iy += canvas_h + 8.0;
                        jtext::draw(&painter, pos2(ix, iy), Align2::LEFT_TOP, &stage_text(zone, m, sel, now), &f, p.ink);
                        iy += fh;
                        if cleared > 0 {
                            iy += 4.0;
                            for (i, cl) in m.clears.iter().take(cleared).enumerate() {
                                let l = format!("{}.  {}  -  {}, {}", i + 1, m.dungeons[i], cl.ign, day_time(zone, cl.at));
                                jtext::draw(&painter, pos2(ix, iy), Align2::LEFT_TOP, &l, &f, fixed::MUTED);
                                iy += fh;
                            }
                        }
                        h
                    };
                    if let Some(m) = &maps.current {
                        y += card(ui, m, true, y);
                    }
                    y += 22.0;
                    let h30 = head(30.0);
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, "Past quest maps", &h30, p.gold);
                    y += jtext::height(&painter, &h30) + 8.0;
                    if maps.past.is_empty() {
                        jtext::draw(
                            &painter,
                            pos2(x, y),
                            Align2::LEFT_TOP,
                            "None yet: this is your guild's first quest map.",
                            &f,
                            fixed::MUTED,
                        );
                        y += fh;
                    }
                    for m in &maps.past {
                        y += card(ui, m, false, y) + 16.0;
                    }
                    ui.allocate_exact_size(vec2(w, y + 12.0 - top.y), Sense::hover());
                });
            },
        );
        if refresh.get() {
            c.asks.push(Ask::Server(ServerAsk::MapsLoad));
        }
    }
}

/// The animation's step (Java's 80 ms timer).
fn c_now_phase(now: i64) -> i64 {
    now / 80
}
