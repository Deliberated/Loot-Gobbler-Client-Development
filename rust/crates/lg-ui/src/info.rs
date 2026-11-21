//! The Info menu's windows (Java `LogsDialog`, `AboutDialog`, `TomatoBandwidth`, the Java version message).

use crate::jtext;
use crate::theme::{Palette, fixed};
use crate::widgets::text;
use crate::window::Ask;
use egui::{Align2, Color32, Rect, Sense, Stroke, StrokeKind, pos2, vec2};

/// Bigger log files show their end.
const MAX_SHOWN: usize = 2_000_000;

#[derive(Default)]
pub struct Info {
    about: bool,
    net: bool,
    logs: Option<Logs>,
    icon: Option<egui::TextureHandle>,
}

struct Logs {
    files: Vec<std::path::PathBuf>,
    selected: usize,
    text: String,
}

impl Logs {
    fn load() -> Logs {
        let mut l = Logs { files: lg_app::applog::files(), selected: 0, text: String::new() };
        l.show();
        l
    }

    fn reload(&mut self) {
        let was = self.files.get(self.selected).cloned();
        self.files = lg_app::applog::files();
        self.selected = was.and_then(|w| self.files.iter().position(|f| *f == w)).unwrap_or(0);
        self.show();
    }

    fn show(&mut self) {
        let Some(file) = self.files.get(self.selected) else {
            self.text = "No logs yet. They are written as the app runs.".into();
            return;
        };
        self.text = match std::fs::read(file) {
            Ok(all) => {
                let from = all.len().saturating_sub(MAX_SHOWN);
                let s = String::from_utf8_lossy(&all[from..]).to_string();
                if from > 0 { format!("... (start cut off, export the file to see all of it)\n{s}") } else { s }
            }
            Err(e) => format!("Could not read {}: {e}", file.display()),
        };
    }
}

/// Opens a folder in the system's file manager.
pub fn open_folder(dir: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let program = if cfg!(windows) {
        "explorer"
    } else if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    std::process::Command::new(program).arg(dir).spawn().map(|_| ())
}

fn message(title: &str, text: &str) -> Ask {
    Ask::Confirm { title: title.into(), text: text.into(), yes: "OK".into(), no: String::new(), then: Box::new(Ask::Nothing) }
}

impl Info {
    /// A menu item was picked.
    pub fn open(&mut self, item: &str, asks: &mut Vec<Ask>) {
        match item {
            "Logs" => {
                if lg_app::applog::enabled() {
                    self.logs = Some(Logs::load());
                } else {
                    asks.push(message(
                        "Logs",
                        "Logging is turned off.\nTurn it on in Settings > Logging; from then on, everything the app reports is written to log files you can view here.",
                    ));
                }
            }
            "About" => self.about = true,
            "Net traffic" => self.net = true,
            "Java version" => asks.push(message(
                "Java version",
                &format!(
                    "No Java: this is the Rust build of Loot Gobbler {} ({}, {}).",
                    env!("CARGO_PKG_VERSION"),
                    std::env::consts::OS,
                    std::env::consts::ARCH
                ),
            )),
            _ => {}
        }
    }

    pub fn show(&mut self, ctx: &egui::Context, p: &Palette, asks: &mut Vec<Ask>) {
        self.logs(ctx, p, asks);
        self.about(ctx, p, asks);
        if self.net {
            let mut open = true;
            egui::Window::new("Net Traffic").open(&mut open).default_size(vec2(350.0, 450.0)).resizable(true).show(ctx, |ui| {
                ui.allocate_space(ui.available_size());
            });
            self.net = open;
        }
    }

    fn logs(&mut self, ctx: &egui::Context, p: &Palette, asks: &mut Vec<Ask>) {
        let Some(logs) = &mut self.logs else { return };
        let mut open = true;
        let mut reload = false;
        egui::Window::new("Logs").open(&mut open).default_size(vec2(960.0, 640.0)).resizable(true).collapsible(false).show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label("Day");
                let name = |f: &std::path::PathBuf| f.file_name().map_or(String::new(), |n| n.to_string_lossy().to_string());
                let current = logs.files.get(logs.selected).map(name).unwrap_or_default();
                let mut picked = logs.selected;
                egui::ComboBox::from_id_salt("log-day").selected_text(current).show_ui(ui, |ui| {
                    for (i, f) in logs.files.iter().enumerate() {
                        ui.selectable_value(&mut picked, i, name(f));
                    }
                });
                if picked != logs.selected {
                    logs.selected = picked;
                    logs.show();
                }
                if ui.button("Refresh").clicked() {
                    reload = true;
                }
                if ui.button("Export...").clicked()
                    && let Some(f) = logs.files.get(logs.selected)
                {
                    asks.push(Ask::ExportLog(f.display().to_string()));
                }
                if ui.button("Open folder").clicked()
                    && let Err(e) = open_folder(&lg_app::applog::dir())
                {
                    asks.push(message("Logs", &format!("Could not open {}: {e}", lg_app::applog::dir().display())));
                }
            });
            ui.add_space(8.0);
            let bottom = 8.0 + ui.text_style_height(&egui::TextStyle::Body);
            egui::ScrollArea::vertical().max_height(ui.available_height() - bottom).stick_to_bottom(true).show(ui, |ui| {
                let mut shown = logs.text.as_str();
                ui.add(egui::TextEdit::multiline(&mut shown).font(egui::FontId::monospace(13.0)).desired_width(f32::INFINITY));
            });
            ui.add_space(8.0);
            ui.label(
                egui::RichText::new(format!("Saved in {}  (change it in Settings > Logging)", lg_app::applog::dir().display()))
                    .color(fixed::MUTED),
            );
        });
        if reload {
            logs.reload();
        }
        if !open {
            self.logs = None;
        }
        let _ = p;
    }

    /// Java `AboutDialog`: the app icon (poke it: the goblin cackles), name, author, version, tagline and credits.
    fn about(&mut self, ctx: &egui::Context, p: &Palette, asks: &mut Vec<Ask>) {
        if !self.about {
            return;
        }
        let icon = self
            .icon
            .get_or_insert_with(|| {
                let bytes = include_bytes!("../../../../src/main/resources/icon/lootgobbler-256.png");
                let img = image::load_from_memory(bytes).map(|i| i.to_rgba8()).unwrap_or_default();
                let size = [img.width() as usize, img.height() as usize];
                ctx.load_texture("app-icon", egui::ColorImage::from_rgba_unmultiplied(size, img.as_raw()), egui::TextureOptions::NEAREST)
            })
            .clone();
        let mut close = false;
        let modal = egui::Modal::new(egui::Id::unique("about")).frame(egui::Frame::NONE).show(ctx, |ui| {
            let painter = ui.painter().clone();
            let (title_f, by_f, ver_f) = (crate::theme::head(54.0), crate::theme::body(30.0), crate::theme::body(20.0));
            let (tag_f, line_f) = (crate::theme::body(27.0), crate::theme::body(21.0));
            let h = |f: &egui::FontId| jtext::height(&painter, f);
            let names_h = 10.0 + h(&title_f) + h(&by_f) + 6.0 + h(&ver_f);
            let head_h = 128f32.max(names_h);
            let info_h = 6.0 + h(&tag_f) + h(&line_f) + 14.0 + 2.0 * (h(&line_f) + 2.0) + 10.0;
            let w = 128.0 + 20.0 + jtext::width(&painter, "Your right hand for your RotMG adventures!", &tag_f).max(360.0) + 64.0 + 4.0;
            let box_h = 28.0 + head_h + 18.0 + info_h + 18.0 + 36.0 + 24.0;
            let (outer, _) = ui.allocate_exact_size(vec2(w + 32.0, box_h + 32.0), Sense::hover());
            painter.rect_filled(outer, 0.0, p.sand);
            let r = outer.shrink(16.0);
            painter.rect(r, 0.0, p.paper, Stroke::new(2.0, p.line), StrokeKind::Inside);
            let (x, mut y) = (r.left() + 32.0, r.top() + 28.0);
            let ir = Rect::from_min_size(pos2(x, y), vec2(128.0, 128.0));
            painter.image(icon.id(), ir, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
            let poke = ui.interact(ir, ui.scope_id().with("goblin"), Sense::click()).on_hover_cursor(egui::CursorIcon::PointingHand);
            if poke.is_pointer_button_down_on() && ui.input(|i| i.pointer.any_pressed()) {
                asks.push(Ask::Play("goblin-cackle.wav".into()));
            }
            let nx = ir.right() + 20.0;
            let mut ny = y + 10.0;
            jtext::draw(&painter, pos2(nx, ny), Align2::LEFT_TOP, "Loot Gobbler", &title_f, p.gold);
            ny += h(&title_f);
            jtext::draw(&painter, pos2(nx, ny), Align2::LEFT_TOP, "by Gunshit", &by_f, p.ink);
            ny += h(&by_f) + 6.0;
            jtext::draw(&painter, pos2(nx, ny), Align2::LEFT_TOP, env!("CARGO_PKG_VERSION"), &ver_f, fixed::MUTED);
            y += head_h + 18.0 + 6.0;
            let ix = x + 4.0;
            jtext::draw(&painter, pos2(ix, y), Align2::LEFT_TOP, "Your right hand for your RotMG adventures!", &tag_f, p.ink);
            y += h(&tag_f);
            jtext::draw(&painter, pos2(ix, y), Align2::LEFT_TOP, "designed and developed for the Pluribus Guild", &line_f, p.ink);
            y += h(&line_f) + 14.0;
            for l in ["Built on RealmShark's packet API (X-com)", "MIT license"] {
                jtext::draw(&painter, pos2(ix, y), Align2::LEFT_TOP, l, &line_f, fixed::MUTED);
                y += h(&line_f) + 2.0;
            }
            // CHECK FOR UPDATES and CLOSE at the bottom right
            let small = crate::widgets::small();
            let bw = |t: &str| jtext::width(&painter, t, &small) + 32.0;
            let close_r = Rect::from_min_size(pos2(r.right() - 32.0 - bw("CLOSE"), r.bottom() - 24.0 - 36.0), vec2(bw("CLOSE"), 36.0));
            let upd_r = Rect::from_min_size(
                pos2(close_r.left() - 10.0 - bw("CHECK FOR UPDATES"), close_r.top()),
                vec2(bw("CHECK FOR UPDATES"), 36.0),
            );
            if crate::inventory::button(ui, p, upd_r, "Check for updates", false, "about-updates") {
                asks.push(Ask::CheckUpdates);
            }
            let cr = ui.interact(close_r, ui.scope_id().with("about-close"), Sense::click());
            painter.rect(close_r, 0.0, p.gold, Stroke::new(1.5, Color32::BLACK), StrokeKind::Inside);
            jtext::draw(&painter, close_r.center(), Align2::CENTER_CENTER, "CLOSE", &small, p.on_accent);
            if cr.clicked() || ui.input(|i| i.key_pressed(egui::Key::Escape) || i.key_pressed(egui::Key::Enter)) {
                close = true;
            }
            let _ = text(18.0);
        });
        if close || modal.should_close() {
            self.about = false;
        }
    }
}
