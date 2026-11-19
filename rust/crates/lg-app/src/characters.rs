//! Your characters (Java: `lootgobbler.CharacterStore`): every character seen, with what it wears and holds, its
//! level and fame over time, whether it is alive. Filled every 5 s while you play, and by the game's death packet.
//!
//! Not yet: the character list from the game's servers (at login), syncing with the Loot Gobbler server, the file.

use crate::profile::Profile;
use lg_world::{Abort, Entity, World, stats};
use std::collections::{BTreeMap, BTreeSet, HashMap};

/// What the UPE rules look at after a live update (Java: `Upe.seen`'s arguments).
#[derive(Debug, Clone)]
pub struct UpeLook {
    pub char_id: i32,
    pub level: i32,
    pub slots: Vec<i32>,
    pub enchants: Option<Vec<Vec<i32>>>,
    pub base: Option<[i32; 8]>,
    pub first: bool,
}

/// A fame reading.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sample {
    pub t: i64,
    pub fame: i64,
}

/// One character (Java: `CharacterStore.Char`).
#[derive(Debug, Clone, PartialEq)]
pub struct Char {
    pub char_id: i32,
    pub class_type: i32,
    pub class_name: String,
    pub skin: i32,
    pub tex1: i32,
    pub tex2: i32,
    pub pet_skin: i32,
    pub pet_name: Option<String>,
    pub level: i32,
    pub max_hp: i32,
    pub fame: i64,
    pub stats_maxed: i32,
    pub seasonal: bool,
    pub crucible: bool,
    pub ritual: bool,
    pub favorite: bool,
    pub crucible_loot: Option<f64>,
    pub ritual_loot: Option<f64>,
    pub loot_drop_until: i64,
    pub loot_tier_until: i64,
    pub favorite_at: i64,
    pub backpack: bool,
    pub extender: bool,
    pub alive: bool,
    /// "game", "list" or "user"; None while alive.
    pub died_by: Option<String>,
    pub account: Option<String>,
    pub alive_at: i64,
    pub last_played: i64,
    pub equipment: Option<Vec<i32>>,
    pub enchants: Option<Vec<i32>>,
    pub enchant_ids: Option<Vec<Vec<i32>>>,
    pub slots: Option<Vec<i32>>,
    pub quickslots: Option<Vec<i32>>,
    pub slot_enchant_ids: Option<Vec<Vec<i32>>>,
    pub dungeons: Option<BTreeMap<String, i32>>,
    pub samples: Vec<Sample>,
}

impl Default for Char {
    fn default() -> Self {
        Char {
            char_id: 0,
            class_type: 0,
            class_name: String::new(),
            skin: 0,
            tex1: 0,
            tex2: 0,
            pet_skin: 0,
            pet_name: None,
            level: 0,
            max_hp: 0,
            fame: 0,
            stats_maxed: 0,
            seasonal: false,
            crucible: false,
            ritual: false,
            favorite: false,
            crucible_loot: None,
            ritual_loot: None,
            loot_drop_until: 0,
            loot_tier_until: 0,
            favorite_at: 0,
            backpack: false,
            extender: false,
            alive: true,
            died_by: None,
            account: None,
            alive_at: 0,
            last_played: 0,
            equipment: None,
            enchants: None,
            enchant_ids: None,
            slots: None,
            quickslots: None,
            slot_enchant_ids: None,
            dungeons: None,
            samples: Vec::new(),
        }
    }
}

const LIVE_INTERVAL_MS: i64 = 5_000;
const HEARTBEAT_MS: i64 = 5 * 60_000;
const MAX_SAMPLES: usize = 5_000;
const QUICKSLOTS: [u8; 3] = [stats::POTION_ONE_TYPE, stats::POTION_TWO_TYPE, stats::POTION_THREE_TYPE];

/// Java `CharacterStore`.
#[derive(Debug, Default)]
pub struct Characters {
    /// In the order first seen (Java's `LinkedHashMap`).
    order: Vec<i32>,
    chars: HashMap<i32, Char>,
    /// Characters first seen in this run of the app.
    pub never_seen: BTreeSet<i32>,
    /// The character you are playing (or last played this run), 0 = none yet.
    pub current_char_id: i32,
    /// Your name, as last seen (kept in the settings).
    pub player_name: Option<String>,
    last_live: i64,
    last_save: i64,
    /// Set when the file should be written (the app writes it; nothing here touches files).
    pub dirty: bool,
}

/// Java `String.isBlank`.
fn blank(s: &str) -> bool {
    s.chars().all(char::is_whitespace)
}

impl Characters {
    pub fn get(&self, id: i32) -> Option<&Char> {
        self.chars.get(&id)
    }

    /// Java `slotEnchants`: the enchantments known for that item in that slot (the saved character list is not read
    /// yet).
    pub fn slot_enchants(&self, char_id: i32, slot: usize, item: i32) -> Option<Vec<i32>> {
        let c = self.chars.get(&char_id)?;
        let (slots, ids) = (c.slots.as_ref()?, c.slot_enchant_ids.as_ref()?);
        if slot >= slots.len() || slots[slot] != item {
            return None;
        }
        ids.get(slot).cloned()
    }

    /// The living characters with their slots, in the order first seen (for the holdings).
    pub fn living(&self) -> Vec<crate::holdings::Living> {
        self.all()
            .filter(|c| c.alive)
            .map(|c| crate::holdings::Living {
                char_id: c.char_id,
                seasonal: c.seasonal,
                slots: c.slots.clone(),
                slot_enchant_ids: c.slot_enchant_ids.clone(),
                quickslots: c.quickslots.clone(),
            })
            .collect()
    }

    /// Every character, in the order first seen.
    pub fn all(&self) -> impl Iterator<Item = &Char> {
        self.order.iter().map(|id| &self.chars[id])
    }

    fn save(&mut self, now: i64) {
        self.last_save = now;
        self.dirty = true;
    }

    /// The character you play now (Java `playing`).
    pub fn playing(&self) -> Option<&Char> {
        self.chars.get(&self.current_char_id)
    }

    /// Java `live`, on every tick: at most every 5 s, what you are playing; what the UPE rules then look at. Java
    /// throws (and nothing after it runs for that tick) when your name stat is only commas.
    pub fn live(&mut self, w: &World, now: i64, profile: &mut Profile) -> Result<Option<UpeLook>, Abort> {
        let Some(p) = w.me() else { return Ok(None) };
        if now - self.last_live < LIVE_INTERVAL_MS {
            return Ok(None);
        }
        let id = p.char_id;
        if id <= 0 {
            return Ok(None);
        }
        self.last_live = now;
        self.current_char_id = id;
        let name = w.display_name(p)?;
        if let Some(n) = &name
            && !blank(n)
            && self.player_name.as_deref() != Some(n)
        {
            self.player_name = Some(n.clone());
        }
        profile.update(name.as_deref(), p.text(stats::GUILD_NAME), p.value(stats::GUILD_RANK), now);
        self.update(w, p, id, name, now);
        let c = &self.chars[&id];
        Ok(c.slots.as_ref().map(|s| UpeLook {
            char_id: id,
            level: c.level,
            slots: s.clone(),
            enchants: c.slot_enchant_ids.clone(),
            base: p.base_stats,
            first: self.never_seen.contains(&id),
        }))
    }

    fn update(&mut self, w: &World, p: &Entity, id: i32, name: Option<String>, now: i64) {
        let data = w.data();
        if !self.chars.contains_key(&id) {
            self.order.push(id);
        }
        let c = self.chars.entry(id).or_default();
        c.char_id = id;
        if let Some(n) = name.filter(|n| !blank(n)) {
            c.account = Some(n);
        }
        c.class_type = p.object_type;
        c.class_name = data.class(p.object_type).map_or("Character".into(), |k| k.name.clone());
        let int = |t: u8| p.value(t).unwrap_or(0);
        let skin = int(stats::SKIN_ID);
        if !data.items.transform_skins.contains(&skin) {
            if skin > 0 {
                c.skin = skin;
            }
            let texture = |v: i32| if v == 0 || (v as u32 >> 24) != 0 { v } else { data.dye_texture(v) };
            if let Some(t) = p.value(stats::TEX1) {
                c.tex1 = texture(t);
            }
            if let Some(t) = p.value(stats::TEX2) {
                c.tex2 = texture(t);
            }
        }
        if int(stats::LEVEL) > 0 {
            c.level = int(stats::LEVEL);
        }
        if int(stats::MAX_HP) > 0 {
            c.max_hp = int(stats::MAX_HP);
        }
        if int(stats::CURR_FAME) > 0 {
            c.fame = i64::from(int(stats::CURR_FAME));
        }
        if p.stat(stats::SEASONAL).is_some() {
            c.seasonal = p.value(stats::SEASONAL) == Some(1);
        }
        c.crucible = p.text(stats::CRUCIBLE).is_some_and(|s| !s.is_empty());
        let ritual = p.text(stats::BLOOD_RITUAL);
        c.ritual = ritual.is_some_and(|r| !blank(r) && r != "0");
        let loot = &w.damage.crucible.loot;
        let crucible_loot = p.text(stats::CRUCIBLE).and_then(|id| loot.get(id)).copied();
        if !c.crucible {
            c.crucible_loot = None;
        } else if crucible_loot.is_some() {
            c.crucible_loot = crucible_loot;
        }
        let ritual_loot = if c.ritual { ritual.and_then(|id| loot.get(id)).copied() } else { None };
        if !c.ritual {
            c.ritual_loot = None;
        } else if ritual_loot.is_some() {
            c.ritual_loot = ritual_loot;
        }
        let until = |t: u8, before: i64| match p.value(t) {
            None => before,
            Some(v) if v > 0 => now + i64::from(v) * 1000,
            Some(_) => 0,
        };
        c.loot_drop_until = until(stats::LD_TIMER, c.loot_drop_until);
        c.loot_tier_until = until(stats::LT_TIMER, c.loot_tier_until);
        if let Some(base) = p.base_stats {
            c.stats_maxed = data.class(p.object_type).map_or(0, |k| (0..8).filter(|&i| base[i] >= k.max_stats[i]).count() as i32);
        }
        let pack = p.value(stats::BACKPACK_SLOTS);
        if let Some(v) = pack {
            c.backpack = v > 0;
            c.extender = v > 8;
        }
        read_equipment(p, c);
        let mut quick = [-1; 3];
        let mut seen = false;
        for (k, t) in QUICKSLOTS.iter().enumerate() {
            let q = p.value(*t);
            quick[k] = match q {
                Some(v) if v >= 1 => v,
                _ => -1,
            };
            seen |= q.is_some();
        }
        if seen {
            c.quickslots = Some(quick.to_vec());
        }
        let slots_changed = read_slots(p, c, pack.unwrap_or(-1));
        if c.last_played == 0 {
            self.never_seen.insert(id);
        }
        c.alive = true;
        c.died_by = None;
        c.last_played = now;
        let fame = c.fame;
        let sampled = match c.samples.last() {
            None => true,
            Some(last) => last.fame != fame || now - last.t >= HEARTBEAT_MS,
        };
        if sampled {
            c.samples.push(Sample { t: now, fame });
            if c.samples.len() > MAX_SAMPLES {
                c.samples.remove(0);
            }
        }
        if slots_changed {
            self.last_save = 0;
        }
        if sampled || now - self.last_save > 60_000 {
            self.save(now);
        }
    }

    /// Java `died`: the game says your character died.
    pub fn died(&mut self, char_id: i32, now: i64) {
        if let Some(c) = self.chars.get_mut(&char_id)
            && c.alive
        {
            c.alive = false;
            c.died_by = Some("game".into());
            c.alive_at = now;
            self.save(now);
        }
    }

    /// For the comparison with Java: everything, one line per character.
    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let oq = |s: &Option<String>| s.as_deref().map_or("null".into(), q);
        let arr = |v: &Option<Vec<i32>>| {
            v.as_ref().map_or("null".into(), |v| format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(",")))
        };
        let arr2 = |v: &Option<Vec<Vec<i32>>>| {
            v.as_ref().map_or("null".into(), |v| {
                format!(
                    "[{}]",
                    v.iter()
                        .map(|x| format!("[{}]", x.iter().map(i32::to_string).collect::<Vec<_>>().join(",")))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            })
        };
        let dbl = |d: Option<f64>| d.map_or("null".into(), |d| format!("{:016x}", d.to_bits()));
        let mut out = format!(
            "characters current={} player={} never=[{}]\n",
            self.current_char_id,
            oq(&self.player_name),
            self.never_seen.iter().map(i32::to_string).collect::<Vec<_>>().join(",")
        );
        let mut ids: Vec<&i32> = self.chars.keys().collect();
        ids.sort();
        for id in ids {
            let c = &self.chars[id];
            let b = |v: bool| u8::from(v).to_string();
            let samples: Vec<String> = c.samples.iter().map(|s| format!("{}:{}", s.t, s.fame)).collect();
            let dungeons = c
                .dungeons
                .as_ref()
                .map_or("null".into(), |d| format!("{{{}}}", d.iter().map(|(k, v)| format!("{}:{v}", q(k))).collect::<Vec<_>>().join(",")));
            out.push_str(&format!(
                "char {} cls={} name={} skin={} tex={}/{} pet={}/{} lvl={} hp={} fame={} maxed={} flags={}{}{}{} cl={} rl={} ld={} lt={} fav={} pack={}{} alive={} died={} acct={} aliveAt={} played={} eq={} ench={} eids={} slots={} quick={} seids={} dungeons={} samples=[{}]\n",
                c.char_id,
                c.class_type,
                q(&c.class_name),
                c.skin,
                c.tex1,
                c.tex2,
                c.pet_skin,
                oq(&c.pet_name),
                c.level,
                c.max_hp,
                c.fame,
                c.stats_maxed,
                b(c.seasonal),
                b(c.crucible),
                b(c.ritual),
                b(c.favorite),
                dbl(c.crucible_loot),
                dbl(c.ritual_loot),
                c.loot_drop_until,
                c.loot_tier_until,
                c.favorite_at,
                b(c.backpack),
                b(c.extender),
                c.alive,
                oq(&c.died_by),
                oq(&c.account),
                c.alive_at,
                c.last_played,
                arr(&c.equipment),
                arr(&c.enchants),
                arr2(&c.enchant_ids),
                arr(&c.slots),
                arr(&c.quickslots),
                arr2(&c.slot_enchant_ids),
                dungeons,
                samples.join(",")
            ));
        }
        out
    }
}

/// Java's `split(",", -1)`: every part, empty ones at the end too.
fn codes(p: &Entity) -> Option<Vec<&str>> {
    p.text(stats::UNIQUE_DATA).map(|s| s.split(',').collect())
}

/// Java `readEquipment`: the four equipped items and their enchantments.
fn read_equipment(p: &Entity, c: &mut Char) {
    let mut equip = vec![-1; 4];
    let mut any = false;
    for (i, e) in equip.iter_mut().enumerate() {
        if let Some(v) = p.value(stats::INVENTORY_0 + i as u8) {
            *e = v;
            any = true;
        }
    }
    if !any {
        return;
    }
    if let Some(codes) = codes(p) {
        let ids: Vec<Vec<i32>> = (0..4).map(|i| lg_gamedata::enchants::enchant_ids(codes.get(i).copied())).collect();
        c.enchants = Some(ids.iter().map(|x| x.len() as i32).collect());
        c.enchant_ids = Some(ids);
    } else if c.equipment.as_ref() != Some(&equip) {
        c.enchants = None;
        c.enchant_ids = None;
    }
    c.equipment = Some(equip);
}

/// Java `readSlots`: every slot (equipment, inventory, backpack, extender) and its enchantments; true if changed.
fn read_slots(p: &Entity, c: &mut Char, pack: i32) -> bool {
    let pack = if pack < 0 { c.slots.as_ref().map_or(0, |s| (s.len() as i32 - 12).max(0)) } else { pack };
    let pack = pack.clamp(0, 16) as usize;
    let mut slots = Vec::with_capacity(12 + pack);
    for i in 0..12 + pack {
        let t = if i < 12 { stats::INVENTORY_0 + i as u8 } else { stats::BACKPACK_0 + (i - 12) as u8 };
        let Some(v) = p.value(t) else { return false };
        slots.push(v);
    }
    let codes = codes(p).unwrap_or_default();
    let ids: Vec<Vec<i32>> = (0..slots.len())
        .map(|i| {
            if i < codes.len() {
                lg_gamedata::enchants::enchant_ids(Some(codes[i]))
            } else if let (Some(old), Some(old_ids)) = (&c.slots, &c.slot_enchant_ids)
                && old.get(i) == Some(&slots[i])
                && let Some(x) = old_ids.get(i)
            {
                x.clone()
            } else {
                Vec::new()
            }
        })
        .collect();
    let changed = c.slots.as_ref() != Some(&slots) || c.slot_enchant_ids.as_ref() != Some(&ids);
    c.slots = Some(slots);
    c.slot_enchant_ids = Some(ids);
    changed
}
