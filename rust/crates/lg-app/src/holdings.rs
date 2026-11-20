//! What you hold, slot by slot (Java: `lootgobbler.Holdings`): each character's equipment, inventory, backpack and
//! quick slots, and both vaults' chests, from what the game shows live, vault visits and moves in the vault; with a
//! journal of every change of collectible items.
//!
//! Not yet: syncing, the file (and its journal file).

use lg_gamedata::GameData;
use std::collections::{HashMap, HashSet};

pub const VAULT: &str = "vault";
pub const SEASONAL_VAULT: &str = "vault:seasonal";
pub const SLOTS: &str = "slots";
pub const QUICK: &str = "quick";
pub const VAULT_CHEST: &str = "Vault chest";
pub const GIFT_CHEST: &str = "Gift chest";
pub const SPOILS: &str = "Seasonal spoils";
pub const POTIONS: &str = "Potion storage";
pub const MATERIALS: &str = "Material chest";
const PAGE: i32 = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Slot {
    /// -1 = empty.
    pub item: i32,
    pub enchant_ids: Option<Vec<i32>>,
}

impl Default for Slot {
    fn default() -> Self {
        Slot { item: -1, enchant_ids: None }
    }
}

impl Slot {
    pub fn new(item: i32, enchant_ids: Option<Vec<i32>>) -> Slot {
        let item = if item < 1 { -1 } else { item };
        Slot { item, enchant_ids: if item < 0 { None } else { enchant_ids } }
    }

    pub fn empty(&self) -> bool {
        self.item < 1
    }
}

/// A character or a vault (Java: `Place`): its sections in order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Place {
    pub sections: Vec<(String, Vec<Slot>)>,
    pub seen_at: i64,
    pub source: Option<String>,
}

impl Place {
    pub fn section(&self, name: &str) -> Option<&Vec<Slot>> {
        self.sections.iter().find(|(n, _)| n == name).map(|(_, s)| s)
    }

    fn section_mut(&mut self, name: &str) -> &mut Vec<Slot> {
        if let Some(i) = self.sections.iter().position(|(n, _)| n == name) {
            &mut self.sections[i].1
        } else {
            self.sections.push((name.to_string(), Vec::new()));
            &mut self.sections.last_mut().expect("just added").1
        }
    }

    fn put(&mut self, name: &str, slots: Vec<Slot>) {
        *self.section_mut(name) = slots;
    }
}

/// What the game shows of your slots (Java: `Holdings.Live`); `i32::MIN`: not sent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Live {
    pub items: Vec<i32>,
    pub enchant_ids: Vec<Option<Vec<i32>>>,
    pub quick: Option<Vec<i32>>,
}

pub const UNKNOWN: i32 = i32::MIN;

#[derive(Debug, Clone, Default)]
struct Visit {
    sections: Vec<(String, Vec<Slot>)>,
    complete: bool,
    broken: bool,
    why: Option<String>,
}

#[derive(Debug, Clone)]
struct Move {
    from_obj: i32,
    from_slot: i32,
    from_item: i32,
    from_ench: Option<Vec<i32>>,
    to_obj: i32,
    to_slot: i32,
    to_item: i32,
    to_ench: Option<Vec<i32>>,
    at: i64,
}

/// A vault seen anew, for the UPE rules: what it held at the last look and now.
#[derive(Debug, Clone)]
pub struct VaultChange {
    pub key: String,
    pub before_at: i64,
    pub before: Vec<Slot>,
    pub now_at: i64,
    pub now: Vec<Slot>,
}

/// A character as Holdings needs it: alive, seasonal, its slots.
pub struct Living {
    pub char_id: i32,
    pub seasonal: bool,
    pub slots: Option<Vec<i32>>,
    pub slot_enchant_ids: Option<Vec<Vec<i32>>>,
    pub quickslots: Option<Vec<i32>>,
}

#[derive(Debug, Default)]
pub struct Holdings {
    pub places: HashMap<String, Place>,
    /// Places removed (character died or deleted) -> when.
    pub gone: HashMap<String, i64>,
    map_is_vault: bool,
    told_not_vault: bool,
    visit: Option<Visit>,
    in_vault: Option<String>,
    chest_sections: HashMap<i32, String>,
    pages: HashMap<i32, i32>,
    asked: Vec<Move>,
    /// Every change, as Java's `holdings-journal.txt` has it.
    pub journal: Vec<String>,
    seeded: bool,
    pub dirty: bool,
    /// A vault visit replaced a vault, for the UPE rules: (key, seen before, slots before, seen now, slots now).
    pub vault_changes: Vec<VaultChange>,
    /// The places' keys in the order put (for Java's `HashMap` order), and the most there ever were.
    place_keys: Vec<String>,
    place_ops: Vec<lg_world::fight::MapOp<String>>,
    /// Loaded from a file: Gson's maps keep the file's order (then new keys at the end), not a `HashMap`'s.
    places_linked: bool,
    pub(crate) gone_linked: bool,
    pub(crate) gone_ops: Vec<lg_world::fight::MapOp<String>>,
    /// An earlier version's record of the vaults (Java's `whereabouts.json`), for the first start; Java deletes it
    /// once taken over (here only forgotten: the Java folder is never changed).
    whereabouts: Option<String>,
    /// The places gone in the order put (Java's `HashMap` order), and the journal lines already in the file.
    pub(crate) gone_keys: Vec<String>,
    pub(crate) journal_written: usize,
}

/// What the character list says a character holds (Java: `Holdings.Listed`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Listed {
    pub char_id: i32,
    pub slots: Option<Vec<i32>>,
    pub enchant_ids: Option<Vec<Vec<i32>>>,
    pub quick: Option<Vec<i32>>,
}

/// A character seen live this recently wins over the character list.
const LIVE_WINS_MS: i64 = 60_000;

/// What the holdings need to know of the world around them.
pub struct Ctx<'a> {
    pub data: &'a GameData,
    pub now: i64,
    /// Local time as Java writes it.
    pub time: &'a str,
}

impl Ctx<'_> {
    fn collectible(&self, item: i32) -> bool {
        self.data.items.item(item).is_some_and(|i| i.is_collectible())
    }

    fn name(&self, item: i32) -> String {
        self.data.items.item(item).map_or(format!("#{item}"), |i| i.name.clone())
    }
}

impl Holdings {
    fn line(&mut self, c: &Ctx, place: &str, text: &str) {
        self.journal.push(format!("{}  {:<15} {}", c.time, place, text));
    }

    /// What the Java app kept (`holdings.json`, its journal, an earlier version's `whereabouts.json`), read only.
    pub fn import(dir: &std::path::Path) -> Holdings {
        let mut h = Holdings::default();
        if let Ok(text) = std::fs::read_to_string(dir.join("holdings.json"))
            && let Ok(Some((places, gone))) = parse_state(&text)
        {
            for (k, p) in places {
                if !h.places.contains_key(&k) {
                    h.place_keys.push(k.clone());
                }
                h.places.insert(k, p);
            }
            h.places_linked = true;
            h.gone_linked = gone.2;
            h.gone_keys = gone.0;
            h.gone = gone.1;
        }
        if let Ok(text) = std::fs::read_to_string(dir.join("holdings-journal.txt")) {
            h.journal = text.lines().map(str::to_string).collect();
            h.journal_written = h.journal.len();
        }
        h.whereabouts = std::fs::read_to_string(dir.join("whereabouts.json")).ok();
        h
    }

    /// Java `retire`: the earlier version's file is done with.
    fn retire(&mut self, c: &Ctx) {
        if self.whereabouts.take().is_some() {
            self.line(c, "start", "whereabouts.json deleted: its vaults were taken over");
        }
    }

    /// Java `seedFromSavedData`, on first use: if nothing is known yet, the characters saved with their slots and
    /// the vaults of an earlier version's record.
    pub fn seed_once(&mut self, c: &Ctx, saved: &[Living]) {
        if self.seeded {
            return;
        }
        self.seeded = true;
        if self.known() {
            self.retire(c);
            return;
        }
        let vaults = self.whereabouts.as_deref().map(earlier_vaults).unwrap_or_default();
        for l in saved {
            let Some(slots) = &l.slots else { continue };
            let mut p = Place::default();
            let ench = l.slot_enchant_ids.as_ref();
            let s: Vec<Slot> = slots.iter().enumerate().map(|(i, &item)| Slot::new(item, ench.and_then(|e| e.get(i)).cloned())).collect();
            p.put(SLOTS, s);
            if let Some(q) = &l.quickslots {
                p.put(QUICK, quick_slots(q, None));
            }
            p.seen_at = 0;
            p.source = Some("saved characters".into());
            let key = format!("char:{}", l.char_id);
            self.places.insert(key.clone(), p);
            self.track(&key);
        }
        for (key, by_item) in vaults {
            let mut p = Place::default();
            let mut slots = Vec::new();
            for (item, n) in by_item {
                for _ in 0..n {
                    slots.push(Slot::new(item, None));
                }
            }
            p.put(VAULT_CHEST, slots);
            p.seen_at = 0;
            p.source = Some("earlier version".into());
            self.places.insert(key.clone(), p);
            self.track(&key);
        }
        if self.places.is_empty() {
            return;
        }
        let n = self.places.len();
        self.line(c, "start", &format!("seeded {n} places from saved data (until the game shows them)"));
        self.dirty = true;
        if self.known() {
            self.retire(c);
        }
    }

    /// Java `live`: what the game shows of your character's slots.
    pub fn live(&mut self, c: &Ctx, char_id: i32, seen: &Live) {
        let key = format!("char:{char_id}");
        let fresh = !self.places.contains_key(&key);
        if fresh {
            let mut p = Place::default();
            p.put(SLOTS, Vec::new());
            self.places.insert(key.clone(), p);
            self.track(&key);
        }
        let mut lines = Vec::new();
        let mut changed = false;
        let p = self.places.get_mut(&key).expect("place");
        let slots = p.section_mut(SLOTS);
        let n = seen.items.len();
        while slots.len() < n {
            slots.push(Slot::default());
        }
        for i in 0..n {
            let mut item = seen.items[i];
            let old = slots[i].clone();
            let mut ench = seen.enchant_ids.get(i).cloned().flatten();
            if item == UNKNOWN {
                if ench.is_none() || old.empty() {
                    continue;
                }
                item = old.item;
            }
            if ench.is_none() && old.item == item {
                ench = old.enchant_ids.clone();
            }
            let now = Slot::new(item, ench);
            if now == old {
                continue;
            }
            slots[i] = now.clone();
            note(c, &mut lines, &format!("{} {i}", slot_name(i)), &old, &now);
            changed = true;
        }
        while slots.len() > n {
            let old = slots.pop().expect("slot");
            note(c, &mut lines, &format!("{} {}", slot_name(slots.len()), slots.len()), &old, &Slot::default());
            changed = true;
        }
        if let Some(q) = &seen.quick {
            let before = p.section(QUICK).cloned();
            let quick = quick_slots(q, before.as_ref());
            for (k, s) in quick.iter().enumerate() {
                let old = before.as_ref().and_then(|b| b.get(k)).cloned().unwrap_or_default();
                if *s != old {
                    note(c, &mut lines, &format!("Quick slot {k}"), &old, s);
                    changed = true;
                }
            }
            p.put(QUICK, quick);
        }
        p.seen_at = c.now;
        if fresh || p.source.as_deref() != Some("live") {
            changed = true;
        }
        p.source = Some("live".into());
        if fresh {
            let d = describe(c, self.places.get(&key));
            self.line(c, &key, &format!("first live look: {d}"));
        } else {
            for l in lines {
                self.line(c, &key, &l);
            }
        }
        if changed {
            self.dirty = true;
        }
    }

    /// Java `vaultPacket`: a vault packet; true once the visit's last one came.
    pub fn vault_packet(&mut self, c: &Ctx, p: &lg_protocol::packets::VaultContent) -> bool {
        if !self.map_is_vault {
            if !self.told_not_vault {
                self.line(c, "vault?", "vault packet outside the vault ignored");
            }
            self.told_not_vault = true;
            return false;
        }
        if self.visit.as_ref().is_none_or(|v| v.complete) {
            self.visit = Some(Visit::default());
        }
        for (obj, sec) in [
            (p.vault_chest_object_id, VAULT_CHEST),
            (p.gift_chest_object_id, GIFT_CHEST),
            (p.seasonal_spoil_chest_object_id, SPOILS),
            (p.potion_storage_object_id, POTIONS),
            (p.material_chest_object_id, MATERIALS),
        ] {
            if obj != 0 {
                self.chest_sections.insert(obj, sec.to_string());
            }
        }
        let v = self.visit.as_mut().expect("visit");
        if p.partial && !v.broken {
            v.broken = true;
            v.why = Some("a packet did not decode".into());
        }
        for (sec, ids, ench) in [
            (VAULT_CHEST, &p.vault_contents, Some(&p.vault_chest_enchants)),
            (GIFT_CHEST, &p.gift_contents, Some(&p.gift_chest_enchants)),
            (SPOILS, &p.seasonal_spoil_content, Some(&p.spoils_chest_enchants)),
            (POTIONS, &p.potion_contents, None),
            (MATERIALS, &p.material_contents, None),
        ] {
            if ids.is_empty() {
                continue;
            }
            if !plausible(c, ids) && !v.broken {
                v.broken = true;
                v.why = Some(format!("{sec} did not decode as items"));
            }
            let codes: Option<Vec<&str>> = ench.map(|e| e.split(',').collect());
            let aligned = codes.as_ref().is_some_and(|cs| cs.len() == ids.len());
            let slots = section_of(&mut v.sections, sec);
            for (i, &id) in ids.iter().enumerate() {
                let e = if aligned { Some(lg_gamedata::enchants::enchant_ids(codes.as_ref().map(|cs| cs[i]))) } else { None };
                slots.push(Slot::new(id, e));
            }
        }
        if p.last_vault_packet {
            v.complete = true;
        }
        v.complete
    }

    pub fn vault_waiting(&self) -> bool {
        self.visit.as_ref().is_some_and(|v| v.complete)
    }

    /// Java `vaultSeason`: the visit's season is known: it replaces that vault.
    pub fn vault_season(&mut self, c: &Ctx, seasonal: Option<bool>, how: &str) -> bool {
        let Some(seasonal) = seasonal else { return false };
        if !self.vault_waiting() {
            return false;
        }
        let mut v = self.visit.take().expect("visit");
        let (key, other) = if seasonal { (SEASONAL_VAULT, VAULT) } else { (VAULT, SEASONAL_VAULT) };
        let p = Place { sections: v.sections.clone(), seen_at: c.now, source: Some("vault packet".into()) };
        self.in_vault = None;
        if !v.broken && v.sections.iter().find(|(n, _)| n == VAULT_CHEST).is_none_or(|(_, s)| s.is_empty()) {
            v.broken = true;
            v.why = Some("it has no vault chest slots".into());
        }
        if v.broken {
            let d = describe(c, Some(&p));
            self.line(c, key, &format!("vault visit not saved: {} ({d})", v.why.unwrap_or_default()));
            return true;
        }
        let (mine, theirs) = (self.places.get(key).cloned(), self.places.get(other).cloned());
        let like_mine = overlap(c, Some(&p), mine.as_ref());
        let like_theirs = overlap(c, Some(&p), theirs.as_ref());
        let size: i32 = collectibles(c, Some(&p)).values().sum();
        if theirs.is_some() && size >= 10 && f64::from(like_theirs) > f64::from(size) * 0.8 && like_theirs > like_mine * 2 {
            let which = if seasonal { "regular" } else { "seasonal" };
            self.line(
                c,
                key,
                &format!(
                    "vault visit not saved: it matches the {which} vault ({like_theirs} of {size} copies) far more than this one ({like_mine}); season {how}"
                ),
            );
            return true;
        }
        self.replace(c, key, mine, p, &format!("vault visit, season {how}"));
        self.in_vault = Some(key.to_string());
        self.pages.clear();
        true
    }

    /// Java `entered`: a new map.
    pub fn entered(&mut self, c: &Ctx, vault: bool) {
        self.map_is_vault = vault;
        self.told_not_vault = false;
        self.in_vault = None;
        self.chest_sections.clear();
        self.pages.clear();
        self.asked.clear();
        if let Some(v) = self.visit.take() {
            let (place, why) = if v.complete { ("vault", "its season was never known") } else { ("vault?", "the last packet never came") };
            self.line(c, place, &format!("vault visit dropped on leaving: {why}"));
        }
    }

    pub fn section(&self, obj: i32) -> Option<&String> {
        self.chest_sections.get(&obj)
    }

    pub fn is_vault_object(&self, obj: i32) -> bool {
        self.in_vault.is_some() && self.chest_sections.contains_key(&obj)
    }

    fn enchants_at(&self, player_id: i32, char_id: i32, obj: i32, slot: i32, item: i32) -> Option<Vec<i32>> {
        if item < 1 {
            return None;
        }
        let from = if let Some(sec) = self.chest_sections.get(&obj) {
            self.in_vault.as_ref().and_then(|k| self.places.get(k)).and_then(|v| v.section(sec))
        } else if obj == player_id {
            self.places.get(&format!("char:{char_id}")).and_then(|p| p.section(SLOTS))
        } else {
            None
        }?;
        let s = from.get(usize::try_from(slot).ok()?)?;
        if s.item != item {
            return None;
        }
        s.enchant_ids.clone()
    }

    /// Java `swap`: a move you asked for, when it involves the vault.
    #[allow(clippy::too_many_arguments)]
    pub fn swap(&mut self, now: i64, player_id: i32, char_id: i32, from: (i32, i32, i32), to: (i32, i32, i32)) {
        if !self.chest_sections.contains_key(&from.0) && !self.chest_sections.contains_key(&to.0) {
            return;
        }
        self.asked.retain(|m| now - m.at <= 10_000);
        let from_ench = self.enchants_at(player_id, char_id, from.0, from.1, from.2);
        let to_ench = self.enchants_at(player_id, char_id, to.0, to.1, to.2);
        self.asked.push(Move {
            from_obj: from.0,
            from_slot: from.1,
            from_item: from.2,
            from_ench,
            to_obj: to.0,
            to_slot: to.1,
            to_item: to.2,
            to_ench,
            at: now,
        });
    }

    fn side(&self, c: &Ctx, sec: Option<&String>, slot: i32, item: i32) -> String {
        let what = if item < 1 { "empty".to_string() } else { c.name(item) };
        match sec {
            None => format!("you, slot {slot} ({what})"),
            Some(s) => format!("{s}, slot {slot} ({what})"),
        }
    }

    /// Java `swapResult`: the game's answer to a move.
    pub fn swap_result(&mut self, c: &Ctx, result: bool, kind: i8, from: (i32, i32, i32), to: (i32, i32, i32)) {
        let (from_sec, to_sec) = (self.chest_sections.get(&from.0).cloned(), self.chest_sections.get(&to.0).cloned());
        if from_sec.is_none() && to_sec.is_none() {
            return;
        }
        let m = self.asked.iter().position(|a| a.from_obj == from.0 && a.from_slot == from.1 && a.to_obj == to.0 && a.to_slot == to.1);
        let m = m.map(|i| self.asked.remove(i));
        let what = format!(
            "{} <-> {}",
            self.side(c, from_sec.as_ref(), from.1, m.as_ref().map_or(from.2, |m| m.from_item)),
            self.side(c, to_sec.as_ref(), to.1, m.as_ref().map_or(to.2, |m| m.to_item))
        );
        let place = self.in_vault.clone().unwrap_or("vault?".into());
        if !result {
            self.line(c, &place, &format!("move refused by the game (type {kind}): {what}"));
            return;
        }
        let Some(key) = self.in_vault.clone().filter(|k| self.places.contains_key(k)) else {
            self.line(c, &place, &format!("move not followed, the vault of this visit is not saved: {what}"));
            return;
        };
        let mut lines = Vec::new();
        let mut changed = false;
        let mut v = self.places.remove(&key).expect("vault");
        if from_sec.as_deref() == Some(GIFT_CHEST) && from.2 < 1 {
            changed |= take_gift(c, &mut v, from.1, m.as_ref().map_or(to.2, |m| m.from_item), &mut lines);
        } else if let Some(sec) = &from_sec {
            let ench = m.as_ref().and_then(|m| if m.to_item == from.2 { m.to_ench.clone() } else { None });
            changed |= set(c, &mut v, sec, from.1, from.2, ench, &mut lines);
        }
        if to_sec.as_deref() == Some(GIFT_CHEST) && to.2 < 1 {
            changed |= take_gift(c, &mut v, to.1, m.as_ref().map_or(from.2, |m| m.to_item), &mut lines);
        } else if let Some(sec) = &to_sec {
            let ench = m.as_ref().and_then(|m| if m.from_item == to.2 { m.from_ench.clone() } else { None });
            changed |= set(c, &mut v, sec, to.1, to.2, ench, &mut lines);
        }
        self.places.insert(key.clone(), v);
        let asked = if m.is_none() && kind != 1 { "  (no move asked for)" } else { "" };
        self.line(c, &key, &format!("{}{what}{asked}", if kind == 1 { "used up: " } else { "move: " }));
        for l in lines {
            self.line(c, &key, &l);
        }
        if changed {
            self.dirty = true;
        }
    }

    /// Java `chestView`: what a vault chest shows (a page of 8 slots).
    pub fn chest_view(&mut self, c: &Ctx, obj: i32, shown: &std::collections::BTreeMap<i32, i32>) {
        if shown.is_empty() {
            return;
        }
        let Some(sec) = self.chest_sections.get(&obj).cloned() else { return };
        let Some(key) = self.in_vault.clone().filter(|k| self.places.contains_key(k)) else { return };
        let mut v = self.places.remove(&key).expect("vault");
        let page = self.pages.get(&obj).copied().unwrap_or(0);
        let slots = v.section_mut(&sec).clone();
        let mut matching = Vec::new();
        let mut pg = 0;
        while pg * PAGE < (slots.len() as i32).max(PAGE) {
            if shows(&slots, pg, shown) {
                matching.push(pg);
            }
            pg += 1;
        }
        if matching.contains(&page) {
            self.places.insert(key, v);
            return;
        }
        if shown.len() as i32 >= PAGE {
            if matching.len() == 1 {
                self.pages.insert(obj, matching[0]);
            } else {
                let which = if matching.is_empty() {
                    "matches no stored page".to_string()
                } else {
                    format!("matches pages [{}]", matching.iter().map(i32::to_string).collect::<Vec<_>>().join(", "))
                };
                let m = shown.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join(", ");
                self.line(c, &key, &format!("{sec} shows a page that {which}: not written {{{m}}}"));
            }
            self.places.insert(key, v);
            return;
        }
        let mut lines = Vec::new();
        let mut changed = false;
        for (&k, &item) in shown {
            if k >= PAGE {
                continue;
            }
            changed |= set(c, &mut v, &sec, page * PAGE + k, item, None, &mut lines);
        }
        self.places.insert(key.clone(), v);
        for l in lines {
            self.line(c, &key, &format!("seen in the vault, page {page}: {l}"));
        }
        if changed {
            self.dirty = true;
        }
    }

    /// Java `replace`: a place seen anew, with a journal line of what changed.
    fn replace(&mut self, c: &Ctx, key: &str, before: Option<Place>, now: Place, why: &str) {
        if let Some(b) = &before
            && (key == VAULT || key == SEASONAL_VAULT)
        {
            let all = |p: &Place| p.sections.iter().flat_map(|(_, s)| s.iter().filter(|s| !s.empty()).cloned()).collect::<Vec<_>>();
            self.vault_changes.push(VaultChange {
                key: key.to_string(),
                before_at: b.seen_at,
                before: all(b),
                now_at: now.seen_at,
                now: all(&now),
            });
        }
        let (was, is) = (collectibles(c, before.as_ref()), collectibles(c, Some(&now)));
        let ids: HashSet<i32> = was.keys().chain(is.keys()).copied().collect();
        let (mut gained, mut lost) = (0, 0);
        let mut parts = Vec::new();
        for id in ids {
            let d = is.get(&id).copied().unwrap_or(0) - was.get(&id).copied().unwrap_or(0);
            if d == 0 {
                continue;
            }
            if d > 0 {
                gained += d;
            } else {
                lost -= d;
            }
            parts.push(format!("{}{d} {}", if d > 0 { "+" } else { "" }, c.name(id)));
        }
        parts.sort_by(|a, b| java_cmp(a, b));
        let total: i32 = is.values().sum();
        let change = if before.is_none() { "new".to_string() } else { format!("+{gained} / -{lost}") };
        let tail = if parts.is_empty() { String::new() } else { format!("  {}", parts.join(", ")) };
        self.places.insert(key.to_string(), now);
        self.track(key);
        self.line(c, key, &format!("{why}: {total} copies ({change}){tail}"));
        self.dirty = true;
    }

    /// A place put (Java: `state.places.put`): a new key goes to the end of Java's order.
    fn track(&mut self, key: &str) {
        if !self.place_keys.iter().any(|k| k == key) {
            self.place_keys.push(key.to_string());
            self.place_ops.push(lg_world::fight::MapOp::Put(key.to_string(), lg_world::fight::java_string_hash(key)));
        }
    }

    /// The places' keys in Java's `HashMap` order.
    pub(crate) fn java_place_order(&self) -> Vec<String> {
        if self.places_linked {
            return self.place_keys.iter().filter(|k| self.places.contains_key(*k)).cloned().collect();
        }
        lg_world::fight::java_order_after(&self.place_ops).into_iter().filter(|k| self.places.contains_key(k)).collect()
    }

    /// Java `characters`: what the character list says each character holds. Ignored when it is empty or shares no
    /// character with those known (another account's); a character seen live in the last minute keeps that; the
    /// characters missing from it are removed.
    pub fn characters(&mut self, c: &Ctx, listed: &[Listed]) {
        let now = c.now;
        let keep: Vec<String> = listed.iter().filter(|l| l.slots.is_some()).map(|l| format!("char:{}", l.char_id)).collect();
        let known = self.places.keys().any(|k| k.starts_with("char:"));
        if keep.is_empty() || known && !keep.iter().any(|k| self.places.contains_key(k)) {
            let why = if keep.is_empty() { "it is empty" } else { "it shares no character with the known ones (another account?)" };
            self.line(c, "chars", &format!("character list ignored: {why}"));
            return;
        }
        let mut changed = false;
        for l in listed {
            let Some(slots) = &l.slots else { continue };
            let key = format!("char:{}", l.char_id);
            let before = self.places.get(&key).cloned();
            if let Some(b) = &before
                && b.source.as_deref() == Some("live")
                && now - b.seen_at < LIVE_WINS_MS
            {
                self.line(c, &key, &format!("character list ignored: seen live {} s ago", (now - b.seen_at) / 1000));
                continue;
            }
            let mut p = Place::default();
            let list: Vec<Slot> = slots
                .iter()
                .enumerate()
                .map(|(i, &item)| Slot::new(item, l.enchant_ids.as_ref().and_then(|e| e.get(i)).cloned()))
                .collect();
            p.put(SLOTS, list);
            if let Some(q) = &l.quick {
                p.put(QUICK, quick_slots(q, None));
            } else if let Some(q) = before.as_ref().and_then(|b| b.section(QUICK)) {
                p.put(QUICK, q.clone());
            }
            p.seen_at = now;
            p.source = Some("char list".into());
            self.replace(c, &key, before, p, "character list");
            changed = true;
        }
        for key in self.java_place_order() {
            if !key.starts_with("char:") || keep.contains(&key) {
                continue;
            }
            let d = describe(c, self.places.get(&key));
            self.line(c, &key, &format!("not in the character list: removed ({d})"));
            self.places.remove(&key);
            self.place_keys.retain(|k| *k != key);
            self.place_ops.push(lg_world::fight::MapOp::Removed(key.clone(), lg_world::fight::java_string_hash(&key)));
            if !self.gone.contains_key(&key) {
                self.gone_keys.push(key.clone());
                self.gone_ops.push(lg_world::fight::MapOp::Put(key.clone(), lg_world::fight::java_string_hash(&key)));
            }
            self.gone.insert(key, now);
            changed = true;
        }
        if changed {
            self.dirty = true;
        }
    }

    /// Java `listedVault`: the regular vault from the character list replaces the stored one, except while you are
    /// in it; sections it does not carry keep theirs. Ignored when empty or not decoding as items.
    pub fn listed_vault(&mut self, c: &Ctx, sections: Option<Vec<(String, Vec<Slot>)>>) {
        let Some(sections) = sections else { return };
        if self.in_vault.as_deref() == Some(VAULT) {
            self.line(c, VAULT, "vault from the character list ignored: you are in it");
            return;
        }
        let ids: Vec<i32> = sections.iter().find(|(n, _)| n == VAULT_CHEST).map_or(Vec::new(), |(_, s)| s.iter().map(|s| s.item).collect());
        if ids.is_empty() || !plausible(c, &ids) {
            let why = if ids.is_empty() { "it is empty" } else { "it does not decode as items" };
            self.line(c, VAULT, &format!("vault from the character list ignored: {why}"));
            return;
        }
        let before = self.places.get(VAULT).cloned();
        let mut p = Place::default();
        for (n, s) in sections {
            p.put(&n, s);
        }
        if let Some(b) = &before {
            for (n, s) in &b.sections {
                if p.section(n).is_none() {
                    p.put(n, s.clone());
                }
            }
        }
        p.seen_at = c.now;
        p.source = Some("char list".into());
        self.replace(c, VAULT, before, p, "vault from the character list");
    }

    /// Java `count`: copies of a collectible item held by living characters and in the vaults.
    pub fn count(&self, c: &Ctx, living: &HashMap<i32, bool>, item: i32) -> i32 {
        self.tally(c, living).0.get(&item).copied().unwrap_or(0)
    }

    pub fn best_enchants(&self, c: &Ctx, living: &HashMap<i32, bool>, item: i32) -> i32 {
        self.tally(c, living).1.get(&item).copied().unwrap_or(-1)
    }

    fn tally(&self, c: &Ctx, living: &HashMap<i32, bool>) -> (HashMap<i32, i32>, HashMap<i32, i32>) {
        let (mut n, mut best) = (HashMap::new(), HashMap::new());
        for (key, p) in &self.places {
            if let Some(id) = key.strip_prefix("char:").and_then(|i| i.parse::<i32>().ok())
                && !living.contains_key(&id)
            {
                continue;
            }
            for (_, slots) in &p.sections {
                for s in slots {
                    if s.empty() || !c.collectible(s.item) {
                        continue;
                    }
                    *n.entry(s.item).or_insert(0) += 1;
                    if let Some(e) = &s.enchant_ids {
                        let b = best.entry(s.item).or_insert(e.len() as i32);
                        *b = (*b).max(e.len() as i32);
                    }
                }
            }
        }
        (n, best)
    }

    pub fn known(&self) -> bool {
        !self.places.is_empty()
    }

    pub fn slots(&self, place: &str, section: &str) -> Option<&Vec<Slot>> {
        self.places.get(place).and_then(|p| p.section(section))
    }

    pub fn dump(&self) -> String {
        let mut out = format!(
            "holdings vault={} inVault={} visit={} chests={} pages={} asked={} gone={}\n",
            self.map_is_vault,
            self.in_vault.as_deref().unwrap_or("null"),
            self.visit.as_ref().map_or("null".into(), |v| format!("{}/{}/{}", v.sections.len(), v.complete, v.broken)),
            self.chest_sections.len(),
            self.pages.len(),
            self.asked.len(),
            self.gone.len()
        );
        let mut keys: Vec<&String> = self.places.keys().collect();
        keys.sort();
        for k in keys {
            let p = &self.places[k];
            out.push_str(&format!("place {k} seen={} source={}", p.seen_at, p.source.as_deref().unwrap_or("null")));
            for (name, slots) in &p.sections {
                let s: Vec<String> = slots
                    .iter()
                    .map(|s| match &s.enchant_ids {
                        None => s.item.to_string(),
                        Some(e) => format!("{}{:?}", s.item, e).replace(' ', ""),
                    })
                    .collect();
                out.push_str(&format!(" {name}=[{}]", s.join(",")));
            }
            out.push('\n');
        }
        for l in &self.journal {
            out.push_str(&format!("journal {l}\n"));
        }
        out
    }
}

fn section_of<'a>(sections: &'a mut Vec<(String, Vec<Slot>)>, name: &str) -> &'a mut Vec<Slot> {
    if let Some(i) = sections.iter().position(|(n, _)| n == name) {
        &mut sections[i].1
    } else {
        sections.push((name.to_string(), Vec::new()));
        &mut sections.last_mut().expect("just added").1
    }
}

/// Java's `String.compareTo`: UTF-16 code units.
fn java_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

pub fn slot_name(i: usize) -> &'static str {
    match i {
        0..=3 => "Equipped",
        4..=11 => "Inventory",
        12..=19 => "Backpack",
        _ => "Extender",
    }
}

/// Java `vaultFromCharList`: the regular vault as the full character list shows it (the account's own Account
/// block): vault chests, gifts, potions, material chests; items "id" or "id#instance", an instance's enchantments
/// from the UniqueItemInfo blocks. None without a vault.
pub fn vault_from_char_list(xml: &str) -> Option<Vec<(String, Vec<Slot>)>> {
    let at = xml.find("<Vault>")?;
    // lastIndexOf("<Account>", at): one starting at or before the vault
    let start = xml[..(at + "<Account>".len()).min(xml.len())].rfind("<Account>")?;
    let end = at + xml[at..].find("</Account>")?;
    let account = &xml[start..end];
    let vault = between(account, "<Vault>", "</Vault>")?;
    let mut codes: HashMap<&str, String> = HashMap::new();
    for (id, code) in crate::patterns::item_data(account, crate::patterns::Attr::Id) {
        codes.insert(id, crate::java::trim(code).to_string());
    }
    let mut out = vec![(VAULT_CHEST.to_string(), chests(vault, &codes))];
    if let Some(g) = between(account, "<Gifts>", "</Gifts>") {
        let mut list = items(g, &codes);
        list.retain(|s| !s.empty());
        out.push((GIFT_CHEST.to_string(), list));
    }
    if let Some(p) = between(account, "<Potions>", "</Potions>") {
        out.push((POTIONS.to_string(), items(p, &codes)));
    }
    if let Some(m) = between(account, "<MaterialStorage>", "</MaterialStorage>") {
        out.push((MATERIALS.to_string(), chests(m, &codes)));
    }
    Some(out)
}

/// The vaults of `whereabouts.json` (`places.vault`, `places.vault:seasonal`: item -> its copies), how many copies
/// of each item, in Java's `HashMap` orders; what comes before something it cannot read.
fn earlier_vaults(text: &str) -> Vec<(String, Vec<(i32, usize)>)> {
    use lg_world::json::Json;
    let mut out: Vec<(String, Vec<(i32, usize)>)> = Vec::new();
    let Ok(Json::Object(root)) = lg_world::json::parse(text) else { return out };
    let Some((_, places)) = root.iter().find(|(k, _)| k == "places") else { return out };
    let Json::Object(places) = places else { return out };
    'keys: for key in [VAULT, SEASONAL_VAULT] {
        let Some((_, v)) = places.iter().find(|(k, _)| k == key) else { continue };
        let Json::Object(items) = v else { break };
        let mut by_item: Vec<(i32, usize)> = Vec::new();
        for (item, copies) in items {
            let (Ok(item), Json::Array(a)) = (crate::java::parse_int(item), copies) else {
                break 'keys; // Java throws here: the vaults read so far stay
            };
            match by_item.iter_mut().find(|(i, _)| *i == item) {
                Some(e) => e.1 = a.len(),
                None => by_item.push((item, a.len())),
            }
        }
        let order = lg_world::fight::java_hash_order(&by_item.iter().map(|b| b.0).collect::<Vec<_>>());
        out.push((key.to_string(), order.into_iter().map(|i| *by_item.iter().find(|b| b.0 == i).expect("item")).collect()));
    }
    // vaults: a HashMap<String, ...> of two keys
    let keys: Vec<(String, i32)> = out.iter().map(|(k, _)| (k.clone(), lg_world::fight::java_string_hash(k))).collect();
    lg_world::fight::java_bucket_order(&keys, 16).into_iter().map(|k| out.iter().find(|o| o.0 == k).expect("key").clone()).collect()
}

/// Gson reading `State`: its places (in the file's order) and the places gone; None when Java keeps its own.
/// Places in the file's order, and the places gone.
type State = (Vec<(String, Place)>, (Vec<String>, HashMap<String, i64>, bool));

fn parse_state(text: &str) -> Result<Option<State>, crate::java::Thrown> {
    use crate::gson;
    use crate::java::Thrown;
    use lg_world::json::Json;
    let root = lg_world::json::parse(text).map_err(|_| Thrown)?;
    if root == Json::Null {
        return Ok(None);
    }
    let Json::Object(_) = root else { return Err(Thrown) };
    let places = match root.get("places") {
        None | Some(Json::Null) => return Ok(None),
        Some(Json::Object(p)) => p,
        Some(_) => return Err(Thrown),
    };
    let mut out = Vec::new();
    for (k, v) in places {
        let Json::Object(_) = v else {
            if *v == Json::Null {
                continue;
            }
            return Err(Thrown);
        };
        let mut p = Place::default();
        match v.get("sections") {
            None | Some(Json::Null) => {}
            Some(Json::Object(secs)) => {
                for (name, list) in secs {
                    let slots = gson::list(Some(list), |x| {
                        let Json::Object(_) = x else { return Err(Thrown) };
                        let item = gson::int(x.get("item"))?.unwrap_or(-1);
                        let ids = gson::list(x.get("enchantIds"), |e| gson::int(Some(e))?.ok_or(Thrown))?.flatten();
                        Ok(Slot { item, enchant_ids: ids.map(|l| l.into_iter().map(|i| i.unwrap_or(0)).collect()) })
                    })?
                    .flatten()
                    .unwrap_or_default();
                    p.put(name, slots.into_iter().map(Option::unwrap_or_default).collect());
                }
            }
            Some(_) => return Err(Thrown),
        }
        if let Some(x) = gson::long(v.get("seenAt"))? {
            p.seen_at = x;
        }
        if let Some(x) = gson::string(v.get("source"))? {
            p.source = x;
        }
        out.push((k.clone(), p));
    }
    let (mut keys, mut gone) = (Vec::new(), HashMap::new());
    // a file without it: Java makes a HashMap (files from before syncing)
    let linked = matches!(root.get("gone"), Some(Json::Object(_)));
    if let Some(Json::Object(g)) = root.get("gone") {
        for (k, v) in g {
            if let Some(t) = gson::long(Some(v))? {
                if !gone.contains_key(k) {
                    keys.push(k.clone());
                }
                gone.insert(k.clone(), t);
            }
        }
    }
    Ok(Some((out, (keys, gone, linked))))
}

fn between<'a>(s: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let a = s.find(open)?;
    let b = a + s[a..].find(close)?;
    s.get(a + open.len()..b)
}

fn chests(block: &str, codes: &HashMap<&str, String>) -> Vec<Slot> {
    crate::patterns::texts(block, "Chest").into_iter().flat_map(|t| items(t, codes)).collect()
}

fn items(csv: &str, codes: &HashMap<&str, String>) -> Vec<Slot> {
    if crate::java::is_blank(csv) {
        return Vec::new();
    }
    crate::java::split(csv, ',')
        .into_iter()
        .map(|part| {
            let t = crate::java::trim(part);
            let (id, instance) = match t.find('#') {
                Some(h) => (&t[..h], Some(&t[h + 1..])),
                None => (t, None),
            };
            let item = crate::java::parse_int(id).unwrap_or(-1);
            let code = instance.and_then(|i| codes.get(i));
            Slot::new(item, code.map(|c| lg_gamedata::enchants::enchant_ids(Some(c))))
        })
        .collect()
}

fn quick_slots(quick: &[i32], before: Option<&Vec<Slot>>) -> Vec<Slot> {
    quick
        .iter()
        .enumerate()
        .map(|(k, &q)| if q == UNKNOWN { before.and_then(|b| b.get(k)).cloned().unwrap_or_default() } else { Slot::new(q, None) })
        .collect()
}

fn plausible(c: &Ctx, ids: &[i32]) -> bool {
    let used = ids.iter().filter(|&&i| i >= 1).count();
    let known = ids.iter().filter(|&&i| i >= 1 && !c.name(i).starts_with('#')).count();
    known as f64 >= used as f64 * 0.9
}

fn collectibles(c: &Ctx, p: Option<&Place>) -> HashMap<i32, i32> {
    let mut out = HashMap::new();
    if let Some(p) = p {
        for (_, slots) in &p.sections {
            for s in slots.iter().filter(|s| !s.empty() && c.collectible(s.item)) {
                *out.entry(s.item).or_insert(0) += 1;
            }
        }
    }
    out
}

fn overlap(c: &Ctx, a: Option<&Place>, b: Option<&Place>) -> i32 {
    let (Some(a), Some(b)) = (a, b) else { return 0 };
    let (x, y) = (collectibles(c, Some(a)), collectibles(c, Some(b)));
    x.iter().map(|(k, v)| (*v).min(y.get(k).copied().unwrap_or(0))).sum()
}

fn describe(c: &Ctx, p: Option<&Place>) -> String {
    match p {
        None => "nothing".into(),
        Some(p) => format!("{} copies", collectibles(c, Some(p)).values().sum::<i32>()),
    }
}

/// Java `List.toString`: `[1, 2]`.
fn list(v: &[i32]) -> String {
    format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(", "))
}

fn label(c: &Ctx, s: &Slot) -> String {
    if s.empty() {
        return "empty".into();
    }
    match &s.enchant_ids {
        None => c.name(s.item),
        Some(e) => format!("{} {}", c.name(s.item), list(e)),
    }
}

/// Java `note`: a journal line, for collectible items only.
fn note(c: &Ctx, lines: &mut Vec<String>, r#where: &str, old: &Slot, now: &Slot) {
    let was = !old.empty() && c.collectible(old.item);
    let is = !now.empty() && c.collectible(now.item);
    if !was && !is {
        return;
    }
    lines.push(format!("{}: {} -> {}", r#where, label(c, old), label(c, now)));
}

fn shows(slots: &[Slot], page: i32, shown: &std::collections::BTreeMap<i32, i32>) -> bool {
    shown.iter().all(|(&k, &v)| {
        let idx = page * PAGE + k;
        let stored = usize::try_from(idx).ok().and_then(|i| slots.get(i)).map_or(-1, |s| s.item);
        stored == if v < 1 { -1 } else { v }
    })
}

fn set(c: &Ctx, v: &mut Place, sec: &str, idx: i32, item: i32, ench: Option<Vec<i32>>, lines: &mut Vec<String>) -> bool {
    let slots = v.section_mut(sec);
    let Some(i) = usize::try_from(idx).ok().filter(|&i| i < slots.len()) else {
        lines.push(format!("{sec} {idx}: not in the stored vault ({} slots)", slots.len()));
        return false;
    };
    let old = slots[i].clone();
    let now = if item < 1 { -1 } else { item };
    let slot = Slot::new(now, ench.or_else(|| if old.item == now { old.enchant_ids.clone() } else { None }));
    if slot == old {
        return false;
    }
    slots[i] = slot.clone();
    note(c, lines, &format!("{sec} {idx}"), &old, &slot);
    true
}

fn take_gift(c: &Ctx, v: &mut Place, idx: i32, item: i32, lines: &mut Vec<String>) -> bool {
    let Some(gifts) = v.sections.iter_mut().find(|(n, _)| n == GIFT_CHEST).map(|(_, s)| s) else { return false };
    let mut at = None;
    'find: for d in 0..=5 {
        for i in [idx + d, idx - d] {
            if let Ok(u) = usize::try_from(i)
                && gifts.get(u).is_some_and(|g| g.item == item)
            {
                at = Some(u);
                break 'find;
            }
        }
    }
    let Some(at) = at else {
        lines.push(format!("{GIFT_CHEST} {idx}: the gift taken ({}) is not there: not written", c.name(item)));
        return false;
    };
    let old = gifts.remove(at);
    let named = if at as i32 != idx { format!(" (named {idx})") } else { String::new() };
    note(c, lines, &format!("{GIFT_CHEST} {at}{named}"), &old, &Slot::default());
    true
}
