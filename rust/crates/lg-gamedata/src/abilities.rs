//! Abilities whose damage grows with a stat, from `xml/equip.xml` (Java: `AbilityScalingManager`), read as Java reads
//! it with DOM: `<Activate>` (or `<OnConditionEndActivate>`) with `scalingStat`, and projectiles (`...Proj`, `...Missile`,
//! `...Proc`) mapped to the item that shoots them.

use crate::xml::{self, Element};
use std::collections::HashMap;

/// A stat an ability scales with, by its stat type number (Java: `StatType`).
pub fn scaling_stat(name: &str) -> Option<u8> {
    Some(match name.to_uppercase().as_str() {
        "WIS" | "WISDOM" => 27,
        "DEX" | "DEXTERITY" => 28,
        "ATT" | "ATTACK" => 20,
        "DEF" | "DEFENSE" => 21,
        "SPD" | "SPEED" => 22,
        "VIT" | "VITALITY" => 26,
        "HP" | "HEALTH" | "LIFE" => 0,
        "MP" | "MANA" => 3,
        _ => return None,
    })
}

/// How an ability's damage grows (Java: `AbilityScalingData`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Scaling {
    pub weapon_id: i32,
    /// Stat type number.
    pub stat: u8,
    /// Only the stat above this counts.
    pub min: i32,
    pub damage_per_stat: f32,
    pub num_shots: i32,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Abilities {
    pub scaling: HashMap<i32, Scaling>,
    /// Projectile object type -> the item shooting it.
    pub projectile_to_weapon: HashMap<i32, i32>,
}

impl Abilities {
    /// Java `getScalingData`: the item's own, or that of the item shooting this projectile.
    pub fn scaling(&self, id: i32) -> Option<&Scaling> {
        self.scaling.get(&id).or_else(|| self.projectile_to_weapon.get(&id).and_then(|p| self.scaling.get(p)))
    }

    /// Java `hasScaling`.
    pub fn has_scaling(&self, id: i32) -> bool {
        self.scaling(id).is_some()
    }
}

/// Java `Integer.parseInt(type.replace("0x", ""), 16)`, -1 when it fails or is missing.
fn weapon_id(e: &Element) -> i32 {
    let t = e.attr_or_empty("type");
    if t.is_empty() {
        return -1;
    }
    i32::from_str_radix(&t.replace("0x", ""), 16).unwrap_or(-1)
}

/// Java `Float.parseFloat`: surrounding whitespace allowed, a trailing f/F/d/D too.
pub fn java_float(s: &str) -> Option<f32> {
    let t = s.trim_matches(|c: char| c <= ' ');
    let t = t.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(t);
    t.parse().ok()
}

/// Java `Integer.parseInt`: no whitespace allowed.
fn java_int(s: &str) -> Option<i32> {
    if s.starts_with('+') && s.len() == 1 { None } else { s.parse().ok() }
}

/// What Java's `extractHex` finds: `0x` and the hex digits after it.
fn extract_hex(s: &str) -> Option<&str> {
    let i = s.find("0x")?;
    let digits = s[i + 2..].chars().take_while(char::is_ascii_hexdigit).count();
    (digits > 0).then(|| &s[i..i + 2 + digits])
}

/// Which item shoots what: every `<OnPlayerShootActivate>` / `<Activate>`'s type or text, upper case (Java:
/// `shooterIndex`).
fn shooter_index(objects: &[&Element]) -> HashMap<String, i32> {
    let mut index = HashMap::new();
    for o in objects {
        let id = weapon_id(o);
        if id == -1 {
            continue;
        }
        for tag in ["OnPlayerShootActivate", "Activate"] {
            for act in o.descendants(tag) {
                let t = act.attr_or_empty("type");
                let candidate = if t.is_empty() { act.text_content().trim().to_string() } else { t.trim().to_string() };
                if candidate.is_empty() {
                    continue;
                }
                index.entry(candidate.to_uppercase()).or_insert(id);
                if let Some(hex) = extract_hex(&candidate) {
                    index.entry(hex.to_uppercase()).or_insert(id);
                }
            }
        }
    }
    index
}

/// Java `parseScalingData`; an unreadable `statModDamage` / `statModFlat` / `statModPerc` throws in Java, which stops
/// reading the file (what was read so far is kept): that is the error here.
fn scaling_of(act: &Element, weapon_id: i32) -> Result<Option<Scaling>, String> {
    let stat = act.attr_or_empty("scalingStat");
    if stat.is_empty() {
        return Ok(None);
    }
    let Some(stat) = scaling_stat(stat) else { return Ok(None) };
    let float = |name: &str| java_float(act.attr_or_empty(name)).ok_or_else(|| format!("{name} not a number"));
    let (damage, flat, perc) = (act.attr_or_empty("statModDamage"), act.attr_or_empty("statModFlat"), act.attr_or_empty("statModPerc"));
    let per_stat = if !damage.is_empty() {
        float("statModDamage")?
    } else if !flat.is_empty() && !perc.is_empty() {
        float("statModFlat")? + float("statModPerc")? * 100.0
    } else {
        0.0
    };
    if per_stat <= 0.0 {
        // (NaN passes, as in Java)
        return Ok(None);
    }
    let min = java_int(act.attr_or_empty("statModScalingMin")).unwrap_or(50);
    let num_shots = java_int(act.attr_or_empty("numShots")).unwrap_or(1);
    Ok(Some(Scaling { weapon_id, stat, min, damage_per_stat: per_stat, num_shots }))
}

/// Reads `equip.xml` (Java: `AbilityScalingManager.initialize`). The second part tells why reading stopped early.
pub fn parse(doc: &str) -> (Abilities, Option<String>) {
    let mut a = Abilities::default();
    let root = match xml::parse(doc) {
        Ok(r) => r,
        Err(e) => return (a, Some(e)),
    };
    // getElementsByTagName on the document: the root itself counts too
    let mut objects = Vec::new();
    if root.name == "Object" {
        objects.push(&root);
    }
    objects.extend(root.descendants("Object"));
    let mut index: Option<HashMap<String, i32>> = None;
    for o in &objects {
        let id = weapon_id(o);
        if id == -1 {
            continue;
        }
        let pid = o.attr_or_empty("id");
        if pid.contains("Proj") || pid.contains("Missile") || pid.contains("Proc") {
            let index = index.get_or_insert_with(|| shooter_index(&objects));
            let key = format!("0x{:x}", id).to_uppercase();
            if let Some(&parent) = index.get(&key) {
                a.projectile_to_weapon.insert(id, parent);
            }
        }
        for tag in ["Activate", "OnConditionEndActivate"] {
            if tag == "OnConditionEndActivate" && a.scaling.contains_key(&id) {
                break;
            }
            for act in o.descendants(tag) {
                match scaling_of(act, id) {
                    Ok(Some(s)) => {
                        a.scaling.insert(id, s);
                        break;
                    }
                    Ok(None) => {}
                    Err(e) => return (a, Some(format!("equip.xml, item {id:#x}: {e}; the rest is not read"))),
                }
            }
        }
    }
    (a, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scaling_and_projectiles() {
        let doc = r#"<Objects>
  <Object type="0x100" id="Staff of Things">
    <Activate scalingStat="WIS" statModDamage="2.5" statModScalingMin="40">Shoot</Activate>
    <OnPlayerShootActivate type="0x200">Proc</OnPlayerShootActivate>
  </Object>
  <Object type="0x200" id="Things Proc"/>
  <Object type="0x300" id="Flat Tome"><Activate scalingStat="vit" statModFlat="1" statModPerc="0.02"/></Object>
  <Object type="0x400" id="Late"><OnConditionEndActivate scalingStat="DEX" statModDamage="1"/></Object>
  <Object type="0x500" id="NoStat"><Activate scalingStat="LUCK" statModDamage="1"/></Object>
</Objects>"#;
        let (a, problem) = parse(doc);
        assert_eq!(problem, None);
        let s = a.scaling(0x100).unwrap();
        assert_eq!((s.stat, s.min, s.damage_per_stat, s.num_shots), (27, 40, 2.5, 1));
        assert_eq!(a.projectile_to_weapon.get(&0x200), Some(&0x100));
        assert!(a.has_scaling(0x200), "a projectile scales as its item");
        assert_eq!(a.scaling(0x300).unwrap().damage_per_stat, 1.0 + 0.02 * 100.0);
        assert_eq!(a.scaling(0x400).unwrap().stat, 28);
        assert!(!a.has_scaling(0x500));
        // an unreadable number stops reading, keeping what came before
        let (a, problem) = parse(&doc.replace("statModDamage=\"1\"", "statModDamage=\"x\""));
        assert!(problem.is_some() && a.has_scaling(0x300) && !a.has_scaling(0x400));
    }
}
