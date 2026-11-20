//! What was fought in a map, as the app's run history and dungeon visits read it (Java: `TomatoData`'s hit list
//! and death notifications, `Entity`'s damage lists): of the map you are in ([`World::fight`]), or of the one just
//! left ([`LeftMap`], taken before the world is cleared).

use crate::damage::DamageRecord;
use crate::{Abort, Entity, Map, World, stats};
use lg_gamedata::GameData;
use lg_protocol::packets::Notification;
use std::collections::HashMap;

/// Java's `HashMap` as far as its order goes: the buckets with their keys in chain order. A bucket of 8 grows a table
/// of fewer than 64 buckets (Java's `treeifyBin`); bigger buckets would become trees, not followed (their iteration
/// still keeps the chain).
struct JavaMap<T> {
    table: Vec<Vec<(T, u32)>>,
    size: usize,
}

impl<T: Clone + PartialEq> JavaMap<T> {
    fn new() -> JavaMap<T> {
        JavaMap { table: Vec::new(), size: 0 }
    }

    fn threshold(&self) -> usize {
        self.table.len() / 4 * 3
    }

    fn index(&self, h: u32) -> usize {
        ((h ^ (h >> 16)) as usize) & (self.table.len() - 1)
    }

    fn resize(&mut self) {
        let n = if self.table.is_empty() { 16 } else { self.table.len() * 2 };
        let mut t: Vec<Vec<(T, u32)>> = vec![Vec::new(); n];
        for bin in std::mem::take(&mut self.table) {
            for e in bin {
                let i = ((e.1 ^ (e.1 >> 16)) as usize) & (n - 1);
                t[i].push(e); // the split keeps each chain's order
            }
        }
        self.table = t;
    }

    fn treeify_bin(&mut self) {
        if self.table.len() < 64 {
            self.resize();
        }
    }

    /// `put`: a new key at the end of its chain.
    fn put(&mut self, k: T, h: u32) {
        if self.table.is_empty() {
            self.resize();
        }
        let i = self.index(h);
        if self.table[i].iter().any(|e| e.0 == k) {
            return;
        }
        let before = self.table[i].len();
        self.table[i].push((k, h));
        if before >= 8 {
            self.treeify_bin(); // binCount >= TREEIFY_THRESHOLD - 1, counted from the bin's second node
        }
        self.size += 1;
        if self.size > self.threshold() {
            self.resize();
        }
    }

    /// `computeIfAbsent`: grows first (when already over the threshold), a new key at the head of its chain.
    fn compute(&mut self, k: T, h: u32) {
        if self.table.is_empty() || self.size > self.threshold() {
            self.resize();
        }
        let i = self.index(h);
        if self.table[i].iter().any(|e| e.0 == k) {
            return;
        }
        let bin_count = self.table[i].len();
        self.table[i].insert(0, (k, h));
        if bin_count >= 7 {
            self.treeify_bin();
        }
        self.size += 1;
    }

    fn remove(&mut self, k: &T, h: u32) {
        if self.table.is_empty() {
            return;
        }
        let i = self.index(h);
        if let Some(p) = self.table[i].iter().position(|e| e.0 == *k) {
            self.table[i].remove(p);
            self.size -= 1;
        }
    }

    fn keys(&self) -> Vec<T> {
        self.table.iter().flatten().map(|e| e.0.clone()).collect()
    }
}

/// The order a Java `HashMap` walks keys (with their `hashCode`s) put in this order, by `put` (`computed`: by
/// `computeIfAbsent`, which puts a new key at the head of its bucket).
pub fn java_order<T: Clone + PartialEq>(keys: &[(T, i32)], computed: bool) -> Vec<T> {
    let mut m = JavaMap::new();
    for (k, h) in keys {
        if computed {
            m.compute(k.clone(), *h as u32);
        } else {
            m.put(k.clone(), *h as u32);
        }
    }
    m.keys()
}

/// What was done to a Java `HashMap`, in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MapOp<T> {
    Put(T, i32),
    Computed(T, i32),
    Removed(T, i32),
}

/// The order a Java `HashMap` walks its keys after these operations.
pub fn java_order_after<T: Clone + PartialEq>(ops: &[MapOp<T>]) -> Vec<T> {
    let mut m = JavaMap::new();
    for op in ops {
        match op {
            MapOp::Put(k, h) => m.put(k.clone(), *h as u32),
            MapOp::Computed(k, h) => m.compute(k.clone(), *h as u32),
            MapOp::Removed(k, h) => m.remove(k, *h as u32),
        }
    }
    m.keys()
}

/// The order a Java `HashMap<Integer, ...>` walks keys `put` in this order.
pub fn java_hash_order(keys: &[i32]) -> Vec<i32> {
    java_order(&keys.iter().map(|&k| (k, k)).collect::<Vec<_>>(), false)
}

/// The same for keys added with `computeIfAbsent`.
pub fn java_hash_order_computed(keys: &[i32]) -> Vec<i32> {
    java_order(&keys.iter().map(|&k| (k, k)).collect::<Vec<_>>(), true)
}

/// Java's `String.hashCode`: over the UTF-16 units, wrapping.
pub fn java_string_hash(s: &str) -> i32 {
    s.encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(i32::from(c)))
}

/// The order a Java `HashMap` of `buckets` buckets walks keys with these hashes, put in this order: by bucket, then
/// in the order put.
pub fn java_bucket_order<T: Clone>(keys: &[(T, i32)], buckets: usize) -> Vec<T> {
    let bucket = |h: i32| {
        let h = h as u32;
        ((h ^ (h >> 16)) as usize) & (buckets - 1)
    };
    let mut out: Vec<&(T, i32)> = keys.iter().collect();
    out.sort_by_key(|(_, h)| bucket(*h)); // stable
    out.into_iter().map(|(k, _)| k.clone()).collect()
}

/// The buckets of a Java `HashMap` that grew to `most` entries one by one (16, doubled past three quarters full).
pub fn java_buckets_grown(most: usize) -> usize {
    let mut b = 16;
    while most > b / 4 * 3 {
        b *= 2;
    }
    b
}

/// The buckets of `new HashMap<>(other)` for a map of `n` entries (Java 25: `tableSizeFor(ceil(n / 0.75))`, as
/// checked with jshell).
pub fn java_buckets_copied(n: usize) -> usize {
    let t = (n as f64 / 0.75).ceil() as usize;
    t.max(1).next_power_of_two()
}

/// Java `Entity.name()`: a player's name stat (before the first comma), else the object's name. Java throws for a
/// name stat of only commas.
pub fn name(data: &GameData, e: &Entity) -> Result<Option<String>, Abort> {
    if data.is_player_class(e.object_type)
        && let Some(n) = e.text(stats::NAME)
    {
        if !n.is_empty() && n.chars().all(|c| c == ',') {
            return Err(Abort("a name of only commas"));
        }
        return Ok(Some(n.split(',').next().unwrap_or("").to_string()));
    }
    Ok(e.name.clone())
}

/// The fight data of the map left, as it was before the world was cleared.
#[derive(Debug, Clone, PartialEq)]
pub struct LeftMap {
    pub map: Option<Map>,
    pub entities: HashMap<i32, Entity>,
    pub hit_order: Vec<i32>,
    pub deaths: Vec<Notification>,
    pub player: Option<i32>,
    pub dungeon_time: i64,
}

impl LeftMap {
    pub fn fight<'a>(&'a self, data: &'a GameData) -> Fight<'a> {
        Fight {
            data,
            map: self.map.as_ref(),
            entities: &self.entities,
            hit_list: java_hash_order(&self.hit_order),
            deaths: &self.deaths,
            player: self.player,
            dungeon_time: self.dungeon_time,
        }
    }
}

/// A map's fight data.
pub struct Fight<'a> {
    pub data: &'a GameData,
    pub map: Option<&'a Map>,
    pub entities: &'a HashMap<i32, Entity>,
    /// Every object hit, in Java's order (`entityHitList.values()`).
    pub hit_list: Vec<i32>,
    pub deaths: &'a [Notification],
    pub player: Option<i32>,
    /// Java `dungeonTime()`.
    pub dungeon_time: i64,
}

impl<'a> Fight<'a> {
    pub fn entity(&self, id: i32) -> Option<&'a Entity> {
        self.entities.get(&id)
    }

    /// You, once in view.
    pub fn me(&self) -> Option<&'a Entity> {
        self.player.and_then(|p| self.entities.get(&p))
    }

    /// The objects hit, in Java's order.
    pub fn hits(&self) -> impl Iterator<Item = &'a Entity> + '_ {
        self.hit_list.iter().filter_map(|id| self.entities.get(id))
    }

    pub fn name(&self, e: &Entity) -> Result<Option<String>, Abort> {
        name(self.data, e)
    }

    /// Java `getPlayerDamageList`: each player's sum, most damage first (equal ones in `HashMap` order), with the
    /// player as Java's sum holds it (`None` when no longer known here).
    pub fn player_damage_list(&self, e: &'a Entity) -> Vec<(&'a DamageRecord, Option<&'a Entity>)> {
        let mut out: Vec<_> = java_hash_order_computed(&e.damage_player_order)
            .into_iter()
            .map(|id| (&e.damage_player[&id], self.entities.get(&id)))
            .collect();
        out.sort_by_key(|(d, _)| std::cmp::Reverse(d.damage));
        out
    }

    /// Java `damageTaken(entity)`: the damage `who` took (and how many hits) between the first and last hit on
    /// `during`, both left out.
    pub fn damage_taken(who: &Entity, during: Option<&Entity>) -> (i32, i32) {
        let (first, last) = during.map_or((0, i64::MAX), |e| (e.first_damage, e.last_damage));
        let mut out = (0i32, 0i32);
        for d in &who.damage_list {
            if first < d.time && d.time < last {
                out.0 = out.0.wrapping_add(d.damage);
                out.1 += 1;
            }
        }
        out
    }

    /// The object type behind a hit's owner (Java `d.owner.objectType`).
    pub fn owner_type(&self, d: &DamageRecord) -> Option<i32> {
        let o = d.owner?;
        Some(self.entities.get(&o).map_or(d.owner_object_type, |e| e.object_type))
    }
}

impl World {
    /// The fight data of the map you are in.
    pub fn fight(&self) -> Fight<'_> {
        Fight {
            data: &self.data,
            map: self.map.as_ref(),
            entities: &self.entities,
            hit_list: self.hit_list(),
            deaths: &self.death_notifications,
            player: self.player,
            dungeon_time: self.dungeon_time_ms(),
        }
    }

    /// The objects hit in this map, in Java's order.
    pub(crate) fn hit_list(&self) -> Vec<i32> {
        java_hash_order(&self.hit_order)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_order_as_java() {
        // as Java's jshell printed the keySet() of HashMaps filled in this order
        assert_eq!(java_hash_order(&[40, 3, 17, 1_000_000, -5, 19]), vec![17, 3, 19, -5, 40, 1_000_000]);
        // 13 keys: 32 buckets
        let keys: Vec<i32> = (0..13).map(|i| 31 - i).collect();
        let mut want = keys.clone();
        want.sort();
        assert_eq!(java_hash_order(&keys), want);
        // the high bits spread into the bucket
        assert_eq!(java_hash_order(&[1_048_593, 17, 65_553, 33]), vec![65_553, 1_048_593, 17, 33]);
        assert_eq!(java_string_hash("char:506"), "char:506".chars().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32)));
        assert_eq!(java_string_hash("hello"), 99_162_322);
        assert_eq!((java_buckets_copied(5), java_buckets_copied(6), java_buckets_copied(19), java_buckets_copied(0)), (8, 8, 32, 1));
        assert_eq!((java_buckets_grown(12), java_buckets_grown(13)), (16, 32));
        assert_eq!(
            java_hash_order(&keys_for_put()),
            vec![2, 66, 130, 194, 3, 14866, 18, 82, 146, 210, 19, 34, 98, 162, 35, 5298, 50, 114, 178],
            "put: at the end of the bucket"
        );
        let ops = [MapOp::Put(1, 1), MapOp::Put(17, 17), MapOp::Removed(1, 1), MapOp::Put(1, 1)];
        assert_eq!(java_order_after(&ops), vec![17, 1], "a key put again goes to the end");
        // computeIfAbsent: new keys at the head of their bucket; a bucket of 8 grows the table (as jshell printed)
        assert_eq!(java_hash_order_computed(&[5298, 14866]), vec![14866, 5298]);
        let keys = [5298, 14866, 18, 34, 50, 2, 66, 82, 98, 114, 130, 146, 162, 178, 194, 210, 3, 19, 35];
        assert_eq!(
            java_hash_order_computed(&keys),
            vec![194, 130, 66, 2, 3, 210, 146, 82, 18, 14866, 19, 162, 98, 34, 35, 178, 114, 50, 5298]
        );
    }

    fn keys_for_put() -> Vec<i32> {
        vec![5298, 14866, 18, 34, 50, 2, 66, 82, 98, 114, 130, 146, 162, 178, 194, 210, 3, 19, 35]
    }
}
