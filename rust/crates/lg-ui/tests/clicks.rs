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
