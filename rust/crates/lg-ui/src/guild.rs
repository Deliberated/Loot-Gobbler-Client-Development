//! The Guild tab (Java `GuildPanel`): Loot Caravan, Quest Map, Leaderboard, Recent Drops, Member, Quests. All of it
//! comes from the Loot Gobbler server, loaded when the tab is opened (and after logging in or out).
//! Not yet online: the quest map (it shows its logged-out state).

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use egui::{Align2, Color32, Rect, Stroke, StrokeKind, Ui, pos2, vec2};

const PAGES: [&str; 6] = ["Loot Caravan", "Quest Map", "Leaderboard", "Recent Drops", "Member", "Quests"];
const LOGGED_OUT: &str = "Log in to a server in the Server tab to see your guild.";

#[derive(Default)]
pub struct GuildView {
    pub page: usize,
    /// The frame the tab was last drawn in, and the account then: opening the tab or logging in or out loads it.
    last_pass: u64,
    last_account: String,
    /// The leaderboard's sort: column and ascending (None: by score, as the server's order is shown first).
    sort: Option<(usize, bool)>,
    caravan: crate::caravan::CaravanView,
}

fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

/// A plain button with its text as written (Java `JButton` without upper-casing).
fn plain_button(ui: &Ui, p: &Palette, head: Rect, label: &str) {
    let painter = ui.painter();
    let f = small();
    let w = jtext::width(painter, label, &f) + 40.0;
    let r = Rect::from_min_size(pos2(head.right() - 5.0 - w, head.top()), vec2(w, head.height()));
    painter.rect(r, 0.0, p.header, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
    jtext::draw(painter, r.center(), Align2::CENTER_CENTER, label, &f, p.ink);
}

impl GuildView {
    pub fn show(&mut self, ui: &mut Ui, c: &mut Ctx, rarity: &crate::detail::Rarity) {
        let p = *c.p;
        // load when the tab is opened, and again after logging in or out
        let pass = ui.ctx().cumulative_pass_nr();
        let account = c.app.server.account();
        if pass > self.last_pass + 1 || account != self.last_account {
            c.asks.push(crate::window::Ask::Server(crate::window::ServerAsk::GuildRefresh));
        }
        self.last_pass = pass;
        self.last_account = account;
        let all = ui.max_rect().shrink(8.0);
        if let Some(i) = widgets::segmented(ui, &p, pos2(all.left() + 7.0, all.top()), 36.0, &PAGES, self.page, false, &[]) {
            self.page = i;
        }
        let body = Rect::from_min_max(pos2(all.left(), all.top() + 36.0 + 8.0), all.max);
        let f = text(18.0);
        let note = |ui: &mut Ui, r: Rect, t: &str| {
            jtext::draw(ui.painter(), pos2(r.left() + 14.0, r.top() + 12.0), Align2::LEFT_TOP, t, &f, fixed::MUTED);
        };
        match self.page {
            0 => self.caravan.show(ui, c, body),
            1 => widgets::section(
                ui,
                &p,
                body,
                "Quest Map",
                fixed::GOLD,
                |ui, head| plain_button(ui, &p, head, "Refresh"),
                |ui, r| note(ui, r, "Log in to a server in the Server tab to see your guild's quest map."),
            ),
            2 => self.leaderboard(ui, c, body),
            3 => drops(ui, c, body, rarity, &mut self.page),
            4 => member(ui, c, body),
            _ => {
                // under construction: the notice, the empty section greyed out
                let painter = ui.painter().clone();
                let h = jtext::height(&painter, &f) + 20.0;
                jtext::draw_bold(
                    &painter,
                    pos2(body.center().x, body.top() + h / 2.0),
                    Align2::CENTER_CENTER,
                    "Guild quests are under construction.",
                    &f,
                    fixed::GOLD,
                );
                let pr = Rect::from_min_max(pos2(body.left(), body.top() + h), body.max);
                widgets::section(ui, &p, pr, "Quests", fixed::GOLD, |_, _| {}, |_, _| {});
                let bg = p.sand;
                painter.rect_filled(pr, 0.0, Color32::from_rgba_unmultiplied(bg.r(), bg.g(), bg.b(), 170));
            }
        }
    }
}

/// A section's REFRESH at the right of its head (Java's guild buttons): true when clicked.
fn refresh_button(ui: &Ui, p: &Palette, head: Rect, id: &str) -> bool {
    let w = jtext::width(ui.painter(), "REFRESH", &small()) + 40.0;
    let r = Rect::from_min_size(pos2(head.right() - 5.0 - w, head.top()), vec2(w, head.height()));
    crate::inventory::button(ui, p, r, "Refresh", false, id)
}

/// Java `loadError`: what to show when the guild could not be loaded.
fn load_error(c: &Ctx, e: &lg_app::server::Error) -> String {
    load_error_text(&c.app.profile.guild, e)
}

pub fn load_error_text(guild: &str, e: &lg_app::server::Error) -> String {
    if e.code() == "no_guild" {
        if guild.is_empty() {
            "Your guild is not known yet. Enter the game once with Loot Gobbler running; it is uploaded on the next sync.".into()
        } else {
            format!("Your guild ({guild}) is not on the server yet. It is uploaded on the next sync (Server tab: Sync now).")
        }
    } else {
        format!("Could not load the guild: {}", e.message())
    }
}

/// Logged in and online (else the pages say to log in).
fn online(c: &Ctx) -> bool {
    c.app.server.user.is_some() && c.app.server.state == lg_app::server::State::Online
}

/// Java's `card`: a bordered row in the header colour, 6 below it; its rect.
fn card(painter: &egui::Painter, p: &Palette, x: f32, y: f32, w: f32, h: f32) -> Rect {
    let r = Rect::from_min_size(pos2(x, y), vec2(w, h));
    painter.rect(r, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    r
}

/// "d MMM, HH:mm" from the player's local time ("2026-11-01T16:08:28"), else as it came.
fn drop_time(t: &str) -> String {
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let part = |a: usize, b: usize| t.get(a..b).and_then(|x| x.parse::<usize>().ok());
    match (part(5, 7), part(8, 10), t.get(11..16)) {
        (Some(m), Some(d), Some(hm)) if (1..=12).contains(&m) && t.as_bytes().get(13) == Some(&b':') => {
            format!("{d} {}, {hm}", MONTHS[m - 1])
        }
        _ => t.to_string(),
    }
}

const COLUMNS: [&str; 11] = ["#", "Player", "Rank", "Score", "Items", "Finds", "Fame", "Exalts", "Chars", "Maxed", "Last active"];

impl GuildView {
    /// Java `showLeaderboard`: the table (highest score first, sortable by a click on a column title), the note.
    fn leaderboard(&mut self, ui: &mut Ui, c: &mut Ctx, body: Rect) {
        let p = *c.p;
        let app = c.app;
        let data = app.world.data();
        let w8 = &app.server.weights;
        let title = match &app.server.guild.board {
            Some(Ok(b)) if online(c) => format!("Leaderboard - {} ({})", b.guild, b.members.len()),
            _ => "Leaderboard".into(),
        };
        let refresh = std::cell::Cell::new(false);
        let open = std::cell::Cell::new(None);
        let sort = std::cell::Cell::new(self.sort);
        widgets::section(
            ui,
            &p,
            body,
            &title,
            fixed::GOLD,
            |ui, head| refresh.set(refresh_button(ui, &p, head, "board-refresh")),
            |ui, r| {
                let f = text(18.0);
                let painter = ui.painter().clone();
                let x0 = r.left() + 14.0;
                let board = match &app.server.guild.board {
                    _ if !online(c) => return note(&painter, x0, r.top() + 12.0, LOGGED_OUT, &f),
                    None => return,
                    Some(Err(e)) => return note(&painter, x0, r.top() + 12.0, &load_error(c, e), &f),
                    Some(Ok(b)) => b,
                };
                // highest score first (the # follows it); ties by name
                let mut members: Vec<(&lg_app::guild::Member, f64)> =
                    board.members.iter().map(|m| (m, lg_app::guild::total(data, w8, &m.tally))).collect();
                members.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.ign.to_lowercase().cmp(&b.0.ign.to_lowercase())));
                let mut rows: Vec<(usize, &lg_app::guild::Member, f64)> =
                    members.iter().enumerate().map(|(i, (m, s))| (i + 1, *m, *s)).collect();
                if let Some((col, asc)) = sort.get() {
                    let key = |r: &(usize, &lg_app::guild::Member, f64)| -> (f64, String) {
                        let m = r.1;
                        match col {
                            0 => (r.0 as f64, String::new()),
                            1 => (0.0, m.ign.to_lowercase()),
                            2 => (f64::from(m.guild_rank), String::new()),
                            3 => (r.2, String::new()),
                            4 => (m.items as f64, String::new()),
                            5 => (m.finds as f64, String::new()),
                            6 => (m.fame as f64, String::new()),
                            7 => (m.exalts as f64, String::new()),
                            8 => (m.characters as f64, String::new()),
                            9 => (m.maxed as f64, String::new()),
                            _ => (m.last_active as f64, String::new()),
                        }
                    };
                    rows.sort_by(|a, b| {
                        let (ka, kb) = (key(a), key(b));
                        let o = ka.0.total_cmp(&kb.0).then_with(|| ka.1.cmp(&kb.1));
                        if asc { o } else { o.reverse() }
                    });
                }
                let w = r.width() - 28.0;
                let colw = |i: usize| if i == 0 { 44.0 } else { (w - 44.0) / 10.0 };
                let colx = |i: usize| x0 + (0..i).map(colw).sum::<f32>();
                let mut child = ui.new_child(egui::UiBuilder::new().max_rect(Rect::from_min_max(pos2(r.left(), r.top() + 12.0), r.max)));
                egui::ScrollArea::vertical().id_salt("leaderboard").show(&mut child, |ui| {
                    let top = ui.cursor().min.y;
                    let painter = ui.painter().clone();
                    // the header: click a title to sort (again: the other way)
                    let hh = 30.0;
                    let hr = Rect::from_min_size(pos2(x0, top), vec2(w, hh));
                    painter.rect_filled(hr, 0.0, p.header);
                    for (i, t) in COLUMNS.iter().enumerate() {
                        let cr = Rect::from_min_size(pos2(colx(i), top), vec2(colw(i), hh));
                        let left = matches!(i, 1 | 2 | 10);
                        let at = if left { pos2(cr.left() + 8.0, cr.center().y) } else { pos2(cr.right() - 8.0, cr.center().y) };
                        jtext::draw(&painter, at, if left { Align2::LEFT_CENTER } else { Align2::RIGHT_CENTER }, t, &f, p.ink);
                        if ui.interact(cr, ui.scope_id().with(("board-head", i)), egui::Sense::click()).clicked() {
                            sort.set(Some(match sort.get() {
                                Some((c, asc)) if c == i => (i, !asc),
                                _ => (i, true),
                            }));
                        }
                    }
                    let me = app.profile.ign.to_lowercase();
                    let mut y = top + hh;
                    for (k, (pos, m, score)) in rows.iter().enumerate() {
                        let rr = Rect::from_min_size(pos2(x0, y), vec2(w, 28.0));
                        painter.rect_filled(rr, 0.0, if k % 2 == 1 { p.header } else { p.paper });
                        let color = if m.ign.to_lowercase() == me { fixed::GOLD } else { p.ink };
                        let cells = [
                            pos.to_string(),
                            m.ign.clone(),
                            lg_app::guild::rank_name(m.guild_rank).to_string(),
                            lg_app::guild::format(*score),
                            crate::profile::num(m.items),
                            crate::profile::num(m.finds),
                            crate::profile::num(m.fame),
                            crate::profile::num(m.exalts),
                            crate::profile::num(m.characters),
                            crate::profile::num(m.maxed),
                            if m.last_active == 0 { "-".into() } else { crate::profile::ago(c.now, m.last_active) },
                        ];
                        for (i, t) in cells.iter().enumerate() {
                            let cr = Rect::from_min_size(pos2(colx(i), y), vec2(colw(i), 28.0));
                            let left = matches!(i, 1 | 2 | 10);
                            let at = if left { pos2(cr.left() + 8.0, cr.center().y) } else { pos2(cr.right() - 8.0, cr.center().y) };
                            jtext::draw(&painter.with_clip_rect(cr), at, if left { Align2::LEFT_CENTER } else { Align2::RIGHT_CENTER }, t, &f, color);
                        }
                        let resp = ui.interact(rr, ui.scope_id().with(("board-row", m.id)), egui::Sense::click());
                        if resp.clicked() {
                            open.set(Some(m.id));
                        }
                        resp.on_hover_cursor(egui::CursorIcon::PointingHand).on_hover_text("Click a player to open their profile. Click a column title to sort.");
                        y += 28.0;
                    }
                    y += 8.0;
                    jtext::draw(
                        &painter,
                        pos2(x0, y),
                        Align2::LEFT_TOP,
                        "Score: the points of all items ever found. Items: different UT/ST items. Finds: all copies. Fame, Chars and Maxed count living characters. Exalts over all classes.",
                        &f,
                        fixed::MUTED,
                    );
                    y += jtext::height(&painter, &f) + 14.0;
                    ui.allocate_exact_size(vec2(w, y - top), egui::Sense::hover());
                });
            },
        );
        self.sort = sort.get();
        if refresh.get() {
            c.asks.push(crate::window::Ask::Server(crate::window::ServerAsk::GuildRefresh));
        }
        if let Some(id) = open.get() {
            self.page = 4;
            c.asks.push(crate::window::Ask::Server(crate::window::ServerAsk::OpenMember(id)));
        }
    }
}

fn note(painter: &egui::Painter, x: f32, y: f32, t: &str, f: &egui::FontId) {
    jtext::draw(painter, pos2(x, y), Align2::LEFT_TOP, t, f, fixed::MUTED);
}

/// Java `showDrops`: each drop as a row (the item with its rarity marker, who found it where and when, its points).
fn drops(ui: &mut Ui, c: &mut Ctx, body: Rect, rarity: &crate::detail::Rarity, page: &mut usize) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let refresh = std::cell::Cell::new(false);
    let mut open = None;
    let is_online = online(c);
    let failed = match &app.server.guild.drops {
        Some(Err(e)) => load_error(c, e),
        _ => String::new(),
    };
    let icons = &mut *c.icons;
    widgets::section(
        ui,
        &p,
        body,
        "Recent Drops",
        fixed::PURPLE,
        |ui, head| refresh.set(refresh_button(ui, &p, head, "drops-refresh")),
        |ui, r| {
            let f = text(18.0);
            let painter = ui.painter().clone();
            let x0 = r.left() + 14.0;
            let drops = match &app.server.guild.drops {
                _ if !is_online => return note(&painter, x0, r.top() + 12.0, LOGGED_OUT, &f),
                None => return,
                Some(Err(_)) => return note(&painter, x0, r.top() + 12.0, &failed, &f),
                Some(Ok(d)) => d,
            };
            if drops.is_empty() {
                return note(&painter, x0, r.top() + 12.0, "No drops yet.", &f);
            }
            let fh = jtext::height(&painter, &f);
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(Rect::from_min_max(pos2(r.left(), r.top() + 12.0), r.max)));
            egui::ScrollArea::vertical().id_salt("guild-drops").show(&mut child, |ui| {
                let top = ui.cursor().min.y;
                let painter = ui.painter().clone();
                let w = r.width() - 28.0;
                let mut y = top;
                let pts_font = crate::theme::head(30.0);
                for (k, d) in drops.iter().enumerate() {
                    let h = 6.0 + (40.0f32).max(2.0 * fh) + 6.0;
                    let row = card(&painter, &p, x0, y, w, h);
                    let enchants = d.enchant_ids.as_ref().map_or(d.enchants, |e| e.len() as i32);
                    let ir = Rect::from_min_size(pos2(row.left() + 10.0, row.top() + 6.0), vec2(40.0, 40.0));
                    if let Some(t) = icons.object(ui.ctx(), data, d.item_id, 40) {
                        painter.image(t.id(), ir, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
                    }
                    crate::detail::enchant_badge(&painter, rarity, ir.right_bottom() - vec2(1.0, 1.0), enchants.max(0) as usize, 8.0);
                    let name =
                        data.items.item(d.item_id).filter(|i| !i.name.is_empty()).map_or(format!("Item {}", d.item_id), |i| i.name.clone());
                    jtext::draw(&painter, pos2(ir.right() + 10.0, row.top() + 6.0 + h / 4.0 - 3.0), Align2::LEFT_CENTER, &name, &f, p.ink);
                    let place = if d.r#where.trim().is_empty() { String::new() } else { format!(" - {}", d.r#where) };
                    let sub = format!("{}{place} - {}", d.ign, drop_time(&d.time));
                    jtext::draw(
                        &painter,
                        pos2(row.left() + 10.0 + 50.0, row.top() + 6.0 + 3.0 * h / 4.0 - 9.0),
                        Align2::LEFT_CENTER,
                        &sub,
                        &f,
                        fixed::MUTED,
                    );
                    let pts = format!(
                        "{} pts",
                        lg_app::guild::format(lg_app::guild::score(data, &app.server.weights, d.item_id, &d.r#where, enchants))
                    );
                    let pr = jtext::draw(&painter, pos2(row.right() - 8.0, row.center().y), Align2::RIGHT_CENTER, &pts, &pts_font, p.gold);
                    let breakdown = lg_app::guild::breakdown(data, d.item_id, &d.r#where, enchants);
                    let tip: String = breakdown.iter().map(|(k, v)| format!("{k}  {v}")).collect::<Vec<_>>().join("\n");
                    widgets::tip(ui, pr.expand(4.0), ("drop-pts", k), &tip);
                    let resp = ui.interact(row, ui.scope_id().with(("drop", k)), egui::Sense::click());
                    if resp.clicked() {
                        open = Some(d.user_id);
                    }
                    if !pr.expand(4.0).contains(ui.input(|i| i.pointer.hover_pos()).unwrap_or_default()) {
                        resp.on_hover_cursor(egui::CursorIcon::PointingHand).on_hover_text(format!("Open {}'s profile", d.ign));
                    }
                    y += h + 6.0;
                }
                ui.allocate_exact_size(vec2(w, y - top), egui::Sense::hover());
            });
        },
    );
    if refresh.get() {
        c.asks.push(crate::window::Ask::Server(crate::window::ServerAsk::GuildRefresh));
    }
    if let Some(id) = open {
        *page = 4;
        c.asks.push(crate::window::Ask::Server(crate::window::ServerAsk::OpenMember(id)));
    }
}

/// Java `showMember`: name, rank and guild, the numbers, the characters, exaltations and collection.
fn member(ui: &mut Ui, c: &mut Ctx, body: Rect) {
    let p = *c.p;
    let app = c.app;
    let data = app.world.data();
    let now = c.now;
    let icons = &mut *c.icons;
    let shown = &app.server.guild.member;
    let title = match shown {
        Some((_, Some(Ok(m)))) => format!("Member - {}", m.member.ign),
        _ => "Member".into(),
    };
    widgets::section(
        ui,
        &p,
        body,
        &title,
        fixed::BLUE,
        |_, _| {},
        |ui, r| {
            let f = text(18.0);
            let painter = ui.painter().clone();
            let x0 = r.left() + 14.0;
            let prof = match shown {
                None => return note(&painter, x0, r.top() + 12.0, "Click a player in the leaderboard or in the recent drops.", &f),
                Some((_, None)) => return note(&painter, x0, r.top() + 12.0, "Loading...", &f),
                Some((_, Some(Err(e)))) => {
                    return note(&painter, x0, r.top() + 12.0, &format!("Could not load the profile: {}", e.message()), &f);
                }
                Some((_, Some(Ok(pf)))) => pf,
            };
            let m = &prof.member;
            let fh = jtext::height(&painter, &f);
            let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0));
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(Rect::from_min_max(pos2(r.left(), r.top() + 12.0), r.max)));
            egui::ScrollArea::vertical().id_salt("guild-member").show(&mut child, |ui| {
                let top = ui.cursor().min.y;
                let painter = ui.painter().clone();
                let w = r.width() - 28.0;
                let mut y = top;
                let big = crate::theme::head(40.0);
                jtext::draw(&painter, pos2(x0, y), Align2::LEFT_TOP, &m.ign, &big, p.gold);
                y += jtext::height(&painter, &big);
                let active =
                    if m.last_active > 0 { format!(" - last active {}", crate::profile::ago(now, m.last_active)) } else { String::new() };
                note(&painter, x0, y, &format!("{} of {}{active}", lg_app::guild::rank_name(m.guild_rank), prof.guild), &f);
                y += fh + 8.0;
                let collectible = data.items.collectible.len();
                let of = if collectible > 0 { format!(" of {}", crate::profile::num(collectible as i64)) } else { String::new() };
                let line = format!(
                    "{}{of} UT/ST items ({} found)   -   {} fame on {} characters ({} maxed)   -   {} exaltations",
                    crate::profile::num(m.items),
                    crate::profile::num(m.finds),
                    crate::profile::num(m.fame),
                    m.characters,
                    m.maxed,
                    m.exalts
                );
                jtext::draw(&painter, pos2(x0, y), Align2::LEFT_TOP, &line, &f, p.ink);
                y += fh + 14.0;
                let heading = |y: &mut f32, t: &str| {
                    let hf = text(16.0);
                    jtext::draw(&painter, pos2(x0, *y + 2.0), Align2::LEFT_TOP, &t.to_uppercase(), &hf, p.ink);
                    *y += jtext::height(&painter, &hf) + 8.0;
                };

                // characters: living first, most fame first; three to a row
                heading(&mut y, "Characters");
                let mut chars: Vec<&lg_app::characters::Char> = prof.characters.iter().collect();
                chars.sort_by_key(|c| (!c.alive, -c.fame));
                if chars.is_empty() {
                    note(&painter, x0, y, "No characters synced yet.", &f);
                    y += fh;
                }
                let cw = (w - 16.0) / 3.0;
                let ch = 6.0 + (48.0f32).max(2.0 * fh + 28.0) + 6.0;
                for (i, ch_) in chars.iter().enumerate() {
                    let cx = x0 + (i % 3) as f32 * (cw + 8.0);
                    let cy = y + (i / 3) as f32 * (ch + 8.0);
                    let cr = card(&painter, &p, cx, cy, cw, ch);
                    let skin = if ch_.skin > 0 { ch_.skin } else { ch_.class_type };
                    let sr = Rect::from_min_size(pos2(cr.left() + 10.0, cr.center().y - 24.0), vec2(48.0, 48.0));
                    if let Some(t) = icons.skin(ui.ctx(), data, skin, ch_.tex1, ch_.tex2, 48) {
                        let tint = if ch_.alive { Color32::WHITE } else { Color32::from_rgba_unmultiplied(120, 120, 120, 140) };
                        painter.image(t.id(), sr, uv, tint);
                    }
                    let tx = sr.right() + 10.0;
                    let cls = format!("{}{}", ch_.class_name, if ch_.seasonal { "  (seasonal)" } else { "" });
                    jtext::draw(
                        &painter,
                        pos2(tx, cr.top() + 6.0),
                        Align2::LEFT_TOP,
                        &cls,
                        &f,
                        if ch_.alive { p.ink } else { fixed::MUTED },
                    );
                    let progress = if ch_.alive {
                        let lv =
                            if ch_.level < 20 { format!("Level {}", ch_.level) } else { format!("{} fame", crate::profile::num(ch_.fame)) };
                        format!("{lv} - {}/8", ch_.stats_maxed)
                    } else {
                        "Dead".into()
                    };
                    note(&painter, tx, cr.top() + 6.0 + fh, &progress, &f);
                    let mut gx = tx;
                    for (gi, id) in ch_.equipment.iter().flatten().enumerate() {
                        if *id <= 0 {
                            continue;
                        }
                        let gr = Rect::from_min_size(pos2(gx, cr.top() + 6.0 + 2.0 * fh), vec2(24.0, 24.0));
                        if let Some(t) = icons.object(ui.ctx(), data, *id, 24) {
                            painter.image(t.id(), gr, uv, Color32::WHITE);
                        }
                        let name = data.items.item(*id).map_or(format!("Item {id}"), |it| it.name.clone());
                        widgets::tip(ui, gr, ("member-gear", i, gi), &name);
                        gx += 26.0;
                    }
                }
                if !chars.is_empty() {
                    y += chars.len().div_ceil(3) as f32 * (ch + 8.0) - 8.0;
                }
                y += 14.0;

                // exaltations: most first, four to a row
                heading(&mut y, "Exaltations");
                let mut ex: Vec<&(String, Vec<i32>)> = prof.exalts.iter().collect();
                ex.sort_by_key(|(_, v)| -lg_app::exalt_store::total(Some(v)));
                if ex.is_empty() {
                    note(&painter, x0, y, "No exaltations synced yet.", &f);
                    y += fh;
                }
                let ew = (w - 48.0) / 4.0;
                for (i, (cls, v)) in ex.iter().enumerate() {
                    let name = cls
                        .parse::<i32>()
                        .ok()
                        .and_then(|t| data.classes.iter().find(|k| k.id == t))
                        .map_or(format!("Class {cls}"), |k| k.name.clone());
                    let ex_ = x0 + (i % 4) as f32 * (ew + 16.0);
                    let ey = y + (i / 4) as f32 * (fh + 2.0);
                    painter.rect_filled(
                        Rect::from_min_size(pos2(ex_, ey + fh / 2.0 - 4.0), vec2(8.0, 8.0)),
                        0.0,
                        crate::classes::class_color(&name),
                    );
                    let t = format!("{name}  {}/40", lg_app::exalt_store::total(Some(v)));
                    jtext::draw(&painter, pos2(ex_ + 12.0, ey), Align2::LEFT_TOP, &t, &f, p.ink);
                }
                if !ex.is_empty() {
                    y += ex.len().div_ceil(4) as f32 * (fh + 2.0);
                }
                y += 14.0;

                // the collection: by name, fourteen to a row
                heading(&mut y, &format!("Collection ({} items)", crate::profile::num(prof.collection.len() as i64)));
                let name_of = |id: &str| {
                    id.parse::<i32>()
                        .ok()
                        .and_then(|i| data.items.item(i))
                        .filter(|i| !i.name.is_empty())
                        .map_or(format!("Item {id}"), |i| i.name.clone())
                };
                let mut items: Vec<(String, &(String, i64))> = prof.collection.iter().map(|e| (name_of(&e.0), e)).collect();
                items.sort_by(|a, b| a.0.cmp(&b.0));
                if items.is_empty() {
                    note(&painter, x0, y, "Nothing synced yet.", &f);
                    y += fh;
                }
                let iw = (w - 13.0 * 4.0) / 14.0;
                let size = iw.min(48.0);
                for (i, (name, (id, n))) in items.iter().enumerate() {
                    let cell =
                        Rect::from_min_size(pos2(x0 + (i % 14) as f32 * (iw + 4.0), y + (i / 14) as f32 * (size + 4.0)), vec2(iw, size));
                    let ir = Rect::from_center_size(cell.center(), vec2(size, size));
                    match id.parse::<i32>().ok().and_then(|i| icons.object(ui.ctx(), data, i, size as u32)) {
                        Some(t) => {
                            painter.image(t.id(), ir, uv, Color32::WHITE);
                        }
                        None => {
                            jtext::draw(&painter, ir.left_center(), Align2::LEFT_CENTER, id, &f, p.ink);
                        }
                    }
                    widgets::tip(ui, ir, ("member-item", i), &format!("{name} x{n}"));
                }
                if !items.is_empty() {
                    y += items.len().div_ceil(14) as f32 * (size + 4.0);
                }
                y += 14.0;
                ui.allocate_exact_size(vec2(w, y - top), egui::Sense::hover());
            });
        },
    );
}

/// The sky with its stars and moon, the hills, the road with its moving lines (Java `Scene.paintBackdrop`).
pub fn backdrop(painter: &egui::Painter, r: Rect, phase: i64) {
    const HEIGHT: f32 = 440.0;
    let w = r.width().floor();
    let at = |x: f32, y: f32| pos2(r.left() + x, r.top() + y);
    let star = rgb(0xF4E6C6);
    painter.rect_filled(Rect::from_min_size(r.min, vec2(w, HEIGHT)), 0.0, rgb(0x141B2B));
    let mut rnd = lg_app::java::JavaRandom::new(7);
    for i in 0..40 {
        let sx = i64::from(rnd.next_int(10_000));
        let sy = 8 + rnd.next_int(150);
        let dim = (phase / 12 + i) % 7 == 0;
        // Color.darker() twice: 0.7 * 0.7
        let c = if dim {
            Color32::from_rgb(
                ((0xF4 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
                ((0xE6 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
                ((0xC6 as f32 * 0.7) as u8 as f32 * 0.7) as u8,
            )
        } else {
            star
        };
        painter.rect_filled(Rect::from_min_size(at((sx * w as i64 / 10_000) as f32, sy as f32), vec2(4.0, 4.0)), 0.0, c);
    }
    let mx = w - 120f32.max((w / 7.0).floor());
    painter.rect_filled(Rect::from_min_size(at(mx - 6.0, 28.0), vec2(60.0, 60.0)), 0.0, Color32::from_rgba_unmultiplied(244, 230, 198, 30));
    painter.rect_filled(Rect::from_min_size(at(mx, 34.0), vec2(48.0, 48.0)), 0.0, star);
    painter.rect_filled(Rect::from_min_size(at(mx + 8.0, 44.0), vec2(10.0, 10.0)), 0.0, rgb(0xD2B988));
    painter.rect_filled(Rect::from_min_size(at(mx + 28.0, 62.0), vec2(6.0, 6.0)), 0.0, rgb(0xD2B988));
    let hills = |top: f32, height: f32, color: Color32, peaks: &[i32]| {
        let n = peaks.len() as i64;
        let mut pts: Vec<egui::Pos2> = peaks
            .iter()
            .enumerate()
            .map(|(i, pk)| at((i as i64 * w as i64 / (n - 1)) as f32, top + height - (height as i64 * i64::from(100 - pk) / 100) as f32))
            .collect();
        pts.push(at(w, top + height));
        pts.push(at(0.0, top + height));
        // not convex: a triangle strip between each top point and the band's bottom
        let mut mesh = egui::Mesh::default();
        let base = top + height;
        for k in 0..peaks.len() - 1 {
            let (a, b) = (pts[k], pts[k + 1]);
            let i = mesh.vertices.len() as u32;
            mesh.colored_vertex(a, color);
            mesh.colored_vertex(b, color);
            mesh.colored_vertex(pos2(b.x, r.top() + base), color);
            mesh.colored_vertex(pos2(a.x, r.top() + base), color);
            mesh.add_triangle(i, i + 1, i + 2);
            mesh.add_triangle(i, i + 2, i + 3);
        }
        painter.add(egui::Shape::mesh(mesh));
    };
    hills(HEIGHT - 150.0, 120.0, rgb(0x1E2A2A), &[70, 40, 55, 20, 50, 35, 60, 25, 50, 30, 55]);
    hills(HEIGHT - 110.0, 90.0, rgb(0x24301F), &[50, 30, 60, 35, 55, 30, 60, 40, 60]);
    painter.rect_filled(Rect::from_min_size(at(0.0, HEIGHT - 112.0), vec2(w, 112.0)), 0.0, rgb(0x4A3522));
    painter.rect_filled(Rect::from_min_size(at(0.0, HEIGHT - 112.0), vec2(w, 4.0)), 0.0, rgb(0x6B4E2E));
    let mut x = -52 + (phase * 2) % 52;
    while (x as f32) < w {
        painter.rect_filled(Rect::from_min_size(at(x as f32, HEIGHT - 50.0), vec2(26.0, 4.0)), 0.0, rgb(0x6B4E2E));
        x += 52;
    }
    let mut x = -40 + (phase * 2) % 40;
    while (x as f32) < w {
        painter.rect_filled(Rect::from_min_size(at(x as f32, HEIGHT - 22.0), vec2(14.0, 4.0)), 0.0, rgb(0x3A2818));
        x += 40;
    }
}
