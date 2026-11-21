//! Your collection (Java: `lootgobbler.CollectionStore`): every collectible item you found or hold, with a history of
//! each copy: dropped for you, seen in your vault or inventory, and when it left (forged, fed, dropped, used up).
//!

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

/// Java `trim`: the oldest finds go first, removals stay.
fn trim(h: &mut Vec<Find>) {
    let mut i = 0;
    while h.len() > MAX_HISTORY && i < h.len() {
        if h[i].is_removal() {
            i += 1;
        } else {
            h.remove(i);
        }
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
        trim(h);
    }

    pub fn removed(&self) -> i32 {
        self.history.as_ref().map_or(0, |h| h.iter().filter(|f| f.is_removal()).count() as i32)
    }

    /// Java `maxEnchants`: the most enchantment slots a copy found had (removals left out).
    pub fn max_enchants(&self) -> i32 {
        self.history
            .iter()
            .flatten()
            .filter(|f| !f.is_removal())
            .map(|f| f.enchant_ids.as_ref().map_or(f.enchants, |e| e.len() as i32))
            .max()
            .unwrap_or(0)
            .max(0)
    }

    pub fn last_date(&self) -> &str {
        self.last.as_deref().map_or("", |l| &l[..l.len().min(10)])
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
    /// A change the guild should see right away (Java `ServerSync.uploadSoon`), for the sync to take.
    pub soon: bool,
    /// Drops since last taken (Java `onDrop`: the caravan puts them on your wagon at once).
    pub drops: Vec<(i32, Find)>,
}

impl Collection {
    /// What the Java app kept (`collection.json`), read only. Unreadable: nothing.
    pub fn import(dir: &std::path::Path) -> Collection {
        let mut c = Collection::default();
        let Ok(text) = std::fs::read_to_string(dir.join("collection.json")) else { return c };
        if let Ok(list) = parse_entries(&text) {
            for (id, e) in list {
                if !c.entries.contains_key(&id) {
                    c.order.push(id);
                }
                c.entries.insert(id, e);
            }
        }
        c
    }

    /// Java `clear`: the whole collection history gone.
    pub fn clear(&mut self) {
        self.order.clear();
        self.entries.clear();
        self.changed = true;
        self.dirty = true;
    }

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
        self.soon = true; // shows up in the guild's caravan
        if self.drops.len() < 1000 {
            self.drops.push((item, f.clone()));
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
        self.soon = true;
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

// --- merging another PC's collection (Java `mergeSync`, `mergeEntry`) ---

/// A find's time, `None` where Java's is null.
fn time_of(f: &Find) -> Option<&str> {
    (!f.time.is_empty()).then_some(f.time.as_str())
}

fn removal_key(f: &Find) -> String {
    format!("{}|{}|{}", f.source, f.time, f.r#where)
}

fn drop_key(f: &Find) -> String {
    format!("{}|{}|{}", f.time, f.r#where, f.bag)
}

/// Java `pairHeld`: the same copy among mine (by its enchantments), as a position in `unpaired`.
fn pair_held(h: &[Find], unpaired: &[usize], f: &Find) -> Option<usize> {
    if f.enchant_ids.is_some()
        && let Some(i) = unpaired.iter().position(|&m| h[m].enchant_ids == f.enchant_ids)
    {
        return Some(i);
    }
    let loose = |m: usize| h[m].enchant_ids.is_none() || f.enchant_ids.is_none();
    unpaired.iter().position(|&m| loose(m) && h[m].source == f.source).or_else(|| unpaired.iter().position(|&m| loose(m)))
}

fn latest<'a>(mut current: Option<&'a str>, finds: impl IntoIterator<Item = &'a Find>) -> Option<&'a str> {
    for f in finds {
        if let Some(t) = time_of(f)
            && current.is_none_or(|c| t > c)
        {
            current = Some(t);
        }
    }
    current
}

/// Java `explained`: how many copies the drops and sightings account for.
fn explained<'a>(drops: impl IntoIterator<Item = &'a Find>, sightings: usize, last_sighting: Option<&str>) -> i32 {
    let (mut n, mut after) = (0, 0);
    for d in drops {
        n += 1;
        if last_sighting.is_none_or(|l| time_of(d).is_some_and(|t| t > l)) {
            after += 1;
        }
    }
    n.max(sightings as i32 + after)
}

fn fill_enchants(m: &mut Find, f: &Find) -> bool {
    if m.enchant_ids.is_some() || f.enchant_ids.is_none() {
        return false;
    }
    m.enchant_ids = f.enchant_ids.clone();
    m.enchants = f.enchants;
    true
}

fn earlier(a: Option<String>, b: Option<String>) -> Option<String> {
    match (a, b) {
        (Some(a), Some(b)) => Some(if a <= b { a } else { b }),
        (a, b) => a.or(b),
    }
}

fn later(a: Option<String>, b: Option<String>) -> Option<String> {
    match (a, b) {
        (Some(a), Some(b)) => Some(if a >= b { a } else { b }),
        (a, b) => a.or(b),
    }
}

/// Java `mergeEntry`: one item's copies from both sides, none counted twice.
fn merge_entry(mine: &mut Entry, theirs: Entry) -> bool {
    let mut mh = mine.history.clone().unwrap_or_default();
    let th = theirs.history.unwrap_or_default();
    let split = |h: &[Find]| -> (Vec<usize>, Vec<usize>) {
        let kept = (0..h.len()).filter(|&i| !h[i].is_removal());
        kept.partition(|&i| h[i].source == DROP)
    };
    let (my_drops, my_held) = split(&mh);
    let (their_drops, their_held) = split(&th);
    // removals are events: one only the other side saw is added (counted per key)
    let mut my_removals: HashMap<String, i32> = HashMap::new();
    for f in mh.iter().filter(|f| f.is_removal()) {
        *my_removals.entry(removal_key(f)).or_default() += 1;
    }
    let mut their_removals_only = Vec::new();
    for (i, f) in th.iter().enumerate().filter(|(_, f)| f.is_removal()) {
        match my_removals.get_mut(&removal_key(f)) {
            Some(n) if *n > 0 => *n -= 1,
            _ => their_removals_only.push(i),
        }
    }
    // match drops by time and place, pair sightings by enchantments
    let mut my_drops_by_key: HashMap<String, std::collections::VecDeque<usize>> = HashMap::new();
    for &i in &my_drops {
        my_drops_by_key.entry(drop_key(&mh[i])).or_default().push_back(i);
    }
    let (mut their_drops_only, mut same_drops) = (Vec::new(), Vec::new());
    for &t in &their_drops {
        match my_drops_by_key.get_mut(&drop_key(&th[t])).and_then(|q| q.pop_front()) {
            Some(m) => same_drops.push((m, t)),
            None => their_drops_only.push(t),
        }
    }
    let mut unpaired = my_held.clone();
    let (mut their_held_only, mut same_copies) = (Vec::new(), Vec::new());
    for &t in &their_held {
        match pair_held(&mh, &unpaired, &th[t]) {
            Some(p) => same_copies.push((unpaired.remove(p), t)),
            None => their_held_only.push(t),
        }
    }

    let count = {
        let mine_of = |v: &[usize]| v.iter().map(|&i| &mh[i]).collect::<Vec<_>>();
        let theirs_of = |v: &[usize]| v.iter().map(|&i| &th[i]).collect::<Vec<_>>();
        let (md, mhl, td, thl) = (mine_of(&my_drops), mine_of(&my_held), theirs_of(&their_drops), theirs_of(&their_held));
        let all_drops = md.iter().copied().chain(theirs_of(&their_drops_only));
        let last_sighting = latest(latest(None, mhl.iter().copied()), thl.iter().copied());
        let merged = explained(all_drops, mhl.len() + their_held_only.len(), last_sighting);
        let unexplained = (mine.count - explained(md.iter().copied(), mhl.len(), latest(None, mhl.iter().copied())))
            .max(theirs.count - explained(td.iter().copied(), thl.len(), latest(None, thl.iter().copied())))
            .max(0);
        mine.count.max(theirs.count).max(merged + unexplained)
    };

    let mut changed = false;
    for &(m, t) in &same_drops {
        changed |= fill_enchants(&mut mh[m], &th[t]);
    }
    for &(m, t) in &same_copies {
        let (m, f) = (&mut mh[m], &th[t]);
        if let Some(ft) = time_of(f)
            && time_of(m).is_none_or(|mt| ft < mt)
        {
            // keep the first sighting
            m.time = f.time.clone();
            m.r#where = f.r#where.clone();
            m.bag = f.bag.clone();
            changed = true;
        }
        changed |= fill_enchants(m, f);
    }
    let added: Vec<Find> = their_drops_only.iter().chain(&their_held_only).chain(&their_removals_only).map(|&i| th[i].clone()).collect();
    let first = earlier(mine.first.clone(), theirs.first);
    let last = later(mine.last.clone(), theirs.last);
    changed |= !added.is_empty() || count != mine.count || first != mine.first || last != mine.last;
    if changed {
        mh.extend(added);
        mh.sort_by(|a, b| a.time.cmp(&b.time)); // stable, as Java's
        trim(&mut mh);
        mine.history = if mh.is_empty() && mine.history.is_none() { None } else { Some(mh) };
    }
    mine.count = count;
    mine.first = first;
    mine.last = last;
    changed
}

impl Collection {
    /// Java `mergeSync`: another PC's collection (the server's document) taken in; unknown items added, known ones
    /// merged copy by copy. True if anything changed.
    pub fn merge_sync(&mut self, remote: &lg_world::json::Json) -> Result<bool, crate::java::Thrown> {
        let theirs = parse_entries(&remote.write())?;
        let mut changed = false;
        for (id, t) in theirs {
            match self.entries.get_mut(&id) {
                None => {
                    self.order.push(id);
                    self.entries.insert(id, t);
                    changed = true;
                }
                Some(mine) => changed |= merge_entry(mine, t),
            }
        }
        if changed {
            self.dirty = true;
            self.changed = true;
        }
        Ok(changed)
    }
}

/// Gson reading `LinkedHashMap<Integer, Entry>`.
fn parse_entries(text: &str) -> Result<Vec<(i32, Entry)>, crate::java::Thrown> {
    use crate::gson;
    use crate::java::Thrown;
    use lg_world::json::Json;
    let root = lg_world::json::parse(text).map_err(|_| Thrown)?;
    let Json::Object(o) = root else {
        return if root == Json::Null { Ok(Vec::new()) } else { Err(Thrown) };
    };
    let string = |v: Option<&Json>| -> Result<Option<Option<String>>, Thrown> { gson::string(v) };
    let seen = |v: &Json| -> Result<Seen, Thrown> {
        let Json::Object(_) = v else { return Err(Thrown) };
        let mut s = Seen::default();
        if let Some(x) = gson::int(v.get("charId"))? {
            s.char_id = x;
        }
        if let Some(x) = string(v.get("place"))? {
            s.place = x.unwrap_or_default();
        }
        for (f, k) in [(&mut s.class_type, "classType"), (&mut s.skin, "skin"), (&mut s.tex1, "tex1"), (&mut s.tex2, "tex2")] {
            if let Some(x) = gson::int(v.get(k))? {
                *f = x;
            }
        }
        if let Some(x) = gson::boolean(v.get("seasonal"))? {
            s.seasonal = x;
        }
        Ok(s)
    };
    let find = |v: &Json| -> Result<Find, Thrown> {
        let Json::Object(_) = v else { return Err(Thrown) };
        let mut f = Find::of(DROP, -1, "", "", 0, "");
        if let Some(x) = string(v.get("time"))? {
            f.time = x.unwrap_or_default();
        }
        if let Some(x) = gson::long(v.get("at"))? {
            f.at = x;
        }
        if let Some(x) = string(v.get("source"))? {
            f.source = x.unwrap_or_default();
        }
        if let Some(x) = gson::int(v.get("enchants"))? {
            f.enchants = x;
        }
        if let Some(x) = string(v.get("where"))? {
            f.r#where = x.unwrap_or_default();
        }
        if let Some(x) = string(v.get("bag"))? {
            f.bag = x.unwrap_or_default();
        }
        if let Some(x) = gson::list(v.get("enchantIds"), |e| gson::int(Some(e))?.ok_or(Thrown))? {
            f.enchant_ids = x.map(|l| l.into_iter().map(|i| i.unwrap_or(0)).collect());
        }
        f.seasonal = gson::boolean(v.get("seasonal"))?;
        f.seen = match v.get("seen") {
            None | Some(Json::Null) => None,
            Some(s) => Some(seen(s)?),
        };
        Ok(f)
    };
    let mut out = Vec::new();
    for (k, v) in &o {
        let id = crate::java::parse_int(k)?;
        if *v == Json::Null {
            continue;
        }
        let Json::Object(_) = v else { return Err(Thrown) };
        let mut e = Entry::default();
        if let Some(x) = string(v.get("first"))? {
            e.first = x;
        }
        if let Some(x) = string(v.get("last"))? {
            e.last = x;
        }
        if let Some(x) = gson::int(v.get("count"))? {
            e.count = x;
        }
        if let Some(x) = gson::list(v.get("history"), find)? {
            e.history = x.map(|l| l.into_iter().flatten().collect());
        }
        out.push((id, e));
    }
    Ok(out)
}
