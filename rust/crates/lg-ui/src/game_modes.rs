//! The Game Modes tab (Java: `GameModesView`, `RuleChips`): the categories (PPE, UPE) on the left; on the right the
//! chosen one: for a UPE its variant, the guild version and no-pet switches, the rules at a glance and in full, and
//! your characters playing it, able to start a run, or that lost theirs.

use crate::jtext;
use crate::profile::{Ctx, num};
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, brighter, small, text, wrapped};
use egui::{Align2, Color32, Rect, Sense, Shape, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::upe::{self, CATEGORY_INFO, GUILD_RULES, NO_PET_RULES, OWN_RULES, PREREQUISITE, UPE_VARIANTS};

const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

pub struct GameModesView {
    pub category: String,
    pub variant: String,
    pub guild: bool,
    pub no_pet: bool,
}

impl Default for GameModesView {
    fn default() -> Self {
        GameModesView { category: "UPE".into(), variant: "UPE".into(), guild: false, no_pet: false }
    }
}

/// A rule tag (Java `RuleChips.Chip`): words, fill, edge, ink, allowed, and the shiny star.
struct Chip {
    text: &'static str,
    fill: Color32,
    edge: Color32,
    ink: Color32,
    allowed: bool,
    sparkle: bool,
}

fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

fn chip(text: &'static str, fill: u32, ink: Color32, allowed: bool) -> Chip {
    let f = rgb(fill);
    Chip { text, fill: f, edge: brighter(f), ink, allowed, sparkle: false }
}

/// What the loadout of a run in this mode may hold (Java `RuleChips.loadout`).
fn loadout_chips(mode: &str) -> Vec<Chip> {
    let (ut, st, tiered, divine) = (0x8E4FD0, 0xD9A21B, 0x5F6B7A, 0xF2C232);
    let w = Color32::WHITE;
    let st_ink = rgb(0x3A2604);
    let mut l = match upe::base(mode) {
        "PPE" => vec![chip("Tiered", tiered, w, true), chip("UT", ut, w, true), chip("ST", st, st_ink, true)],
        "PUPE" => vec![chip("UT", ut, w, true), chip("ST", st, st_ink, false), chip("Tiered", tiered, w, false)],
        "STPE" => vec![chip("ST", st, st_ink, true), chip("UT", ut, w, false), chip("Tiered", tiered, w, false)],
        "ShinyUPE" => vec![
            Chip { text: "UT", fill: rgb(0xFFC8E0), edge: w, ink: rgb(0x3A2350), allowed: true, sparkle: true },
            chip("ST", st, st_ink, false),
            chip("Tiered", tiered, w, false),
        ],
        "DivinePE" => vec![chip("Divine", divine, rgb(0x4A2E00), true), chip("Common to Legendary", tiered, w, false)],
        _ => vec![chip("UT", ut, w, true), chip("ST", st, st_ink, true), chip("Tiered", tiered, w, false)],
    };
    let darker =
        |c: Color32| Color32::from_rgb((f32::from(c.r()) * 0.7) as u8, (f32::from(c.g()) * 0.7) as u8, (f32::from(c.b()) * 0.7) as u8);
    let t0 = darker(rgb(tiered));
    l.push(Chip { text: "T0 start gear", fill: t0, edge: brighter(t0), ink: w, allowed: true, sparkle: false });
    l
}

/// Where what it equips may come from (Java `RuleChips.sources`).
fn source_chips(guild: bool) -> Vec<Chip> {
    let w = Color32::WHITE;
    vec![
        chip("Own drops", 0x3C9347, w, true),
        chip("Vault", 0x4D5A3A, w, false),
        if guild { chip("Guild trades", 0xB07D0A, w, true) } else { chip("Trades", 0x4D5A3A, w, false) },
        chip("Shop & rewards", 0x4D5A3A, w, false),
    ]
}

/// A row of tags after a caption (Java `RuleChips.row`); gives its height.
fn chip_row(painter: &egui::Painter, at: egui::Pos2, caption: &str, chips: &[Chip]) -> f32 {
    const H: f32 = 28.0;
    const PAD: f32 = 9.0;
    const MARK: f32 = 16.0;
    let f = text(16.0);
    let mut x = at.x + 6.0;
    let y = at.y + 2.0;
    jtext::draw(painter, pos2(x, y + (H + 2.0) / 2.0), Align2::LEFT_CENTER, caption, &f, fixed::MUTED);
    x += 90.0 + 6.0;
    for c in chips {
        let star = if c.sparkle { 14.0 + 4.0 } else { 0.0 };
        let mark = if c.allowed { 6.0 + MARK - 2.0 } else { 0.0 };
        let w = PAD + star + jtext::width(painter, c.text, &f) + mark + PAD;
        let plate = Rect::from_min_size(pos2(x + 0.5, y + 1.0), vec2(w - 1.0, H - 1.0));
        let fade = |col: Color32| if c.allowed { col } else { col.gamma_multiply(0.8) };
        painter.rect_filled(plate, 4.5, fade(c.fill));
        painter.rect_stroke(plate, 4.5, Stroke::new(1.0, fade(c.edge)), StrokeKind::Inside);
        let mut tx = x + PAD;
        if c.sparkle {
            let (cx, cy, r) = (x + PAD + 7.0, plate.center().y, 7.0);
            let k = r * 0.24;
            let pts = vec![
                pos2(cx, cy - r),
                pos2(cx + k, cy - k),
                pos2(cx + r, cy),
                pos2(cx + k, cy + k),
                pos2(cx, cy + r),
                pos2(cx - k, cy + k),
                pos2(cx - r, cy),
                pos2(cx - k, cy - k),
            ];
            painter.add(Shape::closed_line(pts.clone(), Stroke::new(1.4, rgb(0x4B2F8F))));
            painter.add(Shape::convex_polygon(vec![pts[0], pts[2], pts[4], pts[6]], Color32::WHITE, Stroke::NONE));
            tx += 14.0 + 4.0;
        }
        jtext::draw(painter, pos2(tx, plate.center().y), Align2::LEFT_CENTER, c.text, &f, fade(c.ink));
        if c.allowed {
            let (mx, my) = (x + w - 1.0 - PAD + 2.0 - MARK, y + 1.0 + (H - 1.0 - MARK) / 2.0);
            painter.circle_filled(pos2(mx + MARK / 2.0, my + MARK / 2.0), MARK / 2.0, rgb(0x2E8B3E));
            let s = Stroke::new(2.0, Color32::WHITE);
            painter.line_segment([pos2(mx + 4.0, my + 8.5), pos2(mx + 7.0, my + 11.5)], s);
            painter.line_segment([pos2(mx + 7.0, my + 11.5), pos2(mx + 12.0, my + 5.0)], s);
        } else {
            let (a, b) = (pos2(x + 3.0, y + H - 2.0), pos2(x + w - 4.0, y + 3.0));
            painter.line_segment([a, b], Stroke::new(4.6, rgb(0x5A1410)));
            painter.line_segment([a, b], Stroke::new(3.0, rgb(0xFF4A3D)));
        }
        x += w + 6.0;
    }
    H + 2.0 + 4.0
}

fn date(zone: &lg_app::localtime::Zone, t: i64) -> String {
    if t <= 0 {
        return "?".into();
    }
    let l = zone.local_date_time(t);
    format!("{} {} {}", l[8..10].trim_start_matches('0'), MONTHS[l[5..7].parse::<usize>().unwrap_or(1) - 1], &l[11..16])
}

impl GameModesView {
    fn mode(&self) -> String {
        upe::with_guild(if self.category == "UPE" { &self.variant } else { &self.category }, self.guild)
    }

    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx) {
        let p = *c.p;
        let app = c.app;
        let all = ui.max_rect().shrink(8.0);
        let mut runs: Vec<&upe::Run> = app.upe.runs.values().collect();
        runs.sort_by_key(|r| r.char_id);
        let alive = |id: i32| app.characters.get(id).is_some_and(|ch| ch.alive);
        // the categories on the left
        let list = Rect::from_min_size(all.min, vec2(290.0, all.height()));
        let mut picked = None;
        widgets::section(
            ui,
            &p,
            list,
            "Game Modes",
            fixed::GOLD,
            |ui, head| {
                // the plus: a new game mode run (the wizard comes with the window)
                let painter = ui.painter();
                let r = Rect::from_center_size(pos2(head.right() - 13.0, head.center().y), vec2(26.0, 26.0));
                painter.rect_filled(r.shrink(3.0), 2.0, fixed::GREEN);
                let s = Stroke::new(2.5, Color32::WHITE);
                painter.line_segment([pos2(r.center().x - 5.0, r.center().y), pos2(r.center().x + 5.0, r.center().y)], s);
                painter.line_segment([pos2(r.center().x, r.center().y - 5.0), pos2(r.center().x, r.center().y + 5.0)], s);
            },
            |ui, r| {
                let painter = ui.painter().clone();
                let mut y = r.top() + 8.0;
                for m in &CATEGORY_INFO {
                    let playing = runs
                        .iter()
                        .filter(|x| upe::category(&x.mode) == m.code && x.marked && x.broken.is_none() && alive(x.char_id))
                        .count();
                    let ch = jtext::height(&painter, &text(24.0)).max(jtext::height(&painter, &text(14.0)));
                    let h = 8.0 + 2.0 * ch + 8.0;
                    let er = Rect::from_min_size(pos2(r.left() + 8.0, y), vec2(r.width() - 16.0, h));
                    if m.code == self.category {
                        painter.rect_filled(er, 0.0, mix(p.gold, p.paper, 0.25));
                        painter.rect_stroke(er, 0.0, Stroke::new(2.0, p.gold), StrokeKind::Inside);
                    }
                    jtext::draw(
                        &painter,
                        pos2(er.left() + 10.0, er.top() + 8.0 + ch / 2.0),
                        Align2::LEFT_CENTER,
                        m.code,
                        &text(24.0),
                        p.gold,
                    );
                    jtext::draw(
                        &painter,
                        pos2(er.left() + 10.0, er.top() + 8.0 + ch * 1.5),
                        Align2::LEFT_CENTER,
                        m.name,
                        &text(14.0),
                        p.ink,
                    );
                    if playing > 0 {
                        jtext::draw(
                            &painter,
                            pos2(er.right() - 10.0, er.center().y),
                            Align2::RIGHT_CENTER,
                            &playing.to_string(),
                            &small(),
                            fixed::GREEN,
                        );
                    }
                    if ui.interact(er, ui.scope_id().with(("mode", m.code)), Sense::click()).clicked() {
                        picked = Some(m.code.to_string());
                    }
                    y += h + 6.0;
                }
            },
        );
        if let Some(m) = picked {
            self.category = m;
        }
        // the chosen mode on the right
        let mode = self.mode();
        let info = upe::info(&mode);
        let title = format!(
            "{} · {}{}{}",
            upe::label(&mode),
            if self.guild { "Guild " } else { "" },
            info.name,
            if self.no_pet { " · no pet" } else { "" }
        );
        let detail = Rect::from_min_max(pos2(list.right() + 8.0, all.top()), all.max);
        let cat_summary = CATEGORY_INFO.iter().find(|m| m.code == self.category).map_or(CATEGORY_INFO[1].summary, |m| m.summary);
        let mut variant = None;
        let mut toggles = (self.guild, self.no_pet);
        let icons = &mut *c.icons;
        let category = self.category.clone();
        let cur_variant = self.variant.clone();
        widgets::section(
            ui,
            &p,
            detail,
            &title,
            fixed::PURPLE,
            |_, _| {},
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("game-modes").show(ui, |ui| {
                let painter = ui.painter().clone();
                let top = ui.cursor().min;
                let x = top.x + 16.0;
                let mut y = top.y + 12.0;
                let w = 640.0f32.min(r.width() - 32.0 - 12.0);
                y += wrapped(&painter, pos2(x, y), cat_summary, &text(18.0), w, p.ink);
                let heading = |painter: &egui::Painter, y: &mut f32, t: &str| {
                    let r = jtext::draw(painter, pos2(x, *y), Align2::LEFT_TOP, &t.to_uppercase(), &text(18.0), p.gold);
                    *y = r.bottom();
                };
                if category == "UPE" {
                    y += 12.0;
                    heading(&painter, &mut y, "Variant");
                    y += 4.0;
                    let names: Vec<String> = UPE_VARIANTS.iter().map(|v| upe::label(v)).collect();
                    let refs: Vec<&str> = names.iter().map(String::as_str).collect();
                    let sel = UPE_VARIANTS.iter().position(|v| *v == cur_variant).unwrap_or(0);
                    if let Some(i) = widgets::segmented(ui, &p, pos2(x + 6.0, y), 36.0, &refs, sel, false, &[]) {
                        variant = Some(UPE_VARIANTS[i].to_string());
                    }
                    y += 36.0;
                }
                y += 10.0;
                for (k, label) in [format!("Guild version ({}): trade with Loot Gobbler guild mates on a guild run", upe::label(&upe::with_guild(&mode, true))), "No pet (NPE): the whole run without a pet".to_string()].iter().enumerate() {
                    let on = if k == 0 { toggles.0 } else { toggles.1 };
                    let lh = jtext::height(&painter, &text(15.0)).max(20.0);
                    let bx = Rect::from_min_size(pos2(x + 2.0, y + (lh - 15.0) / 2.0), vec2(15.0, 15.0));
                    painter.rect(bx, 0.0, if on { p.check } else { p.paper }, Stroke::new(1.0, p.line), StrokeKind::Inside);
                    if on {
                        painter.line_segment([pos2(bx.left() + 3.0, bx.center().y), pos2(bx.center().x - 1.0, bx.bottom() - 4.0)], Stroke::new(2.0, p.ink));
                        painter.line_segment([pos2(bx.center().x - 1.0, bx.bottom() - 4.0), pos2(bx.right() - 3.0, bx.top() + 3.0)], Stroke::new(2.0, p.ink));
                    }
                    let tr = jtext::draw(&painter, pos2(x + 24.0, y + lh / 2.0), Align2::LEFT_CENTER, label, &text(15.0), p.ink);
                    if ui.interact(Rect::from_min_max(bx.min, tr.max), ui.scope_id().with(("toggle", k)), Sense::click()).clicked() {
                        if k == 0 {
                            toggles.0 = !toggles.0;
                        } else {
                            toggles.1 = !toggles.1;
                        }
                    }
                    y += lh;
                }
                y += 8.0;
                y += chip_row(&painter, pos2(x, y), "Loadout", &loadout_chips(&mode));
                y += chip_row(&painter, pos2(x, y), "From", &source_chips(toggles.0));
                // the rules in full
                y += 8.0;
                y += wrapped(&painter, pos2(x, y), info.summary, &text(15.0), w, fixed::MUTED);
                y += 12.0;
                heading(&painter, &mut y, "Before a run starts");
                y += wrapped(&painter, pos2(x, y), &format!("•  {PREREQUISITE}"), &text(15.0), w, p.ink);
                if self.no_pet {
                    y += wrapped(&painter, pos2(x, y), "•  It has no pet: the character list shows none following it.", &text(15.0), w, p.ink);
                }
                y += 12.0;
                heading(&painter, &mut y, "Rules");
                for rule in std::iter::once(info.first_rule).chain(OWN_RULES) {
                    y += wrapped(&painter, pos2(x, y), &format!("•  {rule}"), &text(15.0), w, p.ink);
                }
                if upe::guild(&mode) {
                    y += 8.0;
                    heading(&painter, &mut y, "Guild version");
                    for rule in GUILD_RULES {
                        y += wrapped(&painter, pos2(x, y), &format!("•  {rule}"), &text(15.0), w, p.ink);
                    }
                }
                if self.no_pet {
                    y += 8.0;
                    heading(&painter, &mut y, "No pet");
                    for rule in NO_PET_RULES {
                        y += wrapped(&painter, pos2(x, y), &format!("•  {rule}"), &text(15.0), w, p.ink);
                    }
                }
                // the characters: playing, could start, lost
                let mine: Vec<&&upe::Run> = runs.iter().filter(|x| upe::category(&x.mode) == category && app.characters.get(x.char_id).is_some()).collect();
                let playing: Vec<&upe::Run> = mine.iter().filter(|x| x.marked && x.broken.is_none() && alive(x.char_id)).map(|x| **x).collect();
                let could: Vec<&upe::Run> = runs.iter().filter(|x| app.characters.get(x.char_id).is_some() && x.potential() && alive(x.char_id)).copied().collect();
                let lost: Vec<&upe::Run> = mine.iter().filter(|x| x.marked && (x.broken.is_some() || !alive(x.char_id))).map(|x| **x).collect();
                let card_w = r.width() - 32.0 - 12.0;
                for (title, group, empty) in [
                    ("Playing", &playing, Some(format!("No character is playing a {category} run."))),
                    ("Could start", &could, Some("Create a character with the sniffer running and upgrades disabled: it can start a run until it breaks a rule.".to_string())),
                    ("Lost", &lost, None),
                ] {
                    if title == "Lost" && group.is_empty() {
                        continue;
                    }
                    y += 18.0;
                    heading(&painter, &mut y, &format!("{title}{}", if group.is_empty() { String::new() } else { format!("  ({})", group.len()) }));
                    y += 6.0;
                    if group.is_empty()
                        && let Some(e) = &empty
                    {
                        y += wrapped(&painter, pos2(x, y), e, &text(15.0), w, fixed::MUTED);
                    }
                    for run in group.iter() {
                        let ch = app.characters.get(run.char_id).expect("checked");
                        y += card(ui, &p, app, icons, run, ch, &mode, toggles.1, Rect::from_min_size(pos2(x, y), vec2(card_w, 0.0))) + 6.0;
                    }
                }
                ui.allocate_exact_size(vec2(r.width() - 12.0, y + 16.0 - top.y), Sense::hover());
            });
            },
        );
        if let Some(v) = variant {
            self.variant = v;
        }
        self.guild = toggles.0;
        self.no_pet = toggles.1;
    }
}

/// One character in a mode (Java `card`): its look, where it stands, what can be done with it; gives its height.
fn card(
    ui: &Ui,
    p: &Palette,
    app: &lg_app::App,
    icons: &mut crate::icons::Icons,
    r: &upe::Run,
    c: &lg_app::characters::Char,
    mode: &str,
    no_pet: bool,
    at: Rect,
) -> f32 {
    let painter = ui.painter().clone();
    let data = app.world.data();
    let zone = &app.zone;
    let lvl = if c.level < 20 { format!("Level {}", c.level.max(1)) } else { format!("{} Fame", num(c.fame)) };
    let name = format!(
        "{}   {lvl}{}",
        c.class_name,
        if r.marked { format!("   ·   {}{}", upe::label(&r.mode), if r.no_pet { " · no pet" } else { "" }) } else { String::new() }
    );
    let status = if !c.alive {
        format!("Died{}", r.broken.as_ref().map_or(String::new(), |b| format!(" (the run was lost before: {b})")))
    } else if let Some(b) = &r.broken {
        format!("Lost {}: {b}", date(zone, r.broken_at))
    } else if r.marked {
        format!("Playing since {}", date(zone, r.marked_at))
    } else {
        "Seen from its creation, nothing against the rules yet".to_string()
    };
    let n = r.gathered.len();
    let mut more = format!("{n} item{} gathered", if n == 1 { "" } else { "s" });
    if !r.allowed.is_empty() {
        more += &format!(
            "  ·  {} quest/mission reward{} it may take from the gift chest",
            r.allowed.len(),
            if r.allowed.len() == 1 { "" } else { "s" }
        );
    }
    // the buttons, right to left at the card's right edge
    use crate::window::Ask;
    let id = r.char_id;
    // each button: its label, primary, enabled, what it does (an ask, or a page to open)
    let mut buttons: Vec<(String, bool, bool, Result<Ask, String>)> = Vec::new();
    if c.alive && r.potential() {
        let ask = Ask::UpeMark { char_id: id, on: true, mode: Some(mode.to_string()), no_pet: Some(no_pet) };
        buttons.push((
            format!("Start {}{}", upe::label(mode), if no_pet { " without pet" } else { "" }),
            true,
            r.can_start(mode, no_pet),
            Ok(ask),
        ));
    }
    if c.alive && r.marked && r.broken.is_none() {
        let on = upe::guild(&r.mode);
        let other = upe::with_guild(&r.mode, !on);
        let label = format!("{}{}", if on { "Guild off: " } else { "Guild on: " }, upe::label(&other));
        buttons.push((label, false, !(on && r.guild_locked), Ok(Ask::UpeMode(id, other))));
    }
    if c.alive && r.marked && r.broken.is_none() && r.no_pet {
        buttons.push(("Allow a pet".into(), false, true, Ok(Ask::UpeNoPet(id, false))));
    }
    if c.alive && r.marked && r.broken.is_none() {
        buttons.push(("End run".into(), false, true, Ok(Ask::UpeMark { char_id: id, on: false, mode: None, no_pet: None })));
    }
    if r.marked {
        buttons.push(("Finds".into(), false, true, Err(format!("Inventory:upe:{id}"))));
    }
    buttons.push(("Open".into(), false, true, Err(format!("Characters:{id}"))));
    let lines = 3 + r.notes.len();
    let text_h = jtext::height(&painter, &text(22.0))
        + jtext::height(&painter, &text(15.0))
        + 4.0
        + (lines - 2) as f32 * (jtext::height(&painter, &text(13.0)) + 4.0);
    let h = 8.0 + text_h.max(56.0) + 8.0;
    let rect = Rect::from_min_size(at.min, vec2(at.width(), h));
    painter.rect_filled(rect, 0.0, p.paper);
    painter.rect_stroke(rect, 0.0, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let skin = if c.skin > 0 { c.skin } else { c.class_type };
    if let Some(t) = icons.skin(ui.ctx(), data, skin, c.tex1, c.tex2, 56) {
        painter.image(
            t.id(),
            Rect::from_min_size(pos2(rect.left() + 10.0, rect.center().y - 28.0), vec2(56.0, 56.0)),
            crate::detail::uv(),
            if c.alive { Color32::WHITE } else { Color32::from_white_alpha(90) },
        );
    }
    let tx = rect.left() + 10.0 + 56.0 + 14.0;
    let mut ty = rect.top() + 8.0 + (h - 16.0 - text_h) / 2.0;
    let t = jtext::draw(&painter, pos2(tx, ty), Align2::LEFT_TOP, &name, &text(22.0), p.ink);
    ty = t.bottom();
    let t = jtext::draw(
        &painter,
        pos2(tx, ty + 2.0),
        Align2::LEFT_TOP,
        &status,
        &text(15.0),
        if r.broken.is_some() || !c.alive { fixed::MUTED } else { p.ink },
    );
    ty = t.bottom() + 2.0;
    for line in std::iter::once(more).chain(r.notes.iter().map(|n| format!("Worth a look: {n}"))) {
        let t = jtext::draw(&painter, pos2(tx, ty + 2.0), Align2::LEFT_TOP, &line, &text(13.0), fixed::MUTED);
        ty = t.bottom() + 2.0;
    }
    let mut bx = rect.right() - 10.0;
    for (i, (label, primary, enabled, action)) in buttons.iter().enumerate().rev() {
        // FlatLaf buttons are at least 72 px wide, scaled
        let bw = (jtext::width(&painter, &label.to_uppercase(), &small()) + 32.0).max(108.0);
        bx -= bw;
        let br = Rect::from_min_size(pos2(bx, rect.center().y - 18.0), vec2(bw, 36.0));
        let clicked = if !*enabled {
            // greyed out, as a disabled button
            painter.rect(br, 0.0, p.header, Stroke::new(1.5, p.line), StrokeKind::Inside);
            jtext::draw(&painter, br.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &small(), p.ink_muted);
            false
        } else if *primary {
            painter.rect(br, 0.0, p.gold, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
            jtext::draw(&painter, br.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &small(), p.on_accent);
            ui.interact(br, ui.scope_id().with(("mode-start", r.char_id, i)), egui::Sense::click()).clicked()
        } else {
            crate::inventory::button(ui, p, br, label, false, &format!("mode-{}-{i}", r.char_id))
        };
        if clicked {
            match action {
                Ok(ask) => crate::window::ask_later(ui.ctx(), ask.clone()),
                Err(spec) => crate::window::open_later(ui.ctx(), spec.clone()),
            }
        }
        bx -= 6.0;
    }
    h
}
