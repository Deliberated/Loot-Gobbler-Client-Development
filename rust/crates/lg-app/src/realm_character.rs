//! The character list from the game's servers (`char/list`), read as Tomato reads it (Java: `RealmCharacter.getCharList`,
//! through `StringXML`), and the account's exaltations (`account/listPowerUpStats`, Java: `checkExaltNew`).
//!
//! Java reads every child of a character (its first child node, then its attributes sorted by name, then the other
//! child nodes) and throws at the first value it cannot read: the whole list is then lost ([`Thrown`]).

use crate::java::{self, Thrown};
use lg_gamedata::xml::{self, JavaNode};
use std::collections::BTreeMap;

/// One character of the list (the parts the app uses).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RealmCharacter {
    pub char_id: i32,
    pub class_num: i16,
    pub level: i32,
    pub skin: i32,
    pub exp: i64,
    pub fame: i64,
    pub seasonal: bool,
    pub backpack: bool,
    pub equipment: Option<Vec<i32>>,
    pub hp: i32,
    pub mp: i32,
    pub atk: i32,
    pub def: i32,
    pub spd: i32,
    pub dex: i32,
    pub vit: i32,
    pub wis: i32,
    /// Every child of the character by name, the first value seen (None for a child element), in order.
    pub raw_tags: Vec<(String, Option<String>)>,
    /// How often each dungeon was completed (Java's `charStats.dungeonStats`): None without statistics, or where
    /// they could not be read.
    pub dungeon_counts: Option<Vec<i32>>,
    pub pet_name: Option<String>,
    pub pet_skin: i32,
    pub pet_type: i32,
    pub pet_instance_id: i32,
    pub pet_max_ability_power: i32,
    pub pet_rarity: i32,
    /// Per ability: points, power, type (its attributes sorted by name).
    pub pet_abilities: Option<[i32; 9]>,
}

impl RealmCharacter {
    /// Java's `rawTags.get`.
    pub fn tag(&self, name: &str) -> Option<&str> {
        self.raw_tags.iter().find(|(k, _)| k == name).and_then(|(_, v)| v.as_deref())
    }

    /// `rawTags.putIfAbsent`: a key mapped to null takes the new value too.
    fn put_tag(&mut self, name: &str, value: Option<&str>) {
        match self.raw_tags.iter_mut().find(|(k, _)| k == name) {
            Some((_, v)) if v.is_none() => *v = value.map(str::to_string),
            Some(_) => {}
            None => self.raw_tags.push((name.to_string(), value.map(str::to_string))),
        }
    }
}

/// Tomato's exaltation progress per class (`RealmCharacter.exalts`; a class can be there without values).
pub type Exalts = BTreeMap<i32, Option<Vec<i32>>>;

fn children<'a>(n: &JavaNode<'a>) -> Vec<JavaNode<'a>> {
    n.element.map_or_else(Vec::new, |e| e.java_children())
}

fn value<'a>(n: &JavaNode<'a>) -> Result<&'a str, Thrown> {
    n.value.ok_or(Thrown) // Java: parseInt(null), null.equals(...), null.split(...)
}

fn int(n: &JavaNode) -> Result<i32, Thrown> {
    java::parse_int(value(n)?)
}

/// Java `getCharList`: None when the answer is no XML (Java returns null), `Err` where Java throws.
pub fn char_list(answer: &str, exalts: &mut Exalts) -> Result<Option<Vec<RealmCharacter>>, Thrown> {
    let Ok(root) = xml::parse(answer) else { return Ok(None) };
    let mut list = Vec::new();
    for x in root.java_children() {
        if x.name != "Char" {
            continue;
        }
        let mut c = RealmCharacter::default();
        for info in children(&x) {
            if info.name == "id" {
                c.char_id = int(&info)?;
            }
            for v in children(&info) {
                c.put_tag(info.name, v.value);
                match info.name {
                    "ObjectType" => c.class_num = java::parse_short(value(&v)?)?,
                    "Equipment" => {
                        let mut e = Vec::new();
                        for s in java::split(value(&v)?, ',') {
                            let first = java::split(s, '#').first().copied().ok_or(Thrown)?;
                            e.push(java::parse_int(first)?);
                        }
                        c.equipment = Some(e);
                    }
                    "EquipQS" => {
                        value(&v)?;
                    }
                    "Level" => c.level = int(&v)?,
                    "Texture" => c.skin = int(&v)?,
                    "HasBackpack" => c.backpack = value(&v)? == "1",
                    "Has3Quickslots" => {
                        value(&v)?;
                    }
                    "MaxHitPoints" => c.hp = int(&v)?,
                    "MaxMagicPoints" => c.mp = int(&v)?,
                    "Attack" => c.atk = int(&v)?,
                    "Defense" => c.def = int(&v)?,
                    "Speed" => c.spd = int(&v)?,
                    "Dexterity" => c.dex = int(&v)?,
                    "HpRegen" => c.vit = int(&v)?,
                    "MpRegen" => c.wis = int(&v)?,
                    "Seasonal" => c.seasonal = value(&v)? == "True",
                    "Exp" => c.exp = java::parse_long(value(&v)?)?,
                    "CurrentFame" => c.fame = java::parse_long(value(&v)?)?,
                    // decoded in a try: what goes wrong leaves the counts unknown
                    "PCStats" => c.dungeon_counts = v.value.and_then(crate::pc_stats::dungeon_counts),
                    "Pet" => pet(&mut c, &info)?,
                    _ => {}
                }
            }
            check_exalt(&info, exalts)?;
        }
        list.push(c);
    }
    Ok(Some(list))
}

fn pet(c: &mut RealmCharacter, info: &JavaNode) -> Result<(), Thrown> {
    for p in children(info) {
        match p.name {
            "instanceId" => c.pet_instance_id = int(&p)?,
            "maxAbilityPower" => c.pet_max_ability_power = int(&p)?,
            "name" => c.pet_name = p.value.map(str::to_string),
            "rarity" => c.pet_rarity = int(&p)?,
            "skin" => c.pet_skin = int(&p)?,
            "type" => c.pet_type = int(&p)?,
            "Abilities" => {
                let mut a = [0i32; 9];
                let mut i = 0;
                for abilities in children(&p) {
                    for ability in children(&abilities) {
                        *a.get_mut(i).ok_or(Thrown)? = int(&ability)?;
                        i += 1;
                    }
                }
                c.pet_abilities = Some(a);
            }
            _ => {}
        }
    }
    Ok(())
}

/// Java `checkExalt`: a character's PowerUpStats (the game sends them outside the characters, so this rarely finds any).
fn check_exalt(info: &JavaNode, exalts: &mut Exalts) -> Result<(), Thrown> {
    if info.name != "PowerUpStats" {
        return Ok(());
    }
    for s in children(info) {
        if s.name != "ClassStats" {
            continue;
        }
        let (mut class, mut values) = (0, None);
        for c in children(&s) {
            match c.name {
                "class" => class = int(&c)?,
                "#text" => values = Some(java::split(value(&c)?, ',').into_iter().map(java::parse_int).collect::<Result<Vec<_>, _>>()?),
                _ => {}
            }
        }
        exalts.insert(class, values);
    }
    Ok(())
}

/// Java `checkExaltNew`: the account's exaltations into `exalts`; false when the answer is no XML.
pub fn exalts_answer(answer: &str, exalts: &mut Exalts) -> Result<bool, Thrown> {
    let Ok(root) = xml::parse(answer) else { return Ok(false) };
    if root.name != "AccountPowerups" {
        return Ok(true);
    }
    for info in root.java_children() {
        if info.name != "ClassPowerup" {
            continue;
        }
        let (mut class, mut v) = (0, vec![0i32; 8]);
        for c in children(&info) {
            let slot = match c.name {
                "Class" => None,
                "Dex" => Some(0),
                "Spd" => Some(1),
                "Vit" => Some(2),
                "Wis" => Some(3),
                "Def" => Some(4),
                "Att" => Some(5),
                "Mana" => Some(6),
                "Life" => Some(7),
                _ => continue,
            };
            let first = children(&c).first().copied().ok_or(Thrown)?; // c.children.get(0)
            let n = int(&first)?;
            match slot {
                None => class = n,
                Some(i) => v[i] = n,
            }
        }
        exalts.insert(class, Some(v));
    }
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_list() {
        let xml = r#"<Chars nextCharId="9"><Account><Name>Me</Name></Account><Char id="7"><ObjectType>768</ObjectType><Level>20</Level><Equipment>2580#123,-1,2680,-1</Equipment><Seasonal>True</Seasonal><Tex1>4096</Tex1><Pet name="Rex" type="1" instanceId="55" skin="2" rarity="3" maxAbilityPower="90"><Abilities><Ability type="402" power="50" points="1234"/><Ability type="406" power="1" points="0"/></Abilities></Pet></Char></Chars>"#;
        let mut ex = Exalts::new();
        let l = char_list(xml, &mut ex).unwrap().unwrap();
        assert_eq!(l.len(), 1);
        let c = &l[0];
        assert_eq!((c.char_id, c.class_num, c.level, c.seasonal), (7, 768, 20, true));
        assert_eq!(c.equipment.as_deref(), Some(&[2580, -1, 2680, -1][..]));
        assert_eq!(
            (c.pet_name.as_deref(), c.pet_instance_id, c.pet_abilities),
            (Some("Rex"), 55, Some([1234, 50, 402, 0, 1, 406, 0, 0, 0]))
        );
        assert_eq!(c.tag("Tex1"), Some("4096"));
        assert_eq!(c.tag("Pet"), Some("55"), "its first child is an element (null), then putIfAbsent takes the first attribute");
        assert_eq!(char_list("not xml", &mut ex), Ok(None));
        assert_eq!(char_list("<Chars><Char id=\"x\"/></Chars>", &mut ex), Err(Thrown));
    }

    #[test]
    fn reads_exalts() {
        let mut ex = Exalts::new();
        let a = "<AccountPowerups><ClassPowerup><Class>768</Class><Dex>5</Dex><Life>75</Life></ClassPowerup></AccountPowerups>";
        assert_eq!(exalts_answer(a, &mut ex), Ok(true));
        assert_eq!(ex[&768], Some(vec![5, 0, 0, 0, 0, 0, 0, 75]));
        assert_eq!(exalts_answer("<AccountPowerups><ClassPowerup><Class/></ClassPowerup></AccountPowerups>", &mut ex), Err(Thrown));
    }
}
