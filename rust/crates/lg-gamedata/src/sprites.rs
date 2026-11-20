//! The game's sprites (Java: RealmShark's `SpriteFlatBuffer`, `ImageBuffer`, `IdToAsset` textures): the index of
//! every sprite in the extracted sprite sheets (`flatbuffer/spritesheetf`, a FlatBuffers file), and each object's
//! sprite cut from its sheet (`sprites/*.png`).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Where a sprite is: its sheet (1 groundTiles, 2 characters, 3 characters_masks, 4 mapObjects), place and size.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Sprite {
    pub atlas: i32,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    /// Its dye mask in characters_masks (x, y, w, h), for characters and skins.
    pub mask: Option<[i32; 4]>,
    pub color: [f32; 4],
}

/// The sheets, in Java's order (atlas id - 1).
pub const SHEETS: [&str; 4] = ["groundTiles", "characters", "characters_masks", "mapObjects"];

/// A little FlatBuffers reader: tables, vectors, strings, structs.
struct Fb<'a>(&'a [u8]);

impl Fb<'_> {
    fn u32(&self, at: usize) -> Option<u32> {
        Some(u32::from_le_bytes(self.0.get(at..at + 4)?.try_into().ok()?))
    }
    fn i32(&self, at: usize) -> Option<i32> {
        self.u32(at).map(|v| v as i32)
    }
    fn i64(&self, at: usize) -> Option<i64> {
        Some(i64::from_le_bytes(self.0.get(at..at + 8)?.try_into().ok()?))
    }
    fn f32(&self, at: usize) -> Option<f32> {
        self.u32(at).map(f32::from_bits)
    }
    fn u16(&self, at: usize) -> Option<u16> {
        Some(u16::from_le_bytes(self.0.get(at..at + 2)?.try_into().ok()?))
    }
    /// A table's field: its position, None if absent.
    fn field(&self, table: usize, slot: usize) -> Option<usize> {
        let vt = (table as i64 - i64::from(self.i32(table)?)) as usize;
        let len = usize::from(self.u16(vt)?);
        if slot >= len {
            return None;
        }
        match self.u16(vt + slot)? {
            0 => None,
            o => Some(table + usize::from(o)),
        }
    }
    fn indirect(&self, at: usize) -> Option<usize> {
        Some(at + self.u32(at)? as usize)
    }
    /// A vector field: its first element's position and length.
    fn vector(&self, table: usize, slot: usize) -> Option<(usize, usize)> {
        let v = self.indirect(self.field(table, slot)?)?;
        Some((v + 4, self.u32(v)? as usize))
    }
    fn string(&self, table: usize, slot: usize) -> Option<String> {
        let (start, len) = self.vector(table, slot)?;
        Some(String::from_utf8_lossy(self.0.get(start..start + len)?).into_owned())
    }
}

/// Every sprite by sheet name and index (Java: `SpriteFlatBuffer.sprites`).
#[derive(Debug, Default, Clone)]
pub struct SpriteIndex {
    pub sheets: HashMap<String, HashMap<i32, Sprite>>,
}

/// `getSprite`: None where Java's reading throws (it then leaves the sprite out).
fn sprite(fb: &Fb, t: usize) -> Option<(i32, Sprite)> {
    let pos = |slot| -> Option<[f32; 4]> {
        let at = fb.field(t, slot)?;
        Some([fb.f32(at)?, fb.f32(at + 4)?, fb.f32(at + 8)?, fb.f32(at + 12)?]) // x y h w
    };
    let p = pos(4)?;
    let index = fb.field(t, 10).and_then(|a| fb.i32(a)).unwrap_or(0);
    let atlas = fb.field(t, 18).and_then(|a| fb.i64(a)).unwrap_or(0) as i32;
    let color = pos(12)?; // r g b a
    let mask = pos(6).map(|m| [m[0] as i32, m[1] as i32, m[3] as i32, m[2] as i32]);
    Some((index, Sprite { atlas, x: p[0] as i32, y: p[1] as i32, w: p[3] as i32, h: p[2] as i32, mask, color }))
}

impl SpriteIndex {
    /// Reads `spritesheetf`; nothing if it is missing or unreadable.
    pub fn read(path: &Path) -> SpriteIndex {
        std::fs::read(path).ok().map(|b| SpriteIndex::parse(&b)).unwrap_or_default()
    }

    pub fn parse(b: &[u8]) -> SpriteIndex {
        let fb = Fb(b);
        let mut out = SpriteIndex::default();
        let Some(root) = fb.indirect(0) else { return out };
        if let Some((start, n)) = fb.vector(root, 4) {
            for i in 0..n {
                let Some(sheet) = fb.indirect(start + 4 * i) else { continue };
                let name = fb.string(sheet, 4).unwrap_or_default();
                let map = out.sheets.entry(name).or_default();
                if let Some((s, m)) = fb.vector(sheet, 8) {
                    for j in 0..m {
                        if let Some((index, sp)) = fb.indirect(s + 4 * j).and_then(|t| sprite(&fb, t)) {
                            map.insert(index, sp);
                        }
                    }
                }
            }
        }
        if let Some((start, n)) = fb.vector(root, 6) {
            for i in 0..n {
                let Some(a) = fb.indirect(start + 4 * i) else { continue };
                let name = fb.string(a, 4).unwrap_or_default();
                let num = |slot| fb.field(a, slot).and_then(|f| fb.i32(f)).unwrap_or(0);
                let (index, set, direction, action) = (num(6), num(8), num(10), num(12));
                let Some((_, sp)) = fb.field(a, 14).and_then(|f| fb.indirect(f)).and_then(|t| sprite(&fb, t)) else {
                    continue;
                };
                let map = out.sheets.entry(name).or_default();
                // every frame shares one index: the standing, right-facing one is kept (Loot Gobbler's patch)
                let standing_right = direction == 0 && action == 0 && set == 0;
                if standing_right || !map.contains_key(&index) {
                    map.insert(index, sp);
                }
            }
        }
        out
    }

    pub fn get(&self, sheet: &str, index: i32) -> Option<&Sprite> {
        self.sheets.get(sheet)?.get(&index)
    }
}

/// An object's first texture (Java: `IdToAsset.getObjectTextureName/Index(id, 0)`): `index,file,...` in
/// ObjectID.list.
pub fn first_texture(texture: &str) -> Option<(String, i32)> {
    let parts: Vec<&str> = texture.split(',').collect();
    let (index, name) = (parts.first()?, parts.get(1)?);
    Some((name.to_string(), index.parse().ok()?))
}

/// An image: width, height, RGBA rows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub w: u32,
    pub h: u32,
    pub rgba: Vec<u8>,
}

impl Image {
    pub fn crop(&self, x: i32, y: i32, w: i32, h: i32) -> Option<Image> {
        let (x, y, w, h) = (u32::try_from(x).ok()?, u32::try_from(y).ok()?, u32::try_from(w).ok()?, u32::try_from(h).ok()?);
        if x + w > self.w || y + h > self.h {
            return None;
        }
        let mut rgba = Vec::with_capacity((w * h * 4) as usize);
        for row in y..y + h {
            let s = ((row * self.w + x) * 4) as usize;
            rgba.extend_from_slice(&self.rgba[s..s + (w * 4) as usize]);
        }
        Some(Image { w, h, rgba })
    }

    pub fn read_png(path: &Path) -> Option<Image> {
        let dec = png::Decoder::new(std::io::BufReader::new(std::fs::File::open(path).ok()?));
        let mut r = dec.read_info().ok()?;
        let mut buf = vec![0; r.output_buffer_size()];
        let info = r.next_frame(&mut buf).ok()?;
        buf.truncate(info.buffer_size());
        let rgba = match info.color_type {
            png::ColorType::Rgba => buf,
            png::ColorType::Rgb => buf.as_chunks::<3>().0.iter().flat_map(|p| [p[0], p[1], p[2], 255]).collect(),
            _ => return None,
        };
        Some(Image { w: info.width, h: info.height, rgba })
    }
}

/// The sprite sheets of an assets folder, read when first needed (Java: `ImageBuffer`).
#[derive(Debug, Default)]
pub struct Sprites {
    pub index: SpriteIndex,
    dir: PathBuf,
    sheets: [Option<Image>; 4],
}

impl Sprites {
    pub fn new(assets: &Path) -> Sprites {
        Sprites {
            index: SpriteIndex::read(&assets.join("flatbuffer/spritesheetf")),
            dir: assets.join("sprites"),
            sheets: Default::default(),
        }
    }

    fn sheet(&mut self, atlas: i32) -> Option<&Image> {
        let i = usize::try_from(atlas - 1).ok().filter(|i| *i < 4)?;
        if self.sheets[i].is_none() {
            self.sheets[i] = Image::read_png(&self.dir.join(format!("{}.png", SHEETS[i])));
        }
        self.sheets[i].as_ref()
    }

    /// A sprite by sheet name and index.
    pub fn sprite(&mut self, sheet: &str, index: i32) -> Option<Image> {
        let s = *self.index.get(sheet, index)?;
        self.sheet(s.atlas)?.crop(s.x, s.y, s.w, s.h)
    }

    /// An object's sprite (Java: `ImageBuffer.getImage`): None where Java gives its empty 8x8 picture.
    pub fn object(&mut self, data: &crate::GameData, id: i32) -> Option<Image> {
        let (name, index) = first_texture(&data.objects.get(&id)?.texture)?;
        self.sprite(&name, index)
    }

    /// An object's dye mask (Java: `ImageBuffer.getMaskImage`).
    pub fn mask(&mut self, data: &crate::GameData, id: i32) -> Option<Image> {
        let (name, index) = first_texture(&data.objects.get(&id)?.texture)?;
        let m = self.index.get(&name, index)?.mask?;
        if m[2] <= 0 || m[3] <= 0 {
            return None;
        }
        self.sheet(3)?.crop(m[0], m[1], m[2], m[3])
    }
}
