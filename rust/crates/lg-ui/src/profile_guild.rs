//! The Profile's guild card (Java `ProfileView.guildCard`): who of the guild stood out over the last 3 days (five of
//! twelve facts, picked anew each day and start), then your wagon of the loot caravan and the quest map's next
//! dungeon, each opening its page in the Guild tab.

use crate::icons::Icons;
use crate::jtext;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, text};
use egui::{Align2, Color32, Pos2, Rect, Sense, Stroke, Ui, pos2, vec2};
use lg_app::App;
use lg_app::guild::MemberFacts;
use std::sync::{Mutex, OnceLock};

const FACT_DAYS: i64 = 3;
const FACT_ICON: f32 = 72.0;
const LOVER_PORTAL: f32 = 80.0;
const LOVER_CHAR: f32 = 52.0;
const FACT_WIDTH: f32 = 130.0;
const FACTS_SHOWN: usize = 5;
const FACTS_SHOWN_KEY: &str = "guildFactsShown";
const DODGE_MIN_MS: i64 = 10 * 60_000;
const GRAVESTONE: i32 = 1282;
const FAME: i32 = 24102;
const SWORD: i32 = 2827;
const HEAVY_ARMOR: i32 = 2812;
const SHINY_SWORD: i32 = 1230;
const BULLET: i32 = 15;
/// Potion of Life (Java `EXALT_POTION[ExaltStore.LIFE]`).
const LIFE_POTION: i32 = 0xae9;
const FACT_NAMES: [&str; 12] = [
    "DUNGEON LOVER",
    "DEATH LOVER",
    "FAME PRODUCER",
    "PUNISHER",
    "PANZER",
    "EXALTER!",
    "JUST DODGE",
    "LOOTER",
    "DIVERSIFIER",
    "ST COLLECTOR",
    "BEST FRIENDS",
    "DUNGEON GAIN",
];
const GUILD_PREFIXES: [&str; 15] = [
    "Your guild",
    "The insane players of",
    "The place of devastation:",
    "The worst guild:",
    "Proud member of",
    "Home sweet",
    "The legends of",
    "Behold,",
    "The mighty",
    "The loot goblins of",
    "Somehow still in",
    "The finest of",
    "Sworn to",
    "Carried by",
    "The unstoppable",
];

/// The words picked once each start (Java's static `pick`s): the guild prefix, the caption above the facts, how
/// the dungeon lover and the panzer are told.
struct Words {
    prefix: &'static str,
    stats: &'static str,
    lover: &'static str,
    tank: &'static str,
}

fn words() -> &'static Words {
    static W: OnceLock<Words> = OnceLock::new();
    W.get_or_init(|| {
        let seed = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(1, |d| d.as_nanos() as i64);
        let mut r = lg_app::java::JavaRandom::new(seed);
        let mut pick = |w: &[&'static str]| w[r.next_int(w.len() as i32) as usize];
        Words {
            prefix: pick(&GUILD_PREFIXES),
            stats: pick(&["GUILD STATS OF THE PAST 3 DAYS", "THE GUILD'S LAST 3 DAYS", "LAST 3 DAYS IN THE GUILD"]),
            lover: pick(&["completed", "done", "cleared", "conquered", "beaten", "run", "gobbled through", "survived"]),
            tank: pick(&["ate", "tanked", "took", "absorbed", "soaked up", "shrugged off"]),
        }
    })
}

/// Java `factOrder`: shuffled once a start and a day, those shown the last time at the end.
fn fact_order(props: &lg_app::properties::Properties, today: i64) -> Vec<&'static str> {
    static ORDER: Mutex<Option<(i64, Vec<&'static str>)>> = Mutex::new(None);
    let mut o = ORDER.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((day, order)) = &*o
        && *day == today
    {
        return order.clone();
    }
    let last: Vec<String> = props.get(FACTS_SHOWN_KEY).map(|s| s.split('|').map(str::to_string).collect()).unwrap_or_default();
    let mut order: Vec<&'static str> = FACT_NAMES.to_vec();
    let seed = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(1, |d| d.as_nanos() as i64);
    lg_app::java::shuffle(&mut order, &mut lg_app::java::JavaRandom::new(seed));
    order.sort_by_key(|n| last.iter().any(|l| l == n)); // stable: the new ones first, still shuffled
    *o = Some((today, order.clone()));
    order
}

/// Java `plural`: "The Nest" -> "Nests", "Tomb of the Ancients" -> "Tombs of the Ancients".
pub fn plural(dungeon: &str) -> String {
    let name = dungeon.strip_prefix("The ").unwrap_or(dungeon);
    let roman = name.rsplit_once(' ').is_some_and(|(_, l)| !l.is_empty() && l.len() <= 3 && l.chars().all(|c| c == 'I'));
    if roman {
        return name.to_string();
    }
    let of = name.find(" of ").filter(|i| *i > 0).or_else(|| name.find(" for ").filter(|i| *i > 0));
    let (head, tail) = match of {
        Some(i) => (&name[..i], &name[i..]),
        None => (name, ""),
    };
    let lower = head.to_lowercase();
    if lower.ends_with('s') && !lower.ends_with("ss") {
        return name.to_string();
    }
    let vowel_y = lower.len() >= 2 && lower.ends_with('y') && "aeiou".contains(&lower[lower.len() - 2..lower.len() - 1]);
    let head = if lower.ends_with('y') && !vowel_y {
        format!("{}ies", &head[..head.len() - 1])
    } else if ["s", "x", "z", "ch", "sh"].iter().any(|e| lower.ends_with(e)) {
        format!("{head}es")
    } else {
        format!("{head}s")
    };
    format!("{head}{tail}")
}

fn num(n: i64) -> String {
    crate::profile::num(n)
}

fn duration(ms: i64) -> String {
    let s = ms / 1000;
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

/// A piece of a fact's line: its text, bold or not, in gold (a name) or not.
type Run = (String, bool, bool);

fn name(f: &MemberFacts) -> Run {
    (f.ign.clone(), false, true)
}

fn plain(t: &str) -> Run {
    (t.to_string(), false, false)
}

fn bold(t: &str) -> Run {
    (t.to_string(), true, false)
}

/// What a fact's picture is.
enum Pic {
    Sprite(i32),
    /// A dungeon's portal with a character in front (hearts for eyes when it is the lover).
    Portal {
        dungeon: String,
        front: Front,
        hearts: bool,
    },
    Exalt,
    Dodge(Option<(i32, i32, i32, i32)>),
    DivineShiny,
    Look(Option<(i32, i32, i32, i32)>),
    StSet([i32; 4]),
    Facing(Option<(i32, i32, i32, i32)>, Option<(i32, i32, i32, i32)>),
}

enum Front {
    Look(Option<(i32, i32, i32, i32)>),
    Sprite(i32),
}

struct Fact {
    caption: &'static str,
    pic: Pic,
    line: Vec<Run>,
    tip: String,
}

impl Pic {
    fn size(&self) -> (f32, f32) {
        match self {
            Pic::Portal { .. } => (LOVER_PORTAL + LOVER_CHAR / 2.0, LOVER_PORTAL + (LOVER_CHAR / 6.0).floor()),
            Pic::Dodge(_) => (FACT_ICON * 2.0, FACT_ICON),
            Pic::Facing(..) => (FACT_ICON * 3.0 / 4.0 * 2.0 - FACT_ICON / 8.0, FACT_ICON * 3.0 / 4.0),
            _ => (FACT_ICON, FACT_ICON),
        }
    }
}

fn look_of(f: &MemberFacts) -> Option<(i32, i32, i32, i32)> {
    f.look.as_ref().filter(|l| l.class_type > 0).map(|l| (l.class_type, l.skin, l.tex1, l.tex2))
}

/// The guildmate with the most of something (ties: the first); None if none has any.
fn most(all: &[MemberFacts], value: impl Fn(&MemberFacts) -> i64) -> Option<&MemberFacts> {
    let mut best: Option<&MemberFacts> = None;
    for f in all {
        if value(f) > 0 && best.is_none_or(|b| value(f) > value(b)) {
            best = Some(f);
        }
    }
    best
}

/// Java `guildFacts`: the twelve facts (only those someone has).
fn facts(app: &App) -> Vec<Fact> {
    let g = &app.server.profile_guild;
    let all = &g.facts;
    let w = words();
    let mut out = Vec::new();
    if let Some(m) = most(all, |f| f.favorite.as_ref().map_or(0, |v| v.count)) {
        let fav = m.favorite.as_ref().expect("has one");
        let look = if fav.class_type > 0 { Some((fav.class_type, fav.skin, fav.tex1, fav.tex2)) } else { look_of(m) };
        let dungeon =
            if fav.count == 1 { fav.dungeon.strip_prefix("The ").unwrap_or(&fav.dungeon).to_string() } else { plural(&fav.dungeon) };
        out.push(Fact {
            caption: "DUNGEON LOVER",
            pic: Pic::Portal { dungeon: fav.dungeon.clone(), front: Front::Look(look), hearts: true },
            line: vec![name(m), plain(&format!(" has {} ", w.lover)), bold(&num(fav.count)), plain(&format!(" {dungeon}"))],
            tip: format!("{}: {}x {} completed in the last {FACT_DAYS} days", m.ign, num(fav.count), fav.dungeon),
        });
    }
    if let Some(m) = most(all, |f| f.deaths) {
        out.push(Fact {
            caption: "DEATH LOVER",
            pic: Pic::Sprite(GRAVESTONE),
            line: vec![name(m), plain(" died most times: "), bold(&format!("{}x", num(m.deaths)))],
            tip: format!("{}: {} characters died in the last {FACT_DAYS} days", m.ign, m.deaths),
        });
    }
    if let Some(m) = most(all, |f| f.fame) {
        out.push(Fact {
            caption: "FAME PRODUCER",
            pic: Pic::Sprite(FAME),
            line: vec![bold(&format!("{} fame", num(m.fame))), plain(" gained by "), name(m)],
            tip: format!("Fame gained by all of {}'s characters in the last {FACT_DAYS} days", m.ign),
        });
    }
    if let Some(m) = most(all, |f| f.best_run.as_ref().map_or(0, |b| b.1)) {
        let (dungeon, fame) = m.best_run.clone().expect("has one");
        out.push(Fact {
            caption: "DUNGEON GAIN",
            pic: Pic::Portal { dungeon: dungeon.clone(), front: Front::Sprite(FAME), hearts: false },
            line: vec![name(m), plain(" gained "), bold(&format!("{} fame", num(fame))), plain(" in a single dungeon: "), bold(&dungeon)],
            tip: format!("{}: {} fame in one {dungeon} in the last {FACT_DAYS} days", m.ign, num(fame)),
        });
    }
    let mut dealt: Option<&MemberFacts> = None;
    for f in all {
        if f.runs > 0 && dealt.is_none_or(|d| f.peak_dps > d.peak_dps) {
            dealt = Some(f);
        }
    }
    if let Some(m) = dealt {
        out.push(Fact {
            caption: "PUNISHER",
            pic: Pic::Sprite(SWORD),
            line: vec![name(m), plain(" has an average of "), bold(&format!("{} DPS", num(m.peak_dps.round() as i64)))],
            tip: format!(
                "{}: their best boss DPS, on average over {} run{} in the last {FACT_DAYS} days",
                m.ign,
                m.runs,
                if m.runs == 1 { "" } else { "s" }
            ),
        });
    }
    let per_minute = |f: &MemberFacts| f.taken as f64 / (f.in_ms as f64 / 60_000.0);
    let mut taken: Option<&MemberFacts> = None;
    for f in all.iter().filter(|f| f.in_ms >= DODGE_MIN_MS) {
        if taken.is_none_or(|t| per_minute(f) > per_minute(t)) {
            taken = Some(f);
        }
    }
    if let Some(m) = taken.filter(|t| t.taken > 0) {
        out.push(Fact {
            caption: "PANZER",
            pic: Pic::Sprite(HEAVY_ARMOR),
            line: vec![
                name(m),
                plain(&format!(" {} ", w.tank)),
                bold(&format!("{} HP", num(per_minute(m).round() as i64))),
                plain(" a minute"),
            ],
            tip: format!("{}: {} HP taken in {} in dungeons in the last {FACT_DAYS} days", m.ign, num(m.taken), duration(m.in_ms)),
        });
    }
    if let Some(m) = most(all, |f| f.exalts) {
        out.push(Fact {
            caption: "EXALTER!",
            pic: Pic::Exalt,
            line: vec![bold(&num(m.exalts)), plain(&format!(" exalt{} done by ", if m.exalts == 1 { "" } else { "s" })), name(m)],
            tip: format!("Exaltations {} earned in the last {FACT_DAYS} days", m.ign),
        });
    }
    let baseline = app.server.guild_settings.baseline_hpm;
    let dodged = |f: &MemberFacts| {
        let hpm = f.hits as f64 / (f.in_ms as f64 / 60_000.0);
        100.0 * baseline / (hpm + baseline)
    };
    let mut dodger: Option<&MemberFacts> = None;
    for f in all.iter().filter(|f| f.in_ms >= DODGE_MIN_MS && baseline > 0.0) {
        if dodger.is_none_or(|d| dodged(f) > dodged(d)) {
            dodger = Some(f);
        }
    }
    if let Some(m) = dodger {
        out.push(Fact {
            caption: "JUST DODGE",
            pic: Pic::Dodge(look_of(m)),
            line: vec![name(m), plain(" just dodged "), bold(&format!("{:.0} %", dodged(m))), plain(" of bullets")],
            tip: format!(
                "{}: {} hits in {} in dungeons in the last {FACT_DAYS} days ({:.1} a minute; the guild counts {:.0} as average)",
                m.ign,
                num(m.hits),
                duration(m.in_ms),
                m.hits as f64 / (m.in_ms as f64 / 60_000.0),
                baseline
            ),
        });
    }
    if let Some(m) = most(all, |f| f.loot) {
        out.push(Fact {
            caption: "LOOTER",
            pic: Pic::DivineShiny,
            line: vec![name(m), plain(" gathered the most items: "), bold(&num(m.loot))],
            tip: format!("Items {} found in the last {FACT_DAYS} days", m.ign),
        });
    }
    if let Some(m) = most(all, |f| f.played) {
        out.push(Fact {
            caption: "DIVERSIFIER",
            pic: Pic::Look(look_of(m)),
            line: vec![plain("Most different characters actively played by "), name(m), plain(": "), bold(&num(m.played))],
            tip: format!("Characters of {} that gained fame in the last {FACT_DAYS} days", m.ign),
        });
    }
    if let Some(m) = most(all, |f| f.st_sets) {
        let data = app.world.data();
        let cls = look_of(m).and_then(|l| data.classes.iter().find(|k| k.id == l.0).map(|k| k.name.clone()));
        let set = data.st_sets.iter().find(|s| Some(&s.class_name) == cls.as_ref()).or(data.st_sets.first());
        out.push(Fact {
            caption: "ST COLLECTOR",
            pic: Pic::StSet(set.map_or([0; 4], |s| s.pieces)),
            line: vec![
                name(m),
                plain(" has "),
                bold(&num(m.st_sets)),
                plain(&format!(" ST set{} ready", if m.st_sets == 1 { "" } else { "s" })),
            ],
            tip: format!("ST sets {} completed in the last {FACT_DAYS} days (the last piece found)", m.ign),
        });
    }
    if let Some((a, b, count)) = g.friends {
        let by = |id: i64| all.iter().find(|f| f.user_id == id && id != 0);
        if let (Some(fa), Some(fb)) = (by(a), by(b)) {
            out.push(Fact {
                caption: "BEST FRIENDS",
                pic: Pic::Facing(look_of(fa), look_of(fb)),
                line: vec![name(fa), plain(" and "), name(fb), plain(" played the most dungeons together: "), bold(&num(count))],
                tip: format!(
                    "{} and {} were in {count} dungeon{} together in the last {FACT_DAYS} days",
                    fa.ign,
                    fb.ign,
                    if count == 1 { "" } else { "s" }
                ),
            });
        }
    }
    out
}

fn uv() -> Rect {
    Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))
}

fn skin(ui: &Ui, icons: &mut Icons, app: &App, look: (i32, i32, i32, i32), size: f32) -> Option<egui::TextureHandle> {
    let (class, skin, t1, t2) = look;
    icons.skin(ui.ctx(), app.world.data(), if skin > 0 { skin } else { class }, t1, t2, size as u32)
}

/// Pixel art from rows of "X" (filled) and "." (empty), each pixel px square.
fn pixels(painter: &egui::Painter, rows: &[&str], x: f32, y: f32, px: f32, c: Color32) {
    for (r, row) in rows.iter().enumerate() {
        for (k, ch) in row.chars().enumerate() {
            if ch == 'X' {
                painter.rect_filled(Rect::from_min_size(pos2(x + k as f32 * px, y + r as f32 * px), vec2(px, px)), 0.0, c);
            }
        }
    }
}

/// Draws a fact's picture with its top left at `at`.
fn paint_pic(ui: &Ui, icons: &mut Icons, app: &App, p: &Palette, rarity: &crate::detail::Rarity, pic: &Pic, at: Pos2) {
    let painter = ui.painter();
    let data = app.world.data();
    let (w, h) = pic.size();
    let object = |icons: &mut Icons, id: i32, size: f32, r: Rect| {
        if let Some(t) = icons.object(ui.ctx(), data, id, size as u32) {
            painter.image(t.id(), r, uv(), Color32::WHITE);
        }
    };
    match pic {
        Pic::Sprite(id) => object(icons, *id, FACT_ICON, Rect::from_min_size(at, vec2(FACT_ICON, FACT_ICON))),
        Pic::Portal { dungeon, front, hearts } => {
            let portal = data.dungeons.portal_by_name.get(dungeon).copied().unwrap_or(-1);
            object(icons, portal, LOVER_PORTAL, Rect::from_min_size(at, vec2(LOVER_PORTAL, LOVER_PORTAL)));
            let r = Rect::from_min_size(pos2(at.x + w - LOVER_CHAR, at.y + h - LOVER_CHAR), vec2(LOVER_CHAR, LOVER_CHAR));
            match front {
                Front::Sprite(id) => object(icons, *id, LOVER_CHAR, r),
                Front::Look(look) => {
                    if let Some(t) = look.and_then(|l| skin(ui, icons, app, l, LOVER_CHAR)) {
                        painter.image(t.id(), r, uv(), Color32::WHITE);
                        if *hearts {
                            // the eyes of a class sprite sit in its upper middle: a heart on each, dark rimmed
                            let px = (LOVER_CHAR / 16.0).floor().max(1.0);
                            let ey = r.top() + (LOVER_CHAR * 0.28).floor();
                            for hx in [r.left() + (LOVER_CHAR * 0.38).floor(), r.left() + (LOVER_CHAR * 0.62).floor()] {
                                let rows = [".X.X.", "XXXXX", ".XXX.", "..X.."];
                                let (left, top) = (hx - (5.0 * px / 2.0).floor(), ey - 2.0 * px);
                                for (ri, row) in rows.iter().enumerate() {
                                    for (k, ch) in row.chars().enumerate() {
                                        if ch == 'X' {
                                            let q = Rect::from_min_size(
                                                pos2(left + k as f32 * px - 1.0, top + ri as f32 * px - 1.0),
                                                vec2(px + 2.0, px + 2.0),
                                            );
                                            painter.rect_filled(q, 0.0, Color32::from_rgb(0x3A, 0x0A, 0x12));
                                        }
                                    }
                                }
                                pixels(painter, &rows, left, top, px, Color32::from_rgb(0xFF, 0x3B, 0x6B));
                            }
                        }
                    }
                }
            }
        }
        Pic::Exalt => {
            let size = FACT_ICON;
            let ps = (size * 3.0 / 4.0).floor();
            object(icons, LIFE_POTION, ps, Rect::from_min_size(pos2(at.x, at.y + size - ps), vec2(ps, ps)));
            let px = (size / 14.0).floor().max(2.0);
            pixels(painter, &["..X..", ".XXX.", "XXXXX", ".XXX.", ".XXX."], at.x + size - 5.0 * px, at.y, px, p.gold);
            let spark = [".X.", "XXX", ".X."];
            pixels(painter, &spark, at.x + px, at.y + px, px, p.gold);
            pixels(painter, &spark, at.x + size - 4.0 * px, at.y + (size / 2.0).floor() + px, px, Color32::from_rgb(0xFF, 0xF2, 0xB0));
        }
        Pic::Dodge(look) => {
            let px = (FACT_ICON / 14.0).floor().max(2.0);
            let char_w = if look.is_some() { FACT_ICON } else { 0.0 };
            let (x, y) = (at.x, at.y);
            let pts = [
                pos2(x + px, y + h - px * 2.0),
                pos2(x + w * 0.15, y + px),
                pos2(x + w * 0.45, y + px),
                pos2(x + w - char_w * 0.9, y + h * 0.45),
            ];
            let curve = egui::epaint::CubicBezierShape::from_points_stroke(pts, false, Color32::TRANSPARENT, Stroke::NONE);
            let path = curve.flatten(Some(0.3));
            painter.extend(egui::Shape::dashed_line(&path, Stroke::new(px * 0.7, Color32::from_rgb(0xF2, 0xE6, 0xC8)), px * 1.2, px));
            let bs = (FACT_ICON * 3.0 / 4.0).floor();
            object(
                icons,
                BULLET,
                bs,
                Rect::from_min_size(pos2(x + (w * 3.0 / 10.0).floor() - (bs / 3.0).floor(), y + h - bs), vec2(bs, bs)),
            );
            if let Some(t) = look.and_then(|l| skin(ui, icons, app, l, FACT_ICON)) {
                painter.image(
                    t.id(),
                    Rect::from_min_size(pos2(x + w - FACT_ICON, y + h - FACT_ICON), vec2(FACT_ICON, FACT_ICON)),
                    uv(),
                    Color32::WHITE,
                );
            }
        }
        Pic::DivineShiny => {
            let r = Rect::from_min_size(at, vec2(FACT_ICON, FACT_ICON));
            object(icons, SHINY_SWORD, FACT_ICON, r);
            crate::detail::enchant_badge(painter, rarity, r.right_bottom() - vec2(1.0, 1.0), 4, 5.0);
        }
        Pic::Look(look) => {
            let r = Rect::from_min_size(at, vec2(FACT_ICON, FACT_ICON));
            match look.and_then(|l| skin(ui, icons, app, l, FACT_ICON)) {
                Some(t) => {
                    painter.image(t.id(), r, uv(), Color32::WHITE);
                }
                None => {
                    let first = data.classes.first().map_or(768, |k| k.id);
                    object(icons, first, FACT_ICON, r);
                }
            }
        }
        Pic::StSet(pieces) => {
            let half = (FACT_ICON / 2.0).floor();
            for (i, id) in pieces.iter().enumerate().filter(|(_, id)| **id > 0) {
                let r = Rect::from_min_size(pos2(at.x + (i % 2) as f32 * half, at.y + (i / 2) as f32 * half), vec2(half, half));
                object(icons, *id, half, r);
            }
        }
        Pic::Facing(a, b) => {
            let part = FACT_ICON * 3.0 / 4.0;
            if let Some(t) = a.and_then(|l| skin(ui, icons, app, l, part)) {
                painter.image(t.id(), Rect::from_min_size(pos2(at.x, at.y + h - part), vec2(part, part)), uv(), Color32::WHITE);
            }
            if let Some(t) = b.and_then(|l| skin(ui, icons, app, l, part)) {
                // mirrored: facing the first
                let r = Rect::from_min_size(pos2(at.x + w - part, at.y + h - part), vec2(part, part));
                painter.image(t.id(), r, Rect::from_min_max(pos2(1.0, 0.0), pos2(0.0, 1.0)), Color32::WHITE);
            }
        }
    }
}

/// A fact's line wrapped to `FACT_WIDTH` (longer words keep their width), each line centred: its lines as runs.
/// Wrapped lines of (word, bold, gold, width).
type Lines = Vec<Vec<(String, bool, bool, f32)>>;

fn wrap(painter: &egui::Painter, runs: &[Run]) -> (Lines, f32) {
    let f = text(20.0);
    let width = |t: &str, b: bool| if b { jtext::bold_width(painter, t, &f) } else { jtext::width(painter, t, &f) };
    let mut words: Vec<(String, bool, bool)> = Vec::new();
    for (t, b, g) in runs {
        let mut cur = String::new();
        for ch in t.chars() {
            cur.push(ch);
            if ch == ' ' {
                words.push((std::mem::take(&mut cur), *b, *g));
            }
        }
        if !cur.is_empty() {
            words.push((cur, *b, *g));
        }
    }
    let mut lines: Vec<Vec<(String, bool, bool, f32)>> = vec![Vec::new()];
    let mut lw = 0.0;
    let mut widest: f32 = 0.0;
    for (t, b, g) in words {
        let w = width(&t, b);
        let trimmed = width(t.trim_end(), b);
        if lw > 0.0 && lw + trimmed > FACT_WIDTH {
            widest = widest.max(lw);
            lines.push(Vec::new());
            lw = 0.0;
        }
        lines.last_mut().expect("a line").push((t, b, g, w));
        lw += w;
    }
    widest = widest.max(lw).max(FACT_WIDTH);
    (lines, widest)
}

/// The card's guild part (Java `guildCard`).
pub fn guild_card(
    ui: &mut Ui,
    p: &Palette,
    app: &App,
    icons: &mut Icons,
    props: &lg_app::properties::Properties,
    rarity: &crate::detail::Rarity,
    now: i64,
) {
    let g = &app.server.profile_guild;
    let w = words();
    // like the greeting: a prefix (soft ink) before the guild's name (gold)
    let f56 = widgets::head_px(56.0);
    match &g.name {
        None => {
            widgets::label(ui, "Guild", &f56, p.gold);
        }
        Some(n) => {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                widgets::label(ui, w.prefix, &f56, p.ink_soft);
                widgets::label(ui, &format!(" {n}"), &f56, p.gold);
            });
        }
    }
    ui.add_space(14.0);
    let Some(wagon) = g.wagon.as_ref().filter(|_| g.note.is_none() && g.error.is_none()) else {
        let note = match (&g.note, &g.error) {
            (_, Some(e)) => lg_app::server::load_error_text(e, Some(&app.profile.guild)),
            (Some(n), _) => n.clone(),
            _ => "Loading...".into(),
        };
        widgets::muted(ui, &note);
        return;
    };
    let line = |ui: &mut Ui| {
        let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 4.0), Sense::hover());
        ui.painter().rect_filled(Rect::from_min_size(pos2(r.left(), r.top() + 1.0), vec2(r.width() - 4.0, 2.0)), 0.0, p.gold);
    };
    // five facts, those not shown the last time first
    let mut all = facts(app);
    if !all.is_empty() {
        let today = (now + i64::from(app.zone.offset(now / 1000)) * 1000).div_euclid(86_400_000);
        let order = fact_order(props, today);
        all.sort_by_key(|f| order.iter().position(|n| *n == f.caption).unwrap_or(usize::MAX));
        all.truncate(FACTS_SHOWN);
        let shown = all.iter().map(|f| f.caption).collect::<Vec<_>>().join("|");
        if props.get(FACTS_SHOWN_KEY) != Some(shown.as_str()) {
            crate::window::ask_later(ui.ctx(), crate::window::Ask::Set(FACTS_SHOWN_KEY.into(), shown));
        }
        widgets::caption(ui, p, w.stats);
        ui.add_space(4.0);
        line(ui);
        ui.add_space(8.0);
        // side by side, wrapping when the card is narrow; the pictures as tall as the tallest, standing on one line
        let painter = ui.painter().clone();
        let tallest = all.iter().map(|f| f.pic.size().1).fold(0.0, f32::max);
        let cap_f = text(16.0);
        let tf = text(20.0);
        let fh = jtext::height(&painter, &tf);
        let cap_h = jtext::height(&painter, &cap_f);
        let laid: Vec<(Lines, f32)> = all.iter().map(|f| wrap(&painter, &f.line)).collect();
        let widths: Vec<f32> =
            all.iter().zip(&laid).map(|(f, (_, tw))| tw.max(jtext::width(&painter, f.caption, &cap_f)).max(f.pic.size().0)).collect();
        let heights: Vec<f32> = laid.iter().map(|(l, _)| cap_h + 2.0 + tallest + 6.0 + l.len() as f32 * fh).collect();
        let full = ui.available_width();
        let top = ui.cursor().min;
        let (mut x, mut y, mut row_h) = (top.x, top.y, 0.0f32);
        for (k, fact) in all.iter().enumerate() {
            let fw = widths[k];
            if x > top.x && x + fw > top.x + full {
                x = top.x;
                y += row_h + 18.0;
                row_h = 0.0;
            }
            let cx = x + fw / 2.0;
            jtext::draw(&painter, pos2(cx, y), Align2::CENTER_TOP, fact.caption, &cap_f, fixed::MUTED);
            let (pw, ph) = fact.pic.size();
            let pic_at = pos2(cx - (pw / 2.0).floor(), y + cap_h + 2.0 + tallest - ph);
            paint_pic(ui, icons, app, p, rarity, &fact.pic, pic_at);
            let mut ly = y + cap_h + 2.0 + tallest + 6.0;
            for l in &laid[k].0 {
                let lw: f32 =
                    l.iter().map(|w| w.3).sum::<f32>() - l.last().map_or(0.0, |w| w.3 - jtext::width(&painter, w.0.trim_end(), &tf));
                let mut wx = cx - (lw / 2.0).floor();
                for (t, b, gold, ww) in l {
                    let c = if *gold { p.gold } else { p.ink };
                    if *b {
                        jtext::draw_bold(&painter, pos2(wx, ly), Align2::LEFT_TOP, t, &tf, c);
                    } else {
                        jtext::draw(&painter, pos2(wx, ly), Align2::LEFT_TOP, t, &tf, c);
                    }
                    wx += ww;
                }
                ly += fh;
            }
            widgets::tip(ui, Rect::from_min_size(pos2(x, y), vec2(fw, heights[k])), ("guild-fact", k), &fact.tip);
            row_h = row_h.max(heights[k]);
            x += fw + 36.0;
        }
        ui.allocate_exact_size(vec2(full, y + row_h - top.y), Sense::hover());
        ui.add_space(14.0);
    }
    widgets::caption(ui, p, "GUILD PROGRESS");
    ui.add_space(4.0);
    line(ui);
    ui.add_space(8.0);
    // your wagon and the quest map's next dungeon side by side: titles in line, links in line; each opens its page
    let painter = ui.painter().clone();
    let top = ui.cursor().min;
    let cap_f = text(16.0);
    let cap_h = jtext::height(&painter, &cap_f);
    let f = text(18.0);
    let fh = jtext::height(&painter, &f);
    let y1 = top.y + cap_h + 6.0;
    let wr = crate::caravan::small_wagon(ui, icons, app.world.data(), &app.server.weights, wagon, p.gold, pos2(top.x, y1), now / 70);
    // the quest map: the next dungeon's portal, "Next: ...", how far the guild got
    let qx = wr.right() + 48.0;
    let q_lines: Option<(String, String, bool, i32)> = g.map.as_ref().filter(|m| !m.dungeons.is_empty()).map(|m| {
        let n = m.dungeons.len();
        let done = m.clears.len().min(n);
        let complete = m.completed_at.is_some() || done == n;
        let next = m.dungeons[if complete { n - 1 } else { done }].clone();
        let portal = app.world.data().dungeons.portal_by_name.get(&next).copied().unwrap_or(-1);
        (
            if complete { "All done!".to_string() } else { format!("Next: {next}") },
            format!("{done}/{n} dungeons completed"),
            complete,
            portal,
        )
    });
    let f20 = text(20.0);
    let q_h = match &q_lines {
        Some(_) => 80.0 + 6.0 + fh + jtext::height(&painter, &f20),
        None => fh,
    };
    let q_w = match &q_lines {
        Some((a, b, _, _)) => 80f32.max(jtext::width(&painter, a, &f)).max(jtext::width(&painter, b, &f20)),
        None => jtext::width(&painter, "No quest map yet", &f),
    };
    let mid_h = wr.height().max(q_h);
    // the wagon is centred in its row; the quest map too
    let qy = y1 + ((mid_h - q_h) / 2.0).floor();
    match &q_lines {
        Some((a, b, complete, portal)) => {
            let cx = qx + q_w / 2.0;
            if let Some(t) = icons.object(ui.ctx(), app.world.data(), *portal, 80) {
                painter.image(t.id(), Rect::from_min_size(pos2(cx - 40.0, qy), vec2(80.0, 80.0)), uv(), Color32::WHITE);
            }
            jtext::draw(&painter, pos2(cx, qy + 86.0), Align2::CENTER_TOP, a, &f, p.ink);
            jtext::draw(&painter, pos2(cx, qy + 86.0 + fh), Align2::CENTER_TOP, b, &f20, if *complete { fixed::GREEN } else { p.gold });
        }
        None => {
            jtext::draw(&painter, pos2(qx, qy), Align2::LEFT_TOP, "No quest map yet", &f, fixed::MUTED);
        }
    }
    let cols = [
        (top.x, wr.width(), "YOUR WAGON", "To the caravan", "Guild:Loot Caravan"),
        (qx, q_w, "QUEST MAP", "To the quest map", "Guild:Quest Map"),
    ];
    let link_y = y1 + mid_h + 6.0;
    for (i, (x, w, title, link, spec)) in cols.iter().enumerate() {
        jtext::draw(&painter, pos2(x + w / 2.0, top.y), Align2::CENTER_TOP, title, &cap_f, fixed::MUTED);
        jtext::draw(&painter, pos2(x + w / 2.0, link_y), Align2::CENTER_TOP, &format!("{link}  ›"), &f, p.gold);
        let zone = Rect::from_min_max(pos2(*x, top.y), pos2(x + w, link_y + fh));
        let resp = ui.interact(zone, ui.scope_id().with(("guild-open", i)), Sense::click()).on_hover_cursor(egui::CursorIcon::PointingHand);
        if resp.clicked() {
            crate::window::open_later(ui.ctx(), *spec);
        }
    }
    ui.allocate_exact_size(vec2(ui.available_width(), link_y + fh - top.y), Sense::hover());
}

#[cfg(test)]
mod tests {
    #[test]
    fn plurals_as_java() {
        use super::plural;
        assert_eq!(plural("The Nest"), "Nests");
        assert_eq!(plural("Tomb of the Ancients"), "Tombs of the Ancients");
        assert_eq!(plural("Lost Halls"), "Lost Halls");
        assert_eq!(plural("White Snake Invasion II"), "White Snake Invasion II");
        assert_eq!(plural("Battle for the Nexus"), "Battles for the Nexus");
        assert_eq!(plural("Candyland Hunting Grounds"), "Candyland Hunting Grounds");
        assert_eq!(plural("Spider Den"), "Spider Dens");
        assert_eq!(plural("Abyss of Demons"), "Abysses of Demons");
        assert_eq!(plural("The Shatters"), "Shatters");
        assert_eq!(plural("Puppet Master's Theatre"), "Puppet Master's Theatres");
        assert_eq!(plural("Kogbold Steamworks"), "Kogbold Steamworks");
        assert_eq!(plural("Cnidarian Reef"), "Cnidarian Reefs");
        assert_eq!(plural("Deadwater Docks"), "Deadwater Docks");
        assert_eq!(plural("Ice Cave"), "Ice Caves");
        assert_eq!(plural("Davy Jones' Locker"), "Davy Jones' Lockers");
        assert_eq!(plural("Toxic Sewers"), "Toxic Sewers");
        assert_eq!(plural("Forgotten King's Fairy"), "Forgotten King's Fairies");
    }
}
