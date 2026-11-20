//! The Java app's look (FlatLaf with Loot Gobbler's themes, `src/main/resources/lootgobbler/themes`): six palettes,
//! square corners, the bundled pixel fonts Jersey 10 (headings, tabs, buttons) and Jersey 15 (text).
//!
//! Sizes are in the Java app's pixels: FlatLaf scales the sizes in the theme files by 1.5 (it derives that from
//! the 18 px default font), which [`s`] does here; one egui point is one such pixel, and the system's display
//! scaling comes on top in both apps.

use egui::{Color32, FontData, FontDefinitions, FontFamily, FontId};
use std::sync::Arc;

/// FlatLaf's `UIScale.scale`: a size from a theme file or `UIScale.scale(n)` in the Java code.
pub fn s(n: f32) -> f32 {
    (n * 1.5).round()
}

/// One theme's palette (Java: `<Name>Laf.properties`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Palette {
    pub paper: Color32,
    pub sand: Color32,
    pub header: Color32,
    pub line: Color32,
    pub ink: Color32,
    pub ink_soft: Color32,
    pub ink_muted: Color32,
    pub gold: Color32,
    pub gold_dark: Color32,
    pub gold_hover: Color32,
    pub on_accent: Color32,
    pub red: Color32,
    pub check: Color32,
    pub title: Color32,
    pub title_fg: Color32,
    pub text_sel: Color32,
}

const fn hex(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

macro_rules! palette {
    ($paper:expr, $sand:expr, $header:expr, $line:expr, $ink:expr, $soft:expr, $muted:expr, $gold:expr, $gdark:expr,
     $ghover:expr, $on:expr, $red:expr, $check:expr, $title:expr, $tfg:expr, $sel:expr) => {
        Palette {
            paper: hex($paper),
            sand: hex($sand),
            header: hex($header),
            line: hex($line),
            ink: hex($ink),
            ink_soft: hex($soft),
            ink_muted: hex($muted),
            gold: hex($gold),
            gold_dark: hex($gdark),
            gold_hover: hex($ghover),
            on_accent: hex($on),
            red: hex($red),
            check: hex($check),
            title: hex($title),
            title_fg: hex($tfg),
            text_sel: hex($sel),
        }
    };
}

/// The themes in the picker's order, with the keys saved as `theme` in the settings (Java: `Themes`).
pub const THEMES: [(&str, &str, Palette); 6] = [
    (
        "gobblerGold",
        "Gobbler Gold",
        palette!(
            0xF3EBD3, 0xE6D9B4, 0xD8C690, 0xA89150, 0x2E2412, 0x6B5526, 0x9C8656, 0xC8901C, 0x8A6210, 0xD9A22F, 0x000000, 0xC2344E,
            0xA8C94A, 0xFBF5E4, 0x6B5526, 0xF5E3A0
        ),
    ),
    (
        "goblinGreen",
        "Goblin Green",
        palette!(
            0x1F2A17, 0x172012, 0x2B3A1F, 0x4A5E33, 0xE8F0D2, 0xB5C78E, 0x7F8F62, 0xA8C94A, 0x6F8F2A, 0xBCD866, 0x10170B, 0xE05A6A,
            0xF2C53D, 0x10170B, 0xB5C78E, 0x3E5226
        ),
    ),
    (
        "treasureHoard",
        "Treasure Hoard",
        palette!(
            0x2E2116, 0x23180F, 0x3D2C1C, 0x6B4E2E, 0xF4E6C6, 0xD2B988, 0x9A815A, 0xE5B23A, 0xA9771B, 0xF0C454, 0x1A120A, 0xD8285A,
            0xA8C94A, 0x1A120A, 0xD2B988, 0x5A4318
        ),
    ),
    (
        "burlapSack",
        "Burlap Sack",
        palette!(
            0xE9D8B8, 0xD7BF94, 0xC4A574, 0x8B6A3E, 0x2C1D0E, 0x5E4122, 0x8C6C47, 0x6F9A2B, 0x4C6E18, 0x82AE38, 0xFFFFFF, 0xB8364A,
            0xE5B23A, 0xF2E6CC, 0x5E4122, 0xE8D18A
        ),
    ),
    (
        "gemVault",
        "Gem Vault",
        palette!(
            0x261D33, 0x1C1527, 0x332746, 0x54426E, 0xEFE6FA, 0xC3B2DC, 0x8E7DA8, 0xE5B23A, 0xA9771B, 0xF0C454, 0x140F1C, 0xD8285A,
            0x3FC3E0, 0x140F1C, 0xC3B2DC, 0x4A3866
        ),
    ),
    (
        "amethystMoss",
        "Amethyst Moss",
        palette!(
            0x24222B, 0x1B1A21, 0x2E3A22, 0x4D5A3A, 0xECE6F2, 0xB5C78E, 0x8A8496, 0xB07BE0, 0x7A4BA8, 0xC293EA, 0x120D18, 0xE8607A,
            0xA8C94A, 0x121117, 0xB5C78E, 0x4A3A5E
        ),
    ),
];

/// Java `Themes.normalize`: a saved theme (also of older versions) to one of today's; none saved: Treasure Hoard.
pub fn palette(saved: Option<&str>) -> Palette {
    let key = match saved {
        Some(k @ ("gobblerGold" | "goblinGreen" | "treasureHoard" | "burlapSack" | "gemVault" | "amethystMoss")) => k,
        Some("goblinBurlap" | "leatherSatchel" | "mossPouch") => "burlapSack",
        Some("dark" | "darcula" | "contrastDark" | "solarizedDark") => "goblinGreen",
        Some("midnight" | "purp") => "gemVault",
        _ => "treasureHoard",
    };
    THEMES.iter().find(|t| t.0 == key).map_or(THEMES[2].2, |t| t.2)
}

/// The Java app's fixed colors (`Palette`), the same in every theme.
pub mod fixed {
    use super::hex;
    use egui::Color32;
    pub const BLUE: Color32 = hex(0x2D7AB0);
    pub const GREEN: Color32 = hex(0x3C9347);
    pub const GOLD: Color32 = hex(0xB07D0A);
    pub const GOLD_DARK: Color32 = hex(0x856008);
    pub const PURPLE: Color32 = hex(0x8049A8);
    pub const RED: Color32 = hex(0xBE332D);
    pub const STONE: Color32 = hex(0x645D4E);
    pub const MUTED: Color32 = hex(0x938B79);
}

pub const HEAD: &str = "Jersey 10";
pub const BODY: &str = "Jersey 15";

/// A heading font (Jersey 10) of a theme-file size (scaled), e.g. tabs 17, buttons 16.
pub fn head(size: f32) -> FontId {
    FontId::new(s(size), FontFamily::Name(HEAD.into()))
}

/// The text font (Jersey 15) of a size in pixels as it is (the default font, 18, is not scaled).
pub fn body(px: f32) -> FontId {
    FontId::new(px, FontFamily::Name(BODY.into()))
}

/// The bundled fonts, Jersey 15 also as egui's own text font.
pub fn fonts() -> FontDefinitions {
    let mut f = FontDefinitions::default();
    let head = include_bytes!("../../../../src/main/resources/fonts/Jersey10-Regular.ttf");
    let body = include_bytes!("../../../../src/main/resources/fonts/Jersey15-Regular.ttf");
    f.font_data.insert(HEAD.into(), Arc::new(FontData::from_static(head)));
    f.font_data.insert(BODY.into(), Arc::new(FontData::from_static(body)));
    let fallback = f.families.get(&FontFamily::Proportional).cloned().unwrap_or_default();
    f.families.insert(FontFamily::Name(HEAD.into()), [vec![HEAD.to_string()], fallback.clone()].concat());
    f.families.insert(FontFamily::Name(BODY.into()), [vec![BODY.to_string()], fallback.clone()].concat());
    f.families.insert(FontFamily::Proportional, [vec![BODY.to_string()], fallback].concat());
    f
}

/// The theme on a context: fonts, colors, square corners.
pub fn apply(ctx: &egui::Context, p: &Palette) {
    ctx.set_fonts(fonts());
    ctx.all_styles_mut(|st| {
        st.text_styles.insert(egui::TextStyle::Body, body(18.0));
        st.text_styles.insert(egui::TextStyle::Button, head(16.0));
        let v = &mut st.visuals;
        v.dark_mode = p.ink.r() > p.paper.r();
        v.panel_fill = p.sand;
        v.window_fill = p.paper;
        v.extreme_bg_color = p.paper;
        v.faint_bg_color = p.header;
        v.override_text_color = Some(p.ink);
        v.selection.bg_fill = p.text_sel;
        v.hyperlink_color = p.gold;
        for w in
            [&mut v.widgets.noninteractive, &mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.open]
        {
            w.corner_radius = egui::CornerRadius::ZERO;
        }
        v.window_corner_radius = egui::CornerRadius::ZERO;
        // Java's scroll bars: 12 px, a square line-coloured thumb on the sand track, always there when needed
        let mut sc = egui::style::ScrollStyle::solid();
        sc.bar_width = 12.0;
        sc.handle_min_length = 24.0;
        sc.bar_inner_margin = 0.0;
        sc.bar_outer_margin = 0.0;
        st.spacing.scroll = sc;
        v.extreme_bg_color = p.paper;
        v.widgets.inactive.bg_fill = p.line;
        v.widgets.hovered.bg_fill = p.ink_soft;
        v.widgets.active.bg_fill = p.ink;
        v.menu_corner_radius = egui::CornerRadius::ZERO;
    });
}

/// Java `Palette.mix`: t of a, the rest of b.
pub fn mix(a: Color32, b: Color32, t: f64) -> Color32 {
    let m = |x: u8, y: u8| (f64::from(x) * t + f64::from(y) * (1.0 - t)).round() as u8;
    Color32::from_rgb(m(a.r(), b.r()), m(a.g(), b.g()), m(a.b(), b.b()))
}
