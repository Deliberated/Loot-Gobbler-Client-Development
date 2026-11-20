//! The ping editors (Settings > Ping Lists), as dialogs over the window: Java's `CustomListGUI` (chat, item and
//! entity pings: a list of entries), `DungeonPingGUI` and `EnchantPingGUI` (search and tick), `PingSoundGUI` (a
//! sound and volume per kind of ping).

use crate::jtext;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use crate::window::Ask;
use egui::{Align2, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::properties::Properties;

const SEP: char = '\u{00A7}';

/// A dialog open over the window.
pub enum Dialog {
    /// A list of entries saved under a key (separated by §); `ints`: only whole numbers.
    List { key: &'static str, title: &'static str, header: &'static str, ints: bool, rows: Vec<String> },
    /// Names ticked from a list, saved comma separated: dungeons (by name) or enchantments (by id, grouped).
    Pick {
        key: &'static str,
        title: &'static str,
        items: Vec<(String, String, String)>,
        ticked: Vec<bool>,
        search: String,
        open_groups: Vec<String>,
        dungeons: bool,
    },
    /// A sound and its volume per kind of ping.
    PingSounds { chosen: [String; 4] },
}

pub const PING_KINDS: [(&str, &str); 4] = [
    ("pingSound.enchant", "Enchant ping"),
    ("pingSound.dungeon", "Dungeon ping"),
    ("pingSound.item", "Item ping"),
    ("pingSound.entity", "Entity ID ping"),
];

/// The bundled sounds offered for pings (Java lists its sound folder, and Windows' notification sounds there).
pub fn ping_choices() -> Vec<String> {
    let mut v: Vec<String> = [
        "alien_arpeggio.wav",
        "alien_beacon.wav",
        "alien_saucer.wav",
        "alien_signal.wav",
        "alien_warp.wav",
        "amulet_activation.wav",
        "bluebag.wav",
        "custom.wav",
        "eggbag.wav",
        "goblin-cackle.wav",
        "goblin-gobble.wav",
        "goldbag.wav",
        "guild.wav",
        "keypop.wav",
        "orangebag.wav",
        "party.wav",
        "pm.wav",
        "redbag.wav",
        "trade.wav",
        "whitebag.wav",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    if let Ok(dir) = std::fs::read_dir("C:/Windows/Media") {
        let mut more: Vec<String> = dir
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("wav")))
            .map(|p| p.display().to_string())
            .collect();
        more.sort_by_key(|p| p.to_lowercase());
        v.extend(more);
    }
    v
}

impl Dialog {
    /// Java's `CustomListGUI`: the saved entries, or one empty row.
    pub fn list(props: &Properties, key: &'static str, title: &'static str, header: &'static str, ints: bool) -> Dialog {
        let mut rows: Vec<String> = props.get(key).unwrap_or("").split(SEP).filter(|s| !s.is_empty()).map(str::to_string).collect();
        if rows.is_empty() {
            rows.push(String::new());
        }
        Dialog::List { key, title, header, ints, rows }
    }

    /// Java `DungeonPingGUI`: every dungeon by name, the saved ones ticked.
    pub fn dungeons(props: &Properties, data: &lg_gamedata::GameData) -> Dialog {
        let saved: Vec<String> =
            props.get("dungeonPing.selected").unwrap_or("").split(',').map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty()).collect();
        let items: Vec<(String, String, String)> = data.dungeons.all_names().into_iter().map(|n| (n.clone(), n, String::new())).collect();
        let ticked = items.iter().map(|i| saved.contains(&i.0.to_lowercase())).collect();
        Dialog::Pick {
            key: "dungeonPing.selected",
            title: "Dungeon Pings",
            items,
            ticked,
            search: String::new(),
            open_groups: Vec::new(),
            dungeons: true,
        }
    }

    /// Java `EnchantPingGUI`: every enchantment by name, grouped by its first letter (the unique ones together),
    /// a group open when one of it is ticked.
    pub fn enchants(props: &Properties, data: &lg_gamedata::GameData) -> Dialog {
        let saved: Vec<i32> = props
            .get("enchantPing.selected")
            .unwrap_or("")
            .split(',')
            .filter_map(|p| p.trim().parse::<i16>().ok())
            .map(i32::from)
            .collect();
        let mut all: Vec<(i16, String, Option<String>)> = data
            .enchants
            .values()
            .map(|e| (e.id, e.name.clone().or(e.internal_id.clone()).unwrap_or_default(), e.internal_id.clone()))
            .collect();
        all.push((-1, "[empty]".into(), None));
        all.sort_by_key(|e| e.1.to_lowercase());
        let mut items = Vec::new();
        for (id, name, internal) in all {
            let group = if internal.as_ref().is_some_and(|i| !i.is_empty() && *i == i.to_uppercase()) {
                "Unique".to_string()
            } else {
                match name.chars().next() {
                    Some(c) if c.is_alphabetic() => c.to_uppercase().to_string(),
                    _ => "#".into(),
                }
            };
            items.push((id.to_string(), name.replace('_', " ").trim().to_string(), group));
        }
        // groups in name order (Java's TreeMap), the entries in each in name order
        items.sort_by(|a, b| a.2.cmp(&b.2));
        let ticked: Vec<bool> = items.iter().map(|i| i.0.parse::<i32>().is_ok_and(|id| saved.contains(&id))).collect();
        let mut open_groups: Vec<String> = Vec::new();
        for (i, it) in items.iter().enumerate() {
            if ticked[i] && !open_groups.contains(&it.2) {
                open_groups.push(it.2.clone());
            }
        }
        Dialog::Pick {
            key: "enchantPing.selected",
            title: "Enchant Pings",
            items,
            ticked,
            search: String::new(),
            open_groups,
            dungeons: false,
        }
    }

    pub fn ping_sounds(props: &Properties) -> Dialog {
        let chosen = PING_KINDS
            .map(|(k, _)| props.get(k).map(str::trim).filter(|s| !s.is_empty()).map_or_else(|| "custom.wav".to_string(), str::to_string));
        Dialog::PingSounds { chosen }
    }
}

fn message(title: &str, text: &str) -> Ask {
    Ask::Confirm { title: title.into(), text: text.into(), yes: "OK".into(), no: String::new(), then: Box::new(Ask::Nothing) }
}

/// The dialog over the window this frame; gives whether it stays open.
pub fn show(ctx: &egui::Context, p: &Palette, props: &Properties, d: &mut Dialog, asks: &mut Vec<Ask>) -> bool {
    let mut open = true;
    let title = match d {
        Dialog::List { title, .. } | Dialog::Pick { title, .. } => title.to_string(),
        Dialog::PingSounds { .. } => "Ping Sounds".into(),
    };
    let size = match d {
        Dialog::List { .. } => vec2(520.0, 420.0),
        Dialog::Pick { .. } => vec2(420.0, 700.0),
        Dialog::PingSounds { .. } => vec2(900.0, 330.0),
    };
    let modal = egui::Modal::new(egui::Id::unique("lg-dialog")).frame(egui::Frame::NONE).show(ctx, |ui| {
        let (r, _) = ui.allocate_exact_size(size, Sense::hover());
        let painter = ui.painter().clone();
        painter.rect(r, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
        let head = Rect::from_min_size(r.min, vec2(r.width(), 34.0));
        painter.rect_filled(head, 0.0, p.title);
        jtext::draw(&painter, pos2(head.left() + 12.0, head.center().y), Align2::LEFT_CENTER, &title, &text(18.0), p.title_fg);
        let close = Rect::from_center_size(pos2(head.right() - 17.0, head.center().y), vec2(24.0, 24.0));
        if ui.interact(close, ui.scope_id().with("dialog-x"), Sense::click()).clicked() {
            open = false;
        }
        jtext::draw(&painter, close.center(), Align2::CENTER_CENTER, "x", &text(20.0), p.title_fg);
        let body = Rect::from_min_max(pos2(r.left() + 10.0, head.bottom() + 10.0), r.max - vec2(10.0, 10.0));
        match d {
            Dialog::List { key, header, ints, rows, .. } => {
                if list(ui, p, body, header, rows) {
                    // Java's Save: the entries trimmed, no empty ones, no doubles
                    let bad = rows.iter().map(|s| s.trim()).find(|s| !s.is_empty() && *ints && s.parse::<i32>().is_err());
                    if bad.is_some() {
                        asks.push(message("Validation error", "Invalid Entity ID! Please enter a valid integer."));
                    } else {
                        let mut out: Vec<&str> = Vec::new();
                        for s in rows.iter().map(|s| s.trim()).filter(|s| !s.is_empty()) {
                            if !out.contains(&s) {
                                out.push(s);
                            }
                        }
                        asks.push(Ask::Set(key.to_string(), out.join(&SEP.to_string())));
                        open = false;
                    }
                }
            }
            Dialog::Pick { key, items, ticked, search, open_groups, dungeons, .. } => {
                if let Some(n) = pick(ui, p, body, items, ticked, search, open_groups, *dungeons) {
                    let chosen: Vec<&str> = items.iter().zip(ticked.iter()).filter(|(_, t)| **t).map(|(i, _)| i.0.as_str()).collect();
                    asks.push(Ask::Set(key.to_string(), chosen.join(",")));
                    asks.push(message("Save", &if *dungeons { format!("Saved {n} dungeon(s).") } else { format!("Saved {n} items.") }));
                }
            }
            Dialog::PingSounds { chosen } => ping_sounds(ui, p, props, body, chosen, asks),
        }
    });
    if modal.should_close() {
        open = false;
    }
    open
}

/// The list editor: a row per entry (its text, "-" to remove it), "+" adds one, Save; gives whether Save was hit.
fn list(ui: &mut Ui, p: &Palette, body: Rect, header: &str, rows: &mut Vec<String>) -> bool {
    let painter = ui.painter().clone();
    let hh = 30.0;
    let hr = Rect::from_min_size(body.min, vec2(body.width(), hh));
    painter.rect_filled(hr, 0.0, p.header);
    jtext::draw(&painter, pos2(hr.center().x - 30.0, hr.center().y), Align2::CENTER_CENTER, header, &small(), p.ink_soft);
    let list_r = Rect::from_min_max(pos2(body.left(), hr.bottom()), pos2(body.right(), body.bottom() - 48.0));
    let mut remove = None;
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(list_r));
    egui::ScrollArea::vertical().id_salt("dialog-list").show(&mut child, |ui| {
        let top = ui.cursor().min;
        let w = ui.available_width();
        for (i, row) in rows.iter_mut().enumerate() {
            let y = top.y + i as f32 * 28.0;
            let field = Rect::from_min_size(pos2(top.x, y), vec2(w - 60.0, 28.0));
            widgets::search_field(ui, p, field, row, header, &format!("dialog-row-{i}"));
            let br = Rect::from_min_size(pos2(field.right(), y), vec2(60.0, 28.0));
            if crate::inventory::button(ui, p, br, "-", false, &format!("dialog-remove-{i}")) {
                remove = Some(i);
            }
        }
        ui.allocate_exact_size(vec2(w, rows.len() as f32 * 28.0), Sense::hover());
    });
    if let Some(i) = remove {
        rows.remove(i);
        if rows.is_empty() {
            rows.push(String::new());
        }
    }
    let by = body.bottom() - 36.0;
    if crate::inventory::button(ui, p, Rect::from_min_size(pos2(body.left(), by), vec2(60.0, 36.0)), "+", false, "dialog-add") {
        rows.push(String::new());
    }
    crate::inventory::button(ui, p, Rect::from_min_size(pos2(body.right() - 108.0, by), vec2(108.0, 36.0)), "Save", false, "dialog-save")
}

/// The picker: search, the ticks (grouped for enchantments), Select All, Clear All, Save; gives the number saved.
#[allow(clippy::too_many_arguments)]
fn pick(
    ui: &mut Ui,
    p: &Palette,
    body: Rect,
    items: &[(String, String, String)],
    ticked: &mut [bool],
    search: &mut String,
    open_groups: &mut Vec<String>,
    dungeons: bool,
) -> Option<usize> {
    let painter = ui.painter().clone();
    let f = text(18.0);
    let lr = jtext::draw(&painter, pos2(body.left(), body.top() + 15.0), Align2::LEFT_CENTER, "Search: ", &f, p.ink);
    widgets::search_field(
        ui,
        p,
        Rect::from_min_max(pos2(lr.right(), body.top()), pos2(body.right(), body.top() + 30.0)),
        search,
        "Search",
        "dialog-search",
    );
    let q = search.trim().to_lowercase();
    let list_r = Rect::from_min_max(pos2(body.left(), body.top() + 38.0), pos2(body.right(), body.bottom() - 48.0));
    painter.rect_stroke(list_r, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(list_r.shrink(4.0)));
    let mut toggle_group: Option<String> = None;
    let mut group_all: Option<(String, bool)> = None;
    egui::ScrollArea::vertical().id_salt("dialog-pick").show(&mut child, |ui| {
        let top = ui.cursor().min;
        let w = ui.available_width();
        let mut y = top.y;
        let mut group = String::new();
        for (i, it) in items.iter().enumerate() {
            if !q.is_empty() && !it.1.to_lowercase().contains(&q) {
                continue;
            }
            if !dungeons && it.2 != group {
                // a group's head: open or closed, All and Clear at its right
                group = it.2.clone();
                let open = open_groups.contains(&group) || !q.is_empty();
                let hr = Rect::from_min_size(pos2(top.x, y), vec2(w, 30.0));
                let tr = jtext::draw(
                    ui.painter(),
                    pos2(hr.left() + 4.0, hr.center().y),
                    Align2::LEFT_CENTER,
                    &format!("{} {group}", if open { "\u{25BC}" } else { "\u{25B6}" }),
                    &f,
                    p.ink,
                );
                if ui.interact(tr, ui.scope_id().with(("group", &group)), Sense::click()).clicked() {
                    toggle_group = Some(group.clone());
                }
                let cr = Rect::from_min_size(pos2(hr.right() - 70.0, hr.top() + 2.0), vec2(70.0, 26.0));
                let ar = Rect::from_min_size(pos2(cr.left() - 5.0 - 56.0, hr.top() + 2.0), vec2(56.0, 26.0));
                if crate::inventory::button(ui, p, ar, "All", false, &format!("group-all-{group}")) {
                    group_all = Some((group.clone(), true));
                }
                if crate::inventory::button(ui, p, cr, "Clear", false, &format!("group-clear-{group}")) {
                    group_all = Some((group.clone(), false));
                }
                y += 32.0;
            }
            if !dungeons && !(open_groups.contains(&it.2) || !q.is_empty()) {
                continue;
            }
            let x = top.x + if dungeons { 4.0 } else { 20.0 };
            let cy = y + 13.0;
            let b = crate::social::pixel_check(ui.painter(), p, pos2(x, cy - 9.5), ticked[i]);
            let t = jtext::draw(ui.painter(), pos2(b.right() + 6.0, cy), Align2::LEFT_CENTER, &it.1, &f, p.ink);
            let resp = ui.interact(b.union(t), ui.scope_id().with(("tick", i)), Sense::click());
            if !dungeons {
                let internal = it.0.clone();
                resp.clone().on_hover_text(format!("id {internal}"));
            }
            if resp.clicked() {
                ticked[i] = !ticked[i];
            }
            y += 26.0;
        }
        ui.allocate_exact_size(vec2(w, y - top.y), Sense::hover());
    });
    if let Some(g) = toggle_group {
        match open_groups.iter().position(|x| *x == g) {
            Some(i) => {
                open_groups.remove(i);
            }
            None => open_groups.push(g),
        }
    }
    if let Some((g, on)) = group_all {
        for (i, it) in items.iter().enumerate() {
            if it.2 == g {
                ticked[i] = on;
            }
        }
    }
    let by = body.bottom() - 36.0;
    let n = ticked.iter().filter(|t| **t).count();
    if dungeons {
        jtext::draw(
            &painter,
            pos2(body.left() + 4.0, by + 18.0),
            Align2::LEFT_CENTER,
            &format!("{n} of {} selected", items.len()),
            &f,
            p.ink,
        );
    }
    let bw = |t: &str| jtext::width(&painter, &t.to_uppercase(), &small()) + 24.0;
    let save = Rect::from_min_size(pos2(body.right() - bw("Save"), by), vec2(bw("Save"), 36.0));
    let clear = Rect::from_min_size(pos2(save.left() - 6.0 - bw("Clear All"), by), vec2(bw("Clear All"), 36.0));
    let all = Rect::from_min_size(pos2(clear.left() - 6.0 - bw("Select All"), by), vec2(bw("Select All"), 36.0));
    // Java: dungeons tick only the ones shown by the search; enchantments all
    let shown = |it: &(String, String, String)| !dungeons || q.is_empty() || it.1.to_lowercase().contains(&q);
    if crate::inventory::button(ui, p, all, "Select All", false, "pick-all") {
        for (i, it) in items.iter().enumerate() {
            if shown(it) {
                ticked[i] = true;
            }
        }
    }
    if crate::inventory::button(ui, p, clear, "Clear All", false, "pick-clear") {
        for (i, it) in items.iter().enumerate() {
            if shown(it) {
                ticked[i] = false;
            }
        }
    }
    crate::inventory::button(ui, p, save, "Save", false, "pick-save").then_some(n)
}

/// Java `PingSoundGUI`: per kind its sound (a list to pick from, Browse... for any WAV), Test, its volume;
/// Restore Defaults and Save.
fn ping_sounds(ui: &mut Ui, p: &Palette, props: &Properties, body: Rect, chosen: &mut [String; 4], asks: &mut Vec<Ask>) {
    let painter = ui.painter().clone();
    let f = text(18.0);
    jtext::draw(
        &painter,
        body.min,
        Align2::LEFT_TOP,
        "Sounds are taken from Loot Gobbler's sound folder and the Windows notification library (C:\\Windows\\Media).",
        &f,
        fixed::MUTED,
    );
    jtext::draw(&painter, body.min + vec2(0.0, 22.0), Align2::LEFT_TOP, "Use Browse to pick any other .wav file.", &f, fixed::MUTED);
    let choices = ping_choices();
    let mut y = body.top() + 60.0;
    for (k, (key, label)) in PING_KINDS.iter().enumerate() {
        let cy = y + 18.0;
        jtext::draw(&painter, pos2(body.left() + 4.0, cy), Align2::LEFT_CENTER, &format!("{label}:"), &f, p.ink);
        // the sound: a drop-down of the choices
        let shown = std::path::Path::new(&chosen[k]).file_name().map_or(chosen[k].clone(), |n| n.to_string_lossy().into_owned());
        let cr = Rect::from_min_size(pos2(body.left() + 160.0, cy - 15.0), vec2(260.0, 30.0));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(cr));
        child.style_mut().visuals.widgets.inactive.weak_bg_fill = p.paper;
        child.style_mut().visuals.widgets.hovered.weak_bg_fill = p.header;
        child.style_mut().visuals.override_text_color = Some(p.ink);
        egui::ComboBox::from_id_salt(("ping-sound", k)).width(cr.width() - 8.0).selected_text(shown).show_ui(&mut child, |ui| {
            for c in &choices {
                let name = std::path::Path::new(c).file_name().map_or(c.clone(), |n| n.to_string_lossy().into_owned());
                ui.selectable_value(&mut chosen[k], c.clone(), name);
            }
        });
        let bx = cr.right() + 8.0;
        let br = Rect::from_min_size(pos2(bx, cy - 18.0), vec2(108.0, 36.0));
        if crate::inventory::button(ui, p, br, "Browse...", false, &format!("ping-browse-{k}")) {
            asks.push(Ask::Choose { key: key.to_string(), title: format!("Sound for the {}", label.to_lowercase()), folder: false });
        }
        let tr = Rect::from_min_size(pos2(br.right() + 6.0, cy - 18.0), vec2(108.0, 36.0));
        if crate::inventory::button(ui, p, tr, "Test", false, &format!("ping-test-{k}")) {
            asks.push(Ask::Set(key.to_string(), chosen[k].clone()));
            asks.push(Ask::Play(format!("ping:{key}")));
        }
        // its volume at the right
        let vol = props.get(&format!("{key}.volume")).and_then(|v| v.trim().parse::<i32>().ok()).map_or(100, |v| v.clamp(0, 100));
        let lw = jtext::width(&painter, "100%", &f) + 4.0;
        let lx = body.right() - lw;
        jtext::draw(&painter, pos2(lx, cy), Align2::LEFT_CENTER, &format!("{vol}%"), &f, p.ink);
        let sx = lx - 6.0 - 140.0;
        crate::settings::slider(&painter, p, sx, cy, 140.0, vol as f32 / 100.0);
        jtext::draw(&painter, pos2(sx - 6.0, cy), Align2::RIGHT_CENTER, "Volume", &f, p.ink);
        let resp = ui.interact(
            Rect::from_min_size(pos2(sx, cy - 15.0), vec2(140.0, 30.0)),
            ui.scope_id().with(("ping-vol", k)),
            Sense::click_and_drag(),
        );
        if (resp.clicked() || resp.drag_stopped())
            && let Some(pos) = resp.interact_pointer_pos()
        {
            let v = (((pos.x - sx - 15.0) / 110.0).clamp(0.0, 1.0) * 100.0).round() as i32;
            asks.push(Ask::Set(format!("{key}.volume"), v.to_string()));
            asks.push(Ask::Set(key.to_string(), chosen[k].clone()));
            asks.push(Ask::Play(format!("ping:{key}")));
        }
        y += 44.0;
    }
    let by = body.bottom() - 36.0;
    let save = Rect::from_min_size(pos2(body.right() - 108.0, by), vec2(108.0, 36.0));
    let def_w = jtext::width(&painter, "RESTORE DEFAULTS", &small()) + 32.0;
    let defaults = Rect::from_min_size(pos2(save.left() - 6.0 - def_w, by), vec2(def_w, 36.0));
    if crate::inventory::button(ui, p, defaults, "Restore Defaults", false, "ping-defaults") {
        for (k, (key, _)) in PING_KINDS.iter().enumerate() {
            asks.push(Ask::Set(key.to_string(), String::new()));
            asks.push(Ask::Set(format!("{key}.volume"), "100".into()));
            chosen[k] = "custom.wav".into();
        }
    }
    if crate::inventory::button(ui, p, save, "Save", false, "ping-save") {
        for (k, (key, _)) in PING_KINDS.iter().enumerate() {
            asks.push(Ask::Set(key.to_string(), chosen[k].clone()));
        }
        asks.push(message("Save", "Ping sounds saved."));
    }
}
