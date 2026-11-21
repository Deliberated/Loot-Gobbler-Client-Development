//! The window around the views (Java: `TomatoGUI.create`): the menu bar, the row of main tabs (`TabBar`), the
//! status bar (`StatusBar`). Painted as Java paints them, at the same sizes.

use crate::jtext;
use crate::theme::{self, Palette, fixed, s};
use egui::{Align2, Color32, FontId, Pos2, Rect, Sense, Stroke, StrokeKind, Ui, pos2, vec2};

/// The main tabs in the order the app opens with (Java: `restoreOrder`'s default).
pub const TABS: [&str; 9] = ["Profile", "Inventory", "Characters", "Game Modes", "Battles", "Social", "Guild", "Server", "Settings"];

/// Height of the menu bar, the tab row (without its bottom line) and the status bar.
const MENU_H: f32 = 30.0;
const TAB_H: f32 = 46.0;
const STATUS_H: f32 = 43.0;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Server {
    NotConfigured,
    Offline,
    Connected,
}

/// What the frame shows and what was clicked in it.
#[derive(Debug, Clone)]
pub struct Shell {
    pub order: Vec<String>,
    pub selected: String,
    pub sniffing: bool,
    pub server: Server,
    /// The server label's tooltip.
    pub server_tip: String,
    /// The bell: its entries (title, line), whether something is new, whether its list is open; clicks this frame.
    pub notifications: Vec<(String, String)>,
    pub unread: bool,
    pub bell_open: bool,
    pub clicked_bell: bool,
    pub clicked_notification: Option<usize>,
    /// The Info menu: open, and the item picked this frame.
    pub info_open: bool,
    pub clicked_info: Option<&'static str>,
    /// Info > Newcomer's Guide's own menu (Quick tour, Extensive tour) is open.
    pub guide_menu_open: bool,
    /// Where the menus end in the menu bar (the dungeon you are in shows after them), and the bar.
    pub menu_end: f32,
    pub menu_rect: Rect,
    /// E.g. "build v0.0.1" (Java: "stable" for a released app).
    pub version: String,
    pub overlays_on: bool,
    /// Clicks this frame.
    pub clicked_sniffer: bool,
    pub clicked_overlay: bool,
    pub clicked_updates: bool,
    pub clicked_server: bool,
}

impl Default for Shell {
    fn default() -> Self {
        Shell {
            order: TABS.iter().map(|t| t.to_string()).collect(),
            selected: TABS[0].into(),
            sniffing: false,
            server: Server::Offline,
            server_tip: String::new(),
            notifications: Vec::new(),
            unread: false,
            bell_open: false,
            clicked_bell: false,
            clicked_notification: None,
            info_open: false,
            clicked_info: None,
            guide_menu_open: false,
            menu_end: 0.0,
            menu_rect: Rect::NOTHING,
            version: concat!("build v", env!("CARGO_PKG_VERSION")).into(),
            overlays_on: false,
            clicked_sniffer: false,
            clicked_overlay: false,
            clicked_updates: false,
            clicked_server: false,
        }
    }
}

fn text_width(ui: &Ui, text: &str, font: &FontId) -> f32 {
    jtext::width(ui.painter(), text, font)
}

fn text(ui: &Ui, pos: Pos2, align: Align2, text: &str, font: FontId, color: Color32) -> Rect {
    jtext::draw(ui.painter(), pos, align, text, &font, color)
}

impl Shell {
    /// The frame in the whole of `ui`; `view` fills the content area with the selected tab.
    pub fn show(&mut self, ui: &mut Ui, p: &Palette, view: impl FnOnce(&mut Ui, &str)) {
        self.clicked_sniffer = false;
        self.clicked_overlay = false;
        self.clicked_updates = false;
        self.clicked_server = false;
        self.clicked_bell = false;
        self.clicked_notification = None;
        self.clicked_info = None;
        let all = ui.max_rect();
        let painter = ui.painter().clone();
        painter.rect_filled(all, 0.0, p.sand);

        // the menu bar
        let menu = Rect::from_min_size(all.min, vec2(all.width(), MENU_H));
        painter.rect_filled(menu, 0.0, p.title);
        painter.rect_filled(Rect::from_min_max(pos2(menu.left(), menu.bottom() - 1.5), menu.max), 0.0, p.line);
        let info_r = text(ui, pos2(menu.left() + 12.0, menu.center().y - 0.75), Align2::LEFT_CENTER, "Info", theme::body(18.0), p.ink);
        let info_zone = Rect::from_min_max(pos2(menu.left(), menu.top()), pos2(info_r.right() + 12.0, menu.bottom()));
        self.menu_rect = menu;
        self.menu_end = info_zone.right();
        crate::guide::mark(ui, "menu.info", info_zone);
        if ui.interact(info_zone, ui.scope_id().with("info-menu"), Sense::click()).clicked() {
            self.info_open = !self.info_open;
            self.guide_menu_open = false;
        }
        if !self.info_open {
            self.guide_menu_open = false;
        }
        if self.info_open {
            // Java's Info menu: Logs, Release Notes, What's New Tour | Newcomer's Guide | About, Java version, Net traffic
            const ITEMS: [(&str, bool); 9] = [
                ("Logs", true),
                ("Release Notes", false),
                ("What's New Tour", false),
                ("-", false),
                ("Newcomer's Guide", true),
                ("-", false),
                ("About", true),
                ("Java version", true),
                ("Net traffic", true),
            ];
            let mut guide_item = Rect::NOTHING;
            let area = egui::Area::new(egui::Id::unique("info-popup"))
                .order(egui::Order::Foreground)
                .fixed_pos(pos2(menu.left(), menu.bottom()))
                .show(ui.ctx(), |ui| {
                    egui::Frame::NONE.fill(p.paper).stroke(Stroke::new(1.0, p.line)).inner_margin(4.0).show(ui, |ui| {
                        ui.set_min_width(200.0);
                        let f = theme::body(18.0);
                        for (label, on) in ITEMS {
                            if label == "-" {
                                ui.separator();
                                continue;
                            }
                            let color = if on { p.ink } else { fixed::MUTED };
                            let resp = ui.add_enabled(
                                on,
                                egui::Button::new(egui::RichText::new(label).font(f.clone()).color(color))
                                    .frame(false)
                                    .min_size(vec2(192.0, 26.0)),
                            );
                            let resp = if on { resp } else { resp.on_disabled_hover_text("Not in the Rust app yet") };
                            if label == "Newcomer's Guide" {
                                // a menu of its own: it opens as the pointer comes on it, its arrow at the right
                                guide_item = resp.rect;
                                if resp.hovered() || resp.clicked() {
                                    self.guide_menu_open = true;
                                }
                                if self.guide_menu_open {
                                    ui.painter().rect_filled(resp.rect, 0.0, p.sand.gamma_multiply(0.35));
                                }
                                let c = pos2(resp.rect.right() - 10.0, resp.rect.center().y);
                                ui.painter().add(egui::Shape::convex_polygon(
                                    vec![pos2(c.x - 4.0, c.y - 5.0), pos2(c.x + 1.0, c.y), pos2(c.x - 4.0, c.y + 5.0)],
                                    p.ink,
                                    Stroke::NONE,
                                ));
                            } else if resp.hovered() {
                                self.guide_menu_open = false;
                            }
                            if resp.clicked() && label != "Newcomer's Guide" {
                                self.clicked_info = Some(label);
                            }
                        }
                    });
                });
            crate::guide::mark(ui, "menu.info.popup", area.response.rect);
            let mut sub = Rect::NOTHING;
            if self.guide_menu_open {
                let a = egui::Area::new(egui::Id::unique("guide-popup"))
                    .order(egui::Order::Foreground)
                    .fixed_pos(pos2(area.response.rect.right() - 2.0, guide_item.top() - 4.0))
                    .show(ui.ctx(), |ui| {
                        egui::Frame::NONE.fill(p.paper).stroke(Stroke::new(1.0, p.line)).inner_margin(4.0).show(ui, |ui| {
                            ui.set_min_width(170.0);
                            let f = theme::body(18.0);
                            let quick = ui.add(
                                egui::Button::new(egui::RichText::new("Quick tour").font(f.clone()).color(p.ink))
                                    .frame(false)
                                    .min_size(vec2(162.0, 26.0)),
                            );
                            if quick.clicked() {
                                self.clicked_info = Some("Quick tour");
                            }
                            ui.add_enabled(
                                false,
                                egui::Button::new(egui::RichText::new("Extensive tour").font(f).color(fixed::MUTED))
                                    .frame(false)
                                    .min_size(vec2(162.0, 26.0)),
                            )
                            .on_disabled_hover_text("Coming soon");
                        });
                    });
                sub = a.response.rect;
                crate::guide::mark(ui, "menu.guide.popup", sub);
            }
            let on_menus = ui.input(|i| i.pointer.interact_pos()).is_some_and(|q| info_zone.contains(q) || sub.contains(q));
            if self.clicked_info.is_some() || (area.response.clicked_elsewhere() && !on_menus) {
                self.info_open = false;
                self.guide_menu_open = false;
            }
        }

        // the tab row
        let row = Rect::from_min_size(pos2(all.left(), menu.bottom()), vec2(all.width(), TAB_H));
        painter.rect_filled(row, 0.0, p.sand);
        let font = theme::head(17.0);
        let mut x = row.left();
        for title in self.order.clone() {
            let label = title.to_uppercase();
            let w = text_width(ui, &label, &font) + s(26.0);
            let r = Rect::from_min_size(pos2(x, row.top()), vec2(w, TAB_H));
            let resp = ui.interact(r, ui.scope_id().with(("tab", &title)), Sense::click());
            let on = title == self.selected;
            let bg = if on {
                p.paper
            } else if resp.hovered() {
                p.header
            } else {
                p.sand
            };
            painter.rect_filled(r, 0.0, bg);
            let underline = s(3.0);
            if on {
                painter.rect_filled(Rect::from_min_max(pos2(r.left(), r.bottom() - underline), r.max), 0.0, p.gold);
            }
            painter.rect_filled(Rect::from_min_max(pos2(r.right() - 1.0, r.top()), r.max), 0.0, Color32::BLACK);
            let (tw, th) = (jtext::width(&painter, &label, &font), jtext::height(&painter, &font));
            let top = r.top() + ((TAB_H - underline - th) / 2.0).floor();
            let left = r.left() + ((w - 1.0 - tw) / 2.0).floor();
            jtext::draw(&painter, pos2(left, top), Align2::LEFT_TOP, &label, &font, if on { p.gold } else { p.ink_soft });
            if resp.clicked() {
                self.selected = title.clone();
            }
            x += w;
        }
        painter.rect_filled(Rect::from_min_size(pos2(all.left(), row.bottom()), vec2(all.width(), 1.0)), 0.0, p.line);
        // the notifications bell at the right end of the tab row
        let bell = Rect::from_min_size(pos2(row.right() - 36.0, row.top()), vec2(36.0, TAB_H));
        let bell_resp = ui.interact(bell, ui.scope_id().with("bell"), Sense::click());
        let hover = bell_resp.hovered();
        if bell_resp.clicked() {
            self.bell_open = !self.bell_open;
            self.clicked_bell = self.bell_open;
        }
        let bell_resp = bell_resp.on_hover_text("Notifications");
        let _ = bell_resp;
        paint_bell(&painter, bell.center(), if hover { p.ink } else { p.ink.gamma_multiply(190.0 / 255.0) });
        if self.unread {
            // a ring in the background colour sets the dot off the bell
            let (x, y) = ((bell.center().x + 3.0).round(), (bell.center().y - 11.0).round());
            painter.circle_filled(pos2(x + 4.0, y + 4.0), 6.0, p.sand);
            painter.circle_filled(pos2(x + 4.0, y + 4.0), 4.0, Color32::from_rgb(0xE5, 0x48, 0x4D));
        }
        if self.bell_open {
            // Java's popup menu under the bell, its right edge at the bell's
            let area = egui::Area::new(egui::Id::unique("bell-menu"))
                .order(egui::Order::Foreground)
                .pivot(Align2::RIGHT_TOP)
                .fixed_pos(pos2(bell.right(), bell.bottom()))
                .show(ui.ctx(), |ui| {
                    egui::Frame::NONE.fill(p.paper).stroke(Stroke::new(1.0, p.line)).inner_margin(4.0).show(ui, |ui| {
                        let f = theme::body(18.0);
                        if self.notifications.is_empty() {
                            ui.add_space(6.0);
                            ui.horizontal(|ui| {
                                ui.add_space(10.0);
                                ui.label(egui::RichText::new("No notifications").font(f.clone()).color(fixed::MUTED));
                                ui.add_space(10.0);
                            });
                            ui.add_space(6.0);
                        }
                        for (i, (title, text)) in self.notifications.iter().enumerate() {
                            let resp =
                                egui::Frame::NONE.inner_margin(egui::Margin { left: 8, right: 12, top: 6, bottom: 6 }).show(ui, |ui| {
                                    ui.label(egui::RichText::new(title).font(f.clone()).strong().color(p.ink));
                                    ui.label(egui::RichText::new(text).font(f.clone()).color(Color32::from_rgb(0x9A, 0x9A, 0x9A)));
                                });
                            let r = resp.response.rect;
                            let click = ui.interact(r, ui.scope_id().with(("bell-item", i)), Sense::click());
                            if click.hovered() {
                                ui.painter().rect_filled(r, 0.0, p.sand.gamma_multiply(0.35));
                            }
                            if click.clicked() {
                                self.clicked_notification = Some(i);
                            }
                        }
                    });
                });
            if self.clicked_notification.is_some() || (area.response.clicked_elsewhere() && !bell_resp_contains(ui, bell)) {
                self.bell_open = false;
            }
        }

        // the status bar
        let bar = Rect::from_min_max(pos2(all.left(), all.bottom() - STATUS_H), all.max);
        painter.rect_filled(bar, 0.0, p.line);
        let label = theme::head(16.0);
        let mut lx = bar.left() + s(8.0);
        let square = |x: f32, c: Color32| {
            painter.rect_filled(Rect::from_min_size(pos2(x, bar.center().y - 5.0), vec2(10.0, 10.0)), 0.0, c);
        };
        square(lx, if self.sniffing { fixed::GREEN } else { fixed::STONE });
        lx += 10.0 + s(8.0);
        let r = text(
            ui,
            pos2(lx, bar.center().y),
            Align2::LEFT_CENTER,
            if self.sniffing { "SNIFFING" } else { "SNIFFER OFF" },
            label.clone(),
            p.ink,
        );
        lx = r.right() + s(8.0) + 24.0;
        let (color, server) = match self.server {
            Server::NotConfigured => (fixed::STONE, "NO SERVER CONFIGURED YET"),
            Server::Offline => (fixed::RED, "NO SERVER CONNECTION"),
            Server::Connected => (fixed::GREEN, "CONNECTED"),
        };
        square(lx, color);
        let r = text(ui, pos2(lx + 10.0 + s(8.0), bar.center().y), Align2::LEFT_CENTER, server, label.clone(), p.ink);
        let zone = Rect::from_min_max(pos2(lx, bar.top()), pos2(r.right(), bar.bottom()));
        crate::widgets::tip(ui, zone, "server-tip", &self.server_tip);
        if ui.interact(zone, ui.scope_id().with("server"), Sense::click()).clicked() {
            self.clicked_server = true;
        }

        let mut rx = bar.right();
        let buttons = [
            (if self.sniffing { "STOP SNIFFER" } else { "START SNIFFER" }, 0),
            (if self.overlays_on { "HIDE OVERLAY" } else { "SHOW OVERLAY" }, 1),
            ("CHECK UPDATES", 2),
        ];
        for (i, (t, which)) in buttons.iter().enumerate() {
            if i > 0 {
                rx -= 6.0;
            }
            // the update button keeps the width of its longest text
            let widest = if *which == 2 { "UPDATE AVAILABLE" } else { t };
            let w = text_width(ui, widest, &label) + 2.0 * s(10.0) + 2.0;
            let r = Rect::from_min_max(pos2(rx - w, bar.top() + 3.0), pos2(rx, bar.bottom() - 3.0));
            let resp = ui.interact(r, ui.scope_id().with(("status", which)), Sense::click());
            if *which == 0 {
                crate::guide::mark(ui, "sniffer", r);
            }
            let fill = if resp.is_pointer_button_down_on() {
                p.gold_dark
            } else if resp.hovered() {
                p.gold_hover
            } else {
                p.gold
            };
            painter.rect(r, 0.0, fill, Stroke::new(1.0, Color32::BLACK), StrokeKind::Inside);
            text(ui, r.center(), Align2::CENTER_CENTER, t, label.clone(), p.on_accent);
            if resp.clicked() {
                match which {
                    0 => self.clicked_sniffer = true,
                    1 => self.clicked_overlay = true,
                    _ => self.clicked_updates = true,
                }
            }
            rx -= w;
        }
        let m = fixed::MUTED;
        text(
            ui,
            pos2(rx - 12.0, bar.center().y),
            Align2::RIGHT_CENTER,
            &self.version,
            label,
            Color32::from_rgba_unmultiplied(m.r(), m.g(), m.b(), 110),
        );

        let content = Rect::from_min_max(pos2(all.left(), row.bottom() + 1.0), pos2(all.right(), bar.top()));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(content));
        child.set_clip_rect(content);
        view(&mut child, &self.selected.clone());
    }
}

/// Whether the pointer is on the bell (its own click toggles the list).
fn bell_resp_contains(ui: &Ui, bell: Rect) -> bool {
    ui.input(|i| i.pointer.interact_pos()).is_some_and(|p| bell.contains(p))
}

/// Java `NotificationBell`: a dome widening to a flared rim, the clapper below, a knob on top.
fn paint_bell(painter: &egui::Painter, c: Pos2, color: Color32) {
    use egui::epaint::{CubicBezierShape, PathShape};
    let pt = |x: f32, y: f32| pos2(c.x + x, c.y + y);
    let curves = [
        [pt(-7.0, 4.0), pt(-6.0, 1.0), pt(-6.0, -1.0), pt(-6.0, -3.0)],
        [pt(-6.0, -3.0), pt(-6.0, -7.0), pt(-3.5, -9.0), pt(0.0, -9.0)],
        [pt(0.0, -9.0), pt(3.5, -9.0), pt(6.0, -7.0), pt(6.0, -3.0)],
        [pt(6.0, -3.0), pt(6.0, -1.0), pt(6.0, 1.0), pt(7.0, 4.0)],
    ];
    let mut points = Vec::new();
    for b in curves {
        let shape = CubicBezierShape::from_points_stroke(b, false, Color32::TRANSPARENT, Stroke::NONE);
        points.extend(shape.flatten(Some(0.2)));
    }
    painter.add(PathShape::convex_polygon(points, color, Stroke::NONE));
    painter.rect_filled(Rect::from_min_size(pt(-8.5, 3.5), vec2(17.0, 2.5)), 1.0, color);
    painter.add(egui::Shape::ellipse_filled(pt(0.0, 8.5), vec2(2.5, 2.0), color));
    painter.circle_filled(pt(0.0, -9.5), 1.5, color);
}
