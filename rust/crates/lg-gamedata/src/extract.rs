//! Extracting the game data from the game's `resources.assets` (Java: RealmShark's `UnityExtractor` and
//! `AssetExtractor`, themselves from UnityPy), into an `assets` folder as the Java app makes it:
//!
//! - `flatbuffer/spritesheetf`: the sprite sheet index
//! - `sprites/<name>.png`: the four sprite sheets (characters, characters_masks, groundTiles, mapObjects)
//! - `xml/<name>.xml`: every text asset that is XML
//! - `ObjectID.list`, `TileID.list`: what the app reads of every object and tile, from those XML files
//!
//! The Unity file is read as Java reads it, its odd choices kept (the header skipped as 48 bytes, positions cut to
//! 32 bits, a short read padded with zeros); where Java throws (an unknown kind of object, a file cut off), this
//! stops with an error as Java's extraction does. The sprites' pixels are Java's after drawing them: Java takes the
//! game's pixels as premultiplied by their alpha and converts them with Java2D's own arithmetic, overflow included.

use crate::xml::{self, Element, Node};
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

/// The text assets that are not XML (Java: `TextAsset.NON_XML_FILES`).
const NON_XML: &[&str] = &[
    "manifest_xml",
    "COPYING",
    "Errors",
    "ExplainUnzip",
    "cloth_bazaar",
    "Cursors",
    "Dialogs",
    "Keyboard",
    "LICENSE",
    "LineBreaking Following Characters",
    "LineBreaking Leading Characters",
    "manifest_json",
    "spritesheetf",
    "iso_4217",
    "data",
    "manifest",
    "BillingMode",
];

/// The textures written as sprite sheets (Java: `Texture2D.SPRITESHEET_NAMES`).
pub const SPRITE_SHEETS: &[&str] = &["characters", "characters_masks", "groundTiles", "mapObjects"];

const TEXTURE_2D: i32 = 28;
const TEXT_ASSET: i32 = 49;
const ASSET_BUNDLE: i32 = 142;
const SPRITE_ATLAS: i32 = 687_078_895;

/// The kinds of Unity objects Java knows (`ClassIDType`); another one makes Java's extraction throw.
const KNOWN_CLASSES: &[i32] = &[
    -1, 0, 1, 2, 3, 4, 5, 6, 8, 9, 11, 12, 13, 15, 17, 18, 19, 20, 21, 23, 25, 26, 27, 28, 29, 30, 33, 41, 43, 45, 47, 48, 49, 50, 51, 53,
    54, 55, 56, 57, 58, 59, 60, 61, 62, 64, 65, 66, 68, 70, 72, 74, 75, 76, 78, 81, 82, 83, 84, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96,
    98, 102, 104, 108, 109, 110, 111, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133,
    134, 135, 136, 137, 138, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162,
    163, 164, 165, 166, 167, 168, 169, 170, 171, 180, 181, 182, 183, 184, 185, 186, 187, 188, 191, 192, 193, 194, 195, 196, 197, 198, 199,
    200, 205, 206, 207, 208, 210, 212, 213, 214, 215, 216, 218, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234,
    235, 236, 237, 238, 240, 241, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 271, 272, 273, 280,
    290, 292, 300, 301, 303, 304, 305, 310, 319, 320, 328, 329, 330, 331, 362, 363, 1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008,
    1020, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1034, 1035, 1037, 1038, 1040, 1041, 1042, 1044, 1045, 1046, 1048, 1049, 1050, 1051,
    1052, 1053, 1054, 1055, 1101, 1102, 1105, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127,
    2000, 100000, 100001, 100002, 100003, 100004, 100005, 100006, 100007, 100008, 100009, 100010, 100011, 19719996, 41386430, 73398921,
    76251197, 156049354, 156483287, 171741748, 181963792, 277625683, 285090594, 293259124, 294290339, 334799969, 342846651, 367388927,
    369655926, 382020655, 426301858, 468431735, 478637458, 478637459, 483693784, 488575907, 612988286, 638013454, 641289076, 644342135,
    646504946, 662584278, 668709126, 687078895, 747330370, 825902497, 850595691, 877146078, 890905787, 895512359, 937362698, 994735392,
    994735403, 994735404, 1027052791, 1091556383, 1111377672, 1114811875, 1152215463, 1154873562, 1183024399, 1210832254, 1223240404,
    1268269756, 1325145578, 1386491679, 1392443030, 1403656975, 1480428607, 1542919678, 1571458007, 1628831178, 1742807556, 1766753193,
    1773428102, 1803986026, 1818360608, 1818360609, 1818360610, 1839735485, 1896753125, 1896753126, 1953259897, 1971053207, 1977754360,
    1981279845, 1995898324, 2058629509, 2058629510, 2058629511, 2059678085, 2083052967, 2083778819, 2089858483,
];

/// A Unity file read as Java's `DataReader` reads it: unbuffered in Java, so its position is the file's.
struct Reader {
    f: BufReader<File>,
    pos: u64,
    len: u64,
    big: bool,
}

type R<T> = Result<T, String>;

fn cut() -> String {
    "the game file ends early".into()
}

impl Reader {
    fn open(path: &Path, big: bool) -> R<Reader> {
        let f = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let len = f.metadata().map_err(|e| e.to_string())?.len();
        Ok(Reader { f: BufReader::with_capacity(1 << 16, f), pos: 0, len, big })
    }

    fn bytes<const N: usize>(&mut self) -> R<[u8; N]> {
        let mut b = [0; N];
        self.f.read_exact(&mut b).map_err(|_| cut())?;
        self.pos += N as u64;
        Ok(b)
    }

    fn i32(&mut self) -> R<i32> {
        let b = self.bytes::<4>()?;
        Ok(if self.big { i32::from_be_bytes(b) } else { i32::from_le_bytes(b) })
    }

    fn u32(&mut self) -> R<u32> {
        Ok(self.i32()? as u32)
    }

    fn i16(&mut self) -> R<i16> {
        let b = self.bytes::<2>()?;
        Ok(if self.big { i16::from_be_bytes(b) } else { i16::from_le_bytes(b) })
    }

    fn i64(&mut self) -> R<i64> {
        let b = self.bytes::<8>()?;
        Ok(if self.big { i64::from_be_bytes(b) } else { i64::from_le_bytes(b) })
    }

    fn f32(&mut self) -> R<f32> {
        Ok(f32::from_bits(self.u32()?))
    }

    fn u8(&mut self) -> R<u8> {
        Ok(self.bytes::<1>()?[0])
    }

    fn bool(&mut self) -> R<bool> {
        Ok(self.u8()? != 0)
    }

    /// Java's `read(byte[])`: what is there, the rest of the array left at zero.
    fn read(&mut self, n: i64) -> R<Vec<u8>> {
        let n = usize::try_from(n).map_err(|_| "a negative size in the game file".to_string())?;
        if n as u64 > self.len.saturating_sub(self.pos) + (1 << 20) {
            return Err(cut());
        }
        let mut b = vec![0; n];
        let mut got = 0;
        while got < n {
            match self.f.read(&mut b[got..]) {
                Ok(0) => break,
                Ok(k) => got += k,
                Err(e) => return Err(e.to_string()),
            }
        }
        self.pos += got as u64;
        Ok(b)
    }

    fn align(&mut self) -> R<()> {
        for _ in 0..(4 - self.pos % 4) % 4 {
            self.u8()?;
        }
        Ok(())
    }

    /// Bytes up to a zero, each as one character (Latin-1).
    fn string_to_null(&mut self) -> R<String> {
        let mut s = String::new();
        loop {
            match self.u8()? {
                0 => return Ok(s),
                c => s.push(c as char),
            }
        }
    }

    /// A length, the UTF-8 bytes and alignment; a length out of range gives "" (nothing more read).
    fn aligned_string(&mut self) -> R<String> {
        let n = self.i32()?;
        if n > 0 && (n as u64) < self.len.saturating_sub(self.pos) {
            let b = self.read(i64::from(n))?;
            self.align()?;
            return Ok(String::from_utf8_lossy(&b).into_owned());
        }
        Ok(String::new())
    }

    /// Java: `setPosition((int) offset)`.
    fn seek(&mut self, offset: i64) -> R<()> {
        let p = offset as i32;
        if p < 0 {
            return Err(format!("an object at a negative position ({p})"));
        }
        self.f.seek(SeekFrom::Start(p as u64)).map_err(|e| e.to_string())?;
        self.pos = p as u64;
        Ok(())
    }
}

#[derive(Debug)]
struct Object {
    class_id: i32,
    start: i64,
}

/// What Java takes from the file.
#[derive(Debug, Default)]
pub struct Resources {
    /// Every text asset: name and bytes, in the file's order.
    pub texts: Vec<(String, Vec<u8>)>,
    /// The last text asset named `spritesheetf`.
    pub spritesheet: Option<Vec<u8>>,
    /// Every texture: name, width, height, pixels (None: none in the file).
    pub textures: Vec<Texture>,
}

#[derive(Debug)]
pub struct Texture {
    pub name: String,
    pub width: i32,
    pub height: i32,
    pub data: Option<Vec<u8>>,
}

/// Reads the game file (Java: `FileHeader`, `SerializedFile`, `Resources`).
pub fn read(path: &Path, progress: &mut dyn FnMut(&str)) -> R<Resources> {
    let mut h = Reader::open(path, true)?;
    let size = h.len as i64;
    let mut metadata_size = i64::from(h.u32()?);
    let mut file_size = i64::from(h.u32()?);
    let version = i64::from(h.u32()?);
    let mut data_offset = i64::from(h.u32()?);
    let mut big = false;
    if version >= 22 {
        big = h.bool()?;
        h.bytes::<3>()?;
        metadata_size = i64::from(h.u32()?);
        file_size = h.i64()?;
        data_offset = h.i64()?;
        h.i64()?;
    }
    let resource_file = version > 100
        || file_size < 0
        || data_offset < 0
        || file_size > size
        || metadata_size > size
        || version > size
        || data_offset > size
        || file_size < metadata_size
        || file_size < data_offset;
    if resource_file {
        return Ok(Resources::default()); // Java reads nothing from such a file
    }
    let mut r = Reader::open(path, big)?;
    for _ in 0..12 {
        r.i32()?;
    }
    if version >= 7 {
        r.string_to_null()?;
    }
    if version >= 8 {
        r.i32()?;
    }
    let type_tree = version >= 13 && r.bool()?;
    let n = r.i32()?;
    let types = read_types(&mut r, version, type_tree, n)?;
    let big_ids = if (7..14).contains(&version) { r.i32()? } else { 0 };
    let count = r.i32()?;
    let mut objects = Vec::new();
    for i in 0..count {
        if i % 1000 == 0 {
            progress(&format!("Reading Assets {}K", i / 1000));
        }
        objects.push(read_object(&mut r, version, big_ids, data_offset, &types)?);
    }
    if version >= 11 {
        for _ in 0..r.i32()? {
            r.i32()?;
            if version < 14 {
                r.i32()?;
            } else {
                r.align()?;
                r.i64()?;
            }
        }
    }
    for _ in 0..r.i32()? {
        if version >= 6 {
            r.string_to_null()?;
        }
        if version >= 5 {
            r.read(16)?;
            r.i32()?;
        }
        r.string_to_null()?;
    }
    if version >= 20 {
        let n = r.i32()?;
        read_types(&mut r, version, type_tree, n)?;
    }
    if version >= 5 {
        r.string_to_null()?;
    }
    if objects.iter().any(|o| o.class_id == ASSET_BUNDLE) {
        return Err("Reading unimplemented bundles".into());
    }
    let mut res = Resources::default();
    for (i, o) in objects.iter().enumerate() {
        let n = i + 1;
        if n % 1000 == 0 {
            progress(&format!("Extracting Assets {}K", n / 1000));
        }
        if !KNOWN_CLASSES.contains(&o.class_id) {
            return Err(format!("an object of an unknown kind ({})", o.class_id));
        }
        match o.class_id {
            TEXT_ASSET => {
                r.seek(o.start)?;
                let name = r.aligned_string()?;
                let n = r.i32()?;
                let script = r.read(i64::from(n))?;
                if name == "spritesheetf" {
                    res.spritesheet = Some(script.clone());
                }
                res.texts.push((name, script));
            }
            SPRITE_ATLAS => sprite_atlas(&mut r, o.start)?,
            TEXTURE_2D => res.textures.push(texture(&mut r, o.start)?),
            _ => {}
        }
    }
    Ok(res)
}

/// `SerializedType.getTypes`: the class of each type (a type tree, which Java cannot read, is an error).
fn read_types(r: &mut Reader, version: i64, type_tree: bool, n: i32) -> R<Vec<i32>> {
    let mut types = Vec::new();
    for _ in 0..n {
        let class_id = r.i32()?;
        if version >= 16 {
            r.bool()?;
        }
        if version >= 17 {
            r.i16()?;
        }
        if version >= 13 {
            if (version < 16 && class_id < 0) || (version >= 16 && class_id == 114) {
                r.read(16)?;
            }
            r.read(16)?;
        }
        if type_tree {
            return Err("enable_type_tree".into());
        }
        types.push(class_id);
    }
    Ok(types)
}

/// `ObjectReader`.
fn read_object(r: &mut Reader, version: i64, big_ids: i32, data_offset: i64, types: &[i32]) -> R<Object> {
    if big_ids != 0 {
        r.i64()?;
    } else if version < 14 {
        r.i32()?;
    } else {
        r.align()?;
        r.i64()?;
    }
    let start = if version >= 22 { r.i64()? } else { i64::from(r.i32()?) } + data_offset;
    r.u32()?;
    let type_id = r.i32()?;
    let class_id = if version < 16 {
        r.i16()?;
        0 // Java leaves the class at 0 here
    } else {
        *usize::try_from(type_id).ok().and_then(|t| types.get(t)).ok_or_else(|| format!("type {type_id} out of range"))?
    };
    if version < 17 {
        r.i16()?; // destroyed (before 11) or script type
    }
    if version == 15 || version == 16 {
        r.u8()?;
    }
    Ok(Object { class_id, start })
}

/// `SpriteAtlas`: read as Java reads it (only to fail where it fails); nothing of it is kept.
fn sprite_atlas(r: &mut Reader, start: i64) -> R<()> {
    let pptr = |r: &mut Reader| -> R<()> {
        r.i32()?;
        r.i64()?;
        Ok(())
    };
    let count = |r: &mut Reader| -> R<i32> {
        let n = r.i32()?;
        if n < 0 { Err("a negative count in the game file".into()) } else { Ok(n) }
    };
    r.seek(start)?;
    r.aligned_string()?;
    for _ in 0..count(r)? {
        pptr(r)?;
    }
    for _ in 0..count(r)? {
        r.aligned_string()?;
    }
    for _ in 0..count(r)? {
        r.read(16)?;
        r.i64()?;
        pptr(r)?;
        pptr(r)?;
        for _ in 0..13 {
            r.f32()?;
        }
        r.u32()?;
        for _ in 0..count(r)? {
            pptr(r)?;
            r.string_to_null()?;
        }
        r.align()?;
    }
    Ok(())
}

/// `Texture2D`.
fn texture(r: &mut Reader, start: i64) -> R<Texture> {
    r.seek(start)?;
    let name = r.aligned_string()?;
    r.bool()?;
    r.align()?;
    let width = r.i32()?;
    let height = r.i32()?;
    r.i32()?; // complete image size
    r.i32()?; // mips stripped
    r.i32()?; // format
    r.i32()?; // mip count
    for _ in 0..4 {
        r.bool()?;
    }
    r.align()?;
    for _ in 0..3 {
        r.i32()?; // streaming priority, image count, dimension
    }
    for _ in 0..6 {
        r.i32()?; // filter, aniso, mip bias, wrap u v w
    }
    r.i32()?; // lightmap format
    r.i32()?; // color space
    let blob = r.i32()?;
    r.read(i64::from(blob))?;
    r.align()?;
    let size = r.i32()?;
    let data = if size != 0 { Some(r.read(i64::from(size))?) } else { None };
    r.i64()?;
    r.u32()?;
    r.aligned_string()?;
    Ok(Texture { name, width, height, data })
}

/// A sprite sheet as Java writes it: rows flipped (Unity's go bottom up), and each pixel as Java2D converts the
/// game's RGBA, which it takes as premultiplied, to plain ARGB. Gives RGBA rows, top first.
pub fn sprite_pixels(t: &Texture) -> R<Vec<u8>> {
    let data = t.data.as_ref().ok_or_else(|| format!("{}: no pixels", t.name))?;
    let (w, h) = (usize::try_from(t.width).unwrap_or(0), usize::try_from(t.height).unwrap_or(0));
    if w == 0 || h == 0 || data.len() < w * h * 4 {
        return Err(format!("{}: {}x{} does not fit its {} bytes", t.name, t.width, t.height, data.len()));
    }
    let mut out = vec![0; w * h * 4];
    for y in 0..h {
        let src = &data[(h - 1 - y) * w * 4..][..w * 4];
        let dst = &mut out[y * w * 4..][..w * 4];
        for (s, d) in src.as_chunks::<4>().0.iter().zip(dst.as_chunks_mut::<4>().0) {
            *d = java_pixel(*s);
        }
    }
    Ok(out)
}

/// `ComponentColorModel.getRed` and the others for 8 bit premultiplied colour: in float, unclamped.
fn unpremultiply(c: u8, a: u8) -> u32 {
    let v = (f32::from(c) / 255.0) * (255.0 / f32::from(a)) * 255.0 + 0.5;
    v as u32
}

fn mul8(a: u32, b: u32) -> u32 {
    (a * b + 127) / 255
}

/// Java2D's `div8table`.
fn div8(a: u32, b: u32) -> u32 {
    if b >= a {
        return 255;
    }
    let inc = ((0xff_u32 << 24) + a / 2) / a;
    ((1 << 23) + b * inc) >> 24
}

/// One pixel, game RGBA to what Java's PNG holds (RGBA): `getRGB` ORs the unclamped parts together (an overflow
/// spills into the part above, alpha included), Java2D premultiplies that by its alpha and divides it back.
pub fn java_pixel([r, g, b, a]: [u8; 4]) -> [u8; 4] {
    if a == 0 {
        return [0; 4];
    }
    let argb = (u32::from(a) << 24) | (unpremultiply(r, a) << 16) | (unpremultiply(g, a) << 8) | unpremultiply(b, a);
    let na = argb >> 24;
    let back = |c: u32| {
        let p = mul8(na, c & 0xff);
        if na == 255 { p } else { div8(na, p) }
    };
    [back(argb >> 16) as u8, back(argb >> 8) as u8, back(argb) as u8, na as u8]
}

/// `UnityExtractor.checkDuplicates`: a name taken gets 2, 3...
fn unique(taken: &mut std::collections::BTreeSet<String>, name: &str) -> String {
    let mut n = name.to_string();
    let mut count = 1;
    while taken.contains(&n) {
        count += 1;
        n = format!("{name}{count}");
    }
    taken.insert(n.clone());
    n
}

/// The whole extraction, into `assets` (Java: `extractAssets` then `extractAssetsFromXML`).
pub fn extract(game_file: &Path, assets: &Path, progress: &mut dyn FnMut(&str)) -> R<()> {
    progress("Creating Folders");
    let (flat, sprites, xmls) = (assets.join("flatbuffer"), assets.join("sprites"), assets.join("xml"));
    for d in [&flat, &sprites, &xmls] {
        std::fs::create_dir_all(d).map_err(|e| format!("{}: {e}", d.display()))?;
    }
    let res = read(game_file, progress)?;
    progress("Extracting Spritesheet File");
    if let Some(s) = &res.spritesheet
        && let Err(e) = std::fs::write(flat.join("spritesheetf"), s)
    {
        eprintln!("spritesheetf: {e}"); // Java prints it and goes on
    }
    progress("Extracting Sprites");
    for t in res.textures.iter().filter(|t| SPRITE_SHEETS.contains(&t.name.as_str())) {
        progress(&format!("Extracting Sprite: {}", t.name));
        let px = sprite_pixels(t)?;
        if let Err(e) = write_png(&sprites.join(format!("{}.png", t.name)), t.width as u32, t.height as u32, &px) {
            eprintln!("{}.png: {e}", t.name);
        }
    }
    progress("Extracting Xml Files");
    let mut taken = std::collections::BTreeSet::new();
    for (n, (name, script)) in res.texts.iter().enumerate() {
        progress(&format!("Extracting Xml Files {}", n + 1));
        if !NON_XML.contains(&name.as_str()) {
            let file = unique(&mut taken, name);
            if let Err(e) = std::fs::write(xmls.join(format!("{file}.xml")), script) {
                eprintln!("{file}.xml: {e}");
            }
        }
    }
    id_lists(assets, progress)
}

fn write_png(path: &Path, w: u32, h: u32, rgba: &[u8]) -> Result<(), String> {
    let f = std::io::BufWriter::new(File::create(path).map_err(|e| e.to_string())?);
    let mut enc = png::Encoder::new(f, w, h);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    enc.set_compression(png::Compression::Fast);
    let mut wr = enc.write_header().map_err(|e| e.to_string())?;
    wr.write_image_data(rgba).map_err(|e| e.to_string())
}

/// The XML files in the folder and below, in the folder's own order (Java: `Files.walk`), whose name ends in "xml".
fn xml_files(dir: &Path, out: &mut Vec<PathBuf>) -> R<()> {
    for e in std::fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
        let e = e.map_err(|e| e.to_string())?;
        let p = e.path();
        let t = e.file_type().map_err(|e| e.to_string())?;
        if t.is_file() && p.to_string_lossy().ends_with("xml") {
            out.push(p);
        } else if t.is_dir() {
            xml_files(&p, out)?;
        }
    }
    Ok(())
}

/// `ObjectID.list` and `TileID.list` from the XML files (Java: `extractAssetsFromXML`): a file that is not good XML
/// is left out; a number Java cannot read stops it.
pub fn id_lists(assets: &Path, progress: &mut dyn FnMut(&str)) -> R<()> {
    let mut files = Vec::new();
    xml_files(&assets.join("xml"), &mut files)?;
    let (mut objects, mut tiles) = (Vec::new(), Vec::new());
    for (n, f) in files.iter().enumerate() {
        progress(&format!("Parsing XML Files {}", n + 1));
        let bytes = std::fs::read(f).map_err(|e| format!("{}: {e}", f.display()))?;
        let text = String::from_utf8(bytes).map_err(|_| format!("{}: not UTF-8", f.display()))?;
        let Some(root) = document(&text) else { continue };
        for o in named(&root, "Object") {
            objects.push(object_line(o)?);
        }
        for t in named(&root, "Ground") {
            tiles.push(tile_line(t)?);
        }
    }
    objects.sort_by_key(|o| o.0);
    tiles.sort_by_key(|t| t.0);
    for (list, name) in [(&objects, "ObjectID.list"), (&tiles, "TileID.list")] {
        if list.is_empty() {
            continue; // Java opens the file only for its first line
        }
        let mut s = String::new();
        for (_, line) in list {
            s.push_str(line);
            s.push('\n');
        }
        std::fs::write(assets.join(name), s).map_err(|e| format!("{name}: {e}"))?;
    }
    Ok(())
}

/// DOM `getElementsByTagName` on the document: the root too.
fn named<'a>(root: &'a Element, name: &'static str) -> Vec<&'a Element> {
    let mut v = if root.name == name { vec![root] } else { Vec::new() };
    v.extend(root.descendants(name));
    v
}

/// A whole document: its root, nothing but comments, declarations and spaces after it.
fn document(text: &str) -> Option<Element> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let root = xml::parse(text).ok()?;
    // what follows the root: parse it again as the document's end
    let end = xml::root_end(text)?;
    let mut rest = text[end..].trim_start();
    loop {
        if rest.is_empty() {
            return Some(root);
        }
        let close = if rest.starts_with("<!--") {
            "-->"
        } else if rest.starts_with("<?") {
            "?>"
        } else {
            return None;
        };
        let i = rest.find(close)?;
        rest = rest[i + close.len()..].trim_start();
    }
}

/// DOM: a child's name and its first child's value (`""` without children, None when that is an element).
fn children(e: &Element) -> impl Iterator<Item = (&str, Option<&str>, Option<&Element>)> {
    e.content.iter().filter_map(|n| match n {
        Node::Element(c) => {
            let value = match c.content.first() {
                None => Some(""),
                Some(Node::Text(t) | Node::CData(t) | Node::Comment(t)) => Some(t.as_str()),
                Some(Node::Element(_)) => None,
            };
            Some((c.name.as_str(), value, Some(c)))
        }
        _ => None,
    })
}

fn has_children(e: &Element) -> bool {
    !e.content.is_empty()
}

fn or_null(v: Option<&str>) -> String {
    v.map_or_else(|| "null".to_string(), str::to_string)
}

/// `id` and `type` (Java: `addAttributes`).
fn id_and_name(e: &Element) -> R<(i32, String)> {
    let mut id = 0;
    let mut name = String::new();
    for (k, v) in &e.attrs {
        match k.as_str() {
            "id" => name = v.clone(),
            "type" => id = crate::decode_int(v)?,
            _ => {}
        }
    }
    Ok((id, name))
}

/// `AssetTexture`s: "index,file," each.
fn texture_entry(e: &Element) -> R<Option<String>> {
    let (mut index, mut file) = (-1, None);
    for (name, value, _) in children(e) {
        match name {
            "Index" => {
                let v = value.ok_or("a texture Index Java cannot read")?;
                index = if v.starts_with("0x") { crate::decode_int(v)? } else { parse_int(v)? };
            }
            "File" => file = value,
            _ => {}
        }
    }
    Ok(match file {
        Some(f) if index != -1 => Some(format!("{index},{f},")),
        _ => None,
    })
}

/// Java's `Integer.parseInt`.
fn parse_int(s: &str) -> R<i32> {
    let body = s.strip_prefix(['+', '-']).unwrap_or(s);
    if body.is_empty() || !body.bytes().all(|b| b.is_ascii_digit()) {
        return Err(format!("not a number: {s:?}"));
    }
    s.parse().map_err(|_| format!("out of range: {s:?}"))
}

/// Joined, the last comma dropped (Java: `deleteCharAt(length - 1)`).
fn joined(parts: &[String]) -> String {
    let mut s = parts.concat();
    s.pop();
    s
}

/// One line of `ObjectID.list` (Java: `AssetObject.toString`).
fn object_line(o: &Element) -> R<(i32, String)> {
    let (id, id_name) = id_and_name(o)?;
    let mut f: [String; 6] = Default::default(); // display, class, group, labels, tier, slot type
    let mut projectiles: Vec<String> = Vec::new();
    let mut subattack: Vec<i32> = Vec::new();
    let mut textures: Vec<String> = Vec::new();
    for (name, value, c) in children(o) {
        let slot = ["DisplayId", "Class", "Group", "Labels", "Tier", "SlotType"].iter().position(|n| *n == name);
        if let Some(i) = slot {
            f[i] = or_null(value);
            continue;
        }
        let c = c.expect("an element");
        match name {
            "Subattack" => {
                for (k, v) in &c.attrs {
                    if k == "projectileId" {
                        subattack.push(parse_int(v)?);
                    }
                }
            }
            "Projectile" => {
                let (mut min, mut max, mut pierce) = (None, None, false);
                for (n, v, _) in children(c) {
                    match n {
                        "Damage" => (min, max) = (v, v),
                        "MinDamage" => min = v,
                        "MaxDamage" => max = v,
                        "ArmorPiercing" => pierce = true,
                        _ => {}
                    }
                }
                if let (Some(a), Some(b)) = (min, max) {
                    projectiles.push(format!("{},{},{}", a.replace('\t', ""), b.replace('\t', ""), if pierce { "1," } else { "0," }));
                }
            }
            "Texture" | "AnimatedTexture" => textures.extend(texture_entry(c)?),
            _ => {}
        }
    }
    let [display, class, group, labels, _tier, slot_type] = f;
    let projectile = if projectiles.is_empty() {
        String::new()
    } else {
        let mut parts = vec![format!("{slot_type},")];
        if subattack.is_empty() {
            parts.extend(projectiles.iter().cloned());
        } else {
            for i in subattack {
                let p = usize::try_from(i).ok().and_then(|i| projectiles.get(i)).ok_or(format!("object {id}: no projectile {i}"))?;
                parts.push(p.clone());
            }
        }
        joined(&parts)
    };
    Ok((id, format!("{id};{display};{class};{group};{projectile};{};{labels};{id_name}", joined(&textures))))
}

/// The textures and damage of a tile (Java: `parseChildTiles`; a `MinDamage` also sets the maximum, as Java's
/// switch falls through).
fn tile_parts(e: &Element, textures: &mut Vec<String>, damage: &mut Option<String>) -> R<()> {
    let (mut min, mut max) = (None, None);
    for (name, value, c) in children(e) {
        let c = c.expect("an element");
        match name {
            "Texture" => textures.extend(texture_entry(c)?),
            "RandomTexture" if has_children(c) => tile_parts(c, textures, damage)?,
            "MinDamage" if has_children(c) => (min, max) = (value, value),
            "MaxDamage" if has_children(c) => max = value,
            _ => {}
        }
    }
    if let (Some(a), Some(b)) = (min, max) {
        *damage = Some(format!("{a},{b}"));
    }
    Ok(())
}

/// One line of `TileID.list` (Java: `AssetTile.toString`).
fn tile_line(t: &Element) -> R<(i32, String)> {
    let (id, id_name) = id_and_name(t)?;
    let (mut textures, mut damage) = (Vec::new(), None);
    tile_parts(t, &mut textures, &mut damage)?;
    Ok((id, format!("{id};{};{};{id_name}", joined(&textures), damage.unwrap_or_default())))
}

/// Where the game keeps the file in a Windows prefix's user folder.
const IN_LOCAL_APPDATA: &str = "AppData/Local/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";

/// The game's `resources.assets` (Java: `AssetExtractor.assetFile`, and on Linux `LinuxGameFiles`): on Windows in
/// `%LOCALAPPDATA%` (else under the Documents folder), on macOS in the app, on Linux the newest one in any Steam
/// (Proton), Wine, Lutris or Bottles prefix.
pub fn find_game_file() -> Option<PathBuf> {
    let home = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from)?;
    if cfg!(target_os = "linux") {
        return newest_in_prefixes(&home);
    }
    let file = if cfg!(target_os = "macos") {
        home.join(".local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets")
    } else {
        let tail = "RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
        let local = std::env::var_os("LOCALAPPDATA").map(|l| PathBuf::from(l).join(tail));
        match local {
            Some(f) if f.exists() => f,
            _ => home.join("Documents").join(tail),
        }
    };
    file.exists().then_some(file)
}

fn newest_in_prefixes(home: &Path) -> Option<PathBuf> {
    let mut best: Option<(PathBuf, std::time::SystemTime)> = None;
    for prefix in prefixes(home) {
        let Ok(users) = std::fs::read_dir(prefix.join("drive_c/users")) else { continue };
        for user in users.filter_map(Result::ok) {
            let f = user.path().join(IN_LOCAL_APPDATA);
            if let Ok(m) = std::fs::metadata(&f)
                && m.is_file()
                && let Ok(t) = m.modified()
                && best.as_ref().is_none_or(|(_, bt)| t > *bt)
            {
                best = Some((f, t));
            }
        }
    }
    best.map(|(f, _)| f)
}

/// Every Windows prefix the game may be in, in Java's order (`LinuxGameFiles.prefixes`).
fn prefixes(home: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    let add = |p: PathBuf, out: &mut Vec<PathBuf>| {
        if !out.contains(&p) {
            out.push(p);
        }
    };
    if let Some(w) = std::env::var_os("WINEPREFIX").filter(|w| !w.to_string_lossy().trim().is_empty()) {
        add(PathBuf::from(w), &mut out);
    }
    add(home.join(".wine"), &mut out);
    let real = |p: &Path| std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    let mut libraries: Vec<PathBuf> = Vec::new();
    for root in [
        ".local/share/Steam",
        ".steam/steam",
        ".var/app/com.valvesoftware.Steam/.local/share/Steam",
        ".var/app/com.valvesoftware.Steam/data/Steam",
    ] {
        let root = home.join(root);
        if !root.is_dir() {
            continue;
        }
        add(real(&root), &mut libraries);
        if let Ok(vdf) = std::fs::read_to_string(root.join("steamapps/libraryfolders.vdf")) {
            for path in vdf_paths(&vdf) {
                add(real(Path::new(&path.replace("\\\\", "\\"))), &mut libraries);
            }
        }
    }
    let children = |d: &Path| -> Vec<PathBuf> {
        std::fs::read_dir(d).map(|r| r.filter_map(Result::ok).map(|e| e.path()).filter(|p| p.is_dir()).collect()).unwrap_or_default()
    };
    for lib in &libraries {
        let compat = lib.join("steamapps/compatdata");
        add(compat.join("200210/pfx"), &mut out); // the game on Steam
        for c in children(&compat) {
            add(c.join("pfx"), &mut out);
        }
    }
    for d in ["Games", ".local/share/bottles/bottles", ".var/app/com.usebottles.bottles/data/bottles/bottles"] {
        for c in children(&home.join(d)) {
            add(c, &mut out);
        }
    }
    out
}

/// The `"path" "..."` values of Steam's `libraryfolders.vdf`.
fn vdf_paths(vdf: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = vdf;
    while let Some(i) = rest.find("\"path\"") {
        rest = &rest[i + 6..];
        let t = rest.trim_start_matches(char::is_whitespace);
        if t.len() < rest.len()
            && let Some(v) = t.strip_prefix('"')
            && let Some(end) = v.find('"')
            && end > 0
        {
            out.push(v[..end].to_string());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pixels_as_java() {
        // measured in Java: (R, alpha) to ARGB, with the overflow into alpha
        assert_eq!(java_pixel([121, 0, 0, 66]), [209, 0, 0, 67]);
        assert_eq!(java_pixel([10, 0, 0, 1]), [255, 0, 0, 9]);
        assert_eq!(java_pixel([0, 255, 0, 254]), [1, 0, 0, 254]);
        assert_eq!(java_pixel([255, 0, 0, 254]), [0, 0, 0, 255]);
        assert_eq!(java_pixel([51, 0, 0, 100]), [130, 0, 0, 100]);
        assert_eq!(java_pixel([7, 8, 9, 0]), [0; 4]);
        assert_eq!(java_pixel([1, 2, 3, 255]), [1, 2, 3, 255]);
    }

    #[test]
    fn lines_as_java() {
        let o = xml::parse(
            "<Object type=\"0x10\" id=\"Sword\"><Class>Equipment</Class><SlotType>1</SlotType><DisplayId><x/></DisplayId>\
             <Projectile><MinDamage>5</MinDamage><MaxDamage>\t9</MaxDamage><ArmorPiercing/></Projectile>\
             <Projectile><Damage>3</Damage></Projectile><Subattack projectileId=\"1\"/>\
             <Texture><File>lofi</File><Index>0x2a</Index></Texture><AnimatedTexture><File>c</File><Index>7</Index></AnimatedTexture></Object>",
        )
        .unwrap();
        assert_eq!(object_line(&o).unwrap(), (16, "16;null;Equipment;;1,3,3,0;42,lofi,7,c;;Sword".into()));
        let t = xml::parse("<Ground type=\"5\" id=\"Lava\"><RandomTexture><Texture><File>t</File><Index>1</Index></Texture></RandomTexture><MinDamage>20</MinDamage></Ground>").unwrap();
        assert_eq!(tile_line(&t).unwrap(), (5, "5;1,t;20,20;Lava".into()));
    }

    #[test]
    fn steam_library_paths() {
        let vdf = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"/home/a/.local/share/Steam\"\n\t}\n\t\"1\" { \"path\" \"D:\\\\Games\" }\"path\"\"x\"";
        assert_eq!(vdf_paths(vdf), ["/home/a/.local/share/Steam", "D:\\\\Games"]);
    }

    #[test]
    fn duplicates_numbered() {
        let mut t = std::collections::BTreeSet::new();
        assert_eq!([unique(&mut t, "a"), unique(&mut t, "a"), unique(&mut t, "a")], ["a", "a2", "a3"]);
    }
}
