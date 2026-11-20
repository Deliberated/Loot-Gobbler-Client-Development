//! Game sprites as the Java app shows them (`ImageBuffer.getOutlinedIcon`): scaled to the size less 2 (nearest
//! pixel, Java's `SCALE_DEFAULT`), with a 1 px black outline around the opaque pixels; kept as textures.

use egui::{ColorImage, TextureHandle, TextureOptions};
use lg_gamedata::GameData;
use lg_gamedata::sprites::{Image, Sprites};
use std::collections::HashMap;

pub struct Icons {
    sprites: Sprites,
    cache: HashMap<(i32, u32), Option<TextureHandle>>,
    skins: HashMap<String, TextureHandle>,
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

/// The texture a dye code stands for (Java `Dyes.textureImage`): high byte 1 a solid colour, 4/5/9/10 a tile of
/// the textile sheet of that size.
fn texture_image(sprites: &mut Sprites, code: i32) -> Option<Image> {
    let (kind, value) = ((code as u32 >> 24) & 0xFF, code & 0xFF_FFFF);
    match kind {
        1 => Some(Image { w: 1, h: 1, rgba: vec![(value >> 16) as u8, (value >> 8) as u8, value as u8, 255] }),
        4 | 5 | 9 | 10 => sprites.sprite(&format!("textile{kind}x{kind}"), value),
        _ => None,
    }
}

/// Java `Dyes.dyed`: the sprite 5x, every pixel its mask marks (red: clothing, Tex1; green: accessory, Tex2) taking
/// the dye or the cloth tiled across, darkened by the mask's strength. None without a mask.
fn dyed(sprites: &mut Sprites, data: &GameData, id: i32, tex1: i32, tex2: i32) -> Option<Image> {
    const SCALE: u32 = 5;
    let src = sprites.object(data, id)?;
    let mask = sprites.mask(data, id)?;
    let (t1, t2) = (texture_image(sprites, tex1), texture_image(sprites, tex2));
    let (w, h) = (src.w * SCALE, src.h * SCALE);
    let px = |im: &Image, x: u32, y: u32| -> [u8; 4] {
        let i = ((y * im.w + x) * 4) as usize;
        [im.rgba[i], im.rgba[i + 1], im.rgba[i + 2], im.rgba[i + 3]]
    };
    let shade = |p: [u8; 4], t: [u8; 4], k: u8| -> [u8; 4] {
        let f = |c: u8| (u32::from(c) * u32::from(k) / 255) as u8;
        [f(t[0]), f(t[1]), f(t[2]), p[3]]
    };
    let mut rgba = Vec::with_capacity((w * h * 4) as usize);
    for y in 0..h {
        for x in 0..w {
            let (sx, sy) = (x / SCALE, y / SCALE);
            let mut p = px(&src, sx, sy);
            let m = if sx < mask.w && sy < mask.h { px(&mask, sx, sy) } else { [0; 4] };
            // Java reads the mask as ARGB: its red and green, alpha aside
            if m[0] > 0
                && let Some(t) = &t1
            {
                p = shade(p, px(t, x % t.w, y % t.h), m[0]);
            }
            if m[1] > 0
                && let Some(t) = &t2
            {
                p = shade(p, px(t, x % t.w, y % t.h), m[1]);
            }
            rgba.extend_from_slice(&p);
        }
    }
    Some(Image { w, h, rgba })
}

/// Java `Dyes.outlined`: to size-2 sampling x * w / inner, then the 1 px outline.
fn outlined_dyed(img: &Image, size: u32) -> Image {
    let inner = size.saturating_sub(2).max(1);
    let mut sampled = Image { w: inner, h: inner, rgba: Vec::with_capacity((inner * inner * 4) as usize) };
    for y in 0..inner {
        for x in 0..inner {
            let (sx, sy) = (x * img.w / inner, y * img.h / inner);
            let i = ((sy * img.w + sx) * 4) as usize;
            sampled.rgba.extend_from_slice(&img.rgba[i..i + 4]);
        }
    }
    // the same outline as getOutlinedIcon, the sampled picture at its size already
    outlined(&sampled, size)
}

/// A character's look as an image (what [`Icons::skin`] shows), for comparing with Java.
pub fn skin_image(sprites: &mut Sprites, data: &GameData, sprite: i32, tex1: i32, tex2: i32, size: u32) -> Image {
    let plain = |sprites: &mut Sprites| outlined(&sprites.object(data, sprite).unwrap_or(Image { w: 8, h: 8, rgba: vec![0; 256] }), size);
    if tex1 == 0 && tex2 == 0 {
        return plain(sprites);
    }
    match dyed(sprites, data, sprite, tex1, tex2) {
        Some(d) => outlined_dyed(&d, size),
        None => plain(sprites),
    }
}

impl Icons {
    /// A sprite by sheet and index scaled to a size, pixel-sharp, no outline (Java `EnchantIcons.icon`).
    pub fn sheet(&mut self, ctx: &egui::Context, sheet: &str, index: i32, size: u32) -> Option<TextureHandle> {
        let key = format!("sheet-{sheet}-{index}-{size}");
        if let Some(t) = self.skins.get(&key) {
            return Some(t.clone());
        }
        let im = self.sprites.sprite(sheet, index)?;
        let out = scale(&im, size, size);
        let tex = ctx.load_texture(
            key.clone(),
            ColorImage::from_rgba_unmultiplied([out.w as usize, out.h as usize], &out.rgba),
            TextureOptions::NEAREST,
        );
        self.skins.insert(key, tex.clone());
        Some(tex)
    }

    /// A character's look with its dyes (Java `CharactersView.skin` / `Dyes.icon`): the plain icon without dyes.
    pub fn skin(&mut self, ctx: &egui::Context, data: &GameData, sprite: i32, tex1: i32, tex2: i32, size: u32) -> Option<TextureHandle> {
        if tex1 == 0 && tex2 == 0 {
            return self.object(ctx, data, sprite, size);
        }
        let key = format!("skin-{sprite}-{tex1}-{tex2}-{size}");
        if let Some(t) = self.skins.get(&key) {
            return Some(t.clone());
        }
        let Some(d) = dyed(&mut self.sprites, data, sprite, tex1, tex2) else { return self.object(ctx, data, sprite, size) };
        let out = outlined_dyed(&d, size);
        let tex = ctx.load_texture(
            key.clone(),
            ColorImage::from_rgba_unmultiplied([out.w as usize, out.h as usize], &out.rgba),
            TextureOptions::NEAREST,
        );
        self.skins.insert(key, tex.clone());
        Some(tex)
    }

    pub fn new(assets: &std::path::Path) -> Icons {
        Icons { sprites: Sprites::new(assets), cache: HashMap::new(), skins: HashMap::new() }
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
