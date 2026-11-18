//! The game's data, from the files the app extracts from the game into its `assets` folder (Java: `IdToAsset`,
//! `CharacterClass`, `LootBags`). Those files are the game's content: they stay on the player's machine, are only
//! read, and never go into the repository.
//!
//! - `ObjectID.list`: every object type (items, enemies, bags, portals...): `id;display;class;group;projectiles;
//!   texture;label;id name`
//! - `TileID.list`: every tile type: `id;texture;damage;id name`
//! - `xml/players.xml`: the character classes
//! - `xml/equip.xml`: abilities whose damage scales with a stat ([`abilities`])
//! - `xml/enchantments.xml`: what enchantments do ([`enchants`])

pub mod abilities;
pub mod dungeons;
pub mod enchants;
mod xml;

use std::collections::{BTreeMap, HashMap};
use std::path::Path;

/// One object type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Object {
    pub id: i32,
    pub display: String,
    pub class: String,
    pub group: String,
    pub projectiles: Vec<Projectile>,
    pub texture: String,
    /// Comma separated tags, e.g. `ENEMY,BOSS`.
    pub label: String,
    pub id_name: String,
}

impl Object {
    /// What the game shows: the display name, or the id name if there is none (Java: `IdToAsset.objectName`).
    pub fn name(&self) -> &str {
        if self.display.is_empty() { &self.id_name } else { &self.display }
    }

    pub fn has_label(&self, label: &str) -> bool {
        self.label.split(',').any(|l| l == label)
    }
}

/// A projectile an object (an enemy or a weapon) fires.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Projectile {
    pub min_damage: i32,
    pub max_damage: i32,
    pub armor_piercing: bool,
    /// The weapon's slot type, for items.
    pub slot_type: i32,
}

/// One tile type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tile {
    pub id: i32,
    pub texture: String,
    /// Damage for walking on it (0 if none, or if it varies).
    pub damage: i32,
    pub id_name: String,
}

/// A character class (Java: `CharacterClass`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Class {
    pub id: i32,
    pub name: String,
    /// Maximum life, mana, attack, defense, speed, dexterity, vitality, wisdom.
    pub max_stats: [i32; 8],
    /// Starting weapon, ability, armor; and a ring (2639).
    pub base_gear: [i32; 4],
    /// The classes using the same weapon type, this one included.
    pub weapon_group: Vec<i32>,
}

/// Loot bags by object type, and which of them count as drops (Java: `LootBags`, purple and better).
pub const LOOT_BAGS: &[(i32, &str)] = &[
    (1280, "Brown"),
    (1283, "Soulbound"),
    (1286, "Pink"),
    (1287, "Purple"),
    (1288, "Egg Basket"),
    (1289, "Teal"),
    (1291, "Blue"),
    (1292, "White"),
    (1294, "Gold"),
    (1295, "Orange"),
    (1708, "Red"),
    (1296, "B.White"),
    (1709, "B.Brown"),
    (1710, "B.Pink"),
    (1722, "B.Purple"),
    (1723, "B.Egg"),
    (1724, "B.Gold"),
    (1725, "B.Teal"),
    (1726, "B.Blue"),
    (1727, "B.Orange"),
    (1728, "B.Red"),
];

/// Whether this object type is a loot bag that counts as a drop (Java: `LootBags.isLootDropBag`).
pub fn is_loot_drop_bag(object_type: i32) -> bool {
    object_type >= 1287 && LOOT_BAGS.iter().any(|(id, _)| *id == object_type)
}

/// Everything read from an `assets` folder. Missing files leave their part empty, as in Java.
#[derive(Debug, Default, Clone)]
pub struct GameData {
    pub objects: HashMap<i32, Object>,
    pub tiles: HashMap<i32, Tile>,
    /// In the file's order, which is the game's (Java: `CharacterClass.CHAR_CLASS_LIST`).
    pub classes: Vec<Class>,
    pub abilities: abilities::Abilities,
    pub enchants: HashMap<i16, enchants::Enchant>,
    pub dungeons: dungeons::Dungeons,
    /// What went wrong while reading, for the log.
    pub problems: Vec<String>,
}

impl GameData {
    /// Reads `ObjectID.list`, `TileID.list` and `xml/players.xml` from an `assets` folder.
    pub fn load(assets: &Path) -> GameData {
        let mut d = GameData::default();
        let read = |name: &str| std::fs::read(assets.join(name)).ok().map(|b| String::from_utf8_lossy(&b).into_owned());
        if let Some(s) = read("ObjectID.list") {
            d.objects = parse_objects(&s, &mut d.problems);
        }
        if let Some(s) = read("TileID.list") {
            d.tiles = parse_tiles(&s, &mut d.problems);
        }
        if let Some(s) = read("xml/players.xml") {
            let (classes, problem) = parse_classes(&s);
            d.classes = classes;
            if let Some(e) = problem {
                d.problems.push(format!("players.xml: {e}"));
            }
        }
        if let Some(s) = read("xml/equip.xml") {
            let (a, problem) = abilities::parse(&s);
            d.abilities = a;
            d.problems.extend(problem);
        }
        let (dungeons, problems) = dungeons::Dungeons::load(&assets.join("xml"));
        d.dungeons = dungeons;
        d.problems.extend(problems);
        if let Some(s) = read("xml/enchantments.xml") {
            let (e, problem) = enchants::parse(&s);
            d.enchants = e;
            d.problems.extend(problem);
        }
        d
    }

    pub fn object(&self, id: i32) -> Option<&Object> {
        self.objects.get(&id)
    }

    /// The object's name (Java: `IdToAsset.objectName`).
    pub fn object_name(&self, id: i32) -> Option<&str> {
        self.objects.get(&id).map(Object::name)
    }

    pub fn class(&self, object_type: i32) -> Option<&Class> {
        self.classes.iter().find(|c| c.id == object_type)
    }

    /// Whether this object type is a character class (Java: `CharacterClass.isPlayerCharacter`).
    pub fn is_player_class(&self, object_type: i32) -> bool {
        self.class(object_type).is_some()
    }
}

/// Java's `String.split` with one plain character: trailing empty parts are dropped.
fn split_java(s: &str, sep: char) -> Vec<&str> {
    let mut v: Vec<&str> = s.split(sep).collect();
    while v.len() > 1 && v.last() == Some(&"") {
        v.pop();
    }
    if v == [""] && !s.is_empty() {
        v.clear(); // "," splits to nothing in Java
    }
    v
}

/// Java's `BufferedReader.readLine`: lines end at `\n`, `\r\n` or `\r`.
fn lines(s: &str) -> impl Iterator<Item = &str> {
    let s = s.strip_suffix('\n').unwrap_or(s);
    let s = s.strip_suffix('\r').unwrap_or(s);
    s.split('\n').flat_map(|l| l.strip_suffix('\r').unwrap_or(l).split('\r')).filter(move |_| !s.is_empty())
}

fn int(s: &str) -> Result<i32, String> {
    s.parse().map_err(|_| format!("not a number: {s:?}"))
}

fn parse_projectiles(s: &str) -> Result<Vec<Projectile>, String> {
    let l = split_java(s, ',');
    let first = *l.first().ok_or_else(|| format!("empty projectile list: {s:?}"))?;
    let slot_type = if first.is_empty() { 0 } else { int(first)? };
    let length = l.len().saturating_sub(1);
    let mut p = Vec::new();
    let mut i = 0;
    while i < length {
        let get = |k: usize| l.get(k).copied().ok_or_else(|| format!("projectile list cut short: {s:?}"));
        p.push(Projectile { min_damage: int(get(1 + i)?)?, max_damage: int(get(2 + i)?)?, armor_piercing: get(3 + i)? == "1", slot_type });
        i += 3;
    }
    Ok(p)
}

/// Java reads until the first line it cannot read and keeps what came before; so does this. A later line for the same
/// id replaces the earlier one.
fn parse_objects(s: &str, problems: &mut Vec<String>) -> HashMap<i32, Object> {
    let mut out = HashMap::new();
    for (n, line) in lines(s).enumerate() {
        let parsed = (|| -> Result<Object, String> {
            let l = split_java(line, ';');
            let get = |i: usize| l.get(i).copied().ok_or_else(|| "too few fields".to_string());
            Ok(Object {
                id: int(get(0)?)?,
                display: get(1)?.into(),
                class: get(2)?.into(),
                group: get(3)?.into(),
                projectiles: parse_projectiles(get(4)?)?,
                texture: get(5)?.into(),
                label: get(6)?.into(),
                id_name: get(7)?.into(),
            })
        })();
        match parsed {
            Ok(o) => {
                out.insert(o.id, o);
            }
            Err(e) => {
                problems.push(format!("ObjectID.list line {}: {e}; the rest is not read", n + 1));
                break;
            }
        }
    }
    out.insert(
        -1,
        Object {
            id: -1,
            display: "Unloaded".into(),
            class: String::new(),
            group: "Unloaded".into(),
            projectiles: Vec::new(),
            texture: String::new(),
            label: String::new(),
            id_name: "Unloaded".into(),
        },
    );
    out
}

fn parse_tiles(s: &str, problems: &mut Vec<String>) -> HashMap<i32, Tile> {
    let mut out = HashMap::new();
    for (n, line) in lines(s).enumerate() {
        let parsed = (|| -> Result<Tile, String> {
            let l = split_java(line, ';');
            let get = |i: usize| l.get(i).copied().ok_or_else(|| "too few fields".to_string());
            let dmg = get(2)?;
            let mut damage = 0;
            if !dmg.is_empty() {
                let d = split_java(dmg, ',');
                let (a, b) = (d.first().copied().unwrap_or(""), d.get(1).copied().ok_or("damage cut short")?);
                if a == b {
                    damage = int(a)?;
                }
            }
            Ok(Tile { id: int(get(0)?)?, texture: get(1)?.into(), damage, id_name: get(3)?.into() })
        })();
        match parsed {
            Ok(t) => {
                out.insert(t.id, t);
            }
            Err(e) => {
                problems.push(format!("TileID.list line {}: {e}; the rest is not read", n + 1));
                break;
            }
        }
    }
    out.insert(-1, Tile { id: -1, texture: String::new(), damage: -1, id_name: "Unknown".into() });
    out
}

/// Java's `Integer.decode`: decimal, or hex after `0x`/`#`, with an optional sign.
fn decode_int(s: &str) -> Result<i32, String> {
    let (neg, body) = match s.strip_prefix('-') {
        Some(b) => (true, b),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    let v = if let Some(h) = body.strip_prefix("0x").or_else(|| body.strip_prefix("0X")).or_else(|| body.strip_prefix('#')) {
        i64::from_str_radix(h, 16)
    } else if body.len() > 1 && body.starts_with('0') {
        i64::from_str_radix(&body[1..], 8)
    } else {
        body.parse::<i64>()
    }
    .map_err(|_| format!("not a number: {s:?}"))?;
    i32::try_from(if neg { -v } else { v }).map_err(|_| format!("out of range: {s:?}"))
}

/// The classes in `players.xml` (Java: `CharacterClass.populateFromXML`). At the first class Java cannot read it
/// stops, keeping the classes before it but without their weapon groups; so does this (the error is the second part).
fn parse_classes(s: &str) -> (Vec<Class>, Option<String>) {
    let mut classes = Vec::new();
    match read_classes(s, &mut classes) {
        Ok(()) => (classes, None),
        Err(e) => (classes, Some(e)),
    }
}

fn read_classes(s: &str, classes: &mut Vec<Class>) -> Result<(), String> {
    let mut weapon_groups: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
    for object in xml::elements(s, "Object")? {
        let id = decode_int(object.attr("type").ok_or("a class without a type")?)?;
        let name = object.attr("id").unwrap_or_default().to_string();
        let max = |tag: &str| -> Result<i32, String> {
            match object.child(tag).and_then(|c| c.attr("max")) {
                Some(v) => int(v),
                None => Ok(0),
            }
        };
        let max_stats = [
            max("MaxHitPoints")?,
            max("MaxMagicPoints")?,
            max("Attack")?,
            max("Defense")?,
            max("Speed")?,
            max("Dexterity")?,
            max("HpRegen")?,
            max("MpRegen")?,
        ];
        let equipment = object.child("Equipment").map(xml::Element::text).unwrap_or_default();
        let items = split_java(&equipment, ',');
        let mut base_gear = [0, 0, 0, 2639];
        for (i, gear) in base_gear.iter_mut().take(3).enumerate() {
            *gear = decode_int(items.get(i).ok_or("too few starting items")?.trim())?;
        }
        weapon_groups.entry(base_gear[0]).or_default().push(id);
        classes.push(Class { id, name, max_stats, base_gear, weapon_group: Vec::new() });
    }
    for c in classes.iter_mut() {
        c.weapon_group = weapon_groups[&c.base_gear[0]].clone();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_split() {
        assert_eq!(split_java("a;;b;;", ';'), ["a", "", "b"]);
        assert_eq!(split_java("", ';'), [""]);
        assert_eq!(split_java(",", ','), Vec::<&str>::new());
        assert_eq!(split_java(",30,30,0", ','), ["", "30", "30", "0"]);
    }

    #[test]
    fn objects_as_java() {
        let mut p = Vec::new();
        let o = parse_objects(
            "2;;Projectile;;;214,lofiObj;;Red Fire\n2;;;;;502,lofiObj;;pdiscovery_icon_01\r\n\
             294;Small Bee;Character;;,30,30,0;138,x;ENEMY,MINION;TH Small Bees\n\
             3;;;;;x;\n4;;;;;;;after the bad line\n",
            &mut p,
        );
        assert_eq!(o[&2].name(), "pdiscovery_icon_01");
        let bee = &o[&294];
        assert_eq!((bee.name(), bee.projectiles.len(), bee.projectiles[0].max_damage), ("Small Bee", 1, 30));
        assert!(bee.has_label("MINION") && !bee.has_label("MIN"));
        assert!(!o.contains_key(&4) && o.contains_key(&-1));
        assert_eq!(p.len(), 1);
    }

    #[test]
    fn tiles_and_numbers() {
        let mut p = Vec::new();
        let t = parse_tiles("1;tex;20,20;Lava\n2;tex;10,30;Odd\n3;tex;;Grass\n", &mut p);
        assert_eq!((t[&1].damage, t[&2].damage, t[&3].id_name.as_str()), (20, 0, "Grass"));
        assert_eq!(decode_int("0x0300"), Ok(768));
        assert_eq!(decode_int("-1"), Ok(-1));
        assert_eq!(decode_int("010"), Ok(8));
    }

    #[test]
    fn classes_from_xml() {
        let x = r#"<?xml version="1.0"?>
<Objects>
  <Object type="0x0300" id="Rogue">
    <Equipment>0xa14, 0xa56, 0xa78, -1</Equipment>
    <MaxHitPoints max="750">150</MaxHitPoints>
    <Attack max="55">16</Attack>
  </Object>
  <Object type="0x0307" id="Archer">
    <Equipment>0xa14, 0xa57, 0xa79</Equipment>
  </Object>
</Objects>"#;
        let (c, problem) = parse_classes(x);
        assert_eq!(problem, None);
        assert_eq!((c[0].id, c[0].name.as_str(), c[0].max_stats[0], c[0].max_stats[2]), (768, "Rogue", 750, 55));
        assert_eq!(c[0].base_gear, [0xa14, 0xa56, 0xa78, 2639]);
        assert_eq!(c[1].weapon_group, [768, 775]);
        // a class Java cannot read: the ones before stay, without weapon groups
        let (c, problem) = parse_classes(&x.replace("0xa57", "zz"));
        assert_eq!((c.len(), c[0].weapon_group.len(), problem.is_some()), (1, 0, true));
    }

    #[test]
    fn loot_bags() {
        assert!(is_loot_drop_bag(1287) && is_loot_drop_bag(1728));
        assert!(!is_loot_drop_bag(1280) && !is_loot_drop_bag(1286) && !is_loot_drop_bag(1290));
    }
}
