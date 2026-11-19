//! What was fought in a map, as the app's run history and dungeon visits read it (Java: `TomatoData`'s hit list
//! and death notifications, `Entity`'s damage lists): of the map you are in ([`World::fight`]), or of the one just
//! left ([`LeftMap`], taken before the world is cleared).

use crate::damage::DamageRecord;
use crate::{Abort, Entity, Map, World, stats};
use lg_gamedata::GameData;
use lg_protocol::packets::Notification;
use std::collections::HashMap;

/// The order Java's `HashMap<Integer, ...>` walks keys put in this order (none removed): by bucket, then in the
/// order put. The table starts at 16 buckets and doubles when more than three quarters are used. (A bucket of 8
/// or more becomes a tree, which can change the order; that needs 8 ids in one bucket of a table of 64 or more,
/// and is not followed.)
pub fn java_hash_order(keys: &[i32]) -> Vec<i32> {
    let mut buckets: usize = 16;
    while keys.len() > buckets / 4 * 3 {
        buckets *= 2;
    }
    let bucket = |k: i32| {
        let h = k as u32;
        ((h ^ (h >> 16)) as usize) & (buckets - 1)
    };
    let mut out = keys.to_vec();
    out.sort_by_key(|&k| bucket(k)); // stable: the order put within a bucket
    out
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
        let mut out: Vec<_> =
            java_hash_order(&e.damage_player_order).into_iter().map(|id| (&e.damage_player[&id], self.entities.get(&id))).collect();
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
    }
}
