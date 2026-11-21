//! Pictures of the window without showing it, to compare with the Java app's (`oracle/UiOracle.java`): each tab
//! painted into `<out dir>/<Tab>.png`, rendered offscreen (wgpu; a software renderer will do).
//!
//!   ui-shot <out dir> <width> <height> [--data <the Java app's data folder>] [--hover x,y] [tab...]
//!
//! With `--data` the views show that folder's records (read only) and its `assets`.

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    // `ui-shot skins <data dir>`: every character's look, "skin <charId> <w>x<h> <fnv of the ARGB pixels>"
    if let [cmd, dir] = args.as_slice()
        && cmd == "skins"
    {
        let dir = std::path::Path::new(dir);
        let data = lg_gamedata::GameData::load(&dir.join("assets"));
        let mut sprites = lg_gamedata::sprites::Sprites::new(&dir.join("assets"));
        let chars = lg_app::characters::Characters::import(dir);
        let mut ids: Vec<_> = chars.all().map(|c| c.char_id).collect();
        ids.sort_unstable();
        for id in ids {
            let c = chars.get(id).expect("char");
            let sprite = if c.skin > 0 { c.skin } else { c.class_type };
            let im = lg_ui::icons::skin_image(&mut sprites, &data, sprite, c.tex1, c.tex2, 96);
            let mut h: u64 = 0xcbf29ce484222325;
            for p in im.rgba.as_chunks::<4>().0 {
                for b in [p[3], p[0], p[1], p[2]] {
                    h = (h ^ u64::from(b)).wrapping_mul(0x100000001b3);
                }
            }
            println!("skin {id} {}x{} {h:016x}", im.w, im.h);
        }
        return;
    }
    let [out, w, h, rest @ ..] = args.as_slice() else {
        eprintln!("usage: ui-shot <out dir> <width> <height> [--data <dir>] [tab...]");
        std::process::exit(2);
    };
    let (data, rest) = match rest {
        [flag, d, tabs @ ..] if flag == "--data" => (Some(std::path::PathBuf::from(d)), tabs),
        tabs => (None, tabs),
    };
    // `--hover x,y`: the pointer rests there (to see a tooltip)
    let (hover, tabs) = match rest {
        [flag, at, tabs @ ..] if flag == "--hover" => {
            let (x, y) = at.split_once(',').expect("--hover x,y");
            (Some(egui::pos2(x.parse().expect("x"), y.parse().expect("y"))), tabs)
        }
        tabs => (None, tabs),
    };
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
    let assets = data.as_ref().map(|d| d.join("assets")).unwrap_or_default();
    let world = lg_world::World::new(lg_gamedata::GameData::load(&assets));
    let mut app = lg_app::App::importing(world, now, data.as_deref());
    let props = lg_app::properties::Properties::in_memory(
        &data.as_ref().and_then(|d| std::fs::read_to_string(d.join("realmShark.properties")).ok()).unwrap_or_default(),
    );
    let (w, h): (f32, f32) = (w.parse().expect("width"), h.parse().expect("height"));
    let tabs: Vec<String> = if tabs.is_empty() { lg_ui::shell::TABS.iter().map(|t| t.to_string()).collect() } else { tabs.to_vec() };
    std::fs::create_dir_all(out).expect("out dir");
    for tab in tabs {
        // "card:<charId>": the loadout card "Copy as image" makes, alone
        if let Some(id) = tab.strip_prefix("card:").and_then(|i| i.parse::<i32>().ok()) {
            let mut win = lg_ui::window::Window::new(&assets, props.clone());
            win.palette = lg_ui::theme::palette(None);
            let ch = app.characters.get(id).expect("character");
            let rect = std::cell::Cell::new(egui::Rect::NOTHING);
            let mut themed = false;
            let mut harness = egui_kittest::Harness::builder()
                .with_size(egui::vec2(w, h))
                .with_pixels_per_point(1.0)
                .with_accessibility_check(false)
                .wgpu()
                .build_ui(|ui| {
                    if !themed {
                        lg_ui::theme::apply(ui.ctx(), &win.palette);
                        themed = true;
                    }
                    let rarity = win.characters.rarity(ui.ctx());
                    let ctx = ui.ctx().clone();
                    rect.set(lg_ui::card::paint(
                        ui.painter(),
                        &ctx,
                        &mut win.icons,
                        app.world.data(),
                        &rarity,
                        &win.palette,
                        ch,
                        egui::Pos2::ZERO,
                        lg_ui::card::SCALE,
                    ));
                });
            harness.run_steps(3);
            let img = harness.render().expect("render");
            drop(harness);
            let frame = egui::ColorImage::from_rgba_unmultiplied([img.width() as usize, img.height() as usize], img.as_raw());
            let card = lg_ui::card::crop(&frame, rect.get(), 1.0, lg_ui::card::SCALE);
            let out_img = image::RgbaImage::from_raw(
                card.size[0] as u32,
                card.size[1] as u32,
                card.pixels.iter().flat_map(|c| c.to_srgba_unmultiplied()).collect(),
            )
            .expect("image");
            out_img.save(std::path::Path::new(out).join(format!("card-{id}.png"))).expect("save");
            println!("card {id}");
            continue;
        }
        // a fresh window per picture, the Java default theme as the oracle shows it
        let mut win = lg_ui::window::Window::new(&assets, props.clone());
        win.palette = lg_ui::theme::palette(None);
        // "Server:online": connected to a made-up server, not logged in (the login form)
        app.server = Default::default();
        let dialog = match tab.as_str() {
            "Server:forgot" => Some(lg_ui::window::ServerAsk::OpenForgot("Gunshit".into())),
            "Server:change" => Some(lg_ui::window::ServerAsk::OpenChange),
            _ => None,
        };
        let shown = if tab == "Server:online" || dialog.is_some() {
            app.server.url = "https://loot.example.com".into();
            app.server.state = lg_app::server::State::Online;
            app.server.status = Some(lg_app::server::Status {
                version: "1.4.0".into(),
                api_version: lg_app::server::API_VERSION,
                admin_claimed: true,
                registration: "approval".into(),
            });
            "Server".to_string()
        } else {
            tab.clone()
        };
        match tab.strip_prefix("Battles:run:").and_then(|t| t.parse::<i64>().ok()) {
            // a run under DPS, its latest boss open
            Some(start) => {
                win.open("Battles:dps");
                win.battles.dps.show_run(&app.dps_history, Some(start));
            }
            None => match tab.strip_prefix("Settings:dialog:").and_then(|t| t.parse::<usize>().ok()) {
                // a ping editor open over Settings
                Some(k) => {
                    win.open("Settings");
                    win.ask(lg_ui::window::Ask::OpenDialog(k));
                    win.take_asks();
                }
                None => {
                    win.open(&shown);
                    if let Some(d) = dialog.clone() {
                        win.ask(lg_ui::window::Ask::Server(d));
                        win.take_asks();
                    }
                }
            },
        }
        let mut harness = egui_kittest::Harness::builder()
            .with_size(egui::vec2(w, h))
            .with_pixels_per_point(1.0)
            .with_accessibility_check(false)
            .wgpu()
            .build_ui(|ui| {
                // the whole window, without the margin the harness puts around it
                let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
                win.show(&mut all, &app, now);
            });
        harness.run_steps(3);
        if let Some(at) = hover {
            harness.input_mut().events.push(egui::Event::PointerMoved(at));
            harness.run_steps(2);
            // past the tooltip delay (the harness's clock moves a step at a time)
            for _ in 0..60 {
                harness.step();
            }
        }
        let img = harness.render().expect("render");
        let path = std::path::Path::new(out).join(format!("{}.png", tab.replace(' ', "_").replace(':', "-")));
        img.save(&path).expect("save");
        println!("tab {tab}");
    }
}
