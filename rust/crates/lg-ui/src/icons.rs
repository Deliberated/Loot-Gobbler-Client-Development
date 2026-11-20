//! Game sprites as the Java app shows them (`ImageBuffer.getOutlinedIcon`): scaled to the size less 2 (nearest
//! pixel, Java's `SCALE_DEFAULT`), with a 1 px black outline around the opaque pixels; kept as textures.

use egui::{ColorImage, TextureHandle, TextureOptions};
use lg_gamedata::GameData;
use lg_gamedata::sprites::{Image, Sprites};
use std::collections::HashMap;

pub struct Icons {
    sprites: Sprites,
    cache: HashMap<(i32, u32), Option<TextureHandle>>,
}

/// Java's `ReplicateScaleFilter`: each target pixel takes the source pixel under its centre.
pub fn scale(im: &Image, w: u32, h: u32) -> Image {
    let mut rgba = Vec::with_capacity((w * h * 4) as usize);
    for y in 0..h {
        let sy = ((2 * y * im.h + im.h) / (2 * h)).min(im.h - 1);
        for x in 0..w {
            let sx = ((2 * x * im.w + im.w) / (2 * w)).min(im.w - 1);
            let i = ((sy * im.w + sx) * 4) as usize;
            rgba.extend_from_slice(&im.rgba[i..i + 4]);
        }
    }
    Image { w, h, rgba }
}

/// The outlined icon of a sprite at a size (Java `getOutlinedIcon`).
pub fn outlined(im: &Image, size: u32) -> Image {
    let inner = scale(im, size.saturating_sub(2).max(1), size.saturating_sub(2).max(1));
    let (w, h) = (inner.w + 2, inner.h + 2);
    let alpha = |x: i64, y: i64| -> u8 {
        if x < 1 || y < 1 || x > i64::from(inner.w) || y > i64::from(inner.h) {
            return 0;
        }
        inner.rgba[(((y as u32 - 1) * inner.w + (x as u32 - 1)) * 4 + 3) as usize]
    };
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    for y in 0..h as i64 {
        for x in 0..w as i64 {
            let o = ((y as u32 * w + x as u32) * 4) as usize;
            if alpha(x, y) != 0 {
                let i = (((y as u32 - 1) * inner.w + (x as u32 - 1)) * 4) as usize;
                rgba[o..o + 4].copy_from_slice(&inner.rgba[i..i + 4]);
            } else if alpha(x + 1, y) != 0 || alpha(x - 1, y) != 0 || alpha(x, y + 1) != 0 || alpha(x, y - 1) != 0 {
                rgba[o..o + 4].copy_from_slice(&[0, 0, 0, 255]);
            }
        }
    }
    Image { w, h, rgba }
}

impl Icons {
    pub fn new(assets: &std::path::Path) -> Icons {
        Icons { sprites: Sprites::new(assets), cache: HashMap::new() }
    }

    /// An object's outlined icon at a size (Java `CharactersView.sprite`), None without a sprite.
    pub fn object(&mut self, ctx: &egui::Context, data: &GameData, id: i32, size: u32) -> Option<TextureHandle> {
        if id <= 0 {
            return None;
        }
        if let Some(t) = self.cache.get(&(id, size)) {
            return t.clone();
        }
        // Java's blank 8x8 for an object without a sprite: an empty icon of the size
        let im = self.sprites.object(data, id).unwrap_or(Image { w: 8, h: 8, rgba: vec![0; 256] });
        let out = outlined(&im, size);
        let tex = ctx.load_texture(
            format!("icon-{id}-{size}"),
            ColorImage::from_rgba_unmultiplied([out.w as usize, out.h as usize], &out.rgba),
            TextureOptions::NEAREST,
        );
        self.cache.insert((id, size), Some(tex.clone()));
        Some(tex)
    }
}
