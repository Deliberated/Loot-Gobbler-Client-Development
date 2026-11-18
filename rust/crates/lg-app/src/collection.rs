//! Your collection (Java: `lootgobbler.CollectionStore`): every collectible item you found or hold, with a history of
//! each copy: dropped for you, seen in your vault or inventory, and when it left (forged, fed, dropped, used up).
//!
//! Not yet: syncing with the Loot Gobbler server, the file.

use std::collections::HashMap;

pub const DROP: &str = "drop";
pub const VAULT: &str = "vault";
pub const INVENTORY: &str = "inventory";
pub const FORGED: &str = "forged";
pub const FED: &str = "fed";
pub const DROPPED: &str = "dropped";
pub const CONSUMED: &str = "consumed";
const MAX_HISTORY: usize = 200;

/// Where a copy was seen last: a character or a vault chest (Java: `Seen`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Seen {
    pub char_id: i32,
    pub place: String,
    pub class_type: i32,
    pub skin: i32,
    pub tex1: i32,
    pub tex2: i32,
    pub seasonal: bool,
}

impl Seen {
    pub fn vault(place: &str) -> Seen {
        Seen { place: if place.trim().is_empty() { "Vault".into() } else { place.to_string() }, ..Default::default() }
    }

    pub fn character(c: &crate::characters::Char) -> Seen {
        Seen {
            char_id: c.char_id,
            place: c.class_name.clone(),
            class_type: c.class_type,
            skin: c.skin,
            tex1: c.tex1,
            tex2: c.tex2,
            seasonal: c.seasonal,
        }
    }
}

/// One copy found, or one gone (Java: `Find`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Find {
    /// Local time, as Java's `LocalDateTime` writes it.
    pub time: String,
    /// ms since 1970.
    pub at: i64,
    pub source: String,
    /// How many enchant slots; -1 = unknown.
    pub enchants: i32,
    pub r#where: String,
    pub bag: String,
    pub enchant_ids: Option<Vec<i32>>,
    pub seasonal: Option<bool>,
    pub seen: Option<Seen>,
}

impl Find {
    /// Java `Find.of(source, enchants, where, bag)`, at `now`.
    pub fn of(source: &str, enchants: i32, r#where: &str, bag: &str, now: i64, time: &str) -> Find {
        Find {
            time: time.to_string(),
            at: now,
            source: source.into(),
            enchants,
            r#where: r#where.into(),
            bag: bag.into(),
            enchant_ids: None,
            seasonal: None,
            seen: None,
        }
    }

    /// Java `Find.of(source, enchantIds, where, bag)`.
    pub fn with_ids(source: &str, ids: Option<Vec<i32>>, r#where: &str, bag: &str, now: i64, time: &str) -> Find {
        let mut f = Find::of(source, ids.as_ref().map_or(-1, |i| i.len() as i32), r#where, bag, now, time);
        f.enchant_ids = ids;
        f
    }

    pub fn is_removal(&self) -> bool {
        [FORGED, FED, DROPPED, CONSUMED].contains(&self.source.as_str())
    }
}

/// One item (Java: `Entry`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Entry {
    pub first: Option<String>,
    pub last: Option<String>,
    pub count: i32,
    pub history: Option<Vec<Find>>,
}

impl Entry {
    fn add(&mut self, f: Find) {
        let h = self.history.get_or_insert_with(Vec::new);
        h.push(f);
        // Java `trim`: the oldest finds go first, removals stay
        let mut i = 0;
        while h.len() > MAX_HISTORY && i < h.len() {
            if h[i].is_removal() {
                i += 1;
            } else {
                h.remove(i);
            }
        }
    }

    pub fn removed(&self) -> i32 {
        self.history.as_ref().map_or(0, |h| h.iter().filter(|f| f.is_removal()).count() as i32)
    }

    pub fn held(&self) -> i32 {
        (self.count - self.removed()).max(0)
    }
}

#[derive(Debug, Default)]
pub struct Collection {
    order: Vec<i32>,
    entries: HashMap<i32, Entry>,
    /// Changed since the last time someone looked (Java's listeners: the profile counts the ST sets).
    pub changed: bool,
    pub dirty: bool,
}

impl Collection {
    pub fn entry(&self, item: i32) -> Option<&Entry> {
        self.entries.get(&item)
    }

    /// Every entry, in the order first collected.
    pub fn all(&self) -> impl Iterator<Item = (i32, &Entry)> {
        self.order.iter().map(|id| (*id, &self.entries[id]))
    }

    pub fn contains(&self, item: i32) -> bool {
        self.entries.contains_key(&item)
    }

    /// Java `record`: a new copy (a drop, a chest opened).
    pub fn record(&mut self, item: i32, f: Find) {
        if !self.entries.contains_key(&item) {
            self.order.push(item);
            self.entries.insert(item, Entry { first: Some(f.time.clone()), ..Default::default() });
        }
        let e = self.entries.get_mut(&item).expect("entry");
        e.last = Some(f.time.clone());
        e.count += 1;
        e.add(f);
        self.dirty = true;
        self.changed = true;
    }

    /// Java `recordHeld`: copies held of items not in the collection yet (seen in the vault or inventory). How many
    /// were added.
    pub fn record_held(&mut self, held: &[(i32, Vec<Find>)]) -> usize {
        let mut added = 0;
        for (item, copies) in held {
            if copies.is_empty() || self.entries.contains_key(item) {
                continue;
            }
            let mut e =
                Entry { first: Some(copies[0].time.clone()), last: Some(copies[copies.len() - 1].time.clone()), ..Default::default() };
            for f in copies {
                e.add(f.clone());
            }
            e.count = copies.len() as i32;
            self.order.push(*item);
            self.entries.insert(*item, e);
            added += copies.len();
        }
        if added > 0 {
            self.dirty = true;
            self.changed = true;
        }
        added
    }

    /// Java `recordRemoved`: a copy left (forged, fed, dropped, used up). False if the item is not collected.
    pub fn record_removed(&mut self, item: i32, source: &str, r#where: &str, copy: Option<&Find>, now: i64, time: &str) -> bool {
        let Some(e) = self.entries.get_mut(&item) else { return false };
        let mut f = match copy.and_then(|c| c.enchant_ids.clone()) {
            Some(ids) => Find::with_ids(source, Some(ids), r#where, "", now, time),
            None => Find::of(source, -1, r#where, "", now, time),
        };
        if let Some(c) = copy {
            f.seasonal = c.seasonal;
            f.seen = c.seen.clone();
        }
        e.add(f);
        self.dirty = true;
        self.changed = true;
        true
    }

    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let oq = |s: &Option<String>| s.as_deref().map_or("null".into(), q);
        let mut out = format!("collection {}\n", self.entries.len());
        let mut ids: Vec<&i32> = self.entries.keys().collect();
        ids.sort();
        for id in ids {
            let e = &self.entries[id];
            let hist: Vec<String> = e.history.iter().flatten().map(find).collect();
            out.push_str(&format!(
                "item {id} first={} last={} count={} history={}\n",
                oq(&e.first),
                oq(&e.last),
                e.count,
                e.history.as_ref().map_or("null".into(), |_| format!("[{}]", hist.join(",")))
            ));
        }
        out
    }
}

/// One find, as `oracle/WorldOracle.java` writes Java's.
pub fn find(f: &Find) -> String {
    let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
    let ids = f.enchant_ids.as_ref().map_or("null".into(), |v| format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(",")));
    let seen = f.seen.as_ref().map_or("null".into(), |s| {
        format!("{}/{}/{}/{}/{}/{}/{}", s.char_id, q(&s.place), s.class_type, s.skin, s.tex1, s.tex2, s.seasonal)
    });
    format!(
        "{{{} {} {} {} {} {} {ids} {} {seen}}}",
        q(&f.time),
        f.at,
        q(&f.source),
        f.enchants,
        q(&f.r#where),
        q(&f.bag),
        f.seasonal.map_or("null".into(), |s| s.to_string())
    )
}
