//! The window: the views of `lg-ui` over the app's records, fed by the capture while the sniffer runs (Java: the
//! "Start sniffer" button; `sniffer=T` in the settings starts it with the app, as the app was left).

use crate::{Feeder, feeder, now_ms};
use std::path::PathBuf;

/// The window as it was left (Java `WindowMemory`: `windowBounds` "x,y,w,h", `windowMaximized`), else 1799 x 1062.
pub fn viewport(props: &lg_app::properties::Properties) -> egui::ViewportBuilder {
    let mut v = egui::ViewportBuilder::default().with_title("Loot Gobbler").with_min_inner_size([640.0, 420.0]);
    let bounds: Option<Vec<f32>> = props.get("windowBounds").and_then(|b| b.split(',').map(|x| x.trim().parse::<f32>().ok()).collect());
    match bounds.as_deref() {
        Some([x, y, w, h]) if *w >= 200.0 && *h >= 150.0 => v = v.with_position([*x, *y]).with_inner_size([*w, *h]),
        _ => v = v.with_inner_size([1799.0, 1062.0]),
    }
    v.with_maximized(props.get("windowMaximized") == Some("true"))
}

pub struct Gui {
    app: lg_app::App,
    own: PathBuf,
    window: lg_ui::window::Window,
    feeder: Feeder,
    capture: Option<lg_capture::live::Capture>,
    /// The window's place and size as last seen, and when it last changed (saved once it rests 600 ms).
    bounds: Option<(String, bool)>,
    bounds_changed: Option<std::time::Instant>,
}

impl Gui {
    pub fn new(app: lg_app::App, own: PathBuf, assets: PathBuf) -> Gui {
        let props = lg_app::properties::Properties::load(&own);
        let start = props.get("sniffer") == Some("T");
        let mut g = Gui {
            app,
            own,
            window: lg_ui::window::Window::new(&assets, props),
            feeder: feeder(),
            capture: None,
            bounds: None,
            bounds_changed: None,
        };
        if start {
            g.start();
        }
        g
    }

    fn start(&mut self) {
        match lg_capture::live::start("tcp port 2050") {
            Ok(c) => {
                let names: Vec<String> = c.opened.iter().map(|i| i.name.clone()).collect();
                eprintln!("capturing on {}", names.join(", "));
                self.feeder = feeder(); // a new capture starts its streams afresh
                self.capture = Some(c);
            }
            Err(e) => {
                eprintln!("{e}");
                let line = if matches!(e, lg_capture::live::Error::Permission(_)) && cfg!(target_os = "linux") {
                    "Capturing needs the rights: sudo setcap cap_net_raw,cap_net_admin=eip <path to loot-gobbler>".to_string()
                } else {
                    format!("The sniffer could not start: {e}")
                };
                let time = self.app.zone.local_date_time(now_ms());
                self.app.chat.system(&line, time.get(11..).unwrap_or(""));
            }
        }
    }

    fn set(&mut self, key: &str, value: &str) {
        if let Err(e) = self.window.props.set(key, value, now_ms(), &self.app.zone) {
            eprintln!("cannot save the settings: {e}");
        }
    }
}

impl Gui {
    /// Java `WindowMemory.save`: maximized or not, and the normal bounds, once the window rests.
    fn remember_bounds(&mut self, ctx: &egui::Context) {
        let (rect, maximized, minimized) = ctx.input(|i| {
            let v = i.viewport();
            (v.outer_rect, v.maximized.unwrap_or(false), v.minimized.unwrap_or(false))
        });
        let Some(r) = rect else { return };
        let now = (format!("{},{},{},{}", r.left().round(), r.top().round(), r.width().round(), r.height().round()), maximized);
        if minimized {
            return;
        }
        match &self.bounds {
            None => self.bounds = Some(now),
            Some(b) if *b != now => {
                self.bounds = Some(now);
                self.bounds_changed = Some(std::time::Instant::now());
            }
            _ => {}
        }
        if self.bounds_changed.is_some_and(|t| t.elapsed().as_millis() >= 600)
            && let Some((b, max)) = self.bounds.clone()
        {
            self.bounds_changed = None;
            self.set("windowMaximized", if max { "true" } else { "false" });
            if !max {
                self.set("windowBounds", &b);
            }
        }
    }
}

impl eframe::App for Gui {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // what was captured since the last frame
        if let Some(c) = &self.capture {
            let frames: Vec<_> = c.frames.try_iter().take(5000).collect();
            for f in frames {
                self.feeder.frame(&mut self.app, &self.own, f.link_type, (f.micros / 1000) as i64, &f.data);
            }
        }
        self.window.shell.sniffing = self.capture.is_some();
        let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
        self.window.show(&mut all, &self.app, now_ms());
        if self.window.shell.clicked_sniffer {
            if let Some(mut c) = self.capture.take() {
                c.stop();
                self.set("sniffer", "F");
            } else {
                self.start();
                if self.capture.is_some() {
                    self.set("sniffer", "T");
                }
            }
        }
        self.remember_bounds(ui.ctx());
        // the clocks and the live views move on; packets come in between
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(200));
    }
}
