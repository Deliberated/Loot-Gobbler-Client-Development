//! Clicks in the window ask for the settings Java would save.

use egui::{Event, PointerButton, Pos2};
use lg_ui::window::{Ask, Window};

fn click(h: &mut egui_kittest::Harness<'_, Window>, at: Pos2) {
    h.input_mut().events.push(Event::PointerMoved(at));
    h.run_steps(1);
    for pressed in [true, false] {
        h.input_mut().events.push(Event::PointerButton { pos: at, button: PointerButton::Primary, pressed, modifiers: Default::default() });
        h.run_steps(1);
    }
}

#[test]
fn settings_clicks_ask_to_save() {
    let app = Box::leak(Box::new(lg_app::App::new(lg_world::World::new(lg_gamedata::GameData::default()), 0)));
    let props = lg_app::properties::Properties::in_memory("chatPing=false\n");
    let mut win = Window::new(std::path::Path::new("/nonexistent"), props);
    win.open("Settings");
    let mut h = egui_kittest::Harness::builder().with_size(egui::vec2(1799.0, 1005.0)).with_pixels_per_point(1.0).build_ui_state(
        |ui, win: &mut Window| {
            let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
            win.show(&mut all, app, 0);
        },
        win,
    );
    h.run_steps(2);
    let _ = h.state_mut().take_asks();
    // the "PM ping" check box, first in the Sound & Pings grid
    click(&mut h, Pos2::new(40.0, 193.0));
    let asks = h.state_mut().take_asks();
    assert!(asks.contains(&Ask::Set("chatPing".into(), "true".into())), "{asks:?}");
    assert_eq!(h.state().props.get("chatPing"), Some("true"));
}

#[test]
fn clear_all_dungeons_asks_first() {
    let app = Box::leak(Box::new(lg_app::App::new(lg_world::World::new(lg_gamedata::GameData::default()), 0)));
    let mut win = Window::new(std::path::Path::new("/nonexistent"), lg_app::properties::Properties::in_memory(""));
    win.open("Battles");
    // a picture only when asked (LG_SHOT): it needs a GPU (or a software one), which CI does not have
    let shot = std::env::var("LG_SHOT").ok();
    let mut b = egui_kittest::Harness::builder().with_size(egui::vec2(1799.0, 1005.0)).with_pixels_per_point(1.0);
    if shot.is_some() {
        b = b.wgpu();
    }
    let mut h = b.build_ui_state(
        |ui, win: &mut Window| {
            let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
            win.show(&mut all, app, 0);
        },
        win,
    );
    h.run_steps(2);
    click(&mut h, Pos2::new(1197.0, 151.0));
    let asks = h.state_mut().take_asks();
    assert!(asks.is_empty(), "{asks:?}");
    h.run_steps(2);
    match h.state().asking() {
        Some(Ask::Confirm { title, then, .. }) => {
            assert_eq!(title, "Clear dungeons");
            assert_eq!(**then, Ask::ClearVisits);
        }
        other => panic!("no question: {other:?}"),
    }
    if let Some(dir) = shot {
        h.render().expect("render").save(format!("{dir}/question.png")).expect("save");
    }
}

#[test]
fn open_later_shows_a_run_under_dps() {
    let mut app = lg_app::App::new(lg_world::World::new(lg_gamedata::GameData::default()), 0);
    app.dps_history.runs.push(lg_app::dps_history::Run { dungeon: "Pirate Cave".into(), start: 5, ..Default::default() });
    let app = Box::leak(Box::new(app));
    let mut win = Window::new(std::path::Path::new("/nonexistent"), lg_app::properties::Properties::in_memory(""));
    win.open("Profile");
    let mut asked = false;
    let mut h = egui_kittest::Harness::builder().with_size(egui::vec2(1799.0, 1005.0)).build_ui_state(
        |ui, win: &mut Window| {
            if !asked {
                lg_ui::window::open_later(ui.ctx(), "Battles:run:5");
                asked = true;
            }
            let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
            win.show(&mut all, app, 0);
        },
        win,
    );
    h.run_steps(3);
    assert_eq!(h.state().shell.selected, "Battles");
    assert_eq!(h.state().battles.page, 1);
    assert_eq!(h.state().battles.dps.selected, Some(5));
}

#[test]
fn typing_into_the_inventory_search() {
    let app = Box::leak(Box::new(lg_app::App::new(lg_world::World::new(lg_gamedata::GameData::default()), 0)));
    let mut win = Window::new(std::path::Path::new("/nonexistent"), lg_app::properties::Properties::in_memory(""));
    win.open("Inventory");
    let mut h = egui_kittest::Harness::builder().with_size(egui::vec2(1799.0, 1005.0)).build_ui_state(
        |ui, win: &mut Window| {
            let mut all = ui.new_child(egui::UiBuilder::new().max_rect(ui.ctx().content_rect()));
            win.show(&mut all, app, 0);
        },
        win,
    );
    h.run_steps(2);
    click(&mut h, Pos2::new(946.0, 150.0));
    h.input_mut().events.push(Event::Text("lute".into()));
    h.run_steps(2);
    assert_eq!(h.state().inventory.search, "lute");
}
