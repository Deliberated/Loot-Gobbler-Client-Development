//! Every item in the game's files, as Loot Gobbler knows them (Java: `lootgobbler.ItemDb`): names, slots, tiers,
//! labels (UT, ST, origins...), weapons and their shots, enchant slots, what counts for the collection.
//!
//! Java reads each `assets/xml/*.xml` as a stream (StAX); this walks the parsed file the same way, including the
//! stream's quirks: `getElementText` takes the element's end with it, and throws on an element with elements inside
//! (which, uncaught, skips the rest of that file; where Java catches it, the first inner element's start is lost).

use crate::xml::{self, Element, Node};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::Path;

#[derive(Debug, Clone, PartialEq)]
pub struct Stat {
    pub stat: String,
    pub amount: i32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Shot {
    pub min: f64,
    pub max: f64,
    pub armor_piercing: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Volley {
    pub projectile: i32,
    pub count: i32,
    /// NaN: the weapon's own.
    pub rate_of_fire: f64,
    pub burst: i32,
    pub burst_delay: f64,
    pub burst_min_delay: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Weapon {
    pub rate_of_fire: f64,
    pub num_projectiles: i32,
    pub shots: BTreeMap<i32, Shot>,
    pub volleys: Vec<Volley>,
}

impl Default for Weapon {
    fn default() -> Self {
        Weapon { rate_of_fire: 1.0, num_projectiles: 1, shots: BTreeMap::new(), volleys: Vec::new() }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct EnchantSlots {
    pub pool: String,
    pub slot_chance: Vec<f64>,
    pub seasonal_mod: f64,
    pub forge_mod: f64,
    pub dust: Option<String>,
    pub dust_amounts: Vec<i32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Item {
    pub id: i32,
    pub name: String,
    pub slot_type: i32,
    pub tier: String,
    pub description: String,
    pub min_damage: i32,
    pub max_damage: i32,
    pub mp_cost: i32,
    pub feed_power: i32,
    pub soulbound: bool,
    /// Opens into an item when used.
    pub chest: bool,
    /// Used only from a quick slot.
    pub quickslot_only: bool,
    pub loot_boost: bool,
    pub on_equip: Vec<Stat>,
    pub labels: BTreeSet<String>,
    pub enchant_slots: Option<EnchantSlots>,
    pub key: String,
    pub weapon: Option<Weapon>,
    pub second_chance: bool,
    pub collection_icon: i32,
    /// Has a display name (unnamed items are unused test or event leftovers).
    pub named: bool,
}

const LOOT_ACCELERATORS: [&str; 4] = ["DROPLOOTBOOST", "TIEREDLOOTBOOST", "ITEMSLOTCHANCE", "DROPDUSTVOLUMEBOOST"];
const FORGE_DROPS: [&str; 3] = ["Concentrated Soul Fire", "SpecPen Upgrade Token", "SpecPen Upgrade Token Shiny"];

impl Item {
    fn new(id: i32) -> Item {
        Item {
            id,
            name: String::new(),
            slot_type: 0,
            tier: String::new(),
            description: String::new(),
            min_damage: -1,
            max_damage: -1,
            mp_cost: -1,
            feed_power: -1,
            soulbound: false,
            chest: false,
            quickslot_only: false,
            loot_boost: false,
            on_equip: Vec::new(),
            labels: BTreeSet::new(),
            enchant_slots: None,
            key: String::new(),
            weapon: None,
            second_chance: false,
            collection_icon: i32::MAX,
            named: false,
        }
    }

    pub fn is_ut(&self) -> bool {
        self.labels.contains("UT")
    }

    pub fn is_st(&self) -> bool {
        self.labels.contains("ST")
    }

    pub fn is_shiny(&self) -> bool {
        self.labels.contains("SHINY")
    }

    pub fn is_top_tier(&self) -> bool {
        ["T14_WEAPON", "T14_ARMOR", "T7_ABILITY", "T7_RING"].iter().any(|l| self.labels.contains(*l))
            || (self.labels.contains("T8") && self.labels.contains("ABILITY"))
    }

    /// Java `isCollectible`: what the collection tracks.
    pub fn is_collectible(&self) -> bool {
        let dungeon_ut = self.is_ut() && !self.labels.contains("RESKIN") && self.labels.iter().any(|l| l.starts_with("ORG_"));
        let ut_st = self.labels.contains("TAB_UT")
            || self.labels.contains("TAB_ST")
            || (self.named && (self.is_ut() || self.is_st()))
            || dungeon_ut;
        (ut_st && ((self.slot_type != 0 && self.slot_type != 10) || self.quickslot_only))
            || self.is_top_tier()
            || FORGE_DROPS.contains(&self.key.as_str())
    }

    pub fn tier_tag(&self) -> String {
        if self.is_ut() {
            "(UT)".into()
        } else if self.is_st() {
            "(ST)".into()
        } else if self.tier.is_empty() {
            String::new()
        } else {
            format!("Tier {}", self.tier)
        }
    }

    pub fn slot_name(&self) -> &'static str {
        slot_name(self.slot_type)
    }

    /// The dungeon it comes from, by its `ORG_` label.
    pub fn origin(&self) -> Option<&'static str> {
        for l in &self.labels {
            let Some(code) = l.strip_prefix("ORG_") else { continue };
            let code = code.strip_suffix("_S").unwrap_or(code);
            if let Some((_, n)) = ORIGINS.iter().find(|(c, _)| *c == code) {
                return Some(n);
            }
        }
        None
    }

    fn weapon(&mut self) -> &mut Weapon {
        self.weapon.get_or_insert_with(Weapon::default)
    }
}

pub fn slot_name(slot_type: i32) -> &'static str {
    SLOT_NAMES.iter().find(|(s, _)| *s == slot_type).map_or("Item", |(_, n)| n)
}

const SLOT_NAMES: [(i32, &str); 30] = [
    (1, "Sword"),
    (2, "Dagger"),
    (3, "Bow"),
    (4, "Tome"),
    (5, "Shield"),
    (6, "Leather Armor"),
    (7, "Heavy Armor"),
    (8, "Wand"),
    (9, "Ring"),
    (10, "Consumable"),
    (11, "Spell"),
    (12, "Seal"),
    (13, "Cloak"),
    (14, "Robe"),
    (15, "Quiver"),
    (16, "Helm"),
    (17, "Staff"),
    (18, "Poison"),
    (19, "Skull"),
    (20, "Trap"),
    (21, "Orb"),
    (22, "Prism"),
    (23, "Scepter"),
    (24, "Katana"),
    (25, "Shuriken"),
    (27, "Wakizashi"),
    (28, "Lute"),
    (29, "Mace"),
    (30, "Sheath"),
    (31, "Sigil"),
];

const ORIGINS: [(&str, &str); 41] = [
    ("OSANC", "Oryx's Sanctuary"),
    ("MOONLIGHT", "Moonlight Village"),
    ("SHTT", "The Shatters"),
    ("NEST", "The Nest"),
    ("SPECTRAL", "Spectral Penitentiary"),
    ("LOD", "Lair of Draconis"),
    ("PARASITE", "Parasite Chambers"),
    ("3D", "The Third Dimension"),
    ("MANOR", "Manor of the Immortals"),
    ("KOGBOLD", "Advanced Kogbold Steamworks"),
    ("FUNGAL", "Fungal Cavern"),
    ("CEM", "Haunted Cemetery"),
    ("VOID", "The Void"),
    ("UDL", "Undead Lair"),
    ("TOMB", "Tomb of the Ancients"),
    ("SEWER", "Toxic Sewers"),
    ("OTRENCH", "Ocean Trench"),
    ("MWOODS", "Magic Woods"),
    ("LIBRARY", "Cursed Library"),
    ("LH", "Lost Halls"),
    ("LAB", "Mad Lab"),
    ("DAVY", "Davy Jones' Locker"),
    ("CULT", "Cultist Hideout"),
    ("THICKET", "Secluded Thicket"),
    ("SPRITE", "Sprite World"),
    ("SHAITAN", "Lair of Shaitan"),
    ("MTEMPLE", "Mountain Temple"),
    ("ICECAVE", "Ice Cave"),
    ("HIVE", "The Hive"),
    ("CLAND", "Candyland Hunting Grounds"),
    ("ABYSS", "Abyss of Demons"),
    ("SPIDER", "Spider Den"),
    ("RUINS", "Ancient Ruins"),
    ("PUPPET", "Puppet Master's Theatre"),
    ("ENCORE", "Puppet Master's Encore"),
    ("JUNGLE", "Forbidden Jungle"),
    ("CASTLE", "Oryx's Castle"),
    ("DDOCKS", "Deadwater Docks"),
    ("FMAZE", "Forest Maze"),
    ("PCAVE", "Pirate Cave"),
    ("CDEPTHS", "The Crawling Depths"),
];

/// Java `ItemDb.parseInt`: `Integer.decode` of the trimmed text, else the fallback.
fn int(s: Option<&str>, fallback: i32) -> i32 {
    s.and_then(|s| decode(s.trim())).unwrap_or(fallback)
}

fn decode(s: &str) -> Option<i32> {
    let (neg, body) = match s.strip_prefix('-') {
        Some(b) => (true, b),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    let v = if let Some(h) = body.strip_prefix("0x").or_else(|| body.strip_prefix("0X")).or_else(|| body.strip_prefix('#')) {
        i64::from_str_radix(h, 16).ok()?
    } else if body.len() > 1 && body.starts_with('0') {
        i64::from_str_radix(&body[1..], 8).ok()?
    } else {
        body.parse::<i64>().ok()?
    };
    i32::try_from(if neg { -v } else { v }).ok()
}

/// Java `Double.parseDouble(s.trim())`, else the fallback.
fn double(s: Option<&str>, fallback: f64) -> f64 {
    s.and_then(|s| {
        let t = s.trim_matches(|c: char| c <= ' ');
        let unsigned = t.trim_start_matches(['+', '-']);
        if unsigned.chars().next().is_some_and(|c| c.is_ascii_alphabetic()) {
            // Java reads only these words (Rust also "inf", "nan" in any case)
            return match unsigned {
                "Infinity" | "NaN" => t.replace("Infinity", "inf").parse().ok(),
                _ => None,
            };
        }
        let t = t.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(t);
        t.parse().ok()
    })
    .unwrap_or(fallback)
}

/// StAX `getElementText`: the text inside, comments skipped; an element inside throws.
fn element_text(e: &Element) -> Result<String, ()> {
    let mut s = String::new();
    for n in &e.content {
        match n {
            Node::Text(t) | Node::CData(t) => s.push_str(t),
            Node::Comment(_) => {}
            Node::Element(_) => return Err(()),
        }
    }
    Ok(s)
}

/// Java's `String.split(",")` (trailing empty parts dropped).
fn split_java(s: &str) -> Vec<&str> {
    let mut v: Vec<&str> = s.split(',').collect();
    while v.len() > 1 && v.last() == Some(&"") {
        v.pop();
    }
    if v == [""] && !s.is_empty() {
        v.clear();
    }
    v
}

fn enchant_slots(e: &Element) -> EnchantSlots {
    let doubles = |name: &str| -> Vec<f64> {
        match e.attr(name) {
            Some(s) if !s.trim().is_empty() => split_java(s).iter().map(|x| double(Some(x), 0.0)).collect(),
            _ => Vec::new(),
        }
    };
    EnchantSlots {
        pool: e.attr("enchantmentList").map_or("Default Enchantment Pool".into(), |p| p.trim().to_string()),
        slot_chance: doubles("slotChance"),
        seasonal_mod: double(e.attr("seasonalSlotMod"), 1.0),
        forge_mod: double(e.attr("forgeSlotMod"), 1.0),
        dust: e.attr("dustType").map(str::to_string),
        dust_amounts: doubles("dustAmounts").iter().map(|d| *d as i32).collect(),
    }
}

/// Where the stream reader is in an `<Object>`.
#[derive(Default)]
struct Cur {
    item: Option<Item>,
    class: Option<String>,
    in_projectile: bool,
    shot: Option<[f64; 4]>,
    volley: Option<[f64; 6]>,
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Items {
    pub items: HashMap<i32, Item>,
    pub by_key: HashMap<String, i32>,
    /// What the collection tracks, in the collection's order.
    pub collectible: Vec<i32>,
    /// Skins an ability turns you into for a while.
    pub transform_skins: HashSet<i32>,
}

/// The file stopped being read here (an element where Java expected text).
struct Stop;

impl Items {
    pub fn item(&self, id: i32) -> Option<&Item> {
        self.items.get(&id)
    }

    pub fn by_key(&self, key: &str) -> Option<&Item> {
        self.by_key.get(key).and_then(|id| self.items.get(id))
    }

    /// Reads every `*.xml` in the folder, in the folder's order (Java: `ItemDb`). The second part lists the files
    /// Java stops reading partway.
    pub fn load(dir: &Path) -> (Items, Vec<String>) {
        let mut db = Items::default();
        let mut problems = Vec::new();
        let mut files: Vec<_> = std::fs::read_dir(dir)
            .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "xml")).collect())
            .unwrap_or_default();
        // "*.xml" as Java's glob: the name, case sensitive
        files.retain(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.ends_with(".xml")));
        for f in files {
            let Ok(bytes) = std::fs::read(&f) else { continue };
            let Ok(root) = xml::parse(&String::from_utf8_lossy(&bytes)) else { continue };
            let mut cur = Cur::default();
            if db.walk(&root, &mut cur, false).is_err() {
                problems.push(format!("{}: an element where text was expected; the rest is not read", f.display()));
            }
        }
        let mut c: Vec<&Item> = db.items.values().filter(|i| i.is_collectible()).collect();
        c.sort_by_key(|i| (category(i.slot_type), !i.is_top_tier(), i.slot_type, i.id));
        db.collectible = c.iter().map(|i| i.id).collect();
        (db, problems)
    }

    /// One element's start, what is inside, its end, as the stream gives them. `skip_start`: the stream lost this
    /// element's start (see `element_text`).
    fn walk(&mut self, e: &Element, cur: &mut Cur, skip_start: bool) -> Result<(), Stop> {
        let mut lost_first = false;
        if !skip_start {
            match self.start(e, cur) {
                Start::Inside => {}
                Start::TextTaken => return Ok(()), // the end went with the text
                Start::FirstStartLost => lost_first = true,
                Start::Stop => return Err(Stop),
            }
        }
        let mut first = true;
        for n in &e.content {
            if let Node::Element(c) = n {
                self.walk(c, cur, lost_first && first)?;
                first = false;
            }
        }
        self.end(e, cur);
        Ok(())
    }

    fn start(&mut self, e: &Element, cur: &mut Cur) -> Start {
        let n = e.name.as_str();
        if n == "Object" {
            *cur = Cur::default();
            let Some(t) = e.attr("type") else { return Start::Inside };
            let Some(id) = decode(t.trim()) else { return Start::Inside };
            let mut item = Item::new(id);
            item.name = e.attr("id").unwrap_or("").to_string();
            item.key = item.name.clone();
            item.collection_icon = int(e.attr("collectionIcon"), i32::MAX);
            cur.item = Some(item);
            return Start::Inside;
        }
        let Some(item) = cur.item.as_mut() else { return Start::Inside };
        // text-only elements: the stream reads their text, and their end with it
        let text = || element_text(e);
        macro_rules! text_or_stop {
            () => {
                match text() {
                    Ok(t) => t,
                    Err(()) => return Start::Stop,
                }
            };
        }
        match n {
            "Projectile" => {
                cur.in_projectile = true;
                cur.shot = Some([f64::from(int(e.attr("id"), 0)), -1.0, -1.0, 0.0]);
            }
            "Subattack" => cur.volley = Some([f64::from(int(e.attr("projectileId"), 0)), 1.0, f64::NAN, 0.0, 0.0, 0.0]),
            "ArmorPiercing" => {
                if let Some(s) = cur.shot.as_mut() {
                    s[3] = 1.0;
                }
            }
            "Damage" => {
                let v = double(Some(&text_or_stop!()), -1.0);
                if let Some(s) = cur.shot.as_mut() {
                    s[1] = v;
                    s[2] = v;
                }
                return Start::TextTaken;
            }
            "RateOfFire" => {
                let v = double(Some(&text_or_stop!()), 1.0);
                if let Some(vo) = cur.volley.as_mut() {
                    vo[2] = v;
                } else if !cur.in_projectile {
                    item.weapon().rate_of_fire = v;
                }
                return Start::TextTaken;
            }
            "NumProjectiles" => {
                let v = int(Some(&text_or_stop!()), 1);
                if let Some(vo) = cur.volley.as_mut() {
                    vo[1] = f64::from(v);
                } else if !cur.in_projectile {
                    item.weapon().num_projectiles = v;
                }
                return Start::TextTaken;
            }
            "BurstCount" | "BurstDelay" | "BurstMinDelay" => {
                let t = text_or_stop!();
                if let Some(vo) = cur.volley.as_mut() {
                    match n {
                        "BurstCount" => vo[3] = f64::from(int(Some(&t), 0)),
                        "BurstDelay" => vo[4] = double(Some(&t), 0.0),
                        _ => vo[5] = double(Some(&t), 0.0),
                    }
                }
                return Start::TextTaken;
            }
            "Soulbound" => item.soulbound = true,
            "OnlyUsableFromQuickslot" => item.quickslot_only = true,
            "Chest" => item.chest = true,
            "LDBoosted" | "LTBoosted" => item.loot_boost = true,
            "Activate" => {
                if e.attr("acceleratorId").is_some_and(|a| LOOT_ACCELERATORS.contains(&a)) {
                    item.loot_boost = true;
                }
            }
            "EnchantmentSlots" => item.enchant_slots = Some(enchant_slots(e)),
            "OnPlayerHitActivate" => {
                let low_life = e.attr("hpMinThresholdRelative").is_some();
                let effect = e.attr("effect");
                let what = text_or_stop!();
                let what = what.trim();
                if low_life
                    && (what == "Heal" || (what == "ConditionEffectSelf" && (effect == Some("Invulnerable") || effect == Some("Healing"))))
                {
                    item.second_chance = true;
                }
                return Start::TextTaken;
            }
            "ActivateOnEquip" => {
                let stat = e.attr("stat");
                let amount = int(e.attr("amount"), 0);
                let kind = text_or_stop!();
                if kind.trim() == "IncrementStat"
                    && let (Some(s), true) = (stat, amount != 0)
                {
                    item.on_equip.push(Stat { stat: s.to_string(), amount });
                }
                return Start::TextTaken;
            }
            "Class" => {
                cur.class = Some(text_or_stop!().trim().to_string());
                return Start::TextTaken;
            }
            "DisplayId" => {
                item.name = text_or_stop!().trim().to_string();
                item.named = !item.name.is_empty();
                return Start::TextTaken;
            }
            "SlotType" | "MpCost" | "feedPower" => {
                let t = text_or_stop!();
                match n {
                    "SlotType" => item.slot_type = int(Some(&t), 0),
                    "MpCost" => item.mp_cost = int(Some(&t), -1),
                    _ => item.feed_power = int(Some(&t), -1),
                }
                return Start::TextTaken;
            }
            "Tier" => {
                item.tier = text_or_stop!().trim().to_string();
                return Start::TextTaken;
            }
            "Description" => {
                item.description = text_or_stop!().trim().to_string();
                return Start::TextTaken;
            }
            "Labels" => {
                let t = text_or_stop!();
                item.labels = split_java(t.trim()).into_iter().map(str::to_string).collect();
                return Start::TextTaken;
            }
            "MinDamage" | "MaxDamage" => {
                let v = int(Some(&text_or_stop!()), -1);
                let (field, k) = if n == "MinDamage" { (&mut item.min_damage, 1) } else { (&mut item.max_damage, 2) };
                if cur.in_projectile && *field < 0 {
                    *field = v;
                }
                if let Some(s) = cur.shot.as_mut() {
                    s[k] = f64::from(v);
                }
                return Start::TextTaken;
            }
            _ => {
                if let Some(skin) = e.attr("skin") {
                    // Java catches what goes wrong here: an element inside loses its start to the stream
                    return match element_text(e) {
                        Ok(t) => {
                            if t.trim() == "SelfTransform"
                                && let Some(s) = decode(skin.trim())
                            {
                                self.transform_skins.insert(s);
                            }
                            Start::TextTaken
                        }
                        Err(()) => Start::FirstStartLost,
                    };
                }
            }
        }
        Start::Inside
    }

    fn end(&mut self, e: &Element, cur: &mut Cur) {
        match e.name.as_str() {
            "Object" => {
                if let Some(item) = cur.item.take()
                    && cur.class.as_deref() == Some("Equipment")
                {
                    self.by_key.entry(item.key.clone()).or_insert(item.id);
                    self.items.insert(item.id, item);
                }
            }
            "Projectile" => {
                cur.in_projectile = false;
                if let (Some(item), Some(s)) = (cur.item.as_mut(), cur.shot)
                    && s[1] >= 0.0
                    && s[2] >= 0.0
                {
                    item.weapon().shots.entry(s[0] as i32).or_insert(Shot { min: s[1], max: s[2], armor_piercing: s[3] > 0.0 });
                }
                cur.shot = None;
            }
            "Subattack" => {
                if let (Some(item), Some(v)) = (cur.item.as_mut(), cur.volley) {
                    item.weapon().volleys.push(Volley {
                        projectile: v[0] as i32,
                        count: v[1] as i32,
                        rate_of_fire: v[2],
                        burst: v[3] as i32,
                        burst_delay: v[4],
                        burst_min_delay: v[5],
                    });
                }
                cur.volley = None;
            }
            _ => {}
        }
    }
}

enum Start {
    Inside,
    TextTaken,
    FirstStartLost,
    Stop,
}

/// Java `ItemDb.category`: weapons, then abilities, armor, rings, the rest.
fn category(slot_type: i32) -> i32 {
    match slot_type {
        1 | 2 | 3 | 8 | 17 | 24 => 0,
        6 | 7 | 14 => 2,
        9 => 3,
        0 | 10 => 4,
        _ => 1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn items_as_java_streams_them() {
        let doc = r#"<Objects>
  <Object type="0xa14" id="Dagger of Foul Malevolence">
    <Class>Equipment</Class>
    <SlotType>2</SlotType>
    <DisplayId>Dagger of Foul Malevolence</DisplayId>
    <Labels>UT,ORG_UDL,TAB_UT</Labels>
    <RateOfFire>1.2</RateOfFire>
    <Projectile id="0"><MinDamage>30</MinDamage><MaxDamage>50</MaxDamage><ArmorPiercing/></Projectile>
    <Subattack projectileId="0"><NumProjectiles>2</NumProjectiles></Subattack>
    <ActivateOnEquip stat="20" amount="5">IncrementStat</ActivateOnEquip>
    <EnchantmentSlots slotChance="1,0.5," dustType="Weapon"/>
    <Activate skin="0x123">SelfTransform</Activate>
  </Object>
  <Object type="0x10" id="Rock"><Class>GameObject</Class></Object>
</Objects>"#;
        let mut db = Items::default();
        let root = xml::parse(doc).unwrap();
        assert!(db.walk(&root, &mut Cur::default(), false).is_ok());
        let d = db.item(0xa14).unwrap();
        assert_eq!((d.slot_type, d.named, d.min_damage, d.max_damage), (2, true, 30, 50));
        assert!(d.is_ut() && d.is_collectible());
        assert_eq!(d.origin(), Some("Undead Lair"));
        let w = d.weapon.as_ref().unwrap();
        assert_eq!((w.rate_of_fire, w.shots[&0].armor_piercing, w.volleys[0].count), (1.2, true, 2));
        assert!(w.volleys[0].rate_of_fire.is_nan());
        assert_eq!(d.on_equip, [Stat { stat: "20".into(), amount: 5 }]);
        assert_eq!(d.enchant_slots.as_ref().unwrap().slot_chance, [1.0, 0.5]);
        assert!(db.item(0x10).is_none(), "not equipment");
        assert!(!db.transform_skins.contains(&0x123), "an Activate is handled as Activate, not by its skin");
    }

    #[test]
    fn an_element_where_text_was_expected_stops_the_file() {
        let doc = r#"<Objects><Object type="1"><Class>Equipment</Class></Object>
<Object type="2"><Class>Equipment</Class><Tier><b>x</b></Tier></Object><Object type="3"><Class>Equipment</Class></Object></Objects>"#;
        let mut db = Items::default();
        let root = xml::parse(doc).unwrap();
        assert!(db.walk(&root, &mut Cur::default(), false).is_err());
        assert!(db.item(1).is_some() && db.item(2).is_none() && db.item(3).is_none());
    }
}
