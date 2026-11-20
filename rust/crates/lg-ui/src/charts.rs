//! Fame charts (Java: `CharactersView.FameChart`, `IdleTimeline`): fame as steps over a time range, stretches of an
//! hour or more without a change cut out to narrow marked breaks, a time axis, the dungeons gained fame in above.

use crate::jtext;
use crate::theme::{Palette, fixed};
use egui::{Align2, Color32, Painter, Rect, Shape, Stroke, pos2, vec2};

const HOUR: i64 = 3_600_000;
const IDLE: i64 = HOUR;
const PAD: i64 = 10 * 60_000;

/// The time axis without the idle stretches (Java `IdleTimeline`).
pub struct Timeline {
    pub from: i64,
    pub to: i64,
    pub segments: Vec<(i64, i64)>,
    break_w: f32,
    leading: bool,
    trailing: bool,
}

/// The times where the value changed (and the first).
pub fn changes(points: &[(i64, i64)]) -> Vec<i64> {
    points.iter().enumerate().filter(|(i, p)| *i == 0 || p.1 != points[i - 1].1).map(|(_, p)| p.0).collect()
}

impl Timeline {
    pub fn new(changes: &[i64], from: i64, to: i64, break_w: f32) -> Timeline {
        let to = to.max(from + 1);
        let mut t = Timeline { from, to, segments: Vec::new(), break_w, leading: false, trailing: false };
        let Some(&first) = changes.first() else {
            t.segments.push((from, to));
            return t;
        };
        let mut start = if first - from <= IDLE { from } else { from.max(first - PAD) };
        let mut last = first;
        for &c in &changes[1..] {
            if c - last > IDLE {
                t.segments.push((start, to.min(last + PAD)));
                start = from.max(c - PAD);
            }
            last = c;
        }
        t.segments.push((start, if to - last <= IDLE { to } else { to.min(last + PAD) }));
        t.leading = t.segments[0].0 > from;
        t.trailing = t.segments.last().expect("one").1 < to;
        t
    }

    fn breaks_n(&self) -> usize {
        self.segments.len() - 1 + usize::from(self.leading) + usize::from(self.trailing)
    }

    pub fn x(&self, t: i64, left: f32, right: f32) -> f32 {
        let shown: i64 = self.segments.iter().map(|s| (s.1 - s.0).max(1)).sum();
        let per_ms = f64::from((right - left - self.breaks_n() as f32 * self.break_w).max(1.0)) / shown as f64;
        let mut x = f64::from(left + if self.leading { self.break_w } else { 0.0 });
        if t < self.segments[0].0 {
            return left + if self.leading { self.break_w / 2.0 } else { 0.0 };
        }
        for s in &self.segments {
            if t <= s.1 {
                return if t >= s.0 { (x + (t - s.0) as f64 * per_ms) as f32 } else { x as f32 - self.break_w / 2.0 };
            }
            x += (s.1 - s.0).max(1) as f64 * per_ms + f64::from(self.break_w);
        }
        right - if self.trailing { self.break_w / 2.0 } else { 0.0 }
    }

    /// The cut-out stretches: from, to, the x of their middle.
    pub fn breaks(&self, left: f32, right: f32) -> Vec<(i64, i64, f32)> {
        let mut out = Vec::new();
        if self.leading {
            out.push((self.from, self.segments[0].0, left + self.break_w / 2.0));
        }
        for w in self.segments.windows(2) {
            out.push((w[0].1, w[1].0, self.x(w[0].1, left, right) + self.break_w / 2.0));
        }
        if self.trailing {
            out.push((self.segments.last().expect("one").1, self.to, right - self.break_w / 2.0));
        }
        out
    }
}

/// Java `IdleTimeline.gap`.
pub fn gap(ms: i64) -> String {
    let m = ms / 60_000;
    let h = m / 60;
    if h == 0 {
        format!("{m}m")
    } else if h >= 10 || m % 60 == 0 {
        format!("{h}h")
    } else {
        format!("{h}h {}m", m % 60)
    }
}

const STEPS: [i64; 15] = [
    5 * 60_000,
    10 * 60_000,
    15 * 60_000,
    30 * 60_000,
    HOUR,
    2 * HOUR,
    3 * HOUR,
    6 * HOUR,
    12 * HOUR,
    24 * HOUR,
    48 * HOUR,
    7 * 24 * HOUR,
    14 * 24 * HOUR,
    30 * 24 * HOUR,
    90 * 24 * HOUR,
];
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/// A dungeon to mark on the chart: its portal's texture, where it was and the fame gained in it.
pub struct Mark {
    pub icon: Option<egui::TextureHandle>,
    pub start: i64,
    pub end: i64,
}

/// Java `FameChart.paintComponent`, in `r` (200 px high in Java).
pub fn fame_chart(
    painter: &Painter,
    p: &Palette,
    r: Rect,
    points: &[(i64, i64)],
    since: i64,
    now: i64,
    zone: &lg_app::localtime::Zone,
    marks: &[Mark],
) {
    const BREAK: f32 = 34.0;
    const ICON: f32 = 18.0;
    let first = points.iter().filter(|q| q.0 >= since).map(|q| q.0).min();
    let pts: Vec<(i64, i64)> = points.iter().copied().filter(|q| q.0 >= since).collect();
    let from = if since > 0 { since } else { first.unwrap_or(now - HOUR) };
    let tl = Timeline::new(&changes(&pts), from, now, BREAK);
    let (w, h) = (r.width(), r.height());
    let (top, bottom) = (r.top() + 18.0 + ICON, r.top() + h - 24.0);
    let (left, right) = (r.left() + 8.0, r.left() + w - 8.0);
    painter.rect_filled(r, 0.0, p.sand);
    painter.rect_stroke(r, 0.0, Stroke::new(1.0, p.line), egui::StrokeKind::Inside);
    let font = crate::widgets::text(13.0);
    // the time axis
    let now_w = jtext::width(painter, "now", &font);
    let now_left = right - now_w;
    painter.line_segment([pos2(left, bottom), pos2(right, bottom)], Stroke::new(1.0, fixed::MUTED));
    let mut last_end = f32::NEG_INFINITY;
    for &(s0, s1) in &tl.segments {
        let (x0, x1) = (tl.x(s0, left, right), tl.x(s1, left, right));
        let span = (s1 - s0).max(1) as f64;
        let max_labels = (((x1 - x0) / 80.0) as i64).max(1) as f64;
        let step = STEPS.iter().copied().find(|s| span / *s as f64 <= max_labels).unwrap_or(STEPS[14]);
        let days = step >= 24 * HOUR;
        let offset = i64::from(zone.offset(s0.div_euclid(1000))) * 1000;
        let mut tick = (s0 + offset + step - 1).div_euclid(step) * step - offset;
        while tick < s1 {
            let x = tl.x(tick, left, right).round();
            let g = p.line;
            painter
                .line_segment([pos2(x, top), pos2(x, bottom)], Stroke::new(1.0, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 70)));
            painter.line_segment([pos2(x, bottom), pos2(x, bottom + 3.0)], Stroke::new(1.0, fixed::MUTED));
            let local = zone.local_date_time(tick);
            let label = if days {
                let (m, d) = (&local[5..7], &local[8..10]);
                format!("{} {}", d.trim_start_matches('0'), MONTHS[m.parse::<usize>().unwrap_or(1) - 1])
            } else {
                local.get(11..16).unwrap_or("00:00").to_string()
            };
            let lw = jtext::width(painter, &label, &font);
            let lx = x - (lw / 2.0).floor();
            if lx >= r.left() + 2.0 && lx > last_end + 6.0 && lx + lw < now_left - 6.0 {
                jtext::draw(painter, pos2(lx, r.top() + h - 5.0), Align2::LEFT_BOTTOM, &label, &font, fixed::MUTED);
                last_end = lx + lw;
            }
            tick += step;
        }
    }
    jtext::draw(painter, pos2(now_left, r.top() + h - 5.0), Align2::LEFT_BOTTOM, "now", &font, fixed::MUTED);
    if pts.is_empty() {
        jtext::draw(
            painter,
            pos2(left + 6.0, r.top() + h / 2.0),
            Align2::LEFT_BOTTOM,
            "No fame recorded in this range yet.",
            &font,
            fixed::MUTED,
        );
        return;
    }
    let min = pts.iter().map(|q| q.1).min().unwrap_or(0);
    let max = pts.iter().map(|q| q.1).max().unwrap_or(1);
    let span = (max - min).max(1) as f32;
    let y_of = |v: i64| bottom - (v - min) as f32 / span * (bottom - top);
    let mut line = Vec::new();
    let mut area = Vec::new();
    let mut last_y = 0.0;
    for (i, &(t, v)) in pts.iter().enumerate() {
        let x = tl.x(t, left, right);
        let y = y_of(v);
        if i == 0 {
            line.push(pos2(x, y));
            area.push(pos2(x, bottom));
        } else {
            line.push(pos2(x, last_y));
            area.push(pos2(x, last_y));
        }
        line.push(pos2(x, y));
        area.push(pos2(x, y));
        last_y = y;
    }
    line.push(pos2(right, last_y));
    area.push(pos2(right, last_y));
    area.push(pos2(right, bottom));
    let g = p.gold;
    // the area as columns between the steps (it is not convex)
    for wv in area.windows(2) {
        if wv[1].x > wv[0].x {
            let y = wv[0].y.min(wv[1].y).max(wv[0].y);
            painter.rect_filled(
                Rect::from_min_max(pos2(wv[0].x, y), pos2(wv[1].x, bottom)),
                0.0,
                Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 50),
            );
        }
    }
    painter.add(Shape::line(line, Stroke::new(2.0, g)));
    for (a, b, cx) in tl.breaks(left, right) {
        let cx = cx.round();
        painter.rect_filled(
            Rect::from_min_max(pos2(cx - BREAK / 2.0 + 4.0, r.top() + 1.0), pos2(cx + BREAK / 2.0 - 4.0, bottom)),
            0.0,
            p.sand,
        );
        for dx in [-4.0, 3.0] {
            painter.line_segment([pos2(cx + dx - 3.0, bottom + 4.0), pos2(cx + dx + 3.0, bottom - 6.0)], Stroke::new(1.5, fixed::MUTED));
        }
        let t = gap(b - a);
        let tw = jtext::width(painter, &t, &font);
        jtext::draw(painter, pos2(cx - (tw / 2.0).floor(), (top + bottom) / 2.0), Align2::LEFT_CENTER, &t, &font, fixed::MUTED);
    }
    // the dungeons gained fame in, their portals above the line
    let mut last_icon = f32::NEG_INFINITY;
    for m in marks {
        if m.end < from || m.start > now {
            continue;
        }
        let (Some(gained), Some(at)) = (fame_in(&pts, m.start, m.end), fame_at(&pts, m.end)) else { continue };
        if gained <= 0 {
            continue;
        }
        let x = tl.x((m.start + m.end) / 2, left, right).round();
        if x - last_icon < ICON {
            continue;
        }
        let Some(icon) = &m.icon else { continue };
        let y = (y_of(at) - ICON - 4.0).round().max(r.top() + 2.0);
        painter.image(
            icon.id(),
            Rect::from_min_size(pos2(x - ICON / 2.0, y), vec2(ICON, ICON)),
            Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
            Color32::WHITE,
        );
        last_icon = x;
    }
    let num = crate::profile::num;
    jtext::draw(painter, pos2(left + 4.0, r.top() + 14.0), Align2::LEFT_BOTTOM, &num(max), &font, fixed::MUTED);
    jtext::draw(painter, pos2(left + 4.0, bottom - 4.0), Align2::LEFT_BOTTOM, &num(min), &font, fixed::MUTED);
}

/// Java `DungeonVisits.fameAt`.
pub fn fame_at(points: &[(i64, i64)], t: i64) -> Option<i64> {
    let mut at = None;
    for p in points {
        if p.0 <= t {
            at = Some(p.1);
        } else {
            return Some(at.unwrap_or(p.1));
        }
    }
    at
}

/// Java `DungeonVisits.fameIn`.
pub fn fame_in(points: &[(i64, i64)], start: i64, end: i64) -> Option<i64> {
    let (mut a, mut b) = (None, None);
    for p in points {
        if p.0 <= start {
            a = if start - p.0 <= 15_000 { Some(p.1) } else { None };
        } else if p.0 <= end {
            if a.is_none() {
                a = Some(p.1);
            }
            b = Some(p.1);
        } else {
            break;
        }
    }
    let a = a?;
    Some(b.map_or(0, |b| (b - a).max(0)))
}
