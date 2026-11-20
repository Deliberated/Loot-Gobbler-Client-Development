//! The Settings tab (Java `SettingsPanel`): sounds and pings, the startup sound, the item sounds, the ping lists,
//! the loot filter, the theme, logging and the Newcomer's Guide, each as kept in the settings file.

use crate::jtext;
use crate::profile::Ctx;
use crate::social::pixel_check;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::properties::Properties;

const STARTUP_IDS: [&str; 6] = ["off", "goblinCackle", "goblinGobble", "pm", "whitebag", "goldbag"];
const STARTUP_LABELS: [&str; 6] = ["Off", "Goblin cackle", "Goblin gobble", "PM ping", "White bag", "Gold bag"];
const MATRICES: [i32; 3] = [0x0483, 0x0484, 0x0485];
const REFINERY: i32 = 44495;
const ALIEN_GENERATOR: i32 = 48164;
const DEFAULT_VOLUME: i32 = 77;

/// An item sound (Java `ItemSound`): its setting, the item, the bundled sounds to pick from.
struct ItemSound {
    key: &'static str,
    item: &'static str,
    builtins: &'static [&'static str],
}

const ITEM_SOUNDS: [(ItemSound, &str, Option<bool>); 6] = [
    (
        ItemSound { key: "amuletSoundFile", item: "Amulet of Restoration broken", builtins: &["Amulet activation"] },
        "Played when your Amulet of Restoration breaks (it saved you).",
        Some(false),
    ),
    (
        ItemSound {
            key: "amuletReadySoundFile",
            item: "Amulet of Restoration recharged",
            builtins: &["Chain", "Dangle", "Pull", "Clasp", "Gleam"],
        },
        "Played when your Amulet of Restoration is charged again (1800 seconds after it saved you).",
        Some(true),
    ),
    (
        ItemSound { key: "refinerySoundFile", item: "Coral Juice Refinery", builtins: &["Bubble", "Chime", "Ping", "Droplet", "Shell"] },
        "Played when your Coral Juice Refinery can be used again (15 seconds after using it).",
        None,
    ),
    (
        ItemSound {
            key: "alienSoundFile",
            item: "Miniature Alien Generator",
            builtins: &["Warp", "Beacon", "Saucer", "Arpeggio", "Signal"],
        },
        "Played when your Miniature Alien Generator can be used again (45 seconds after using it).",
        None,
    ),
    (
        ItemSound {
            key: "matrixBreakSoundFile",
            item: "Protective Matrix broken",
            builtins: &["Pufferfish", "Core down", "Collapse", "Shatter", "Brownout", "Reactor"],
        },
        "Played when your Protective Matrix (Heavy, Magic or Fitted) breaks (it shielded you).",
        Some(false),
    ),
    (
        ItemSound {
            key: "matrixSoundFile",
            item: "Protective Matrix recharged",
            builtins: &["Pulse", "Zip", "Shield", "Recharge", "Lock"],
        },
        "Played when your Protective Matrix is charged again (180 seconds after it shielded you).",
        Some(true),
    ),
];

impl ItemSound {
    fn setting<'a>(&self, props: &'a Properties) -> &'a str {
        props.get(self.key).map_or("", str::trim)
    }

    fn volume(&self, props: &Properties) -> i32 {
        props.get(&format!("{}Volume", self.key)).and_then(|v| v.trim().parse::<i32>().ok()).map_or(100, |v| v.clamp(0, 100))
    }

    /// The bundled sound chosen (0 = the default), or None for your own file or off.
    fn builtin(&self, props: &Properties) -> Option<usize> {
        let s = self.setting(props);
        if s.is_empty() {
            return Some(0);
        }
        let name = s.strip_prefix("builtin:")?;
        Some(self.builtins.iter().position(|b| b.eq_ignore_ascii_case(name)).unwrap_or(0))
    }

    /// What the label beside the picker says.
    fn current(&self, props: &Properties) -> String {
        let s = self.setting(props);
        if s == "off" {
            "Off".into()
        } else if self.builtin(props).is_none() {
            std::path::Path::new(s).file_name().map_or(s.to_string(), |n| n.to_string_lossy().into_owned())
        } else if self.builtins.len() > 1 {
            String::new()
        } else {
            format!("Default ({})", self.builtins[0])
        }
    }
}

/// Where a click changes a setting, found while painting and answered after it.
enum Zone {
    /// A click sets the key to the value.
    Set(Rect, String, String),
    /// A slider: the key gets the value under the pointer (track from, to; values min..max) when let go.
    Slider(Rect, String, f32, f32, i32, i32),
    /// Asked already (a picker was clicked).
    Now(String, String),
    /// A sound to play (a Play button).
    Play(String),
    /// Anything else asked of the app.
    Ask(crate::window::Ask),
}

/// What a changed setting lets you hear (Java plays it with every click): the ping or bag sound switched, the
/// volume (with the PM ping), the startup or item sound chosen.
fn preview(key: &str, value: &str) -> Option<String> {
    let file = match key {
        "chatPing" => "pm.wav",
        "chatPingParty" => "party.wav",
        "chatPingGuild" => "guild.wav",
        "whiteBagSound" => "whitebag.wav",
        "orangeBagSound" => "orangebag.wav",
        "redBagSound" => "redbag.wav",
        "goldBagSound" => "goldbag.wav",
        "eggBagSound" => "eggbag.wav",
        "blueBagSound" => "bluebag.wav",
        "tradePing" => "trade.wav",
        "soundVolume" => "pm.wav",
        "startupSound" => return Some(format!("startup:{value}")),
        k => {
            let item = k.strip_suffix("Volume").unwrap_or(k);
            return ITEM_SOUNDS.iter().any(|(s, _, _)| s.key == item).then(|| format!("item:{item}"));
        }
    };
    Some(file.into())
}

fn bool_prop(props: &Properties, key: &str, by_default: bool) -> bool {
    props.get(key).map_or(by_default, |v| v == "true")
}

/// A Java `JSlider` as the app's theme draws it: a thin track, gold up to the round thumb.
pub fn slider(painter: &egui::Painter, p: &Palette, x: f32, cy: f32, w: f32, frac: f32) {
    let track = Rect::from_min_max(pos2(x + 15.0, cy - 1.5), pos2(x + w - 15.0, cy + 1.5));
    painter.rect_filled(track, 1.5, p.line);
    let tx = track.left() + frac.clamp(0.0, 1.0) * track.width();
    painter.rect_filled(Rect::from_min_max(track.min, pos2(tx, track.bottom())), 1.5, Color32::from_rgb(0xE8, 0xB8, 0x3A));
    painter.circle_filled(pos2(tx, cy), 9.0, Color32::from_rgb(0xE8, 0xB8, 0x3A));
}

/// A check box with its text (Java `JCheckBox`); gives its right edge.
fn check(painter: &egui::Painter, p: &Palette, x: f32, cy: f32, label: &str, on: bool) -> Rect {
    let b = pixel_check(painter, p, pos2(x, cy - 9.5), on);
    if label.is_empty() {
        return b;
    }
    b.union(jtext::draw(painter, pos2(b.right() + 6.0, cy), Align2::LEFT_CENTER, label, &text(18.0), p.ink))
}

/// A button at its left edge, centred on `cy`; gives its rect.
fn button(ui: &Ui, p: &Palette, x: f32, cy: f32, label: &str) -> (Rect, bool) {
    // FlatLaf's buttons are at least 72 (x 1.5) wide
    let w = (jtext::width(ui.painter(), &label.to_uppercase(), &small()) + 32.0).max(108.0);
    let r = Rect::from_min_size(pos2(x, cy - 18.0), vec2(w, 36.0));
    let clicked = crate::inventory::button(ui, p, r, label, false, &format!("{label}-{x}-{cy}"));
    (r, clicked)
}

/// A drop-down (Java `JComboBox`) showing one entry.
fn combo(painter: &egui::Painter, p: &Palette, x: f32, cy: f32, w: f32, shown: &str) -> Rect {
    let r = Rect::from_min_size(pos2(x, cy - 16.0), vec2(w, 32.0));
    painter.rect(r, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
    jtext::draw(painter, pos2(r.left() + 8.0, cy), Align2::LEFT_CENTER, shown, &text(18.0), p.ink);
    let ax = r.right() - 16.0;
    painter
        .add(egui::Shape::line(vec![pos2(ax - 5.0, cy - 2.5), pos2(ax, cy + 2.5), pos2(ax + 5.0, cy - 2.5)], Stroke::new(1.5, p.ink_soft)));
    r
}

/// Behind an item sound's name: down (red) when the item broke, up (green) when it is charged again (14 x 18).
fn arrow(painter: &egui::Painter, at: egui::Pos2, up: bool) {
    let color = if up { Color32::from_rgb(0x4C, 0xAF, 0x50) } else { Color32::from_rgb(0xE0, 0x53, 0x3D) };
    let pts = [(7.0, 1.0), (13.0, 8.0), (9.5, 8.0), (9.5, 17.0), (4.5, 17.0), (4.5, 8.0), (1.0, 8.0)];
    let pts: Vec<egui::Pos2> = pts.iter().map(|(x, y)| if up { at + vec2(*x, *y) } else { at + vec2(14.0 - x, 18.0 - y) }).collect();
    // the head and the shaft as two convex parts
    painter.add(egui::Shape::convex_polygon(vec![pts[0], pts[1], pts[6]], color, Stroke::NONE));
    painter.add(egui::Shape::convex_polygon(vec![pts[2], pts[3], pts[4], pts[5]], color, Stroke::NONE));
    let dark = Color32::from_rgb((color.r() as f32 * 0.49) as u8, (color.g() as f32 * 0.49) as u8, (color.b() as f32 * 0.49) as u8);
    painter.add(egui::Shape::closed_line(pts, Stroke::new(1.0, dark)));
}

pub fn show(ui: &mut Ui, c: &mut Ctx) {
    let p = *c.p;
    let props = c.props;
    let data = c.app.world.data();
    let icons = &mut *c.icons;
    let asks = &mut *c.asks;
    // a setting saved, and the sound it is about played, as Java does on every click (it plays the new choice)
    let set = |asks: &mut Vec<crate::window::Ask>, k: &str, v: &str| {
        asks.push(crate::window::Ask::Set(k.to_string(), v.to_string()));
        if let Some(sound) = preview(k, v) {
            asks.push(crate::window::Ask::Play(sound));
        }
    };
    let f = text(18.0);
    let all = ui.max_rect();
    let zones = std::cell::RefCell::new(Vec::<Zone>::new());
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(all));
    egui::ScrollArea::vertical().id_salt("settings").show(&mut child, |ui| {
        let top = ui.cursor().min;
        let x0 = top.x + 8.0;
        let w = ui.available_width() - 16.0;
        let mut y = top.y + 8.0;
        let fh = jtext::height(ui.painter(), &f);
        let ctx = ui.ctx().clone();
        let row_h = 36.0 + 8.0;
        // a row of text: FlowLayout's 6 px in and 4 above and below
        let hint = |painter: &egui::Painter, x: f32, y: f32, t: &str| {
            jtext::draw(painter, pos2(x + 6.0, y + 4.0), Align2::LEFT_TOP, t, &f, p.ink);
            fh + 8.0
        };
        let grid_rows = |painter: &egui::Painter,
                         x: f32,
                         y: f32,
                         w: f32,
                         items: &[(String, bool, Option<i32>, &str)],
                         icons: &mut crate::icons::Icons|
         -> f32 {
            let cw = ((w - 12.0 - 2.0 * 24.0) / 3.0).floor();
            let ch = 26.0;
            for (i, (label, on, bag, key)) in items.iter().enumerate() {
                let cx = x + 6.0 + (i % 3) as f32 * (cw + 24.0);
                let cy = y + 4.0 + (i / 3) as f32 * (ch + 4.0) + ch / 2.0;
                zones.borrow_mut().push(Zone::Set(
                    Rect::from_min_size(pos2(cx, cy - ch / 2.0), vec2(cw, ch)),
                    key.to_string(),
                    (!on).to_string(),
                ));
                match bag {
                    None => {
                        check(painter, &p, cx, cy, label, *on);
                    }
                    Some(id) => {
                        let r = check(painter, &p, cx + 2.0, cy, "", *on);
                        let ix = r.right() + 2.0 + 6.0;
                        if let Some(t) = icons.object(&ctx, data, *id, 22) {
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(ix, cy - 11.0), vec2(22.0, 22.0)),
                                crate::detail::uv(),
                                Color32::WHITE,
                            );
                        }
                        jtext::draw(painter, pos2(ix + 22.0 + 6.0, cy), Align2::LEFT_CENTER, label, &f, p.ink);
                    }
                }
            }
            let rows = items.len().div_ceil(3) as f32;
            4.0 + rows * ch + (rows - 1.0) * 4.0 + 8.0
        };
        // Sound & Pings
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Sound & Pings",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                let cy = ty + 4.0 + 15.0;
                let lr = jtext::draw(&painter, pos2(x + 6.0, cy), Align2::LEFT_CENTER, "Volume", &f, p.ink);
                let vol = props.get("soundVolume").and_then(|v| v.parse::<i32>().ok()).unwrap_or(DEFAULT_VOLUME);
                let sx = lr.right() + 6.0 + 24.0 + 6.0;
                slider(&painter, &p, sx, cy, 300.0, vol as f32 / 100.0);
                zones.borrow_mut().push(Zone::Slider(
                    Rect::from_min_size(pos2(sx, cy - 15.0), vec2(300.0, 30.0)),
                    "soundVolume".into(),
                    sx + 15.0,
                    sx + 285.0,
                    0,
                    100,
                ));
                ty += 30.0 + 8.0;
                let toggles = [
                    ("PM ping", "chatPing", false),
                    ("Party ping", "chatPingParty", false),
                    ("Guild ping", "chatPingGuild", false),
                    ("White bag", "whiteBagSound", true),
                    ("Orange bag", "orangeBagSound", true),
                    ("Red bag", "redBagSound", false),
                    ("Gold bag", "goldBagSound", false),
                    ("Egg bag", "eggBagSound", false),
                    ("Blue bag", "blueBagSound", false),
                    ("Trade", "tradePing", false),
                ];
                let items: Vec<(String, bool, Option<i32>, &str)> =
                    toggles.iter().map(|(l, k, d)| (l.to_string(), bool_prop(props, k, *d), None, *k)).collect();
                ty += grid_rows(&painter, x, ty, r.width() + 2.0, &items, icons);
                ty - r.top() + 2.0
            },
        );
        // Startup Sound
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Startup Sound",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                ty += hint(&painter, x, ty, "Played once when Loot Gobbler opens.");
                let saved = props.get("startupSound").filter(|s| STARTUP_IDS.contains(s)).unwrap_or("goldbag");
                let sel = STARTUP_IDS.iter().position(|s| *s == saved).unwrap_or(5);
                if let Some(i) = widgets::segmented(ui, &p, pos2(x + 6.0, ty + 4.0), 36.0, &STARTUP_LABELS, sel, true, &[]) {
                    zones.borrow_mut().push(Zone::Now("startupSound".into(), STARTUP_IDS[i].into()));
                }
                let sw = widgets::segmented_width(&painter, &STARTUP_LABELS, true);
                if button(ui, &p, x + 6.0 + sw + 6.0 + 8.0 + 6.0, ty + 4.0 + 18.0, "Play").1 {
                    zones.borrow_mut().push(Zone::Play(format!("startup:{saved}")));
                }
                ty += row_h;
                ty - r.top() + 2.0
            },
        );
        // Item Sounds
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Item Sounds",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let right = r.right() + 1.0;
                let mut ty = r.top();
                for (k, (s, when, up)) in ITEM_SOUNDS.iter().enumerate() {
                    if k > 0 {
                        ty += 14.0;
                    }
                    // the name, its items and the arrow; its volume at the right
                    let head_h = 30.0 + 8.0;
                    let cy = ty + head_h / 2.0;
                    let tf = text(20.0);
                    let nr = jtext::draw_bold(&painter, pos2(x + 6.0, cy), Align2::LEFT_CENTER, s.item, &tf, fixed::GOLD);
                    let mut ix = nr.right() + 6.0;
                    let items: Vec<i32> = match s.key {
                        "amuletSoundFile" | "amuletReadySoundFile" => vec![data.amulets.restoration],
                        "refinerySoundFile" => vec![REFINERY],
                        "alienSoundFile" => vec![ALIEN_GENERATOR],
                        _ => MATRICES.to_vec(),
                    };
                    for id in items.into_iter().filter(|id| *id >= 0) {
                        if let Some(t) = icons.fitted(ui.ctx(), data, id, 40, 24) {
                            painter.image(
                                t.id(),
                                Rect::from_min_size(pos2(ix, cy - 12.0), vec2(24.0, 24.0)),
                                crate::detail::uv(),
                                Color32::WHITE,
                            );
                        }
                        ix += 24.0 + 2.0;
                    }
                    if let Some(up) = up {
                        arrow(&painter, pos2(ix + 2.0, cy - 9.0), *up);
                    }
                    let vol = s.volume(props);
                    let lt = format!("{vol}%");
                    let lw = jtext::width(&painter, "100%", &f) + 4.0;
                    let lx = right - 6.0 - lw;
                    jtext::draw(&painter, pos2(lx, cy), Align2::LEFT_CENTER, &lt, &f, p.ink);
                    let sx = lx - 6.0 - 150.0;
                    slider(&painter, &p, sx, cy, 150.0, vol as f32 / 100.0);
                    zones.borrow_mut().push(Zone::Slider(
                        Rect::from_min_size(pos2(sx, cy - 15.0), vec2(150.0, 30.0)),
                        format!("{}Volume", s.key),
                        sx + 15.0,
                        sx + 135.0,
                        0,
                        100,
                    ));
                    jtext::draw(&painter, pos2(sx - 6.0, cy), Align2::RIGHT_CENTER, "Volume", &f, p.ink);
                    ty += head_h;
                    ty += hint(&painter, x, ty, when);
                    // the bundled sounds, what is chosen, Play, Choose..., Default, Off
                    let cy = ty + row_h / 2.0;
                    let mut bx = x + 6.0;
                    if s.builtins.len() > 1 {
                        let cw = s.builtins.iter().map(|b| jtext::width(&painter, b, &f)).fold(0.0, f32::max) + 62.0;
                        let shown = match (s.setting(props) == "off", s.builtin(props)) {
                            (false, Some(b)) => s.builtins[b],
                            _ => "",
                        };
                        let cr = combo(&painter, &p, bx, cy, cw, shown);
                        bx = cr.right() + 6.0 + 8.0 + 6.0;
                    }
                    let cur = s.current(props);
                    let cr = jtext::draw(&painter, pos2(bx, cy), Align2::LEFT_CENTER, &cur, &f, p.ink);
                    bx = cr.right() + 6.0 + 12.0 + 6.0;
                    for b in ["Play", "Choose...", "Default", "Off"] {
                        let (br, clicked) = button(ui, &p, bx, cy, b);
                        if clicked && b == "Choose..." {
                            zones.borrow_mut().push(Zone::Ask(crate::window::Ask::Choose {
                                key: s.key.into(),
                                title: format!("Sound for the {}", s.item),
                                folder: false,
                            }));
                        } else if clicked && b == "Play" {
                            zones.borrow_mut().push(Zone::Play(format!("item:{}", s.key)));
                        } else if clicked && b == "Default" {
                            zones.borrow_mut().push(Zone::Now(s.key.into(), String::new()));
                        } else if clicked && b == "Off" {
                            zones.borrow_mut().push(Zone::Now(s.key.into(), "off".into()));
                        }
                        bx = br.right() + 6.0;
                    }
                    ty += row_h;
                }
                ty - r.top() + 2.0
            },
        );
        // Ping Lists
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Ping Lists",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                ty += hint(&painter, x, ty, "Play a sound when chat, items, enchants, entities or dungeons match your lists.");
                let gw = r.width() + 2.0 - 12.0;
                let cw = ((gw - 16.0) / 3.0).floor();
                for (i, b) in
                    ["Chat pings...", "Item pings...", "Enchant pings...", "Entity pings...", "Dungeon pings...", "Ping sounds..."]
                        .iter()
                        .enumerate()
                {
                    let bx = x + 6.0 + (i % 3) as f32 * (cw + 8.0);
                    let by = ty + 4.0 + (i / 3) as f32 * (36.0 + 6.0);
                    if crate::inventory::button(ui, &p, Rect::from_min_size(pos2(bx, by), vec2(cw, 36.0)), b, false, b) {
                        zones.borrow_mut().push(Zone::Ask(crate::window::Ask::OpenDialog(i)));
                    }
                }
                ty += 4.0 + 2.0 * 36.0 + 6.0 + 4.0;
                ty - r.top() + 2.0
            },
        );
        // Loot Filter
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Loot Filter",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                ty += hint(&painter, x, ty, "Which loot-bag colours appear in the logger.");
                let bags = [
                    ("White bags", 1292, "filterWhiteBag", true),
                    ("Orange bags", 1295, "filterOrangeBag", true),
                    ("Red bags", 1708, "filterRedBag", true),
                    ("Gold bags", 1294, "filterGoldBag", true),
                    ("Egg bags", 1288, "filterEggBag", false),
                    ("Blue bags", 1291, "filterBlueBag", false),
                    ("Teal bags", 1289, "filterTealBag", true),
                    ("Purple bags", 1287, "filterPurpleBag", false),
                    ("Pink bags", 1286, "filterPinkBag", false),
                    ("Brown bags", 1280, "filterBrownBag", false),
                ];
                let items: Vec<(String, bool, Option<i32>, &str)> =
                    bags.iter().map(|(l, id, k, d)| (l.to_string(), bool_prop(props, k, *d), Some(*id), *k)).collect();
                ty += grid_rows(&painter, x, ty, r.width() + 2.0, &items, icons);
                ty - r.top() + 2.0
            },
        );
        // Theme
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Theme",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let saved = props.get("theme");
                let key = crate::theme::THEMES.iter().find(|t| Some(t.0) == saved).map_or("treasureHoard", |t| t.0);
                let labels: Vec<&str> = crate::theme::THEMES.iter().map(|t| t.1).collect();
                let sel = crate::theme::THEMES.iter().position(|t| t.0 == key).unwrap_or(2);
                if let Some(i) = widgets::segmented(ui, &p, pos2(r.left() + 1.0, r.top()), 36.0, &labels, sel, true, &[]) {
                    zones.borrow_mut().push(Zone::Now("theme".into(), crate::theme::THEMES[i].0.into()));
                }
                36.0 + 2.0
            },
        );
        // Logging
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Logging",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                ty += hint(
                    &painter,
                    x,
                    ty,
                    "Writes what the app reports, errors included, to one file per day. View and export them under Info > Logs.",
                );
                let on = props.get("logging") == Some("true");
                let cr = check(&painter, &p, x + 6.0, ty + 4.0 + 13.0, "Write log files", on);
                zones.borrow_mut().push(Zone::Set(cr, "logging".into(), (!on).to_string()));
                ty += 26.0 + 8.0;
                let cy = ty + row_h / 2.0;
                let dir = props
                    .get("logDir")
                    .map(str::to_string)
                    .unwrap_or_else(|| std::env::current_dir().unwrap_or_default().join("logs").display().to_string());
                let lr = jtext::draw(&painter, pos2(x + 6.0, cy), Align2::LEFT_CENTER, "Folder", &f, p.ink);
                let dr = jtext::draw(&painter, pos2(lr.right() + 6.0 + 12.0 + 6.0, cy), Align2::LEFT_CENTER, &dir, &f, p.ink);
                let (b, change) = button(ui, &p, dr.right() + 6.0 + 8.0 + 6.0, cy, "Change...");
                if change {
                    zones.borrow_mut().push(Zone::Ask(crate::window::Ask::Choose {
                        key: "logDir".into(),
                        title: "Folder for log files".into(),
                        folder: true,
                    }));
                }
                if button(ui, &p, b.right() + 6.0, cy, "Default").1 {
                    zones
                        .borrow_mut()
                        .push(Zone::Now("logDir".into(), std::env::current_dir().unwrap_or_default().join("logs").display().to_string()));
                }
                ty += row_h;
                let cy = ty + (32.0 + 8.0) / 2.0;
                let keep = props.get("logKeepDays").and_then(|v| v.parse::<i32>().ok()).map_or(14, |v| v.max(0));
                let lr = jtext::draw(&painter, pos2(x + 6.0, cy), Align2::LEFT_CENTER, "Keep logs for", &f, p.ink);
                let sr = Rect::from_min_size(pos2(lr.right() + 6.0 + 12.0 + 6.0, cy - 15.0), vec2(100.0, 30.0));
                painter.rect(sr, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
                jtext::draw(&painter, pos2(sr.right() - 26.0 - 6.0, cy), Align2::RIGHT_CENTER, &keep.to_string(), &f, p.ink);
                let ar = Rect::from_min_max(pos2(sr.right() - 24.0, sr.top()), sr.max);
                painter.rect_stroke(ar, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
                let (ax, ay) = (ar.center().x, ar.center().y);
                painter.add(egui::Shape::line(
                    vec![pos2(ax - 4.0, ay - 2.0), pos2(ax, ay - 6.0), pos2(ax + 4.0, ay - 2.0)],
                    Stroke::new(1.2, p.ink_soft),
                ));
                painter.add(egui::Shape::line(
                    vec![pos2(ax - 4.0, ay + 2.0), pos2(ax, ay + 6.0), pos2(ax + 4.0, ay + 2.0)],
                    Stroke::new(1.2, p.ink_soft),
                ));
                jtext::draw(&painter, pos2(sr.right() + 6.0, cy), Align2::LEFT_CENTER, "days   (0 = keep all logs)", &f, fixed::MUTED);
                ty += 32.0 + 8.0;
                ty - r.top() + 2.0
            },
        );
        // Newcomer's Guide
        y += widgets::section_box(
            ui,
            &p,
            pos2(x0, y),
            w,
            "Newcomer's Guide",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 1.0;
                let mut ty = r.top();
                ty += hint(&painter, x, ty, "The tour through Loot Gobbler. Info > Newcomer's Guide starts the quick tour at any time.");
                let next = props.get("guideDoneVersion") != Some("2");
                let cr = check(&painter, &p, x + 6.0, ty + 4.0 + 13.0, "Show the whole guide on the next start, as if never seen", next);
                // Java `setOnNextStart`: on, it starts from the beginning
                zones.borrow_mut().push(Zone::Set(cr, "guideDoneVersion".into(), if next { "2".into() } else { String::new() }));
                if !next {
                    zones.borrow_mut().push(Zone::Set(cr, "guideActive".into(), "false".into()));
                    zones.borrow_mut().push(Zone::Set(cr, "guideStep".into(), "0".into()));
                }
                ty += 26.0 + 8.0;
                ty - r.top() + 2.0
            },
        );
        ui.allocate_exact_size(vec2(w + 16.0, y - top.y), egui::Sense::hover());
        for (k, z) in zones.take().into_iter().enumerate() {
            match z {
                Zone::Now(key, value) => set(asks, &key, &value),
                Zone::Play(what) => asks.push(crate::window::Ask::Play(what)),
                Zone::Ask(a) => asks.push(a),
                Zone::Set(rect, key, value) => {
                    if ui.interact(rect, ui.scope_id().with(("set", k)), egui::Sense::click()).clicked() {
                        set(asks, &key, &value);
                    }
                }
                Zone::Slider(rect, key, from, to, min, max) => {
                    let resp = ui.interact(rect, ui.scope_id().with(("slider", &key)), egui::Sense::click_and_drag());
                    if (resp.clicked() || resp.drag_stopped())
                        && let Some(pos) = resp.interact_pointer_pos()
                    {
                        let f = ((pos.x - from) / (to - from)).clamp(0.0, 1.0);
                        let v = min + (f * (max - min) as f32).round() as i32;
                        set(asks, &key, &v.to_string());
                    }
                }
            }
        }
    });
}
