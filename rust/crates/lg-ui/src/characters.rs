//! The Characters tab (Java: `CharactersView`): pages Characters, Classes, Pets; the list of your characters as
//! cards (favourites, recently played, not played) beside the selected one's details or everyone's fame gain.

use crate::charts;
use crate::icons::Icons;
use crate::jtext;
use crate::profile::{Ctx, ago, num};
use crate::theme::{Palette, fixed, mix};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::characters::{Char, Characters as Store};

const HOUR: i64 = 3_600_000;
const RANGE_LABELS: [&str; 5] = ["1H", "1D", "1W", "1M", "All"];
const RANGES: [i64; 5] = [HOUR, 24 * HOUR, 168 * HOUR, 720 * HOUR, i64::MAX];
const TOTAL: i32 = 0;
const SEASONAL_BG: Color32 = Color32::from_rgb(0x15, 0xDC, 0xA6);
const SEASONAL_TEXT: Color32 = Color32::from_rgb(0x2B, 0xD8, 0xB0);
const CRUCIBLE_BG: Color32 = Color32::from_rgb(0xD8, 0x30, 0x2C);
const UPE_BG: Color32 = Color32::from_rgb(0x9B, 0x59, 0xD0);
const UPE_LOST_BG: Color32 = Color32::from_rgb(0x6E, 0x6E, 0x6E);
const STATS_MAXED: Color32 = Color32::from_rgb(0xFF, 0xD2, 0x1F);
const STATS_OPEN: Color32 = Color32::from_rgb(0xE6, 0xE2, 0xD8);
const LOOT_ON: Color32 = Color32::from_rgb(0x8C, 0xE0, 0x7A);
const PORTRAIT: f32 = 84.0;
const SLOT: f32 = 54.0;
const QUICK_SLOT: f32 = 34.0;
const BACKPACK_ITEM: i32 = 3180;
const EXTENDER_ITEM: i32 = 65280;
const LOOT_DROP_BOOST: f64 = 50.0;

pub struct CharactersView {
    pub page: usize,
    pub range: usize,
    pub graveyard: bool,
    pub selected: i32,
    grave: Option<egui::TextureHandle>,
    rarity: [Option<egui::TextureHandle>; 5],
    /// The last suggestion worked out: for which character and settings, its picks and numbers.
    suggestion: Option<(String, [lg_app::loadout::Pick; 4], lg_app::loadout::Summary)>,
    pets: crate::pets::PetsView,
    /// The line beside REFRESH (Java's status label).
    pub refresh_status: String,
}

impl Default for CharactersView {
    fn default() -> Self {
        CharactersView {
            page: 0,
            range: 1,
            graveyard: false,
            selected: TOTAL,
            grave: None,
            rarity: Default::default(),
            suggestion: None,
            pets: Default::default(),
            refresh_status: String::new(),
        }
    }
}

fn png_texture(ctx: &egui::Context, name: &str, bytes: &[u8]) -> Option<egui::TextureHandle> {
    let dec = png_decode(bytes)?;
    Some(ctx.load_texture(
        name,
        egui::ColorImage::from_rgba_unmultiplied([dec.0 as usize, dec.1 as usize], &dec.2),
        egui::TextureOptions::NEAREST,
    ))
}

fn png_decode(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let im = image::load_from_memory_with_format(bytes, image::ImageFormat::Png).ok()?.to_rgba8();
    Some((im.width(), im.height(), im.into_raw()))
}

/// Java `LootBonus.parts`/`total`: enchantments of the worn gear, crucible, blood ritual, a running loot drop boost.
pub fn loot_bonus(c: &Char, data: &lg_gamedata::GameData, now: i64) -> f64 {
    let mut total = 0.0;
    if let Some(ids) = &c.enchant_ids {
        for slot in ids.iter().take(4) {
            for id in slot {
                if let Ok(id) = i16::try_from(*id)
                    && id >= 0
                    && let Some(e) = data.enchants.get(&id)
                {
                    total += f64::from(e.effect.loot_bonus);
                }
            }
        }
    }
    if c.crucible {
        total += c.crucible_loot.map_or(0.0, |m| (m - 1.0) * 100.0);
    }
    if c.ritual {
        total += c.ritual_loot.map_or(0.0, |m| (m - 1.0) * 100.0);
    }
    if c.loot_drop_until > now {
        total += LOOT_DROP_BOOST;
    }
    total
}

/// Java's `DecimalFormat("0.#")`.
fn percent(v: f64) -> String {
    let r = (v * 10.0).round() / 10.0;
    if r.fract() == 0.0 { format!("{}", r as i64) } else { format!("{r:.1}") }
}

/// Java `enchantIds`: a slot's enchantments, or as many unknown ones as were counted before ids were kept.
fn enchant_count(c: &Char, slot: usize) -> usize {
    if let Some(ids) = c.enchant_ids.as_ref().and_then(|v| v.get(slot)) {
        return ids.len();
    }
    c.enchants.as_ref().and_then(|v| v.get(slot)).map_or(0, |n| usize::try_from(*n).unwrap_or(0))
}

impl CharactersView {
    /// The rarity marker sprites, for other views.
    pub fn rarity(&mut self, ctx: &egui::Context) -> crate::detail::Rarity {
        self.ensure_textures(ctx);
        self.rarity.clone()
    }

    fn ensure_textures(&mut self, ctx: &egui::Context) {
        if self.grave.is_none() {
            self.grave = png_texture(ctx, "gravestone", include_bytes!("../../../../src/main/resources/icons/gravestone.png"));
            let r: [&[u8]; 4] = [
                include_bytes!("../../../../src/main/resources/rarity/uncommon.png"),
                include_bytes!("../../../../src/main/resources/rarity/rare.png"),
                include_bytes!("../../../../src/main/resources/rarity/legendary.png"),
                include_bytes!("../../../../src/main/resources/rarity/divine.png"),
            ];
            for (i, b) in r.iter().enumerate() {
                self.rarity[i + 1] = png_texture(ctx, &format!("rarity-{i}"), b);
            }
        }
    }

    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx) {
        self.ensure_textures(ui.ctx());
        let p = *c.p;
        let all = ui.max_rect().shrink(8.0);
        let painter = ui.painter().clone();
        // the top bar: Characters / Classes / Pets, REFRESH at the right
        if let Some(i) =
            widgets::segmented(ui, &p, pos2(all.left() + 7.0, all.top()), 36.0, &["Characters", "Classes", "Pets"], self.page, false, &[])
        {
            self.page = i;
        }
        let refresh_w = jtext::width(&painter, "REFRESH", &small()) + 32.0;
        let rr = Rect::from_min_size(pos2(all.right() - refresh_w - 8.0, all.top()), vec2(refresh_w, 36.0));
        let resp = ui.interact(rr, ui.scope_id().with("refresh"), Sense::click());
        let hover = resp.hovered();
        painter.rect(rr, 0.0, if hover { p.sand } else { p.header }, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
        jtext::draw(&painter, rr.center(), Align2::CENTER_CENTER, "REFRESH", &small(), p.ink);
        if resp.on_hover_text("Fetch all your characters from the game servers").clicked() {
            self.refresh_status = "Fetching...".into();
            c.asks.push(crate::window::Ask::RefreshCharacters);
        }
        jtext::draw(
            &painter,
            pos2(rr.left() - 8.0, rr.center().y),
            Align2::RIGHT_CENTER,
            &self.refresh_status,
            &crate::widgets::text(16.0),
            p.ink,
        );
        let body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        if self.page == 1 {
            crate::classes::show(ui, c, body);
            return;
        }
        if self.page == 2 {
            self.pets.show(ui, c, body);
            return;
        }
        self.characters_page(ui, c, body);
    }

    fn characters_page(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let now = c.now;
        let window = RANGES[self.range];
        let since = if window == i64::MAX { 0 } else { now - window };
        let alive: Vec<&Char> = app.characters.all().filter(|x| x.alive != self.graveyard).collect();
        let gain = |x: &Char| -> i64 { Store::sessions(x, since).iter().map(|s| s.gained).sum() };
        if self.selected != TOTAL && !alive.iter().any(|x| x.char_id == self.selected) {
            self.selected = TOTAL;
        }
        let recent = |a: &&Char, b: &&Char| b.last_played.cmp(&a.last_played).then(b.fame.cmp(&a.fame));
        let mut favs: Vec<&Char> = alive.iter().copied().filter(|x| x.favorite).collect();
        favs.sort_by(recent);
        let mut played: Vec<&Char> = alive.iter().copied().filter(|x| !x.favorite && x.last_played > 0).collect();
        played.sort_by(recent);
        let mut not_yet: Vec<&Char> = alive.iter().copied().filter(|x| !x.favorite && x.last_played == 0).collect();
        not_yet.sort_by(recent);

        // the list's natural width decides the split: the details get the rest, at least 300 (Java `SideSplit`)
        let painter = ui.painter().clone();
        let mut natural: f32 = 0.0;
        for x in &alive {
            natural = natural.max(self.card_width(&painter, x, gain(x), app, now));
        }
        let left_min = natural + 16.0 + 12.0 + 2.0 + 24.0;
        let side = (body.width() - 8.0 - left_min).clamp(300.0, 760.0);
        let left = Rect::from_min_max(body.min, pos2(body.right() - side - 8.0, body.bottom()));
        let right = Rect::from_min_max(pos2(left.right() + 8.0, body.top()), body.max);

        let upes: Vec<i32> = {
            let mut v: Vec<i32> = app
                .upe
                .runs
                .iter()
                .filter(|(id, r)| r.potential() && alive.iter().any(|x| x.char_id == **id && x.alive))
                .map(|(id, _)| *id)
                .collect();
            v.sort_unstable_by(|a, b| b.cmp(a));
            v
        };
        let mut range = self.range;
        let mut graveyard = self.graveyard;
        widgets::section(
            ui,
            &p,
            left,
            "Characters",
            fixed::GOLD,
            |ui, head| {
                let painter = ui.painter().clone();
                let h = head.height();
                let mut x = head.right() - 4.0;
                let alive_w = widgets::segmented_width(&painter, &["Alive", "Graveyard"], true);
                x -= alive_w;
                if let Some(i) =
                    widgets::segmented(ui, &p, pos2(x, head.top()), h, &["Alive", "Graveyard"], usize::from(graveyard), true, &[])
                {
                    graveyard = i == 1;
                }
                x -= 24.0 + 4.0;
                let range_w = widgets::segmented_width(&painter, &RANGE_LABELS, true);
                x -= range_w;
                if let Some(i) = widgets::segmented(ui, &p, pos2(x, head.top()), h, &RANGE_LABELS, range, true, &[]) {
                    range = i;
                }
                x -= 4.0;
                let r =
                    jtext::draw(&painter, pos2(x, head.center().y), Align2::RIGHT_CENTER, "FAME GAINED IN THE LAST", &small(), p.ink_muted);
                x = r.left() - 4.0 - 18.0 - 4.0;
                if !upes.is_empty() {
                    let t = format!("{} POTENTIAL RUN{}", upes.len(), if upes.len() == 1 { "" } else { "S" });
                    jtext::draw(&painter, pos2(x, head.center().y), Align2::RIGHT_CENTER, &t, &small(), mix(UPE_BG, Color32::WHITE, 0.7));
                }
            },
            |ui, r| {
                egui::ScrollArea::vertical().id_salt("characters-list").show(ui, |ui| {
                    ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                    let inner = Rect::from_min_max(pos2(r.left() + 8.0, ui.cursor().top() + 8.0), pos2(r.right() - 8.0 - 12.0, r.bottom()));
                    let mut list = ui.new_child(egui::UiBuilder::new().max_rect(inner).layout(egui::Layout::top_down(egui::Align::Min)));
                    list.spacing_mut().item_spacing = vec2(0.0, 0.0);
                    if !alive.is_empty() {
                        let total: i64 = alive.iter().map(|x| gain(x)).sum();
                        self.total_card(&mut list, &p, alive.len(), total);
                        list.add_space(6.0);
                    }
                    let groups: [(&str, &Vec<&Char>); 3] = [
                        ("Favourites", &favs),
                        (if self.graveyard { "Fallen" } else { "Recently played" }, &played),
                        ("Not played since tracking started", &not_yet),
                    ];
                    for (title, chars) in groups {
                        if chars.is_empty() {
                            continue;
                        }
                        list.add_space(6.0);
                        widgets::label(&mut list, &format!("  {}", title.to_uppercase()), &small(), p.ink_muted);
                        list.add_space(4.0);
                        for x in chars.iter() {
                            if self.card(&mut list, &p, c.icons, app, x, gain(x), now) {
                                self.selected = x.char_id;
                            }
                            list.add_space(6.0);
                        }
                    }
                    if alive.is_empty() {
                        widgets::muted(
                            &mut list,
                            if self.graveyard {
                                "No dead characters - long may they live."
                            } else {
                                "Log in once so your character list is loaded."
                            },
                        );
                    }
                    list.add_space(8.0);
                    ui.allocate_exact_size(vec2(r.width() - 12.0, list.min_rect().height() + 8.0), Sense::hover());
                });
            },
        );
        if range != self.range {
            self.range = range;
        }
        if graveyard != self.graveyard {
            self.graveyard = graveyard;
            self.selected = TOTAL;
        }
        let mut side_ui = ui.new_child(egui::UiBuilder::new().max_rect(right).layout(egui::Layout::top_down(egui::Align::Min)));
        side_ui.set_clip_rect(right.intersect(ui.clip_rect()));
        if self.selected == TOTAL {
            self.total_panel(&mut side_ui, c, &alive, since, right);
        } else if let Some(ch) = app.characters.get(self.selected) {
            let ch = ch.clone();
            self.detail_panel(&mut side_ui, c, &ch, since, right);
        }
    }

    /// The "All characters" card on top of the list (Java `totalCard`).
    fn total_card(&mut self, ui: &mut Ui, p: &Palette, count: usize, total: i64) {
        let painter = ui.painter().clone();
        let w = ui.available_width();
        let name = if self.graveyard { "All fallen characters" } else { "All characters" };
        let f_name = text(25.0);
        let f = text(18.0);
        let row_h = jtext::height(&painter, &f_name).max(jtext::height(&painter, &f));
        let h = 2.0 * row_h + 8.0;
        let (r, resp) = ui.allocate_exact_size(vec2(w, h), Sense::click());
        let sel = self.selected == TOTAL;
        self.paint_card_frame(&painter, p, r, sel);
        let x = r.left() + 12.0 + 1.0;
        jtext::draw(&painter, pos2(x, r.top() + 4.0 + row_h / 2.0), Align2::LEFT_CENTER, name, &f_name, p.ink);
        let a = jtext::draw(
            &painter,
            pos2(x, r.top() + 4.0 + row_h * 1.5),
            Align2::LEFT_CENTER,
            &format!("{count} characters.   "),
            &f,
            fixed::MUTED,
        );
        let g = format!("{}{} in {}   ", if total > 0 { "+" } else { "" }, num(total), RANGE_LABELS[self.range]);
        jtext::draw(
            &painter,
            pos2(a.right(), a.center().y),
            Align2::LEFT_CENTER,
            &g,
            &f,
            if total > 0 { fixed::GREEN } else { fixed::MUTED },
        );
        let big = format!("{}{}", if total > 0 { "+" } else { "" }, num(total));
        jtext::draw(
            &painter,
            pos2(r.right() - 14.0 - 10.0, r.center().y),
            Align2::RIGHT_CENTER,
            &big,
            &text(32.0),
            if total > 0 { fixed::GREEN } else { fixed::MUTED },
        );
        if resp.clicked() {
            self.selected = TOTAL;
        }
    }

    fn paint_card_frame(&self, painter: &egui::Painter, p: &Palette, r: Rect, sel: bool) {
        painter.rect_filled(r, 0.0, if sel { mix(p.gold, p.header, 0.3) } else { p.header });
        if sel {
            painter.rect_stroke(r, 0.0, Stroke::new(2.0, p.gold), StrokeKind::Inside);
        } else {
            painter.rect_stroke(r, 0.0, Stroke::new(1.0, Color32::BLACK), StrokeKind::Inside);
        }
    }

    /// What a card is made of, for its width: portrait, loadout, the name and progress, the right side.
    fn card_width(&self, painter: &egui::Painter, c: &Char, gain: i64, app: &lg_app::App, now: i64) -> f32 {
        let name = jtext::width(painter, &c.class_name, &text(30.0)) + 16.0 + jtext::width(painter, &ago_or(c, now), &text(15.0));
        let progress =
            jtext::width(painter, &progress(c), &text(20.0)) + 10.0 + jtext::width(painter, &gained_text(gain, self.range), &text(15.0));
        let (banners, toggles) = self.right_widths(painter, c, app, now);
        4.0 + (PORTRAIT + 6.0) + 14.0 + (5.0 * SLOT + 16.0) + 16.0 + name.max(progress) + 10.0 + 14.0 + banners.max(toggles) + 12.0
    }

    fn right_widths(&self, painter: &egui::Painter, c: &Char, app: &lg_app::App, now: i64) -> (f32, f32) {
        let mut banners = 6.0;
        for t in self.banner_texts(c, app) {
            banners += widgets::banner_size(painter, &t.0).x + 6.0;
        }
        let mut toggles = 8.0;
        if c.pet_skin > 0 {
            toggles += 36.0 + 8.0;
        }
        let lb = format!("+{}%", percent(loot_bonus(c, app.world.data(), now)));
        toggles += 16.0 + widgets::outlined_size(painter, &lb, 34.0).x + 8.0;
        toggles += widgets::outlined_size(painter, &format!("{}/8", c.stats_maxed), 34.0).x + 8.0;
        toggles += 30.0 + 8.0 + 34.0 + 8.0;
        (banners, toggles)
    }

    /// The banners on a card, left to right: a game mode's (if any), Seasonal, Crucible (text, colour, opacity).
    fn banner_texts(&self, c: &Char, app: &lg_app::App) -> Vec<(String, Color32, f32)> {
        let mut out = Vec::new();
        if c.alive
            && let Some(run) = app.upe.runs.get(&c.char_id)
        {
            let label = lg_app::upe::label(&run.mode) + if run.no_pet { " · no pet" } else { "" };
            if run.broken.is_some() {
                if run.marked {
                    out.push((format!("{label} lost"), UPE_LOST_BG, 1.0));
                }
            } else if run.marked {
                out.push((label, UPE_BG, 1.0));
            } else if run.potential() {
                out.push((lg_app::upe::label(&run.mode), UPE_BG, 0.55));
            }
        }
        out.push(("Seasonal".into(), SEASONAL_BG, if c.seasonal { 1.0 } else { 0.22 }));
        out.push(("Crucible".into(), CRUCIBLE_BG, if c.crucible { 1.0 } else { 0.22 }));
        out
    }

    /// One character's card (Java `card`); true when clicked.
    fn card(&self, ui: &mut Ui, p: &Palette, icons: &mut Icons, app: &lg_app::App, c: &Char, gain: i64, now: i64) -> bool {
        let painter = ui.painter().clone();
        let data = app.world.data();
        let w = ui.available_width();
        let quick: Vec<i32> =
            c.quickslots.iter().flatten().copied().filter(|id| *id > 0 && data.items.item(*id).is_some_and(|i| i.quickslot_only)).collect();
        let loadout_h = SLOT + if quick.is_empty() { 0.0 } else { 4.0 + QUICK_SLOT };
        let banner_h = widgets::banner_size(&painter, "X").y;
        let right_row = banner_h.max(36.0).max(widgets::outlined_size(&painter, "8/8", 34.0).y);
        let right_h = 8.0 + 2.0 * right_row + 4.0 + 8.0;
        // the body (portrait, loadout, text) has 4 px above and below; the right side its own 8
        let h = ((PORTRAIT + 6.0).max(loadout_h) + 8.0).max(right_h);
        let (r, resp) = ui.allocate_exact_size(vec2(w, h), Sense::click());
        let sel = self.selected == c.char_id;
        self.paint_card_frame(&painter, p, r, sel);
        let cy = r.center().y;
        // the portrait: a dark tile with a glow in the accent, the skin on it, a flame while blood ritual is on
        let pr = Rect::from_center_size(pos2(r.left() + 4.0 + (PORTRAIT + 6.0) / 2.0, cy), vec2(PORTRAIT, PORTRAIT));
        painter.rect_filled(pr, 0.0, Color32::from_rgb(0x15, 0x14, 0x1A));
        let g = p.gold;
        for i in 0..12 {
            let rad = PORTRAIT * 0.55 * (1.0 - i as f32 / 12.0);
            painter.circle_filled(pr.center(), rad, Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 7));
        }
        painter.rect_filled(pr, 0.0, Color32::TRANSPARENT);
        painter.rect_stroke(pr.shrink(1.0), 0.0, Stroke::new(1.0, Color32::from_rgba_unmultiplied(255, 255, 255, 40)), StrokeKind::Inside);
        painter.rect_stroke(pr, 0.0, Stroke::new(1.0, Color32::BLACK), StrokeKind::Inside);
        let skin_id = if c.skin > 0 { c.skin } else { c.class_type };
        if let Some(t) = icons.skin(ui.ctx(), data, skin_id, c.tex1, c.tex2, 74) {
            painter.image(t.id(), Rect::from_center_size(pr.center(), vec2(74.0, 74.0)), uv(), Color32::WHITE);
        }
        if c.ritual {
            flame(&painter, pr.center() + vec2(37.0 - 21.0, 37.0 - 27.0));
        }
        // the loadout: weapon, ability, armor, ring, backpack, at fixed places; reusable quick-slot gear below
        let lx = pr.right() + 3.0 + 14.0;
        let ly = cy - loadout_h / 2.0;
        for i in 0..5 {
            let sr = Rect::from_min_size(pos2(lx + i as f32 * (SLOT + 4.0), ly), vec2(SLOT, SLOT));
            let id = if i < 4 {
                c.equipment.as_ref().and_then(|e| e.get(i)).copied().unwrap_or(-1)
            } else if c.extender {
                EXTENDER_ITEM
            } else if c.backpack {
                BACKPACK_ITEM
            } else {
                -1
            };
            let tex = if id > 0 { icons.object(ui.ctx(), data, id, 48) } else { None };
            match tex {
                Some(t) => {
                    painter.image(t.id(), Rect::from_center_size(sr.center(), vec2(48.0, 48.0)), uv(), Color32::WHITE);
                }
                None if i < 4 => {
                    painter.rect_filled(sr.shrink(6.0), 0.0, Color32::from_rgba_unmultiplied(0, 0, 0, 40));
                }
                None => {}
            }
            if i < 4 {
                self.enchant_badge(&painter, sr.right_bottom() - vec2(1.0, 1.0), enchant_count(c, i), 8.0);
            }
        }
        for (j, id) in quick.iter().enumerate() {
            let qr = Rect::from_min_size(pos2(lx + j as f32 * (QUICK_SLOT + 4.0), ly + SLOT + 4.0), vec2(QUICK_SLOT, QUICK_SLOT));
            if let Some(t) = icons.object(ui.ctx(), data, *id, 30) {
                painter.image(t.id(), Rect::from_center_size(qr.center(), vec2(30.0, 30.0)), uv(), Color32::WHITE);
            }
        }
        // the name with how long ago, then level or fame and the gain in the range
        let tx = lx + 5.0 * SLOT + 16.0 + 16.0;
        let f_name = text(30.0);
        let nh = jtext::height(&painter, &f_name);
        let ph = jtext::height(&painter, &text(20.0));
        let row_h = nh.max(ph);
        let top = cy - row_h;
        let name_r = jtext::draw(
            &painter,
            pos2(tx, top + (row_h - nh) / 2.0),
            Align2::LEFT_TOP,
            &c.class_name,
            &f_name,
            if c.seasonal { SEASONAL_TEXT } else { p.ink },
        );
        let progress_w =
            jtext::width(&painter, &progress(c), &text(20.0)) + 10.0 + jtext::width(&painter, &gained_text(gain, self.range), &text(15.0));
        let line_w = (name_r.width() + 16.0 + jtext::width(&painter, &ago_or(c, now), &text(15.0))).max(progress_w);
        // the time sits on the name's baseline, at the right end of the two lines
        let base = name_r.top() + (30.0f32 * 1050.0 / 1350.0).ceil();
        let when_asc = (15.0f32 * 1050.0 / 1350.0).ceil();
        jtext::draw(&painter, pos2(tx + line_w, base - when_asc), Align2::RIGHT_TOP, &ago_or(c, now), &text(15.0), fixed::MUTED);
        let pr2 = jtext::draw(&painter, pos2(tx, top + row_h + (row_h - ph) / 2.0), Align2::LEFT_TOP, &progress(c), &text(20.0), p.gold);
        let gcol = if gain > 0 { fixed::GREEN } else { fixed::MUTED };
        jtext::draw(
            &painter,
            pos2(pr2.right() + 10.0, pr2.center().y),
            Align2::LEFT_CENTER,
            &gained_text(gain, self.range),
            &text(15.0),
            gcol,
        );

        // the right side: banners on top; pet, loot bonus, stats, gravestone and star below
        let rx = r.right() - 12.0;
        let row1 = Rect::from_min_max(pos2(r.left(), r.top() + 8.0 + (h - right_h) / 2.0), pos2(rx, 0.0));
        let mut x = rx - 6.0;
        let banners = self.banner_texts(c, app);
        for (t, bg, alpha) in banners.iter().rev() {
            let s = widgets::banner_size(&painter, t);
            x -= s.x;
            widgets::banner(&painter, Rect::from_min_size(pos2(x, row1.top() + (right_row - s.y) / 2.0), s), t, *bg, *alpha);
            x -= 6.0;
        }
        let row2_top = row1.top() + right_row + 4.0;
        let mid = row2_top + right_row / 2.0;
        let mut x = rx - 8.0;
        // the star (favourite) and the gravestone (dead)
        x -= 34.0;
        star(&painter, pos2(x, mid - 17.0), c.favorite, p.ink);
        x -= 8.0 + 30.0;
        if let Some(t) = &self.grave {
            let tint = if c.alive { Color32::from_white_alpha(64) } else { Color32::WHITE };
            painter.image(t.id(), Rect::from_min_size(pos2(x, mid - 15.0), vec2(30.0, 30.0)), uv(), tint);
        }
        x -= 8.0;
        let stats = format!("{}/8", c.stats_maxed);
        let ss = widgets::outlined_size(&painter, &stats, 34.0);
        x -= ss.x;
        widgets::outlined_text(
            &painter,
            Rect::from_min_size(pos2(x, mid - ss.y / 2.0), ss),
            &stats,
            34.0,
            if c.stats_maxed == 8 { STATS_MAXED } else { STATS_OPEN },
        );
        x -= 8.0;
        let lb = loot_bonus(c, data, now);
        let lt = format!("+{}%", percent(lb));
        let ls = widgets::outlined_size(&painter, &lt, 34.0);
        x -= ls.x;
        widgets::outlined_text(
            &painter,
            Rect::from_min_size(pos2(x, mid - ls.y / 2.0), ls),
            &lt,
            34.0,
            if lb > 0.0 { LOOT_ON } else { STATS_OPEN },
        );
        x -= 16.0 + 8.0;
        if c.pet_skin > 0 {
            x -= 36.0;
            if let Some(t) = icons.object(ui.ctx(), data, c.pet_skin, 36) {
                painter.image(t.id(), Rect::from_min_size(pos2(x, mid - 18.0), vec2(36.0, 36.0)), uv(), Color32::WHITE);
            }
        }
        resp.clicked()
    }

    /// Java `EnchantBadge.draw`: the rarity sprite for the count, its bottom right at `at`, at `size` / 5 scale.
    fn enchant_badge(&self, painter: &egui::Painter, at: egui::Pos2, count: usize, size: f32) {
        if count == 0 {
            return;
        }
        let Some(t) = &self.rarity[count.min(4)] else { return };
        let s = (size / 5.0).max(1.0);
        let [w, h] = t.size();
        let (w, h) = ((w as f32 * s).round(), (h as f32 * s).round());
        painter.image(t.id(), Rect::from_min_size(pos2(at.x - w + 1.0, at.y - h + 1.0), vec2(w, h)), uv(), Color32::WHITE);
    }

    /// One character's boxes (Java `renderPanel`): loadout, inventory, fame gain (the suggested loadout comes next).
    fn detail_panel(&mut self, ui: &mut Ui, c: &mut Ctx, ch: &Char, since: i64, rect: Rect) {
        let p = *c.p;
        let app = c.app;
        let rarity = self.rarity.clone();
        egui::ScrollArea::vertical().id_salt("character-detail").show(ui, |ui| {
            let top = ui.cursor().min;
            let w = rect.width() - 12.0;
            let icons = &mut *c.icons;
            let mut y = top.y;
            y += widgets::section_box(
                ui,
                &p,
                pos2(top.x, y),
                w,
                "Loadout",
                fixed::GOLD,
                |ui, head| {
                    // the loadout as a picture to share
                    let painter = ui.painter().clone();
                    let f = text(18.0);
                    let bw = jtext::width(&painter, "Copy as image", &f) + 30.0;
                    let r = Rect::from_min_max(pos2(head.right() - bw, head.top()), head.max);
                    let resp = ui.interact(r, ui.scope_id().with("copy-loadout"), Sense::click());
                    if resp.clicked() {
                        ui.ctx().data_mut(|d| d.insert_temp(egui::Id::unique("lg-copy-card"), ch.char_id));
                    }
                    painter.rect(
                        r,
                        0.0,
                        if resp.hovered() { p.sand } else { p.header },
                        Stroke::new(1.5, Color32::BLACK),
                        StrokeKind::Inside,
                    );
                    // "Copied" for a moment after
                    let copied = ui.ctx().data(|d| d.get_temp::<i64>(egui::Id::unique("lg-card-copied"))).is_some_and(|t| {
                        let real = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
                        real - t < 1500
                    });
                    jtext::draw(&painter, r.center(), Align2::CENTER_CENTER, if copied { "Copied" } else { "Copy as image" }, &f, p.ink);
                    if copied {
                        ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
                    }
                },
                |ui, r| crate::detail::loadout(ui, &p, app, icons, ch, r),
            );
            // the suggestion, worked out again only when the character or the settings change
            let key = format!(
                "{}|{:?}|{}|{}|{}|{}|{}",
                ch.char_id,
                c.loadout.chosen,
                c.loadout.scope,
                c.loadout.enemy_def,
                c.loadout.second_chance,
                c.loadout.item_locks,
                c.loadout.enchant_locks
            );
            if self.suggestion.as_ref().is_none_or(|s| s.0 != key)
                && let Some(picks) = lg_app::loadout::suggestion(app, c.en, ch, c.loadout)
            {
                let sum = lg_app::loadout::summary(app, c.en, ch, c.loadout, &picks);
                self.suggestion = Some((key, picks, sum));
            }
            if let Some((_, picks, sum)) = &self.suggestion {
                let mut change = None;
                let en = c.en;
                let settings = c.loadout.clone();
                let none_chosen = settings.chosen.is_empty();
                let mut reset = false;
                y += widgets::section_box(
                    ui,
                    &p,
                    pos2(top.x, y),
                    w,
                    "Suggested Loadout",
                    fixed::BLUE,
                    |ui, head| {
                        // after the title: the reset box, then (nothing chosen yet) what to do
                        let painter = ui.painter().clone();
                        let tw = jtext::width(&painter, "SUGGESTED LOADOUT", &crate::theme::head(17.0));
                        let bx = Rect::from_center_size(
                            pos2(head.left() + 10.0 + 8.0 + 8.0 + tw + 8.0 + 13.0, head.center().y),
                            vec2(26.0, 26.0),
                        );
                        let hover = ui.interact(bx, ui.scope_id().with("reset"), Sense::click());
                        reset_box(&painter, bx, hover.hovered(), &p);
                        reset = hover.clicked();
                        if none_chosen {
                            jtext::draw(
                                &painter,
                                pos2(bx.right() + 8.0, head.center().y),
                                Align2::LEFT_CENTER,
                                "(click the preferred stats)",
                                &text(18.0),
                                fixed::MUTED,
                            );
                        }
                    },
                    |ui, r| {
                        let (h, clicked) = crate::suggested::suggested(ui, &p, app, icons, &rarity, en, ch, &settings, picks, sum, r);
                        change = clicked;
                        h
                    },
                );
                if reset {
                    c.loadout.chosen.clear();
                    c.loadout.scope = 0;
                    c.loadout.enemy_def = 0.0;
                    c.loadout.second_chance = 0;
                }
                match change {
                    Some(crate::suggested::Change::Toggle(k)) => {
                        if let Some(i) = c.loadout.chosen.iter().position(|x| *x == k) {
                            c.loadout.chosen.remove(i);
                        } else {
                            c.loadout.chosen.push(k);
                        }
                    }
                    Some(crate::suggested::Change::Scope) => c.loadout.scope = (c.loadout.scope + 1) % 3,
                    Some(crate::suggested::Change::SecondChance) => c.loadout.second_chance = (c.loadout.second_chance + 1) % 3,
                    _ => {}
                }
            }
            y += widgets::section_box(
                ui,
                &p,
                pos2(top.x, y),
                w,
                "Inventory",
                fixed::PURPLE,
                |_, _| {},
                |ui, r| crate::detail::inventory(ui, &p, app, icons, &rarity, ch, r),
            );
            let marks = visit_marks(app, icons, ui.ctx());
            y += widgets::section_box(
                ui,
                &p,
                pos2(top.x, y),
                w,
                "Fame Gain",
                fixed::GREEN,
                |_, _| {},
                |ui, r| crate::detail::fame(ui, &p, app, icons, ch, since, r, &marks),
            );
            ui.allocate_exact_size(vec2(w, y - top.y), Sense::hover());
        });
    }

    /// Everyone's fame gain (Java `renderTotal`): KPIs, the chart, each character's part.
    fn total_panel(&mut self, ui: &mut Ui, c: &mut Ctx, chars: &[&Char], since: i64, rect: Rect) {
        let p = *c.p;
        let app = c.app;
        let painter = ui.painter().clone();
        let (mut gained, mut played) = (0i64, 0i64);
        for x in chars {
            for s in Store::sessions(x, since) {
                gained += s.gained;
                played += s.duration_ms();
            }
        }
        let head = widgets::section_head_height(&painter);
        let f20 = text(20.0);
        let f = text(18.0);
        let kh = widgets::kpi_height(&painter);
        let mut contributors: Vec<(&Char, i64)> =
            chars.iter().map(|x| (*x, Store::sessions(x, since).iter().map(|s| s.gained).sum::<i64>())).filter(|(_, g)| *g != 0).collect();
        contributors.sort_by_key(|(_, g)| -*g);
        let row_h = 28.0 + 4.0;
        let content_h = jtext::height(&painter, &f20)
            + jtext::height(&painter, &f)
            + 8.0
            + kh
            + 8.0
            + 200.0
            + 8.0
            + jtext::height(&painter, &small())
            + 4.0
            + if contributors.is_empty() { jtext::height(&painter, &f) } else { contributors.len() as f32 * row_h };
        let sec = Rect::from_min_size(rect.min, vec2(rect.width() - 12.0, head + 1.0 + 20.0 + content_h + 2.0));
        let graveyard = self.graveyard;
        let range = self.range;
        let zone = &app.zone;
        let now = c.now;
        let icons = &mut *c.icons;
        widgets::section(
            ui,
            &p,
            sec,
            "Fame Gain",
            fixed::GREEN,
            |_, _| {},
            |ui, r| {
                let painter = ui.painter().clone();
                let x = r.left() + 10.0;
                let w = r.width() - 20.0;
                let mut y = r.top() + 10.0;
                let t = jtext::draw(
                    &painter,
                    pos2(x, y),
                    Align2::LEFT_TOP,
                    if graveyard { "All fallen characters" } else { "All characters" },
                    &f20,
                    p.ink,
                );
                y = t.bottom();
                let fame: i64 = chars.iter().map(|x| x.fame).sum();
                let t = jtext::draw(
                    &painter,
                    pos2(x, y),
                    Align2::LEFT_TOP,
                    &format!("★ {} fame in total   ·   {} characters", num(fame), chars.len()),
                    &f,
                    fixed::MUTED,
                );
                y = t.bottom() + 8.0;
                let kw = ((w - 16.0) / 3.0).floor();
                let per_hour =
                    if played > 60_000 { num((gained as f64 / (played as f64 / HOUR as f64)).round() as i64) } else { "-".into() };
                let values = [
                    ("Fame gained", format!("+{}", num(gained))),
                    ("Fame / hour", per_hour),
                    ("Time played", format!("{:.1} h", played as f64 / HOUR as f64)),
                ];
                for (i, (cap, v)) in values.iter().enumerate() {
                    widgets::kpi(ui, &p, Rect::from_min_size(pos2(x + i as f32 * (kw + 8.0), y), vec2(kw, kh)), cap, v);
                }
                y += kh + 8.0;
                let points = combined_gain(chars, since);
                let marks = visit_marks(app, icons, ui.ctx());
                charts::fame_chart(&painter, &p, Rect::from_min_size(pos2(x, y), vec2(w, 200.0)), &points, since, now, zone, &marks);
                y += 200.0 + 8.0;
                let t = jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, "CONTRIBUTION", &small(), p.ink_muted);
                y = t.bottom() + 4.0;
                if contributors.is_empty() {
                    jtext::draw(&painter, pos2(x, y), Align2::LEFT_TOP, "No fame gained in this range.", &f, fixed::MUTED);
                }
                let max = contributors.iter().map(|(_, g)| g.abs()).max().unwrap_or(1).max(1);
                for (i, (ch, g)) in contributors.iter().enumerate() {
                    let rr = Rect::from_min_size(pos2(x, y), vec2(w, row_h));
                    painter.rect_filled(rr, 0.0, if i % 2 == 0 { p.header } else { p.sand });
                    let skin = if ch.skin > 0 { ch.skin } else { ch.class_type };
                    if let Some(t) = icons.skin(ui.ctx(), app.world.data(), skin, ch.tex1, ch.tex2, 24) {
                        painter.image(
                            t.id(),
                            Rect::from_min_size(pos2(rr.left() + 6.0, rr.center().y - 12.0), vec2(24.0, 24.0)),
                            uv(),
                            Color32::WHITE,
                        );
                    }
                    jtext::draw(
                        &painter,
                        pos2(rr.left() + 36.0, rr.center().y),
                        Align2::LEFT_CENTER,
                        &format!("{} {}", ch.class_name, ch.level),
                        &f,
                        if ch.seasonal { SEASONAL_TEXT } else { p.ink },
                    );
                    let pct =
                        if gained > 0 { format!("  ({}%)", (100.0 * *g as f64 / gained as f64).round() as i64) } else { String::new() };
                    let v = format!("{}{}{pct}", if *g > 0 { "+" } else { "" }, num(*g));
                    let vr = jtext::draw(
                        &painter,
                        pos2(rr.right() - 8.0, rr.center().y),
                        Align2::RIGHT_CENTER,
                        &v,
                        &f,
                        if *g > 0 { fixed::GREEN } else { fixed::RED },
                    );
                    let bx = rr.left() + 6.0 + 130.0 + 8.0;
                    let bw = vr.left() - 8.0 - bx;
                    let share = g.abs() as f32 / max as f32;
                    let by = rr.center().y - 5.0;
                    painter.rect_filled(Rect::from_min_size(pos2(bx, by), vec2(bw - 4.0, 10.0)), 0.0, p.sand);
                    painter.rect_filled(
                        Rect::from_min_size(pos2(bx, by), vec2(((bw - 4.0) * share).round().max(2.0), 10.0)),
                        0.0,
                        if *g > 0 { fixed::GREEN } else { fixed::RED },
                    );
                    painter.rect_stroke(
                        Rect::from_min_size(pos2(bx, by), vec2(bw - 4.0, 10.0)),
                        0.0,
                        Stroke::new(1.0, Color32::BLACK),
                        StrokeKind::Inside,
                    );
                    y += row_h;
                }
                let _ = range;
            },
        );
    }
}

fn uv() -> Rect {
    Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))
}

fn ago_or(c: &Char, now: i64) -> String {
    if c.last_played == 0 { "not played yet".into() } else { ago(now, c.last_played) }
}

fn progress(c: &Char) -> String {
    if c.level < 20 { format!("Level {}", c.level) } else { format!("{} Fame", num(c.fame)) }
}

fn gained_text(gain: i64, range: usize) -> String {
    format!("({}{} in {})", if gain > 0 { "+" } else { "" }, num(gain), RANGE_LABELS[range])
}

/// Java `CharacterStore.combinedGain`: the steps of all characters' fame since a time, added up from 0.
fn combined_gain(chars: &[&Char], since: i64) -> Vec<(i64, i64)> {
    let mut steps = Vec::new();
    for c in chars {
        let mut prev: Option<&lg_app::characters::Sample> = None;
        for s in &c.samples {
            if s.t < since {
                continue;
            }
            if let Some(p) = prev
                && s.t - p.t <= 20 * 60_000
                && s.fame != p.fame
            {
                steps.push((s.t, s.fame - p.fame));
            }
            prev = Some(s);
        }
    }
    steps.sort_by_key(|s| s.0);
    let mut out = Vec::new();
    let mut total = 0;
    for (t, d) in steps {
        if out.is_empty() {
            out.push((t - 1, 0));
        }
        total += d;
        out.push((t, total));
    }
    out
}

/// The dungeon visits, oldest first, with their portals, to mark on a fame chart.
fn visit_marks(app: &lg_app::App, icons: &mut Icons, ctx: &egui::Context) -> Vec<charts::Mark> {
    let mut v: Vec<&lg_app::dungeon_visits::Visit> = app.dungeon_visits.visits.iter().chain(app.dungeon_visits.current.iter()).collect();
    v.sort_by_key(|x| x.start);
    let data = app.world.data();
    v.iter()
        .map(|x| charts::Mark {
            icon: data.dungeons.portal_by_name.get(&x.dungeon).and_then(|id| icons.object(ctx, data, *id, 18)),
            start: x.start,
            end: x.start + x.duration_ms,
        })
        .collect()
}

/// Java `resetBox`: a framed box with a circular arrow, lit while hovered.
fn reset_box(painter: &egui::Painter, r: Rect, hover: bool, p: &Palette) {
    let g = p.gold;
    painter.rect_filled(
        r,
        3.0,
        if hover { Color32::from_rgba_unmultiplied(g.r(), g.g(), g.b(), 55) } else { Color32::from_black_alpha(70) },
    );
    painter.rect_stroke(r, 3.0, Stroke::new(1.5, if hover { g } else { Color32::from_white_alpha(60) }), StrokeKind::Inside);
    let ink = if hover { g } else { fixed::MUTED };
    let (c, rad) = (r.center(), 26.0 * 0.26);
    // three quarters of a circle from 60 degrees round, open at the top right, the arrow head there
    let pts: Vec<egui::Pos2> = (0..=29)
        .map(|i| {
            let a = (60.0 + 290.0 * i as f32 / 29.0).to_radians();
            pos2(c.x + rad * a.cos(), c.y - rad * a.sin())
        })
        .collect();
    painter.add(egui::Shape::line(pts, Stroke::new(2.0, ink)));
    let (ax, ay) = (c.x + rad * 60f32.to_radians().cos(), c.y - rad * 60f32.to_radians().sin());
    painter.add(egui::Shape::convex_polygon(
        vec![pos2(ax + 4.5, ay - 1.5), pos2(ax - 1.0, ay - 4.0), pos2(ax + 0.5, ay + 3.0)],
        ink,
        Stroke::NONE,
    ));
}

/// The blood ritual's pixel flame, 3 px per pixel.
fn flame(painter: &egui::Painter, at: egui::Pos2) {
    const PIXELS: [&str; 9] = ["...r...", "..ro...", "..roo..", ".rooor.", ".royor.", "royyyor", "royyyor", ".ryyyr.", "..rrr.."];
    for (r, line) in PIXELS.iter().enumerate() {
        for (col, ch) in line.chars().enumerate() {
            let c = match ch {
                'r' => Color32::from_rgb(0xC2, 0x34, 0x1B),
                'o' => Color32::from_rgb(0xF5, 0x7E, 0x1E),
                'y' => Color32::from_rgb(0xFF, 0xD2, 0x3F),
                _ => continue,
            };
            painter.rect_filled(Rect::from_min_size(pos2(at.x + col as f32 * 3.0, at.y + r as f32 * 3.0), vec2(3.0, 3.0)), 0.0, c);
        }
    }
}

/// Java `star`: a white star with a black outline (favourite), or just its outline in the text colour; 34 px.
fn star(painter: &egui::Painter, at: egui::Pos2, filled: bool, ink: Color32) {
    let mut pts = Vec::new();
    for i in 0..10 {
        let a = std::f64::consts::FRAC_PI_2 + f64::from(i) * std::f64::consts::PI / 5.0;
        let r = if i % 2 == 0 { 15.0 } else { 6.3 };
        pts.push(pos2(at.x + 17.0 + (r * a.cos()).round() as f32, at.y + 17.0 - (r * a.sin()).round() as f32));
    }
    if filled {
        // a star is not convex: its centre fan
        let c = pos2(at.x + 17.0, at.y + 17.0);
        for i in 0..10 {
            painter.add(egui::Shape::convex_polygon(vec![c, pts[i], pts[(i + 1) % 10]], Color32::WHITE, Stroke::NONE));
        }
    }
    let mut closed = pts.clone();
    closed.push(pts[0]);
    painter.add(egui::Shape::line(closed, Stroke::new(if filled { 2.4 } else { 2.0 }, if filled { Color32::BLACK } else { ink })));
}
