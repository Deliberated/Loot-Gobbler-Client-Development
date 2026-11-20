//! Pictures of the window without showing it, to compare with the Java app's (`oracle/UiOracle.java`): each tab
//! painted into `<out dir>/<Tab>.png`, rendered offscreen (wgpu; a software renderer will do).
//!
//!   ui-shot <out dir> <width> <height> [--data <the Java app's data folder>] [tab...]
//!
//! With `--data` the views show that folder's records (read only) and its `assets`.

use lg_ui::{battles, characters, game_modes, icons::Icons, inventory, profile, shell, theme};

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
    let (data, tabs) = match rest {
        [flag, d, tabs @ ..] if flag == "--data" => (Some(std::path::PathBuf::from(d)), tabs),
        tabs => (None, tabs),
    };
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
    let assets = data.as_ref().map(|d| d.join("assets")).unwrap_or_default();
    let world = lg_world::World::new(lg_gamedata::GameData::load(&assets));
    let app = lg_app::App::importing(world, now, data.as_deref());
    let en = lg_app::loadout::Enchantments::load(&assets.join("xml"));
    let props = lg_app::properties::Properties::in_memory(
        &data.as_ref().and_then(|d| std::fs::read_to_string(d.join("realmShark.properties")).ok()).unwrap_or_default(),
    );
    let mut loadout = lg_app::loadout::Settings::from(&props);
    let (w, h): (f32, f32) = (w.parse().expect("width"), h.parse().expect("height"));
    let tabs: Vec<String> = if tabs.is_empty() { shell::TABS.iter().map(|t| t.to_string()).collect() } else { tabs.to_vec() };
    std::fs::create_dir_all(out).expect("out dir");
    let palette = theme::palette(None);
    for tab in tabs {
        let shown = tab.split(':').next().unwrap_or(&tab).to_string();
        let mut sh = shell::Shell { selected: shown, ..Default::default() };
        let mut themed = false;
        let mut icons = Icons::new(&assets);
        let mut prof = profile::Profile::default();
        let mut chars = characters::CharactersView::default();
        let mut inv = inventory::InventoryView::default();
        let mut modes = game_modes::GameModesView::default();
        let mut battles = battles::BattlesView { selected: -1, ..Default::default() };
        let mut harness = egui_kittest::Harness::builder().with_size(egui::vec2(w, h)).with_pixels_per_point(1.0).wgpu().build_ui(|ui| {
            if !themed {
                theme::apply(ui.ctx(), &palette);
                themed = true;
            }
            // the whole window, without the margin the harness puts around it
            let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
            let wanted = tab.clone();
            sh.show(&mut all, &palette, |ui, shown| {
                let tab = if wanted.starts_with(shown) { wanted.as_str() } else { shown };
                let mut c = profile::Ctx { app: &app, p: &palette, icons: &mut icons, now, en: &en, loadout: &mut loadout, props: &props };
                match tab {
                    "Profile" => prof.show(ui, &mut c),
                    "Characters" => chars.show(ui, &mut c),
                    "Game Modes" => modes.show(ui, &mut c),
                    "Battles" | "Battles:dps" | "Battles:overlay" => {
                        battles.page = match tab {
                            "Battles:dps" => 1,
                            "Battles:overlay" => 2,
                            _ => 0,
                        };
                        let rarity = chars.rarity(ui.ctx());
                        battles.show(ui, &mut c, &rarity)
                    }
                    "Inventory" | "Inventory:st" | "Inventory:log" => {
                        inv.page = match tab {
                            "Inventory:st" => 1,
                            "Inventory:log" => 2,
                            _ => 0,
                        };
                        let rarity = chars.rarity(ui.ctx());
                        inv.show(ui, &mut c, &rarity)
                    }
                    "Characters:classes" => {
                        chars.page = 1;
                        chars.show(ui, &mut c)
                    }
                    "Characters:pets" => {
                        chars.page = 2;
                        chars.show(ui, &mut c)
                    }
                    t if t.starts_with("Characters:") => {
                        chars.selected = t[11..].parse().unwrap_or(0);
                        chars.show(ui, &mut c)
                    }
                    _ => {}
                }
            });
        });
        harness.run_steps(3);
        let img = harness.render().expect("render");
        let path = std::path::Path::new(out).join(format!("{}.png", tab.replace(' ', "_").replace(':', "-")));
        img.save(&path).expect("save");
        println!("tab {tab}");
    }
}
