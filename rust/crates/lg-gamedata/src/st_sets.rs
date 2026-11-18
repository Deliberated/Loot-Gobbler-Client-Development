//! ST sets (Java: `lootgobbler.StSets`): four ST items that give a bonus together, from `xml/equipmentsets.xml`.

use crate::items::Items;
use crate::xml::{self, Element};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StSet {
    pub name: String,
    pub title: String,
    pub class_name: String,
    pub skin: i32,
    /// Weapon, ability, armor, ring.
    pub pieces: [i32; 4],
    pub bonus: String,
}

impl StSet {
    /// How many of its pieces are among these items.
    pub fn owned(&self, collected: &impl Fn(i32) -> bool) -> usize {
        self.pieces.iter().filter(|&&p| collected(p)).count()
    }
}

const CLASS_BY_ABILITY: [(i32, &str); 19] = [
    (4, "Priest"),
    (5, "Knight"),
    (11, "Wizard"),
    (12, "Paladin"),
    (13, "Rogue"),
    (15, "Archer"),
    (16, "Warrior"),
    (18, "Assassin"),
    (19, "Necromancer"),
    (20, "Huntress"),
    (21, "Mystic"),
    (22, "Trickster"),
    (23, "Sorcerer"),
    (25, "Ninja"),
    (27, "Samurai"),
    (28, "Bard"),
    (29, "Summoner"),
    (30, "Kensei"),
    (31, "Druid"),
];

/// Java `Integer.decode`.
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

/// Java throws here (a number that doesn't read): the sets after it are not read.
struct Thrown;

fn parse(s: &Element, items: &Items) -> Result<Option<StSet>, Thrown> {
    let pieces = s.descendants("Setpiece");
    if pieces.len() < 4 {
        return Ok(None);
    }
    let mut ids = [0; 4];
    for p in pieces {
        let slot: i32 = p.attr_or_empty("slot").parse().map_err(|_| Thrown)?;
        if !(0..=3).contains(&slot) {
            return Ok(None);
        }
        if ids[slot as usize] == 0 {
            ids[slot as usize] = decode(p.attr_or_empty("itemtype")).ok_or(Thrown)?;
        }
    }
    if ids.contains(&0) {
        return Ok(None);
    }
    if !ids.iter().any(|&id| items.item(id).is_some_and(|i| i.is_st())) {
        return Ok(None);
    }
    let class = items
        .item(ids[1])
        .map_or("Other", |a| CLASS_BY_ABILITY.iter().find(|(s, _)| *s == a.slot_type).map_or("Other", |(_, c)| c))
        .to_string();
    let name = s.attr_or_empty("id").to_string();
    let mut title = strip_set(&name);
    if class != "Other" && !title.contains(&class) {
        title = format!("{title} {class}");
    }
    let mut skin = 0;
    for e in s.descendants("ActivateOnEquipAll") {
        if e.text_content().trim() == "ChangeSkin" && !e.attr_or_empty("skinType").is_empty() {
            skin = decode(e.attr_or_empty("skinType")).ok_or(Thrown)?;
        }
    }
    let mut bonus = String::new();
    for tag in ["ActivateOnEquip2", "ActivateOnEquip3", "ActivateOnEquipAll"] {
        let mut part = String::new();
        for e in s.descendants(tag) {
            if e.text_content().trim() != "IncrementStat" {
                continue;
            }
            let amt: i32 = e.attr_or_empty("amount").parse().map_err(|_| Thrown)?;
            let stat = e.attr_or_empty("stat").replace("MAXHP", "HP").replace("MAXMP", "MP");
            part.push_str(&format!("{}{amt} {stat}", if amt > 0 { " +" } else { " " }));
        }
        if !part.is_empty() {
            if !bonus.is_empty() {
                bonus.push_str("   ");
            }
            bonus.push_str(if tag.ends_with('2') {
                "2pc:"
            } else if tag.ends_with('3') {
                "3pc:"
            } else {
                "4pc:"
            });
            bonus.push_str(&part);
        }
    }
    Ok(Some(StSet { name, title, class_name: class, skin, pieces: ids, bonus }))
}

/// Java `replaceAll("\\s+Set$", "")`.
fn strip_set(name: &str) -> String {
    if let Some(before) = name.strip_suffix("Set") {
        let trimmed = before.trim_end_matches(char::is_whitespace);
        if trimmed.len() < before.len() {
            return trimmed.to_string();
        }
    }
    name.to_string()
}

pub fn load(doc: &str, items: &Items) -> Vec<StSet> {
    let Ok(root) = xml::parse(doc) else { return Vec::new() };
    let mut sets: Vec<&Element> = Vec::new();
    if root.name == "EquipmentSet" {
        sets.push(&root);
    }
    sets.extend(root.descendants("EquipmentSet"));
    let mut out = Vec::new();
    for s in sets {
        match parse(s, items) {
            Ok(Some(set)) => out.push(set),
            Ok(None) => {}
            Err(Thrown) => break,
        }
    }
    out
}
