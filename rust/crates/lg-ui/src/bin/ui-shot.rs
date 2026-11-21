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
        // "Guild:Member": logged in, a member's profile made from these records
        if tab == "Guild:Member" {
            app.server.url = "https://loot.example.com".into();
            app.server.state = lg_app::server::State::Online;
            app.server.user = Some(lg_app::server::User { id: 1, ign: "Gunshit".into(), ..Default::default() });
            let member = lg_app::guild::Member {
                id: 1,
                ign: "Gunshit".into(),
                guild_rank: 40,
                last_active: now - 3_600_000,
                items: app.collection.all().count() as i64,
                finds: 771,
                fame: 108_360,
                exalts: 144,
                characters: 20,
                maxed: 15,
                ..Default::default()
            };
            let profile = lg_app::guild::MemberProfile {
                guild: "Pluribus".into(),
                member,
                characters: app.characters.all().cloned().collect(),
                exalts: app.exalts.progress.iter().map(|(k, v)| (k.to_string(), v.clone())).collect(),
                collection: app.collection.all().map(|(id, e)| (id.to_string(), i64::from(e.count))).collect(),
            };
            app.server.guild.member = Some((1, Some(Ok(profile))));
        }
        // "Guild:Quest Map": logged in, a map of every style (the current one half cleared, the others ended,
        // completed or barely begun)
        if tab == "Guild:Quest Map" {
            use lg_app::guild::{QuestMap, QuestMapClear, QuestMaps};
            app.server.url = "https://loot.example.com".into();
            app.server.state = lg_app::server::State::Online;
            app.server.user = Some(lg_app::server::User { id: 1, ign: "Gunshit".into(), ..Default::default() });
            let dungeons: Vec<String> = [
                "Snake Pit",
                "Spider Den",
                "Pirate Cave",
                "Forest Maze",
                "Tomb of the Ancients",
                "Cultist Hideout",
                "The Shatters",
                "Oryx's Sanctuary",
            ]
            .iter()
            .map(|d| d.to_string())
            .collect();
            let map = |id: i64, style: &str, variant: i32, cleared: usize, ended: bool| QuestMap {
                id,
                name: format!("The {style} map"),
                style: style.into(),
                variant,
                dungeons: dungeons.clone(),
                clears: (0..cleared)
                    .map(|i| QuestMapClear { user_id: 1, ign: "Gunshit".into(), at: now - 86_400_000 + i as i64 * 3_600_000 })
                    .collect(),
                starts_at: now - 2 * 86_400_000,
                ends_at: if ended { now - 3_600_000 } else { now + 5 * 86_400_000 },
                completed_at: (cleared == 8).then_some(now - 7_200_000),
            };
            let styles = ["hex", "overworld", "oryx", "constellation", "neon", "adventure", "hunt", "hellforge"];
            app.server.guild.quest_maps = Some(Ok(QuestMaps {
                guild: "Pluribus".into(),
                now,
                current: Some(map(100, "parchment", 0, 3, false)),
                past: styles
                    .iter()
                    .enumerate()
                    .map(|(i, s)| map(i as i64 + 1, s, i as i32, [3, 8, 2, 5, 3, 4, 8, 1][i], i != 0 && i != 2))
                    .collect(),
            }));
        }
        // "Battles:mates:<start>": that visit with a guildmate the server has (as the local test server answers)
        let mates_visit = tab.strip_prefix("Battles:mates:").and_then(|t| t.parse::<i64>().ok());
        if let Some(start) = mates_visit {
            use lg_app::guild::{Looks, RunLook};
            app.server.url = "https://loot.example.com".into();
            app.server.state = lg_app::server::State::Online;
            app.server.user = Some(lg_app::server::User { id: 1, ign: "Gunshit".into(), ..Default::default() });
            app.server.mates.names = ["valhirs".to_string()].into_iter().collect();
            if let Some(v) = app.dungeon_visits.visits.iter().find(|v| v.start == start) {
                let look = RunLook {
                    player: "Valhirs".into(),
                    class_type: 782,
                    source: "visit".into(),
                    max_hp: 980,
                    hit_at: vec![62000, 63000, 63500, 202000, 202400, 302000],
                    hit_damage: vec![120, 150, 200, 90, 310, 60],
                    quick_used: Some(vec![2594, 2594, 2793]),
                    quick_at: Some(vec![63000, 202500, 302100]),
                    ..Default::default()
                };
                let key = format!("{}/{}", v.dungeon, v.start);
                app.server.mates.looks.insert(key, Looks::Got([("valhirs".to_string(), vec![look])].into_iter().collect()));
            }
        }
        let admin = tab == "Server:admin";
        let shown = if mates_visit.is_some() {
            "Battles".to_string()
        } else if tab == "Server:online" || dialog.is_some() || admin {
            app.server.url = "https://loot.example.com".into();
            app.server.state = lg_app::server::State::Online;
            app.server.status = Some(lg_app::server::Status {
                version: "1.4.0".into(),
                api_version: lg_app::server::API_VERSION,
                admin_claimed: true,
                registration: "approval".into(),
            });
            if admin {
                // logged in as the root admin, with made-up lists
                use lg_app::server::{IssuedToken, OpenToken, User};
                let user = |id: i64, ign: &str, role: &str, root: bool, status: &str| User {
                    id,
                    ign: ign.into(),
                    status: status.into(),
                    role: role.into(),
                    root,
                    created_at: 1_795_000_000_000 + id * 3_600_000,
                    last_login_at: (id != 4).then_some(1_795_200_000_000 + id * 60_000),
                };
                app.server.user = Some(user(1, "Gunshit", "admin", true, "active"));
                let m = &mut app.server.moderation;
                m.pending = vec![user(7, "Newbie", "user", false, "pending")];
                m.members = vec![
                    user(1, "Gunshit", "admin", true, "active"),
                    user(2, "Helper", "admin", false, "active"),
                    user(3, "Watcher", "moderator", false, "active"),
                    user(4, "Quiet", "user", false, "active"),
                ];
                m.tokens = vec![OpenToken {
                    id: 1,
                    note: "For Ana".into(),
                    created_by: "Gunshit".into(),
                    created_at: 0,
                    expires_at: Some(1_795_300_000_000),
                }];
                m.last_issued = Some(IssuedToken {
                    id: 1,
                    token: "LGM-ABCD-EFGH-IJKL-MNOP".into(),
                    note: "For Ana".into(),
                    expires_at: 1_795_300_000_000,
                });
                app.sync.phase = lg_app::sync::Phase::UpToDate;
            }
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
                    if let Some(start) = mates_visit {
                        win.battles.selected = start;
                    }
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
