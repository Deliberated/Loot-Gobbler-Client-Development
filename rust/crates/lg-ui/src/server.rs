//! The Server tab (Java `ServerPanel`): the Loot Gobbler server this client is connected to, logging in or
//! registering, and the sync of your records with it.
//! Not yet: moderation, moderator tokens, item scores and the guild settings (for moderators and admins).

use crate::jtext;
use crate::profile::Ctx;
use crate::theme::{Palette, fixed};
use crate::widgets::{self, small, text};
use crate::window::{Ask, ServerAsk};
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};
use lg_app::server::{API_VERSION, State};
use lg_app::sync::Phase;

/// A text field (Java `JTextField`): paper with a line border, the text or its placeholder.
pub fn text_field(painter: &egui::Painter, p: &Palette, r: Rect, value: &str, placeholder: &str) {
    painter.rect(r, 0.0, p.paper, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let f = text(18.0);
    let clip = painter.with_clip_rect(r.shrink(2.0).intersect(painter.clip_rect()));
    if value.is_empty() {
        jtext::draw(&clip, pos2(r.left() + 8.0, r.center().y), Align2::LEFT_CENTER, placeholder, &f, p.ink_muted);
    } else {
        jtext::draw(&clip, pos2(r.left() + 8.0, r.center().y), Align2::LEFT_CENTER, value, &f, p.ink);
    }
}

/// An editable field: true when Enter was pressed in it. Disabled: greyed, not editable.
fn field(ui: &mut Ui, p: &Palette, r: Rect, value: &mut String, placeholder: &str, id: &str, password: bool, enabled: bool) -> bool {
    let painter = ui.painter().clone();
    let font = text(18.0);
    let inner = r.shrink2(vec2(8.0, 2.0));
    painter.rect(r, 0.0, if enabled { p.paper } else { p.header }, Stroke::new(1.0, p.line), StrokeKind::Inside);
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(inner).layout(egui::Layout::left_to_right(egui::Align::Center)));
    child.visuals_mut().extreme_bg_color = Color32::TRANSPARENT;
    child.visuals_mut().selection.bg_fill = p.text_sel;
    child.visuals_mut().text_cursor.stroke = Stroke::new(1.5, p.ink);
    let edit = egui::TextEdit::singleline(value)
        .id_salt(id)
        .frame(egui::Frame::NONE)
        .password(password)
        .font(font.clone())
        .text_color(if enabled { p.ink } else { p.ink_muted })
        .hint_text(egui::RichText::new(placeholder).font(font).color(p.ink_muted))
        .desired_width(inner.width())
        .margin(vec2(0.0, 0.0));
    let resp = child.add_enabled(enabled, edit);
    if resp.has_focus() {
        painter.rect_stroke(r, 0.0, Stroke::new(1.0, p.gold), StrokeKind::Inside);
    }
    resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter))
}

/// A button by its left edge (primary: gold); its rect and whether it was clicked.
fn button_at(ui: &Ui, p: &Palette, x: f32, cy: f32, label: &str, primary: bool, enabled: bool) -> (Rect, bool) {
    let painter = ui.painter();
    let w = jtext::width(painter, &label.to_uppercase(), &small()) + 32.0;
    let r = Rect::from_min_size(pos2(x, cy - 18.0), vec2(w, 36.0));
    if !enabled {
        painter.rect(r, 0.0, p.header, Stroke::new(1.5, p.line), StrokeKind::Inside);
        jtext::draw(painter, r.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &small(), p.ink_muted);
        return (r, false);
    }
    if primary {
        let resp = ui.interact(r, ui.scope_id().with(("server-button", label)), Sense::click());
        painter.rect(r, 0.0, p.gold, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
        jtext::draw(painter, r.center(), Align2::CENTER_CENTER, &label.to_uppercase(), &small(), p.on_accent);
        (r, resp.clicked())
    } else {
        let clicked = crate::inventory::button(ui, p, r, label, false, label);
        (r, clicked)
    }
}

/// What is typed into the tab, kept from frame to frame (Java keeps its fields across rebuilds).
#[derive(Clone, Default)]
struct Form {
    started: bool,
    address: String,
    ign: String,
    password: String,
    register_token: String,
    redeem: String,
    token_note: String,
    validity: usize,
    /// The weights and the baseline as typed, and the load they were last shown from.
    weights: [String; 3],
    baseline: String,
    loaded: Option<u64>,
}

const VALIDITY: [&str; 4] = ["1 hour", "24 hours", "3 days", "7 days"];
const VALIDITY_HOURS: [i32; 4] = [1, 24, 72, 168];

/// Java `NumberEditor` patterns: "0.00" for the weights, "0.#" for the baseline.
fn two_places(v: f64) -> String {
    format!("{v:.2}")
}

fn one_place(v: f64) -> String {
    let s = format!("{v:.1}");
    s.strip_suffix(".0").map_or(s.clone(), str::to_string)
}

/// Java `heading`: small upper-case text.
fn heading(rw: &mut Rows, t: &str) {
    let f = text(16.0); // FlatLaf's "small": 2 smaller
    jtext::draw(&rw.painter, pos2(rw.x + 6.0, rw.y + 2.0), Align2::LEFT_TOP, &t.to_uppercase(), &f, rw.p.ink);
    rw.y += jtext::height(&rw.painter, &f) + 6.0;
}

#[derive(Clone, Copy, PartialEq)]
enum Style {
    Plain,
    Primary,
    Danger,
}

/// Java `listRow`: a bordered row with a title, a muted line under it and buttons at the right; which was clicked.
fn list_row(ui: &Ui, rw: &mut Rows, w: f32, title: &str, sub: &str, buttons: &[(&str, Style)], id: &str) -> Option<usize> {
    let p = rw.p;
    let h = 6.0 + 2.0 * rw.fh + 6.0;
    let r = Rect::from_min_size(pos2(rw.x, rw.y), vec2(w, h));
    rw.painter.rect(r, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    jtext::draw(&rw.painter, pos2(r.left() + 10.0, r.top() + 6.0), Align2::LEFT_TOP, title, &rw.f, p.ink);
    jtext::draw(&rw.painter, pos2(r.left() + 10.0, r.top() + 6.0 + rw.fh), Align2::LEFT_TOP, sub, &rw.f, fixed::MUTED);
    let mut clicked = None;
    let mut bx = r.right() - 8.0;
    for (i, (label, style)) in buttons.iter().enumerate().rev() {
        let bw = jtext::width(&rw.painter, &label.to_uppercase(), &small()) + 32.0;
        let br = Rect::from_min_size(pos2(bx - bw, r.center().y - 18.0), vec2(bw, 36.0));
        bx = br.left() - 6.0;
        let hit = match style {
            Style::Primary => button_at(ui, p, br.left(), br.center().y, label, true, true).1,
            Style::Danger => crate::inventory::button(ui, p, br, label, true, &format!("{id}-{i}")),
            Style::Plain => crate::inventory::button(ui, p, br, label, false, &format!("{id}-{i}")),
        };
        if hit {
            clicked = Some(i);
        }
    }
    rw.y += h + 6.0;
    clicked
}

/// Java `JSpinner`: the number as typed, arrows that step it within its range.
fn spinner(ui: &mut Ui, p: &Palette, r: Rect, value: &mut String, id: &str, step: f64, min: f64, max: f64, fmt: fn(f64) -> String) {
    let field_r = Rect::from_min_max(r.min, pos2(r.right() - 18.0, r.bottom()));
    field(ui, p, field_r, value, "", id, false, true);
    let painter = ui.painter();
    let arrows = Rect::from_min_max(pos2(r.right() - 18.0, r.top()), r.max);
    painter.rect(arrows, 0.0, p.header, Stroke::new(1.0, p.line), StrokeKind::Inside);
    for (k, up) in [(0, true), (1, false)] {
        let half =
            Rect::from_min_size(pos2(arrows.left(), arrows.top() + k as f32 * arrows.height() / 2.0), vec2(18.0, arrows.height() / 2.0));
        let c = half.center();
        let pts = if up {
            vec![pos2(c.x - 4.0, c.y + 2.0), pos2(c.x + 4.0, c.y + 2.0), pos2(c.x, c.y - 3.0)]
        } else {
            vec![pos2(c.x - 4.0, c.y - 2.0), pos2(c.x + 4.0, c.y - 2.0), pos2(c.x, c.y + 3.0)]
        };
        painter.add(egui::Shape::convex_polygon(pts, p.ink_soft, Stroke::NONE));
        if ui.interact(half, ui.scope_id().with((id, k)), Sense::click()).clicked() {
            let v = value.trim().parse::<f64>().unwrap_or(min);
            let n = if up { v + step } else { v - step };
            *value = fmt(n.clamp(min, max));
        }
    }
}

/// A typed number within its range (Java `commitEdit`), else None.
fn number(t: &str, min: f64, max: f64) -> Option<f64> {
    t.trim().parse::<f64>().ok().filter(|v| (min..=max).contains(v))
}

/// Java's "d MMM yyyy, HH:mm" and "HH:mm:ss", in local time.
fn date(zone: &lg_app::localtime::Zone, ms: i64) -> String {
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let (y, m, d, h, mi, _) = zone.fields(ms);
    format!("{d} {} {y}, {h:02}:{mi:02}", MONTHS[(m - 1) as usize])
}

fn time(zone: &lg_app::localtime::Zone, ms: i64) -> String {
    let (_, _, _, h, mi, s) = zone.fields(ms);
    format!("{h:02}:{mi:02}:{s:02}")
}

/// Java `transfers`: what the latest sync did with this kind.
fn transfers(zone: &lg_app::localtime::Zone, k: &lg_app::sync::Kind) -> String {
    let rev = if k.revision > 0 { format!("revision {}", k.revision) } else { "not on the server yet".into() };
    if k.checked_at == 0 {
        return rev;
    }
    let directions = |down: bool, up: bool| {
        let d = format!("server -> client {}", time(zone, k.last_down));
        let u = format!("client -> server {}", time(zone, k.last_up));
        match (down, up) {
            (true, true) if k.last_down <= k.last_up => format!("{d}, {u}"),
            (true, true) => format!("{u}, {d}"),
            (true, false) => d,
            _ => u,
        }
    };
    let (down, up) = (k.last_down >= k.checked_at, k.last_up >= k.checked_at);
    if down || up {
        return format!("{rev} - {}", directions(down, up));
    }
    let text = format!("{rev} - already the same on both sides (checked {})", time(zone, k.synced_at));
    let (ever_down, ever_up) = (k.last_down > 0, k.last_up > 0);
    if ever_down || ever_up { format!("{text}, last transfer {}", directions(ever_down, ever_up)) } else { text }
}

/// Lines drawn down a section's body (Java's BoxLayout rows).
struct Rows<'a> {
    painter: egui::Painter,
    x: f32,
    y: f32,
    top: f32,
    fh: f32,
    f: egui::FontId,
    p: &'a Palette,
}

impl Rows<'_> {
    /// Java `statusLine`: a square of the colour and the text in it.
    fn status(&mut self, color: Color32, t: &str) {
        let ty = self.y + 4.0;
        let sq = Rect::from_min_size(pos2(self.x + 6.0, ty + self.fh / 2.0 - 5.0), vec2(10.0, 10.0));
        self.painter.rect_filled(sq, 0.0, color);
        jtext::draw(&self.painter, pos2(sq.right() + 10.0, ty + self.fh / 2.0), Align2::LEFT_CENTER, t, &self.f, color);
        self.y += self.fh + 10.0;
    }

    /// Java `info`: a muted label in a 150 wide column, then the value.
    fn info(&mut self, label: &str, value: &str) {
        let ty = self.y + 1.0;
        jtext::draw(&self.painter, pos2(self.x + 6.0, ty), Align2::LEFT_TOP, label, &self.f, fixed::MUTED);
        jtext::draw(&self.painter, pos2(self.x + 6.0 + 150.0 + 8.0, ty), Align2::LEFT_TOP, value, &self.f, self.p.ink);
        self.y += self.fh + 2.0;
    }

    fn muted(&mut self, t: &str) {
        jtext::draw(&self.painter, pos2(self.x, self.y), Align2::LEFT_TOP, t, &self.f, fixed::MUTED);
        self.y += self.fh;
    }

    /// Java's feedback label (" " when empty).
    fn feedback(&mut self, t: &str) {
        jtext::draw(&self.painter, pos2(self.x + 6.0, self.y + 6.0), Align2::LEFT_TOP, t, &self.f, self.p.ink);
        self.y += self.fh + 8.0;
    }

    fn height(&self) -> f32 {
        self.y - self.top
    }
}

pub fn show(ui: &mut Ui, c: &mut Ctx) {
    let p = *c.p;
    let app = c.app;
    let server = &app.server;
    let sync = &app.sync;
    let zone = &app.zone;
    let id = egui::Id::unique("server-form");
    let mut form: Form = ui.data(|d| d.get_temp(id)).unwrap_or_default();
    if !form.started {
        form.started = true;
        form.validity = 1; // 24 hours
        form.address = server.url.clone();
    }
    if form.address.trim().is_empty() && !server.url.is_empty() {
        form.address = server.url.clone(); // connected on start, or from elsewhere
    }
    if server.clear_password {
        form.password.clear();
    }
    if server.clear_register_token {
        form.register_token.clear();
    }
    if server.clear_redeem {
        form.redeem.clear();
    }
    let mut asks: Vec<Ask> = Vec::new();
    let user = if server.state == State::Online { server.user.as_ref() } else { None };
    let f = text(18.0);
    let body = ui.max_rect();
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(body));
    egui::ScrollArea::vertical().id_salt("server").show(&mut child, |ui| {
        let top = ui.cursor().min;
        let w = ui.available_width() - 16.0;
        let x = top.x + 8.0;
        let mut y = top.y + 8.0;
        let fh = jtext::height(ui.painter(), &f);
        let rows = |ui: &Ui, r: Rect| Rows { painter: ui.painter().clone(), x: r.left() + 1.0, y: r.top(), top: r.top(), fh, f: f.clone(), p: &p };

        // --- connection ---
        y += widgets::section_box(
            ui,
            &p,
            pos2(x, y),
            w,
            "Connection",
            fixed::BLUE,
            |_, _| {},
            |ui, r| {
                let mut rw = rows(ui, r);
                let cy = rw.y + 22.0;
                let lr = jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, "Server address", &f, p.ink);
                let fr = Rect::from_min_size(pos2(lr.right() + 6.0, cy - 17.0), vec2(330.0, 34.0));
                if field(ui, &p, fr, &mut form.address, "loot.example.com", "server-address", false, true) {
                    asks.push(Ask::Server(ServerAsk::Connect(form.address.clone())));
                }
                let (b, connect) = button_at(ui, &p, fr.right() + 6.0, cy, "Connect", true, true);
                if connect {
                    asks.push(Ask::Server(ServerAsk::Connect(form.address.clone())));
                }
                let (b, test) = button_at(ui, &p, b.right() + 6.0, cy, "Test connection", false, true);
                if test {
                    asks.push(Ask::Server(ServerAsk::Test(form.address.clone())));
                }
                if !server.url.is_empty() && button_at(ui, &p, b.right() + 6.0, cy, "Forget", false, true).1 {
                    asks.push(Ask::Confirm {
                        title: "Forget server".into(),
                        text: format!("Log out and forget {}?", server.url),
                        yes: "Yes".into(),
                        no: "No".into(),
                        then: Box::new(Ask::Server(ServerAsk::Forget)),
                    });
                }
                rw.y += 44.0;
                match server.state {
                    State::Disconnected => rw.status(fixed::MUTED, "Not connected. Enter the address the server's host gave you."),
                    State::Connecting => rw.status(fixed::MUTED, "Connecting..."),
                    State::Unreachable => rw.status(fixed::RED, &format!("Not reachable: {}", server.error)),
                    State::Online => {
                        rw.status(
                            fixed::GREEN,
                            if server.encrypted() { "Online" } else { "Online - not encrypted (http://), only use this on your own network" },
                        );
                        if let Some(st) = &server.status {
                            rw.info("Server version", &st.version);
                            rw.info(
                                "Admin",
                                if st.admin_claimed { "Claimed" } else { "Not claimed yet - the host registers with the token from the server console" },
                            );
                            let reg = if st.registration == "approval" { "Need to be accepted by a moderator or admin" } else { &st.registration };
                            rw.info("New accounts", reg);
                            if st.api_version != API_VERSION {
                                rw.status(
                                    fixed::RED,
                                    if st.api_version > API_VERSION {
                                        "This server needs a newer Loot Gobbler - please update"
                                    } else {
                                        "This server is older than your Loot Gobbler - ask the host to update it"
                                    },
                                );
                            }
                        }
                    }
                }
                // the last connection test's checklist
                match &server.test {
                    None => {}
                    Some(None) => rw.status(fixed::MUTED, "Testing..."),
                    Some(Some(result)) => {
                        heading(&mut rw, "Connection test");
                        match result {
                            Err(e) => {
                                rw.status(fixed::RED, &format!("Server not reached: {e}"));
                                rw.status(
                                    fixed::MUTED,
                                    "Check the address, that the server is running (docker ps) and that nginx proxy manager forwards to it.",
                                );
                            }
                            Ok(t) => {
                                rw.status(fixed::GREEN, &format!("Server reached in {} ms (version {})", t.millis, t.status.version));
                                if server.encrypted() {
                                    rw.status(fixed::GREEN, "Connection is encrypted (https)");
                                } else {
                                    rw.status(fixed::GOLD, "Not encrypted (http) - only fine on your own network");
                                }
                                if t.status.api_version == API_VERSION {
                                    rw.status(fixed::GREEN, "Server and client speak the same API version");
                                } else {
                                    rw.status(
                                        fixed::RED,
                                        &format!(
                                            "API version differs (server {}, client {API_VERSION}) - update the older one",
                                            t.status.api_version
                                        ),
                                    );
                                }
                                if t.private_ip {
                                    rw.status(fixed::GOLD, &format!("The server sees you as {} (a local address)", t.your_ip));
                                    rw.status(fixed::MUTED, "Fine on your own network. Over the internet, the host should check TRUSTED_PROXIES.");
                                } else {
                                    rw.status(fixed::GREEN, &format!("The server sees you as {}", t.your_ip));
                                }
                                match &t.user {
                                    Some(u) => rw.status(fixed::GREEN, &format!("Logged in as {} ({})", u.ign, u.role_name())),
                                    None if t.session_sent => rw.status(fixed::GOLD, "Your login expired - log in again below"),
                                    None => rw.status(fixed::MUTED, "Not logged in"),
                                }
                                rw.status(fixed::MUTED, "The host sees this test in the server log (docker logs loot-gobbler).");
                            }
                        }
                    }
                }
                rw.feedback(&server.connection_feedback);
                rw.height()
            },
        ) + 8.0;

        // --- account ---
        y += widgets::section_box(
            ui,
            &p,
            pos2(x, y),
            w,
            "Account",
            fixed::GOLD,
            |_, _| {},
            |ui, r| {
                let mut rw = rows(ui, r);
                rw.x = r.left() - 11.0 + 12.0;
                if server.state != State::Online {
                    rw.muted("Connect to a server to log in or register.");
                } else if let Some(u) = &server.user {
                    let big = crate::theme::head(36.0);
                    jtext::draw(&rw.painter, pos2(rw.x, rw.y), Align2::LEFT_TOP, &u.ign, &big, p.gold);
                    rw.y += jtext::height(&rw.painter, &big);
                    rw.info("Role", u.role_name());
                    rw.info("Member since", &date(zone, u.created_at));
                    if let Some(l) = u.last_login_at {
                        rw.info("Last login", &date(zone, l));
                    }
                    rw.y += 6.0;
                    let cy = rw.y + 22.0;
                    let lr = jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, "Got a token?", &f, p.ink);
                    let fr = Rect::from_min_size(pos2(lr.right() + 6.0, cy - 17.0), vec2(352.0, 34.0));
                    let enter = field(ui, &p, fr, &mut form.redeem, "LGM-... or LGA-...", "server-redeem", false, true);
                    if button_at(ui, &p, fr.right() + 6.0, cy, "Redeem", false, true).1 || enter {
                        asks.push(Ask::Server(ServerAsk::Redeem(form.redeem.clone())));
                    }
                    rw.y += 44.0;
                    let cy = rw.y + 22.0;
                    let (b, change) = button_at(ui, &p, rw.x + 6.0, cy, "Change password", false, true);
                    if change {
                        asks.push(Ask::Server(ServerAsk::OpenChange));
                    }
                    if button_at(ui, &p, b.right() + 6.0, cy, "Log out", false, true).1 {
                        asks.push(Ask::Server(ServerAsk::Logout));
                    }
                    rw.y += 44.0;
                } else {
                    // your name as the game showed it: an account always carries your real IGN
                    let known = app.characters.player_name.as_deref().filter(|n| !n.trim().is_empty());
                    if let Some(k) = known {
                        form.ign = k.to_string();
                    }
                    let form_row = |ui: &mut Ui, rw: &mut Rows, label: &str, value: &mut String, ph: &str, fid: &str, width: f32, pw: bool, on: bool| {
                        let cy = rw.y + 20.0;
                        jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, label, &f, p.ink);
                        let fr = Rect::from_min_size(pos2(rw.x + 6.0 + 120.0 + 6.0, cy - 17.0), vec2(width, 34.0));
                        let enter = field(ui, &p, fr, value, ph, fid, pw, on);
                        rw.y += 40.0;
                        (fr, enter)
                    };
                    let (ir, _) = form_row(ui, &mut rw, "IGN", &mut form.ign, "Your IGN", "server-ign", 174.0, false, known.is_none());
                    if known.is_some() {
                        widgets::tip(ui, ir, "server-ign-tip", "Your name, as the game shows it");
                    }
                    let (_, enter) = form_row(ui, &mut rw, "Password", &mut form.password, "", "server-password", 174.0, true, true);
                    if enter {
                        asks.push(Ask::Server(ServerAsk::Login(form.ign.clone(), form.password.clone())));
                    }
                    form_row(ui, &mut rw, "Token", &mut form.register_token, "Admin or moderator token (optional)", "server-token", 352.0, false, true);
                    let cy = rw.y + 22.0;
                    let (b, login) = button_at(ui, &p, rw.x + 6.0, cy, "Log in", true, true);
                    if login {
                        asks.push(Ask::Server(ServerAsk::Login(form.ign.clone(), form.password.clone())));
                    }
                    let (b, register) = button_at(ui, &p, b.right() + 6.0, cy, "Register", false, known.is_some());
                    if register {
                        asks.push(Ask::Server(ServerAsk::Register(form.password.clone(), form.register_token.clone())));
                    }
                    if known.is_none() {
                        widgets::tip(ui, b, "server-register-tip", "Loot Gobbler needs to see your name in-game first");
                    }
                    let (fb, forgot) = button_at(ui, &p, b.right() + 6.0, cy, "Forgot password?", false, true);
                    if forgot {
                        asks.push(Ask::Server(ServerAsk::OpenForgot(form.ign.clone())));
                    }
                    widgets::tip(ui, fb, "server-forgot-tip", "Set a new password with a reset code from a moderator or admin");
                    rw.y += 44.0;
                    if known.is_none() {
                        rw.muted("To register, start the sniffer and enter the Nexus or your vault in-game:");
                        rw.muted("Loot Gobbler then knows your name and fills it in here.");
                    }
                    rw.muted("New accounts wait until a moderator or admin accepts them.");
                    rw.muted("With an admin or moderator token, your new account is ready right away.");
                }
                rw.feedback(&server.account_feedback);
                rw.height()
            },
        ) + 8.0;

        // --- sync, once logged in ---
        let sync_now = std::cell::Cell::new(false);
        if user.is_some() {
            y += widgets::section_box(
                ui,
                &p,
                pos2(x, y),
                w,
                "Sync",
                fixed::GREEN,
                |ui, head| {
                    let bw = jtext::width(ui.painter(), "SYNC NOW", &small()) + 32.0;
                    let r = Rect::from_min_size(pos2(head.right() - 6.0 - bw, head.center().y - 15.0), vec2(bw, 30.0));
                    if crate::inventory::button(ui, &p, r, "Sync now", false, "sync-now") {
                        sync_now.set(true);
                    }
                },
                |ui, r| {
                    let mut rw = rows(ui, r);
                    match sync.phase {
                        Phase::Off => rw.status(fixed::MUTED, "Starts once you are logged in."),
                        Phase::Syncing => rw.status(fixed::MUTED, "Syncing..."),
                        Phase::UpToDate => {
                            let last = if sync.last_sync > 0 { format!(" - last sync {}", time(zone, sync.last_sync)) } else { String::new() };
                            rw.status(fixed::GREEN, &format!("Up to date{last}"));
                        }
                        Phase::Waiting => rw.status(fixed::GOLD, "New changes are uploaded within a minute"),
                        Phase::Offline => {
                            rw.status(fixed::RED, &format!("Server not reachable: {}", sync.message));
                            rw.status(fixed::MUTED, "Your changes are kept on this PC and uploaded when it is back.");
                        }
                        Phase::OtherAccount => {
                            rw.status(fixed::GOLD, &format!("The data on this PC was synced as {}.", sync.message));
                            rw.status(
                                fixed::MUTED,
                                "Syncing is paused so two players' data are not mixed. If it is yours, merge it into this account.",
                            );
                            let cy = rw.y + 22.0;
                            if button_at(ui, &p, rw.x + 6.0, cy, "Use for this account", true, true).1 {
                                let ign = user.map_or(String::new(), |u| u.ign.clone());
                                asks.push(Ask::Confirm {
                                    title: "Use data".into(),
                                    text: format!("Merge the data on this PC into {ign}'s account?"),
                                    yes: "Yes".into(),
                                    no: "No".into(),
                                    then: Box::new(Ask::Server(ServerAsk::Adopt)),
                                });
                            }
                            rw.y += 44.0;
                        }
                    }
                    for k in &sync.kinds {
                        rw.info(k.label, &format!("{} - {}", app.sync_count(k.kind), transfers(zone, k)));
                    }
                    rw.height()
                },
            ) + 8.0;
        }
        if sync_now.get() {
            asks.push(Ask::Server(ServerAsk::SyncNow));
        }

        // --- for moderators and admins ---
        let more = std::cell::RefCell::new(Vec::<Ask>::new());
        let ask = |a: ServerAsk| more.borrow_mut().push(Ask::Server(a));
        let confirm = |title: &str, text: String, then: ServerAsk| {
            more.borrow_mut().push(Ask::Confirm {
                title: title.into(),
                text,
                yes: "Yes".into(),
                no: "No".into(),
                then: Box::new(Ask::Server(then)),
            })
        };
        let m = &server.moderation;
        if let Some(me) = user.filter(|u| u.at_least_moderator()) {
            y += widgets::section_box(
                ui,
                &p,
                pos2(x, y),
                w,
                "Moderation",
                fixed::PURPLE,
                |ui, head| {
                    let bw = jtext::width(ui.painter(), "REFRESH", &small()) + 32.0;
                    let r = Rect::from_min_size(pos2(head.right() - 6.0 - bw, head.center().y - 15.0), vec2(bw, 30.0));
                    if crate::inventory::button(ui, &p, r, "Refresh", false, "moderation-refresh") {
                        ask(ServerAsk::RefreshModeration);
                    }
                },
                |ui, r| {
                    let mut rw = rows(ui, r);
                    let rw_w = r.width() - 2.0;
                    heading(&mut rw, &format!("Waiting for approval ({})", m.pending.len()));
                    if m.pending.is_empty() {
                        rw.muted("No one is waiting.");
                    }
                    for u in &m.pending {
                        let sub = format!("registered {}", date(zone, u.created_at));
                        match list_row(ui, &mut rw, rw_w, &u.ign, &sub, &[("Accept", Style::Primary), ("Deny", Style::Danger)], &format!("pending-{}", u.id)) {
                            Some(0) => ask(ServerAsk::Accept(u.id, u.ign.clone())),
                            Some(_) => confirm("Deny registration", format!("Deny {}'s registration?", u.ign), ServerAsk::Deny(u.id, u.ign.clone())),
                            None => {}
                        }
                    }
                    rw.y += 10.0;
                    heading(&mut rw, &format!("Members ({})", m.members.len()));
                    for u in &m.members {
                        let outranks = !u.root && u.id != me.id && me.rank() > u.rank();
                        let mut buttons: Vec<(&str, Style, u8)> = Vec::new();
                        if outranks && me.at_least_admin() && u.rank() == 1 {
                            buttons.push(("Demote", Style::Plain, 0));
                        }
                        if outranks && me.root && u.rank() == 2 {
                            buttons.push(("Demote", Style::Plain, 1));
                        }
                        if outranks {
                            buttons.push(("Reset password", Style::Plain, 2));
                            buttons.push(("Remove", Style::Danger, 3));
                        }
                        let sub = match u.last_login_at {
                            Some(l) => format!("{} - last login {}", u.role_name(), date(zone, l)),
                            None => format!("{} - never logged in", u.role_name()),
                        };
                        let title = if u.id == me.id { format!("{} (you)", u.ign) } else { u.ign.clone() };
                        let shown: Vec<(&str, Style)> = buttons.iter().map(|b| (b.0, b.1)).collect();
                        if let Some(i) = list_row(ui, &mut rw, rw_w, &title, &sub, &shown, &format!("member-{}", u.id)) {
                            match buttons[i].2 {
                                0 => ask(ServerAsk::Demote(u.id, u.ign.clone(), "user".into())),
                                1 => ask(ServerAsk::Demote(u.id, u.ign.clone(), "moderator".into())),
                                2 => confirm(
                                    "Reset password",
                                    format!("Make a password reset code for {}?\nTheir current password keeps working until they use it.", u.ign),
                                    ServerAsk::ResetCode(u.id),
                                ),
                                _ => confirm(
                                    "Remove member",
                                    format!("Remove {} and all their data from the server?", u.ign),
                                    ServerAsk::Remove(u.id, u.ign.clone()),
                                ),
                            }
                        }
                    }
                    rw.feedback(&m.feedback);
                    rw.height()
                },
            ) + 8.0;

            if me.at_least_admin() {
                // --- moderator tokens ---
                if m.clear_note {
                    form.token_note.clear();
                }
                y += widgets::section_box(ui, &p, pos2(x, y), w, "Moderator Tokens", fixed::PURPLE, |_, _| {}, |ui, r| {
                    let mut rw = rows(ui, r);
                    let rw_w = r.width() - 2.0;
                    rw.muted("Give a token to someone you trust to make them a moderator. Each token works once.");
                    rw.muted("They enter it when registering (no approval needed then) or under Account.");
                    let cy = rw.y + 22.0;
                    let lr = jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, "For", &f, p.ink);
                    let nr = Rect::from_min_size(pos2(lr.right() + 6.0, cy - 17.0), vec2(222.0, 34.0));
                    field(ui, &p, nr, &mut form.token_note, "Who it is for (optional)", "token-note", false, true);
                    let vr = jtext::draw(&rw.painter, pos2(nr.right() + 6.0, cy), Align2::LEFT_CENTER, "Valid for", &f, p.ink);
                    let cr = Rect::from_min_size(pos2(vr.right() + 6.0, cy - 17.0), vec2(120.0, 34.0));
                    let mut combo = ui.new_child(egui::UiBuilder::new().max_rect(cr));
                    egui::ComboBox::from_id_salt("token-validity")
                        .width(cr.width() - 8.0)
                        .selected_text(egui::RichText::new(VALIDITY[form.validity]).font(f.clone()).color(p.ink))
                        .show_ui(&mut combo, |ui| {
                            for (i, v) in VALIDITY.iter().enumerate() {
                                ui.selectable_value(&mut form.validity, i, egui::RichText::new(*v).font(f.clone()));
                            }
                        });
                    if button_at(ui, &p, cr.right() + 6.0, cy, "Create token", true, true).1 {
                        ask(ServerAsk::CreateToken(form.token_note.clone(), VALIDITY_HOURS[form.validity]));
                    }
                    rw.y += 44.0;
                    if let Some(t) = &m.last_issued {
                        let cy = rw.y + 22.0;
                        let tr = Rect::from_min_size(pos2(rw.x + 6.0, cy - 17.0), vec2(410.0, 34.0));
                        text_field(&rw.painter, &p, tr, &t.token, "");
                        if button_at(ui, &p, tr.right() + 6.0, cy, "Copy", true, true).1 {
                            ui.ctx().copy_text(t.token.clone());
                            ask(ServerAsk::TokenCopied);
                        }
                        rw.y += 44.0;
                        rw.muted(&format!("Copy it now - it is shown only once. Valid until {}.", date(zone, t.expires_at)));
                    }
                    rw.y += 10.0;
                    heading(&mut rw, &format!("Unused tokens ({})", m.tokens.len()));
                    if m.tokens.is_empty() {
                        rw.muted("None.");
                    }
                    for t in &m.tokens {
                        let title = if t.note.trim().is_empty() { "Moderator token".to_string() } else { t.note.clone() };
                        let by = if t.created_by.trim().is_empty() { "?" } else { &t.created_by };
                        let until = t.expires_at.map_or(String::new(), |e| format!(" - valid until {}", date(zone, e)));
                        let sub = format!("created by {by}{until}");
                        if list_row(ui, &mut rw, rw_w, &title, &sub, &[("Revoke", Style::Danger)], &format!("token-{}", t.id)).is_some() {
                            confirm("Revoke token", "Revoke this moderator token? It can no longer be used.".into(), ServerAsk::RevokeToken(t.id));
                        }
                    }
                    rw.feedback(&m.tokens_feedback);
                    rw.height()
                }) + 8.0;

                // --- item scores and guild settings: shown again when loaded ---
                if form.loaded != Some(server.loaded) {
                    form.loaded = Some(server.loaded);
                    let wt = server.weights;
                    form.weights = [two_places(wt.feed_power), two_places(wt.difficulty), two_places(wt.rarity)];
                    form.baseline = one_place(server.guild_settings.baseline_hpm);
                }
                y += widgets::section_box(ui, &p, pos2(x, y), w, "Item Scores", fixed::GOLD, |_, _| {}, |ui, r| {
                    let mut rw = rows(ui, r);
                    rw.muted("Every found item gets points: feed power weight × feed power / 100 + difficulty weight × dungeon difficulty + rarity weight × rarity (0-4), rounded to one decimal.");
                    rw.muted("The loot caravan, recent drops and leaderboard in the Guild tab show them, for everyone on this server.");
                    rw.y += 6.0;
                    for (i, label) in ["Feed power", "Difficulty", "Rarity"].iter().enumerate() {
                        let cy = rw.y + 20.0;
                        jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, label, &f, p.ink);
                        let sr = Rect::from_min_size(pos2(rw.x + 6.0 + 120.0 + 6.0, cy - 17.0), vec2(90.0, 34.0));
                        spinner(ui, &p, sr, &mut form.weights[i], &format!("weight-{i}"), 0.05, 0.0, 1.0, two_places);
                        rw.y += 40.0;
                    }
                    let cy = rw.y + 22.0;
                    let (b, save) = button_at(ui, &p, rw.x + 6.0, cy, "Save", true, true);
                    if save {
                        let v: Vec<Option<f64>> = form.weights.iter().map(|t| number(t, 0.0, 1.0)).collect();
                        match (v[0], v[1], v[2]) {
                            (Some(a), Some(b), Some(c)) => ask(ServerAsk::SaveScoring(a, b, c)),
                            _ => ask(ServerAsk::Feedback(0, "Each weight must be a number from 0 to 1.".into())),
                        }
                    }
                    if button_at(ui, &p, b.right() + 6.0, cy, "Reset", false, true).1 {
                        form.loaded = None;
                    }
                    rw.y += 44.0;
                    rw.feedback(&m.scoring_feedback);
                    rw.height()
                }) + 8.0;

                y += widgets::section_box(ui, &p, pos2(x, y), w, "Guild", fixed::GREEN, |_, _| {}, |ui, r| {
                    let mut rw = rows(ui, r);
                    rw.muted("\"Zero Hit?\" in a dungeon's Guild part: hits per minute (HPM) = hits / minutes in the dungeon, hit% = HPM / (HPM + baseline HPM) × 100. A player hit as often as the baseline gets 50%.");
                    rw.y += 6.0;
                    let cy = rw.y + 20.0;
                    jtext::draw(&rw.painter, pos2(rw.x + 6.0, cy), Align2::LEFT_CENTER, "Baseline HPM", &f, p.ink);
                    let sr = Rect::from_min_size(pos2(rw.x + 6.0 + 120.0 + 6.0, cy - 17.0), vec2(90.0, 34.0));
                    spinner(ui, &p, sr, &mut form.baseline, "baseline", 1.0, 1.0, 1000.0, one_place);
                    rw.y += 40.0;
                    let cy = rw.y + 22.0;
                    let (b, save) = button_at(ui, &p, rw.x + 6.0, cy, "Save", true, true);
                    if save {
                        match number(&form.baseline, 1.0, 1000.0) {
                            Some(v) => ask(ServerAsk::SaveGuild(v)),
                            None => ask(ServerAsk::Feedback(1, "The baseline must be a number from 1 to 1000.".into())),
                        }
                    }
                    if button_at(ui, &p, b.right() + 6.0, cy, "Reset", false, true).1 {
                        form.loaded = None;
                    }
                    rw.y += 44.0;
                    rw.feedback(&m.guild_feedback);
                    rw.height()
                }) + 8.0;
            }
        }
        asks.extend(more.into_inner());
        ui.allocate_exact_size(vec2(w, y - top.y), Sense::hover());
    });
    ui.data_mut(|d| d.insert_temp(id, form));
    c.asks.extend(asks);
}
