//! Enchantments, from `xml/enchantments.xml` (Java: `ParseEnchants`), read as Java reads it through `StringXML`.
//! And reading an item's enchant code (the `UNIQUE_DATA_STRING` stat), as Java does.

use crate::xml::{self, JavaNode};
use std::collections::HashMap;

/// What one enchantment does (Java: `EnchantEffect`, `RegenEffect`, the loot bonus).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Effect {
    pub min_damage: f32,
    pub max_damage: f32,
    pub rate_of_fire: f32,
    pub stat_damage: f32,
    pub mana_flat_per_sec: f32,
    pub mana_percent_per_sec: f32,
    pub life_flat_per_sec: f32,
    pub life_percent_per_sec: f32,
    pub loot_bonus: f32,
}

impl Default for Effect {
    fn default() -> Self {
        Effect {
            min_damage: 1.0,
            max_damage: 1.0,
            rate_of_fire: 1.0,
            stat_damage: 1.0,
            mana_flat_per_sec: 0.0,
            mana_percent_per_sec: 0.0,
            life_flat_per_sec: 0.0,
            life_percent_per_sec: 0.0,
            loot_bonus: 0.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Enchant {
    pub id: i16,
    /// What the game shows (`DisplayId`), or the internal id.
    pub name: Option<String>,
    /// The internal id (`Attack_Bonus_1`; all upper case for the unique ones).
    pub internal_id: Option<String>,
    pub effect: Effect,
}

/// Java `Short.decode`.
fn decode_short(s: &str) -> Option<i16> {
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
    i16::try_from(if neg { -v } else { v }).ok()
}

/// Java `readNumericChild`: the first child value that reads as a number (projectileId / subAttackIndex left out),
/// else 1.
fn numeric_child(children: &[JavaNode]) -> f32 {
    for c in children {
        if c.name == "projectileId" || c.name == "subAttackIndex" {
            continue;
        }
        if let Some(v) = c.value
            && let Some(f) = crate::abilities::java_float(&v.replace('\t', ""))
        {
            return f;
        }
    }
    1.0
}

/// Reads `enchantments.xml` (Java: `ParseEnchants.loadEnchants`). Where Java throws (a `type` element instead of an
/// attribute) it stops, keeping what came before: the second part tells.
pub fn parse(doc: &str) -> (HashMap<i16, Enchant>, Option<String>) {
    let mut out = HashMap::new();
    let root = match xml::parse(doc) {
        Ok(r) => r,
        Err(e) => return (out, Some(e)),
    };
    for node in root.java_children() {
        if node.name != "Enchantment" {
            continue;
        }
        let Some(e) = node.element else { continue };
        let (mut kind, mut internal, mut display) = (None, None, None);
        let mut effect = Effect::default();
        for n in e.java_children() {
            match n.name {
                "id" => internal = n.value.map(str::to_string),
                "DisplayId" => {
                    display = match n.element {
                        Some(d) => d.java_children().first().and_then(|c| c.value).map(str::to_string),
                        None => n.value.map(str::to_string),
                    }
                }
                "type" => match n.value {
                    Some(v) => kind = decode_short(v).or(kind),
                    None => return (out, Some("enchantments.xml: a type element; the rest is not read".into())),
                },
                "Mutators" => {
                    let Some(m) = n.element else { continue };
                    for mutator in m.java_children() {
                        let Some(me) = mutator.element else { continue };
                        let children = me.java_children();
                        match mutator.name {
                            "MultiplyMinDamage" => effect.min_damage *= numeric_child(&children),
                            "MultiplyMaxDamage" => effect.max_damage *= numeric_child(&children),
                            "MultiplyRateOfFire" => effect.rate_of_fire *= numeric_child(&children),
                            "ActivateOnEquip" => {
                                let (mut stat, mut amount, mut kind_of) = (None, None, None);
                                let (mut loot, mut stat_mod) = (false, false);
                                for c in &children {
                                    match (c.name, c.value) {
                                        ("stat", v) => stat = v,
                                        ("amount", v) => {
                                            if let Some(f) = v.and_then(crate::abilities::java_float) {
                                                amount = Some(f);
                                            }
                                        }
                                        (_, Some(v)) => match v.trim() {
                                            "FlatRegen" => kind_of = Some("Flat"),
                                            "PercentageRegen" => kind_of = Some("Percent"),
                                            "LootBonus" => loot = true,
                                            "StatModMult" => stat_mod = true,
                                            _ => {}
                                        },
                                        _ => {}
                                    }
                                }
                                if let (Some(s), Some(a), Some(k)) = (stat, amount, kind_of) {
                                    match (s, k) {
                                        ("MP", "Flat") => effect.mana_flat_per_sec += a,
                                        ("MP", _) => effect.mana_percent_per_sec += a,
                                        ("HP", "Flat") => effect.life_flat_per_sec += a,
                                        ("HP", _) => effect.life_percent_per_sec += a,
                                        _ => {}
                                    }
                                }
                                if let (true, Some(a)) = (loot, amount) {
                                    effect.loot_bonus += a;
                                }
                                if let (true, Some(a)) = (stat_mod, amount) {
                                    effect.stat_damage *= a;
                                }
                            }
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        if let Some(id) = kind {
            let shown = display.as_deref().map(str::trim).filter(|d| !d.is_empty()).map(str::to_string).or(internal.clone());
            let old_name = out.get(&id).and_then(|o: &Enchant| o.name.clone());
            let old_internal = out.get(&id).and_then(|o: &Enchant| o.internal_id.clone());
            out.insert(id, Enchant { id, name: shown.or(old_name), internal_id: internal.or(old_internal), effect });
        }
    }
    (out, None)
}

/// Totals of the enchants on one item (Java: `ParseEnchants.Totals`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Totals {
    pub min_damage: f32,
    pub max_damage: f32,
    pub rate_of_fire: f32,
    pub stat_damage: f32,
}

/// Why Java would throw reading an enchant code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Malformed;

/// Java `PcStatsDecoder.sixBitStringToBytes`, with its errors: the game's base64 variant (`-` and `_`, `=` padding),
/// counted in UTF-16 units as Java's `charAt` does.
pub fn six_bit_bytes(s: &str) -> Result<Vec<u8>, Malformed> {
    let c: Vec<u16> = s.encode_utf16().collect();
    let len = c.len();
    let padding = c.iter().position(|&x| x == u16::from(b'=')).map_or(0, |i| len - i);
    let size = (len / 4 * 3).checked_sub(padding).ok_or(Malformed)?;
    let mut out = vec![0u8; size];
    let value = |x: u16| -> i32 {
        let x = i32::from(x);
        match x {
            48..=57 => x + 4,
            65..=90 => x - 65,
            97..=122 => x - 71,
            45 => 62,
            95 => 63,
            61 => 0,
            _ => x,
        }
    };
    let mut o = 0;
    let mut i = 0;
    while i < len {
        let at = |k: usize| c.get(i + k).copied().ok_or(Malformed);
        let (v1, v2, c3, c4) = (value(at(0)?), value(at(1)?), at(2)?, at(3)?);
        let (v3, v4) = (value(c3), value(c4));
        let mut put = |k: usize, b: i32| -> Result<(), Malformed> {
            *out.get_mut(o + k).ok_or(Malformed)? = b as u8;
            Ok(())
        };
        put(0, v1 << 2 | v2 >> 4)?;
        if c3 != u16::from(b'=') {
            put(1, (v2 & 0b1111) << 4 | v3 >> 2)?;
            if c4 != u16::from(b'=') {
                put(2, (v3 & 0b11) << 6 | v4)?;
            }
        }
        o += 3;
        i += 4;
    }
    Ok(out)
}

/// Java `computeWeaponMultipliers`: the enchants in an item's code, multiplied. Throws (here: an error) on a malformed
/// code or a dangling byte, as Java does.
pub fn totals(enchants: &HashMap<i16, Enchant>, code: &str) -> Result<Totals, Malformed> {
    let mut t = Totals { min_damage: 1.0, max_damage: 1.0, rate_of_fire: 1.0, stat_damage: 1.0 };
    if code.is_empty() {
        return Ok(t);
    }
    let mut b = six_bit_bytes(code)?;
    b.truncate(11);
    let mut at = 0;
    let short = |at: &mut usize| -> Result<i16, Malformed> {
        let v = b.get(*at..*at + 2).ok_or(Malformed)?;
        *at += 2;
        Ok(i16::from_le_bytes([v[0], v[1]]))
    };
    if at == b.len() {
        return Ok(t);
    }
    at += 1; // header
    if at == b.len() {
        return Ok(t);
    }
    if short(&mut at)? != 1026 {
        return Ok(t);
    }
    while at < b.len() {
        let id = short(&mut at)?;
        if id == -3 {
            break;
        }
        if id <= 0 {
            continue;
        }
        if let Some(e) = enchants.get(&id) {
            t.min_damage *= e.effect.min_damage;
            t.max_damage *= e.effect.max_damage;
            t.rate_of_fire *= e.effect.rate_of_fire;
            t.stat_damage *= e.effect.stat_damage;
        }
    }
    Ok(t)
}

/// Whether Java's `ParseEnchants.parse` reads this code without throwing: it stops at the end marker, a locked or
/// empty slot, but throws on a malformed code or a dangling byte.
pub fn parse_ok(code: &str) -> bool {
    if code.is_empty() {
        return true;
    }
    let Ok(mut b) = six_bit_bytes(code) else { return false };
    b.truncate(11);
    if b.is_empty() {
        return true;
    }
    let mut at = 1;
    let short = |at: &mut usize| -> Option<i16> {
        let v = b.get(*at..*at + 2)?;
        *at += 2;
        Some(i16::from_le_bytes([v[0], v[1]]))
    };
    if at == b.len() {
        return true;
    }
    match short(&mut at) {
        None => return false,
        Some(1026) => {}
        Some(_) => return true,
    }
    while at < b.len() {
        match short(&mut at) {
            None => return false,
            Some(-3..=-1) => return true,
            Some(_) => {}
        }
    }
    true
}

/// Java `getEnchantStrings`: the enchant codes of the four equipped items, from the `UNIQUE_DATA_STRING` stat.
pub fn slot_codes(unique_data: Option<&str>) -> [String; 4] {
    let mut out: [String; 4] = Default::default();
    if let Some(s) = unique_data {
        let mut parts: Vec<&str> = s.split(',').collect();
        while parts.len() > 1 && parts.last() == Some(&"") {
            parts.pop();
        }
        if parts == [""] && !s.is_empty() {
            parts.clear();
        }
        for (o, p) in out.iter_mut().zip(parts) {
            *o = p.to_string();
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const DOC: &str = r#"<Enchantments>
  <Enchantment id="Damage_Bonus_1" type="0x14f">
    <DisplayId>Damage Bonus I</DisplayId>
    <Mutators>
      <MultiplyMinDamage projectileId="-1">1.02</MultiplyMinDamage>
      <MultiplyMaxDamage projectileId="-1">1.02</MultiplyMaxDamage>
    </Mutators>
  </Enchantment>
  <Enchantment id="STAT_MOD" type="0x200">
    <Mutators><ActivateOnEquip amount="1.1">StatModMult</ActivateOnEquip>
    <ActivateOnEquip stat="MP" amount="2">FlatRegen</ActivateOnEquip></Mutators>
  </Enchantment>
</Enchantments>"#;

    #[test]
    fn effects_as_java_reads_them() {
        let (e, problem) = parse(DOC);
        assert_eq!(problem, None);
        let d = &e[&0x14f];
        assert_eq!((d.name.as_deref(), d.internal_id.as_deref()), (Some("Damage Bonus I"), Some("Damage_Bonus_1")));
        assert_eq!((d.effect.min_damage, d.effect.max_damage, d.effect.stat_damage), (1.02, 1.02, 1.0));
        let s = &e[&0x200];
        assert_eq!((s.name.as_deref(), s.effect.stat_damage, s.effect.mana_flat_per_sec), (Some("STAT_MOD"), 1.1, 2.0));
    }

    #[test]
    fn item_codes() {
        let (e, _) = parse(DOC);
        // header, 1026, enchant 0x200, terminator
        let bytes = [0u8, 0x02, 0x04, 0x00, 0x02, 0xfd, 0xff];
        let code = encode(&bytes);
        assert_eq!(totals(&e, &code).unwrap().stat_damage, 1.1);
        assert_eq!(totals(&e, "").unwrap().stat_damage, 1.0);
        assert!(totals(&e, &encode(&[0, 0x02, 0x04, 0x00])).is_err(), "a dangling byte throws in Java");
        assert_eq!(slot_codes(Some("a,,c,")), ["a", "", "c", ""]);
        assert_eq!(slot_codes(None), ["", "", "", ""]);
    }

    /// The game's base64 variant, for tests.
    fn encode(b: &[u8]) -> String {
        const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut s = String::new();
        for chunk in b.chunks(3) {
            let n = (u32::from(chunk[0]) << 16) | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8) | u32::from(*chunk.get(2).unwrap_or(&0));
            for k in 0..4 {
                if k <= chunk.len() {
                    s.push(A[(n >> (18 - 6 * k) & 63) as usize] as char);
                } else {
                    s.push('=');
                }
            }
        }
        s
    }
}
