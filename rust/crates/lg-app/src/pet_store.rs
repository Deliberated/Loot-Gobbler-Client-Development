//! Your pets (Java: `PetStore`): the character list names the pet each character has, with its abilities and
//! saddlebag (regular and seasonal characters each see their own); pets seen in the Pet Yard add their stats.
//!
//! Not yet: the character list's part (it comes with the character list), and reading Java's last saved character list
//! (`char-list.xml`) when it kept no pets yet.

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
        let pets = std::fs::read_to_string(dir.join("pets.json")).ok().and_then(|t| parse_pets(&t)).unwrap_or_default();
        PetStore { pets }
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
