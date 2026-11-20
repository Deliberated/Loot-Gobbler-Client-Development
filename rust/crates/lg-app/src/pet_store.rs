//! Your pets (Java: `PetStore`): the character list names the pet each character has, with its abilities and
//! saddlebag (regular and seasonal characters each see their own); pets seen in the Pet Yard add their stats.
//!
//! Not yet: reading Java's last saved character list (`char-list.xml`) when it kept no pets yet.

use lg_protocol::packets::data::{ObjectStatus, Stat};
use lg_world::json::{self, Json};
use std::path::Path;

pub const RARITY: [&str; 5] = ["Common", "Uncommon", "Rare", "Legendary", "Divine"];

/// Java `abilityName`.
pub fn ability_name(t: i32) -> String {
    match t {
        402 => "Attack Close".into(),
        404 => "Attack Mid".into(),
        405 => "Attack Far".into(),
        406 => "Electric".into(),
        407 => "Heal".into(),
        408 => "Magic Heal".into(),
        409 => "Savage".into(),
        410 => "Decoy".into(),
        411 => "Rising Fury".into(),
        _ => format!("Ability {t}"),
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Ability {
    pub kind: i32,
    /// Its level.
    pub power: i32,
    /// Feed points towards the next level.
    pub points: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Saddlebag {
    /// Item ids, -1 = empty slot.
    pub items: Vec<i32>,
    pub enchant_ids: Vec<Vec<i32>>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Pet {
    pub instance_id: i32,
    pub name: String,
    /// Its kind (object type).
    pub kind: i32,
    pub skin: i32,
    /// 0 common .. 4 divine.
    pub rarity: i32,
    /// The highest ability level its rarity allows.
    pub max_power: i32,
    pub abilities: Vec<Ability>,
    /// Seen on regular / seasonal characters; None = unknown.
    pub saddlebag: Option<Saddlebag>,
    pub seasonal_saddlebag: Option<Saddlebag>,
    pub last_seen: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PetStore {
    /// By instance id, in the order first seen (Java's `LinkedHashMap`).
    pub pets: Vec<(i32, Pet)>,
}

impl PetStore {
    /// What the Java app kept (`pets.json`), read only.
    pub fn import(dir: &Path) -> PetStore {
        let Ok(text) = std::fs::read_to_string(dir.join("pets.json")) else {
            // none kept yet: the character list saved at the last login (before pets were kept)
            let mut p = PetStore::default();
            if let Ok(xml) = std::fs::read_to_string(dir.join("char-list.xml")) {
                let _ = p.import_char_list(&xml, 0);
            }
            return p;
        };
        PetStore { pets: parse_pets(&text).unwrap_or_default() }
    }

    fn pet(&mut self, id: i32) -> &mut Pet {
        let i = match self.pets.iter().position(|p| p.0 == id) {
            Some(i) => i,
            None => {
                self.pets.push((id, Pet::default()));
                self.pets.len() - 1
            }
        };
        &mut self.pets[i].1
    }

    /// Java `importYardPet`: a pet in the Pet Yard: its name, kind, rarity and abilities (no saddlebag there).
    pub fn yard_pet(&mut self, object_type: i32, status: &ObjectStatus, now: i64) {
        let stat = |t: u8| -> Option<&Stat> { status.stats.iter().rev().find(|s| s.stat_type_num == t) };
        let int = |t: u8| stat(t).map_or(0, |s| s.stat_value);
        let id = int(81);
        if id <= 0 {
            return;
        }
        let abilities: Vec<Ability> = [(93u8, 90u8, 87u8), (94, 91, 88), (95, 92, 89)]
            .iter()
            .filter(|(t, _, _)| stat(*t).is_some())
            .map(|&(t, power, points)| Ability { kind: int(t), power: int(power), points: i64::from(int(points)) })
            .collect();
        let p = self.pet(id);
        p.instance_id = id;
        if let Some(n) = stat(82).and_then(|s| s.string_stat_value.as_deref())
            && !n.chars().all(char::is_whitespace)
        {
            p.name = n.to_string();
        }
        p.kind = object_type;
        if int(25) > 0 {
            p.skin = int(25);
        }
        p.rarity = int(84);
        p.max_power = int(85);
        if !abilities.is_empty() {
            p.abilities = abilities;
        }
        p.last_seen = now;
    }

    /// Java `importCharList`: every character's pet from the raw list, its abilities and the saddlebag that character
    /// sees (regular or seasonal).
    pub fn import_char_list(&mut self, xml: &str, now: i64) -> Result<(), crate::java::Thrown> {
        for (_, body) in crate::patterns::chars(xml) {
            let Some((attrs, pet_body)) = first_pet(body) else { continue };
            let seasonal = body.contains("<Seasonal>True</Seasonal>");
            let a = attributes(attrs);
            let get = |k: &str| a.iter().rev().find(|(n, _)| *n == k).map(|(_, v)| *v); // a HashMap: the last wins
            let id = int_of(get("instanceId"));
            if id <= 0 {
                continue;
            }
            let mut abilities = Vec::new();
            for ab in abilities_in(pet_body) {
                let aa = attributes(ab);
                let g = |k: &str| aa.iter().rev().find(|(n, _)| *n == k).map(|(_, v)| *v);
                abilities.push(Ability { kind: int_of(g("type")), power: int_of(g("power")), points: long_of(g("points")) });
            }
            let bag = saddlebag_of(get("inv"), pet_body)?;
            let p = self.pet(id);
            p.instance_id = id;
            if let Some(n) = get("name") {
                p.name = n.to_string();
            }
            p.kind = int_of(get("type"));
            p.skin = int_of(get("skin"));
            p.rarity = int_of(get("rarity"));
            p.max_power = int_of(get("maxAbilityPower"));
            p.last_seen = now;
            if !abilities.is_empty() {
                p.abilities = abilities;
            }
            if let Some(b) = bag {
                if seasonal {
                    p.seasonal_saddlebag = Some(b);
                } else {
                    p.saddlebag = Some(b);
                }
            }
        }
        Ok(())
    }

    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let arr = |a: &[i32]| format!("[{}]", a.iter().map(i32::to_string).collect::<Vec<_>>().join(","));
        let bag = |b: &Option<Saddlebag>| {
            b.as_ref().map_or("null".into(), |b| {
                format!("{}/[{}]", arr(&b.items), b.enchant_ids.iter().map(|e| arr(e)).collect::<Vec<_>>().join(","))
            })
        };
        let mut s = String::new();
        for (k, p) in &self.pets {
            let abilities: Vec<String> = p.abilities.iter().map(|a| format!("{}/{}/{}", a.kind, a.power, a.points)).collect();
            s.push_str(&format!(
                "pet {k}:{} name={} type={} skin={} rarity={} max={} abilities=[{}] bag={} seasonal={} seen={}\n",
                p.instance_id,
                q(&p.name),
                p.kind,
                p.skin,
                p.rarity,
                p.max_power,
                abilities.join(","),
                bag(&p.saddlebag),
                bag(&p.seasonal_saddlebag),
                p.last_seen
            ));
        }
        s
    }
}

/// `<Pet ([^>]*)>(.*?)</Pet>` (dot matching newlines): the first in a character.
fn first_pet(body: &str) -> Option<(&str, &str)> {
    let mut from = 0;
    while let Some(i) = body[from..].find("<Pet ") {
        let a = from + i + "<Pet ".len();
        if let Some(gt) = body[a..].find('>') {
            let inner = a + gt + 1;
            if let Some(end) = body[inner..].find("</Pet>") {
                return Some((&body[a..a + gt], &body[inner..inner + end]));
            }
        }
        from = from + i + 1;
    }
    None
}

/// `(\w+)="([^"]*)"`: every attribute, in order.
fn attributes(s: &str) -> Vec<(&str, &str)> {
    let b = s.as_bytes();
    let word = |c: u8| c.is_ascii_alphanumeric() || c == b'_';
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if !word(b[i]) {
            i += 1;
            continue;
        }
        let start = i;
        while i < b.len() && word(b[i]) {
            i += 1;
        }
        // greedy \w+ from the earliest start; on failure the regex tries the next start
        if b.get(i) == Some(&b'=') && b.get(i + 1) == Some(&b'"') {
            let v = i + 2;
            if let Some(n) = s[v..].find('"') {
                out.push((&s[start..i], &s[v..v + n]));
                i = v + n + 1;
                continue;
            }
        }
        i = start + 1;
    }
    out
}

/// `<Ability ([^/>]*)/?>`: every ability's attributes.
fn abilities_in(s: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = s[from..].find("<Ability ") {
        let a = from + i + "<Ability ".len();
        let n = s[a..].find(['/', '>']).unwrap_or(s.len() - a);
        let rest = &s[a + n..];
        if rest.starts_with('>') || rest.starts_with("/>") {
            out.push(&s[a..a + n]);
            from = a + n + 1;
        } else if rest.starts_with('/') {
            // `/?>` fails at "/x": [^/>]* backs off, which only gives shorter runs that end before '/': no match
            from = from + i + 1;
        } else {
            break;
        }
    }
    out
}

/// Java `intOf` / `longOf`: 0 for none or not a number.
fn int_of(s: Option<&str>) -> i32 {
    s.and_then(|s| crate::java::parse_int(crate::java::trim(s)).ok()).unwrap_or(0)
}

fn long_of(s: Option<&str>) -> i64 {
    s.and_then(|s| crate::java::parse_long(crate::java::trim(s)).ok()).unwrap_or(0)
}

/// Java `saddlebag`: from the pet's inv attribute ("0,6;X;10022#uid,583,-1,..."): item ids, unique ones with "#id"
/// whose enchantments are in the pet's UniqueItemInfo.
fn saddlebag_of(inv: Option<&str>, pet_body: &str) -> Result<Option<Saddlebag>, crate::java::Thrown> {
    let Some(inv) = inv.filter(|i| !crate::java::is_blank(i)) else { return Ok(None) };
    let parts = crate::java::split(inv, ';');
    let list = *parts.last().ok_or(crate::java::Thrown)?; // parts[parts.length - 1]
    let mut codes = std::collections::HashMap::new();
    for (id, code) in item_data_digits_id(pet_body) {
        codes.insert(id, crate::java::trim(code));
    }
    let slots = if crate::java::is_blank(list) { Vec::new() } else { crate::java::split(list, ',') };
    let mut bag = Saddlebag::default();
    for s in slots {
        let item = crate::java::split(crate::java::trim(s), '#');
        let first = item.first().copied().ok_or(crate::java::Thrown)?; // item[0] of "#"
        bag.items.push(if first.is_empty() { -1 } else { int_of(Some(first)) });
        let code = item.get(1).and_then(|k| codes.get(k).copied());
        bag.enchant_ids.push(lg_gamedata::enchants::enchant_ids(code));
    }
    Ok(Some(bag))
}

/// `<ItemData id="(\d+)"[^>]*>([^<]*)</ItemData>`.
fn item_data_digits_id(s: &str) -> Vec<(&str, &str)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = s[from..].find("<ItemData id=\"") {
        let v = from + i + "<ItemData id=\"".len();
        let n = s.as_bytes()[v..].iter().take_while(|b| b.is_ascii_digit()).count();
        let ok = n > 0 && s.as_bytes().get(v + n) == Some(&b'"');
        if ok && let Some(gt) = s[v + n..].find('>') {
            let t = v + n + gt + 1;
            let m = s[t..].find('<').unwrap_or(s.len() - t);
            if s[t + m..].starts_with("</ItemData>") {
                out.push((&s[v..v + n], &s[t..t + m]));
                from = t + m + "</ItemData>".len();
                continue;
            }
        }
        from = from + i + 1;
    }
    out
}

fn ints(v: &Json) -> Option<Vec<i32>> {
    match v {
        Json::Array(a) => a.iter().map(|x| x.as_int().ok()).collect(),
        _ => None,
    }
}

fn long(v: Option<&Json>) -> Option<i64> {
    match v {
        None | Some(Json::Null) => Some(0),
        Some(v) => v.as_string().ok()?.parse().ok(),
    }
}

fn saddlebag(v: Option<&Json>) -> Option<Option<Saddlebag>> {
    match v {
        None | Some(Json::Null) => Some(None),
        Some(b) => {
            let items = b.get("items").map_or(Some(Vec::new()), ints)?;
            let enchant_ids = match b.get("enchantIds") {
                None | Some(Json::Null) => Vec::new(),
                Some(Json::Array(a)) => {
                    a.iter().map(|e| if *e == Json::Null { Some(Vec::new()) } else { ints(e) }).collect::<Option<_>>()?
                }
                _ => return None,
            };
            Some(Some(Saddlebag { items, enchant_ids }))
        }
    }
}

/// Gson reading `LinkedHashMap<Integer, Pet>` (missing fields keep their defaults).
fn parse_pets(text: &str) -> Option<Vec<(i32, Pet)>> {
    let Json::Object(o) = json::parse(text).ok()? else { return None };
    let mut out: Vec<(i32, Pet)> = Vec::new();
    for (key, p) in o {
        let key: i32 = key.parse().ok()?;
        if p == Json::Null {
            continue; // Java keeps the key with no pet; nothing reads it
        }
        let int = |k: &str| p.get(k).map_or(Some(0), |v| v.as_int().ok());
        let abilities = match p.get("abilities") {
            None | Some(Json::Null) => Vec::new(),
            Some(Json::Array(a)) => a
                .iter()
                .map(|x| {
                    Some(Ability {
                        kind: x.get("type").map_or(Some(0), |v| v.as_int().ok())?,
                        power: x.get("power").map_or(Some(0), |v| v.as_int().ok())?,
                        points: long(x.get("points"))?,
                    })
                })
                .collect::<Option<_>>()?,
            _ => return None,
        };
        out.push((
            key,
            Pet {
                instance_id: int("instanceId")?,
                name: match p.get("name") {
                    None | Some(Json::Null) => String::new(),
                    Some(v) => v.as_string().ok()?,
                },
                kind: int("type")?,
                skin: int("skin")?,
                rarity: int("rarity")?,
                max_power: int("maxPower")?,
                abilities,
                saddlebag: saddlebag(p.get("saddlebag"))?,
                seasonal_saddlebag: saddlebag(p.get("seasonalSaddlebag"))?,
                last_seen: long(p.get("lastSeen"))?,
            },
        ));
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_java_pets() {
        let p = parse_pets(
            r#"{"77":{"instanceId":77,"name":"Rex","type":1,"skin":2,"rarity":3,"maxPower":90,"abilities":[{"type":402,"power":50,"points":1234567890123}],"saddlebag":{"items":[1,-1],"enchantIds":[[3],[]]},"lastSeen":5}}"#,
        )
        .unwrap();
        assert_eq!(p.len(), 1);
        let (k, p) = &p[0];
        assert_eq!((*k, p.name.as_str(), p.abilities[0].points, p.seasonal_saddlebag.is_none()), (77, "Rex", 1_234_567_890_123, true));
        assert_eq!(p.saddlebag.as_ref().unwrap().enchant_ids, vec![vec![3], vec![]]);
    }
}
