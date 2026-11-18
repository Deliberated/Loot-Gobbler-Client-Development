//! Damage (Java: the damage parts of `TomatoData` and `Entity`, `Projectile`, `Damage`, `RNG`,
//! `AbilityScalingManager.calculateStatBonus`, `CrucibleBonusManager`): shots, hits, damage dealt per player and
//! taken by you.
//!
//! Your own weapon's damage is rolled as the game rolls it: with the map's random number generator (seeded by the
//! map), advanced by each of your shots. Other players' damage is what the server sends.

use super::*;
use crate::json::{self, Json, Thrown};
use lg_protocol::packets::{Aoe, Damage, EnemyHit, EnemyShoot, GroundDamage, PlayerHit, PlayerShoot, ServerPlayerShoot};
use std::cell::RefCell;
use std::rc::Rc;

/// The game's random number generator (Java: `util.RNG`), a Park-Miller generator.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rng {
    pub seed: i64,
}

impl Rng {
    pub fn new(seed: i64) -> Rng {
        Rng { seed }
    }

    /// The next number (Java: `next()`).
    pub fn roll(&mut self) -> i64 {
        let right16 = (self.seed >> 16).wrapping_mul(0x41A7);
        let left = (self.seed & 0xFFFF).wrapping_mul(0x41A7);
        let right15 = right16 >> 15;
        let right_and = (right16 & 0x7FFF) << 16;
        let sum = left.wrapping_add(right_and).wrapping_add(right15);
        let mut value = i64::from((sum as i32).wrapping_sub(0x7FFF_FFFF) as u32);
        if sum <= 0x7FFF_FFFF {
            value = sum;
        }
        self.seed = value;
        value
    }
}

/// A shot, as Java's `Projectile`. Shared: Java keeps one object in several places (the bullet array, the map by
/// shooter) and clears the ones in the array on a map change.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Projectile {
    pub damage: i32,
    pub summoner_id: i32,
    pub armor_piercing: bool,
    /// The item (or projectile) behind it, -1 if not known.
    pub container_type: i32,
    pub origin_ability_item: i32,
    /// The owner's scaling stat when it was fired; `i32::MIN` if not known.
    pub origin_scaling_stat: i32,
}

pub type Shared = Rc<RefCell<Projectile>>;

impl Projectile {
    pub fn new(damage: i32, armor_piercing: bool) -> Projectile {
        Projectile { damage, summoner_id: 0, armor_piercing, container_type: -1, origin_ability_item: -1, origin_scaling_stat: i32::MIN }
    }

    fn clear(&mut self) {
        self.damage = 0;
        self.armor_piercing = false;
        self.origin_ability_item = -1;
        self.origin_scaling_stat = i32::MIN;
    }
}

/// One hit, or the sum of one player's hits on an object (Java: `Damage`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DamageRecord {
    /// Who did it (an object id), if known.
    pub owner: Option<i32>,
    /// What did it when no owner is known: the object behind an area attack, minus the tile type for ground damage.
    pub source_type: i32,
    pub owner_object_type: i32,
    pub owner_name: Option<String>,
    /// The owner's weapon, ability, armor, ring then.
    pub owner_inventory: Option<[i32; 4]>,
    /// The enchant codes on them then.
    pub owner_enchants: Option<[String; 4]>,
    pub owner_scaling_stat: Option<u8>,
    pub owner_scaling_value: Option<i32>,
    pub damage: i32,
    /// Damage while the boss could not be hurt, and how many hits.
    pub counter_damage: i32,
    pub counter_hits: i32,
    pub oryx_guard: bool,
    pub chancellor_dammah: bool,
    pub walled_garden_reflectors: bool,
}

/// What an object's hp was when a player who hit it left the view (Java: `PlayerRemoved`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dropped {
    pub hp: i32,
    pub max: i32,
    pub name: Option<String>,
}

/// Crucible damage and loot bonuses (Java: `CrucibleBonusManager`, global there: kept across maps).
#[derive(Debug, Clone, Default)]
pub struct Crucible {
    pub damage: HashMap<String, f64>,
    pub loot: HashMap<String, f64>,
    /// Your crucible ids (stats 128 and 155).
    pub id128: Option<String>,
    pub id155: Option<String>,
}

impl Crucible {
    fn multiplier_of(&self, id: &Option<String>) -> f64 {
        id.as_ref().and_then(|i| self.damage.get(i)).copied().unwrap_or(1.0)
    }

    /// Java `getPlayerDamageMultiplier`.
    pub fn multiplier(&self) -> f64 {
        self.multiplier_of(&self.id128) * self.multiplier_of(&self.id155)
    }

    /// Java `updatePlayerCrucibleBonus`.
    fn update(&mut self, me: &Entity) {
        let id = |t: u8| me.text(t).filter(|s| !s.is_empty()).map(str::to_string);
        self.id128 = id(128);
        self.id155 = id(155);
    }

    /// Java `processCrucibleResponse`: where Java throws partway, what was done stays.
    fn response(&mut self, jsons: &[String]) {
        if jsons.is_empty() {
            return;
        }
        let Ok(Json::Array(all)) = json::parse(&format!("[{}]", jsons.join(","))) else { return };
        self.damage.clear();
        let _ = self.read(&all);
    }

    fn read(&mut self, all: &[Json]) -> Result<(), Thrown> {
        for c in all {
            if !matches!(c, Json::Object(_)) {
                return Err(Thrown);
            }
            if let Some(Json::Array(items)) = c.get("array") {
                for item in items {
                    if !matches!(item, Json::Object(_)) {
                        return Err(Thrown);
                    }
                    if let Some(id) = item.get("id") {
                        let id = id.as_string()?;
                        self.type5(item, &id)?;
                        if let Some(l) = find_bonus(item, 1) {
                            self.loot.insert(id, l);
                        }
                    }
                }
            }
        }
        Ok(())
    }

    /// Java `findAndStoreType5Bonuses`: the first damage bonus (type 5) anywhere inside.
    fn type5(&mut self, e: &Json, id: &str) -> Result<(), Thrown> {
        if self.damage.contains_key(id) {
            return Ok(());
        }
        match e {
            Json::Object(o) => {
                if let Some(Json::Array(bonuses)) = e.get("bonuses") {
                    for b in bonuses {
                        if matches!(b, Json::Object(_))
                            && let Some(t) = b.get("type")
                            && t.as_int()? == 5
                            && let Some(a) = b.get("amount")
                        {
                            self.damage.insert(id.to_string(), a.as_double()?);
                            return Ok(());
                        }
                    }
                }
                for (_, v) in o {
                    self.type5(v, id)?;
                    if self.damage.contains_key(id) {
                        return Ok(());
                    }
                }
            }
            Json::Array(a) => {
                for v in a {
                    self.type5(v, id)?;
                    if self.damage.contains_key(id) {
                        return Ok(());
                    }
                }
            }
            _ => {}
        }
        Ok(())
    }
}

/// Java `findBonus`: the first bonus of this type anywhere inside (unreadable ones skipped).
fn find_bonus(e: &Json, kind: i32) -> Option<f64> {
    match e {
        Json::Array(a) => a.iter().find_map(|x| find_bonus(x, kind)),
        Json::Object(o) => {
            if let Some(Json::Array(bonuses)) = e.get("bonuses") {
                for b in bonuses {
                    if !matches!(b, Json::Object(_)) {
                        continue;
                    }
                    let found = (|| -> Result<Option<f64>, Thrown> {
                        if let Some(t) = b.get("type")
                            && t.as_int()? == kind
                            && let Some(a) = b.get("amount")
                        {
                            return Ok(Some(a.as_double()?));
                        }
                        Ok(None)
                    })();
                    if let Ok(Some(v)) = found {
                        return Some(v);
                    }
                }
            }
            o.iter().filter(|(k, _)| k != "bonuses").find_map(|(_, v)| find_bonus(v, kind))
        }
        _ => None,
    }
}

const ORYX_THE_MAD_GOD: i32 = 45363;
const ORYX_GUARD_ANIMATION: i32 = -935_464_302;
const ORYX_GUARD_EXALTED_ANIMATION: i32 = -918_686_683;
const CHANCELLOR_DAMMAH: i32 = 9635;
const FORGOTTEN_KING: i32 = 29039;
const FORGOTTEN_KING_REFLECTOR_ANIMATION: i32 = -123_818_367;
const GUARDED_PHASE: [i32; 3] = [33656, 33557, 33572];
/// What Chancellor Dammah says when countered.
pub const DAMMAH_COUNTERED: &str = "I SAID DO NOT INTERRUPT ME! For this I shall hasten your end!";
const BULLETS: usize = 512;

/// The key Java files a shot under: its shooter and bullet number.
fn key(owner: i32, index: i32) -> i64 {
    (i64::from(owner) << 32) | i64::from(index as u32)
}

/// Java `Projectile.damageWithDefense`.
pub fn damage_with_defense(damage: i32, armor_piercing: bool, defense: i32, conditions: [i32; 2]) -> i32 {
    if damage == 0 {
        return 0;
    }
    let mut def = defense;
    if armor_piercing || conditions[0] & 0x400_0000 != 0 {
        def = 0;
    } else if conditions[0] & 0x200_0000 != 0 {
        def = (f64::from(def) * 1.5) as i32;
    }
    if conditions[1] & 0x2_0000 != 0 {
        def = def.wrapping_sub(20);
    }
    let min = damage.wrapping_mul(2) / 20;
    let mut dmg = min.max(damage.wrapping_sub(def));
    if conditions[0] & 0x100_0000 != 0 {
        dmg = 0;
    }
    if conditions[1] & 0x8 != 0 {
        dmg = (f64::from(dmg) * 0.9) as i32;
    }
    if conditions[1] & 0x40 != 0 {
        dmg = (f64::from(dmg) * 1.25) as i32;
    }
    dmg
}

/// Slot types of weapons (Java: `Projectile.isMainWeapon`).
fn main_weapon(slot: i32) -> bool {
    matches!(slot, 1 | 2 | 3 | 8 | 17 | 24)
}

/// The damage state kept by the world, beside the objects.
#[derive(Debug, Default)]
pub struct DamageState {
    pub rng: Option<Rng>,
    pub(crate) bullets: Vec<Option<Shared>>,
    pub(crate) by_shooter: HashMap<i64, Shared>,
    pub(crate) enemy_shots: HashMap<i64, Projectile>,
    pub(crate) minion_owner: HashMap<i32, i32>,
    pub crucible: Crucible,
}

impl DamageState {
    pub fn new() -> DamageState {
        DamageState { bullets: vec![None; BULLETS], ..Default::default() }
    }

    /// On a map change (Java `clear` and `setNewRealm`).
    pub(crate) fn new_map(&mut self, seed: i32) {
        for p in self.bullets.iter().flatten() {
            p.borrow_mut().clear();
        }
        self.enemy_shots.clear();
        self.minion_owner.clear();
        self.rng = Some(Rng::new(i64::from(seed)));
    }
}

impl World {
    /// Java `Entity.name()`: a player's name stat (before the first comma), else the object's name. Java throws for a
    /// name stat of only commas.
    pub(crate) fn display_name(&self, e: &Entity) -> Result<Option<String>, Abort> {
        if self.data.is_player_class(e.object_type)
            && let Some(n) = e.text(stats::NAME)
        {
            if !n.is_empty() && n.chars().all(|c| c == ',') {
                return Err(Abort("a name of only commas"));
            }
            return Ok(Some(n.split(',').next().unwrap_or("").to_string()));
        }
        Ok(e.name.clone())
    }

    fn class_max(&self, e: &Entity, attack: bool) -> i32 {
        self.data.class(e.object_type).map_or(0, |c| c.max_stats[if attack { 2 } else { 3 }])
    }

    /// Java `playerStatsMultiplier`.
    fn stats_multiplier(&self, e: &Entity) -> f32 {
        let condition = e.value(stats::CONDITION).unwrap_or(0);
        let attack = e.value(stats::ATTACK).unwrap_or_else(|| self.class_max(e, true));
        let exalt = e.value(stats::EXALTATION_BONUS_DAMAGE).unwrap_or(1000) as f32 / 1000.0;
        if condition & 0x4 != 0 {
            return 0.5;
        }
        let mut number = attack.wrapping_add(25) as f32 * 0.02;
        if condition & 0x4_0000 != 0 {
            number = (f64::from(number) * 1.25) as f32;
        }
        if self.player == Some(e.id) {
            number = (f64::from(number) * self.damage.crucible.multiplier()) as f32;
        }
        number * exalt
    }

    /// Java `AbilityScalingManager.getStatDamageMultiplier`: an ability's enchants.
    fn stat_damage_multiplier(&self, player: &Entity, weapon: i32) -> Result<f32, Abort> {
        if player.value(stats::INVENTORY_1) == Some(weapon) {
            let codes = lg_gamedata::enchants::slot_codes(player.text(stats::UNIQUE_DATA));
            if !codes[1].is_empty() {
                return lg_gamedata::enchants::totals(&self.data.enchants, &codes[1])
                    .map(|t| t.stat_damage)
                    .map_err(|_| Abort("an enchant code Java cannot read"));
            }
        }
        Ok(1.0)
    }

    /// Java `calculateStatBonus`.
    fn stat_bonus(&self, weapon: i32, snapshot: Option<i32>, player: Option<&Entity>) -> Result<i32, Abort> {
        let Some(s) = self.data.abilities.scaling(weapon) else { return Ok(0) };
        let Some(player) = player else { return Ok(0) };
        if s.damage_per_stat.is_nan() || s.damage_per_stat <= 0.0 {
            return Ok(0);
        }
        let Some(value) = snapshot.or_else(|| player.value(s.stat)) else { return Ok(0) };
        if value <= s.min {
            return Ok(0);
        }
        let base = value.wrapping_sub(s.min) as f32 * s.damage_per_stat;
        Ok((base * self.stat_damage_multiplier(player, weapon)?) as i32)
    }

    /// Java `IdToAsset.getIdProjectileArmorPierces`, with Java's errors swallowed as the callers do.
    fn pierces(&self, object_type: i32, projectile: i32) -> bool {
        let Some(o) = self.data.object(object_type) else { return false };
        usize::try_from(projectile).ok().and_then(|i| o.projectiles.get(i)).is_some_and(|p| p.armor_piercing)
    }

    /// Java `new Projectile(rng, player, weaponId, projectileId)`: your own shot, its damage rolled.
    fn your_shot(&mut self, weapon: i32, projectile_id: i8) -> Result<Projectile, Abort> {
        let empty = Projectile::new(0, false);
        let Some(me) = self.me().cloned() else { return Ok(empty) };
        let pid = if projectile_id == -1 { 0 } else { i32::from(projectile_id) };
        let (min, max, ap, slot) = match self.data.object(weapon) {
            None => (-1, -1, false, 0),
            Some(o) => match usize::try_from(pid).ok().and_then(|i| o.projectiles.get(i)) {
                Some(p) => (p.min_damage, p.max_damage, p.armor_piercing, o.projectiles[0].slot_type),
                // Java catches the out-of-range projectile, then reads the first one again: none throws on
                None if o.projectiles.is_empty() => return Err(Abort("a weapon without projectiles")),
                None => return Ok(empty),
            },
        };
        let mut dmg = if min == max {
            min
        } else if let Some(rng) = self.damage.rng.as_mut() {
            let r = rng.roll();
            (i64::from(min) + r % i64::from(max.wrapping_sub(min))) as i32
        } else {
            min.wrapping_add(max) / 2 // the roll needs the map's seed: on average this is right
        };
        if !main_weapon(slot) && self.data.abilities.has_scaling(weapon) {
            dmg = dmg.wrapping_add(self.stat_bonus(weapon, None, Some(&me))?);
        }
        let f = if main_weapon(slot) { self.stats_multiplier(&me) } else { 1.0 };
        Ok(Projectile { damage: (dmg as f32 * f) as i32, armor_piercing: ap, ..empty })
    }

    /// Java `playerShoot`.
    pub(crate) fn player_shoot(&mut self, p: &PlayerShoot) -> Result<(), Abort> {
        let shot = Rc::new(RefCell::new(self.your_shot(i32::from(p.weapon_id), p.projectile_id)?));
        if let Ok(i) = usize::try_from(p.bullet_id)
            && i < BULLETS
        {
            self.damage.bullets[i] = Some(shot.clone());
        }
        if let Some(me) = self.player {
            self.damage.by_shooter.insert(key(me, i32::from(p.bullet_id)), shot);
        }
        Ok(())
    }

    /// The ability item and scaling stat of a shot's owner when the server fired it.
    fn origin(&self, owner: i32, shot: &mut Projectile, container: i32) {
        let Some(o) = self.player_list.contains(&owner).then(|| self.entities.get(&owner)).flatten() else { return };
        if let Some(item) = o.value(stats::INVENTORY_1) {
            shot.origin_ability_item = item;
            if let Some(s) = self.data.abilities.scaling(container)
                && let Some(v) = o.value(s.stat)
            {
                shot.origin_scaling_stat = v;
            }
        }
    }

    /// Java `serverPlayerShoot`: a shot the server fires for a player (abilities, pets, traps).
    pub(crate) fn server_player_shoot(&mut self, p: &ServerPlayerShoot) {
        if p.summoner_id != 0 && p.owner_id != 0 {
            self.damage.minion_owner.insert(p.owner_id, p.summoner_id);
        }
        let mut shot = Projectile::new(i32::from(p.damage), self.pierces(p.container_type, i32::from(p.bullet_type)));
        shot.summoner_id = p.summoner_id;
        shot.container_type = p.container_type;
        let bullet = i32::from(p.bullet_id);
        if p.bullet_count > 1 {
            let shot = Rc::new(RefCell::new(shot));
            for j in bullet..bullet + i32::from(p.bullet_count) {
                let index = j % 256 + 256;
                if (0..BULLETS as i32).contains(&index) {
                    self.damage.bullets[index as usize] = Some(shot.clone());
                }
                self.damage.by_shooter.insert(key(p.owner_id, index), shot.clone());
            }
        } else if bullet > 255 && bullet < BULLETS as i32 {
            self.origin(p.owner_id, &mut shot, p.container_type);
            let shot = Rc::new(RefCell::new(shot));
            self.damage.bullets[bullet as usize] = Some(shot.clone());
            self.damage.by_shooter.insert(key(p.owner_id, bullet), shot);
        } else {
            let index = bullet % 256 + 256;
            self.origin(p.owner_id, &mut shot, p.container_type);
            let shot = Rc::new(RefCell::new(shot));
            if (0..BULLETS as i32).contains(&index) {
                self.damage.bullets[index as usize] = Some(shot.clone());
            }
            self.damage.by_shooter.insert(key(p.owner_id, index), shot);
        }
    }

    /// Java `Damage` constructors' `setInv`: the owner as it is now.
    fn record_of(&self, owner: Option<i32>) -> Result<DamageRecord, Abort> {
        let mut r = DamageRecord { owner, owner_object_type: -1, ..Default::default() };
        if let Some(o) = owner.and_then(|id| self.entities.get(&id)) {
            r.owner_object_type = o.object_type;
            r.owner_name = self.display_name(o)?;
            let inv: Vec<i32> = (stats::INVENTORY_0..stats::INVENTORY_0 + 4).filter_map(|t| o.value(t)).collect();
            r.owner_inventory = Some(inv.try_into().unwrap_or([-1; 4]));
            r.owner_enchants = Some(lg_gamedata::enchants::slot_codes(o.text(stats::UNIQUE_DATA)));
        }
        Ok(r)
    }

    /// A hit on an object: the record with the owner's scaling stat, the boss phase flags, into its lists (Java:
    /// `new Damage(...)`, `bossPhaseDamage`, `addPlayerDmg`).
    fn hit(&mut self, target: i32, owner: Option<i32>, shot: &Projectile, dmg: i32) -> Result<(), Abort> {
        let mut r = self.record_of(owner)?;
        r.damage = dmg;
        if shot.container_type != -1
            && let Some(o) = owner.and_then(|id| self.entities.get(&id))
            && let Some(s) = self.data.abilities.scaling(shot.container_type)
        {
            r.owner_scaling_stat = Some(s.stat);
            r.owner_scaling_value = o.value(s.stat);
        }
        let guarded = self.entities.values().any(|e| GUARDED_PHASE.contains(&e.object_type));
        let t = &self.entities[&target];
        let animation = t.value(stats::ANIMATION);
        r.oryx_guard = t.object_type == ORYX_THE_MAD_GOD
            && (animation == Some(ORYX_GUARD_ANIMATION) || animation == Some(ORYX_GUARD_EXALTED_ANIMATION));
        r.walled_garden_reflectors = t.object_type == FORGOTTEN_KING && animation == Some(FORGOTTEN_KING_REFLECTOR_ANIMATION) && guarded;
        r.chancellor_dammah = t.object_type == CHANCELLOR_DAMMAH && !t.dammah_countered;
        // the sum per player starts from the owner as at the first hit
        let first = match owner {
            Some(o) if !self.entities[&target].damage_player.contains_key(&o) => Some(self.record_of(Some(o))?),
            _ => None,
        };
        let t = self.entities.get_mut(&target).expect("hit object");
        t.damage_list.push(r.clone());
        if let Some(o) = owner {
            let sum = t.damage_player.entry(o).or_insert_with(|| first.expect("new sum"));
            sum.damage = sum.damage.wrapping_add(r.damage);
            if r.oryx_guard || r.walled_garden_reflectors || r.chancellor_dammah {
                sum.counter_damage = sum.counter_damage.wrapping_add(r.damage);
                sum.counter_hits += 1;
                sum.oryx_guard = r.oryx_guard;
                sum.walled_garden_reflectors = r.walled_garden_reflectors;
                sum.chancellor_dammah = r.chancellor_dammah;
            }
        }
        Ok(())
    }

    /// Java `Entity.userProjectileHit`: a player's shot hit an object; the damage worked out against its defense.
    fn shot_hit(&mut self, target: i32, attacker: Option<i32>, shot: Option<&Shared>) -> Result<(), Abort> {
        let Some(p) = shot.map(|s| *s.borrow()) else { return Ok(()) };
        if p.damage == 0 {
            return Ok(());
        }
        let t = &self.entities[&target];
        let conditions = [t.value(stats::CONDITION).unwrap_or(0), t.value(stats::NEW_CONDITION).unwrap_or(0)];
        let defense = t.value(stats::DEFENSE).unwrap_or(0);
        let attacker_entity = attacker.and_then(|a| self.entities.get(&a));
        let local = attacker_entity.is_some_and(|a| a.is_user);
        let has_container = local && p.container_type != -1;
        let dmg = if p.summoner_id != 0 && !has_container {
            if p.armor_piercing { p.damage } else { damage_with_defense(p.damage, false, defense, conditions) }
        } else {
            let mut base = p.damage;
            if p.container_type != -1 && self.data.abilities.has_scaling(p.container_type) {
                let s = self.data.abilities.scaling(p.container_type).expect("has scaling");
                let snapshot =
                    attacker_entity.and_then(|a| a.value(s.stat)).or((p.origin_scaling_stat != i32::MIN).then_some(p.origin_scaling_stat));
                base = p.damage.wrapping_add(self.stat_bonus(p.container_type, snapshot, attacker_entity)?);
            }
            damage_with_defense(base, p.armor_piercing, defense, conditions)
        };
        if dmg > 0 {
            self.hit(target, attacker, &p, dmg)?;
        }
        Ok(())
    }

    /// Java `enemtyHit`: your shot hit an object.
    pub(crate) fn enemy_hit(&mut self, h: &EnemyHit) -> Result<(), Abort> {
        let bullet = i32::from(h.bullet_id);
        let wrapped = bullet % 256 + 256;
        let shot = self
            .damage
            .by_shooter
            .get(&key(h.shooter_id, bullet))
            .cloned()
            .or_else(|| (0..BULLETS as i32).contains(&wrapped).then(|| self.damage.bullets[wrapped as usize].clone()).flatten())
            .or_else(|| (0..BULLETS as i32).contains(&bullet).then(|| self.damage.bullets[bullet as usize].clone()).flatten());
        self.entity(h.target_id);
        let shooter = match &shot {
            Some(s) if s.borrow().summoner_id != 0 => s.borrow().summoner_id,
            _ => h.shooter_id,
        };
        let attacker = self.player_list.contains(&shooter).then_some(shooter);
        self.shot_hit(h.target_id, attacker, shot.as_ref())?;
        self.first_hit(h.target_id, attacker);
        self.entity(h.target_id).fought = true;
        Ok(())
    }

    /// Java `damage`: an object took damage the server tells (another player's, a pet's...).
    pub(crate) fn damage_packet(&mut self, d: &Damage) -> Result<(), Abort> {
        self.entity(d.target_id);
        let attacker = if self.player_list.contains(&d.object_id) {
            Some(d.object_id)
        } else if self.entities.contains_key(&d.object_id) {
            // a pet, minion or trap counts for its owner, if the owner is known
            self.damage.minion_owner.get(&d.object_id).copied().filter(|o| self.player_list.contains(o))
        } else {
            None
        };
        if d.damage_amount > 0 {
            let shot = Projectile::new(i32::from(d.damage_amount), false);
            self.hit(d.target_id, attacker, &shot, shot.damage)?;
            self.first_hit(d.target_id, attacker);
        }
        self.entity(d.target_id).fought = true;
        Ok(())
    }

    /// The first hit on an object: it is on the hit list, and if it was yours, counted for the dungeon.
    fn first_hit(&mut self, target: i32, attacker: Option<i32>) {
        if self.entity_hit_list.insert(target)
            && attacker.and_then(|a| self.entities.get(&a)).is_some_and(|a| a.is_user)
            && let Some(m) = &self.map
        {
            let (name, t) = (m.name.clone(), self.entities[&target].object_type);
            self.dungeon_stats.entity_damage(&name, t);
        }
    }

    /// Java `calculatePlayerDmg`: damage to you after your defense. (Java reads the newer conditions from the old
    /// condition stat here; so does this.)
    fn damage_to_you(&self, me: &Entity, dmg: i32, armor_piercing: bool) -> i32 {
        let mut def = me.value(stats::DEFENSE).unwrap_or_else(|| self.class_max(me, false));
        let c = me.value(stats::CONDITION).unwrap_or(0);
        if c & 0x100_0000 != 0 {
            return 0;
        }
        if armor_piercing || c & 0x400_0000 != 0 {
            def = 0;
        } else if c & 0x200_0000 != 0 {
            def = (f64::from(def) * 1.5) as i32;
        }
        if c & 0x2_0000 != 0 {
            def = def.wrapping_sub(20);
        }
        let mut damage = (dmg as f32 * 0.1).max(dmg.wrapping_sub(def) as f32);
        if c & 0x8 != 0 {
            damage = ((damage * 0.9) as i32) as f32;
        }
        if c & 0x40 != 0 {
            damage = ((damage * 1.25) as i32) as f32;
        }
        damage as i32
    }

    /// Java `userDamageTaken`.
    fn taken(&mut self, from: Option<i32>, shot: Projectile, source_type: i32) {
        let Some(id) = self.player else { return };
        let dmg = self.damage_to_you(&self.entities[&id], shot.damage, shot.armor_piercing);
        let r = DamageRecord { owner: from, source_type, damage: dmg, ..Default::default() };
        self.entities.get_mut(&id).expect("you").damage_list.push(r);
    }

    /// Java `userDamage`: an enemy shot hit you.
    pub(crate) fn player_hit(&mut self, h: &PlayerHit) {
        if self.player.is_none() {
            return;
        }
        let from = self.entities.contains_key(&h.object_id).then_some(h.object_id);
        let k = i64::from(h.object_id) + (i64::from(h.bullet_id) << 24);
        if let Some(&shot) = self.damage.enemy_shots.get(&k) {
            self.taken(from, shot, 0);
        }
    }

    /// Java `enemyProjectile`: an enemy fires (kept to know the damage when it hits you).
    pub(crate) fn enemy_shoot(&mut self, s: &EnemyShoot) {
        let ap = self.entities.get(&s.owner_id).is_some_and(|e| self.pierces(e.object_type, i32::from(s.bullet_type)));
        for i in 0..i32::from(s.num_shots) {
            let k = i64::from(s.owner_id) + (i64::from(i32::from(s.bullet_id).wrapping_add(i)) << 24);
            self.damage.enemy_shots.insert(k, Projectile::new(i32::from(s.damage), ap));
        }
    }

    /// Java `aoeDamage`: an area attack lands; it hits you if you are inside.
    pub(crate) fn aoe(&mut self, a: &Aoe) -> Result<(), Abort> {
        let Some(me) = self.me() else { return Ok(()) };
        let pos = me.pos.ok_or(Abort("area damage before your position is known"))?;
        let (x, y) = (f64::from(pos.x - a.pos.x), f64::from(pos.y - a.pos.y));
        if x * x + y * y < f64::from(a.radius * a.radius) {
            self.taken(None, Projectile::new(i32::from(a.damage), a.armor_piercing), i32::from(a.orig_type));
        }
        Ok(())
    }

    /// Java `groundDamage`: the tile you stand on hurts.
    pub(crate) fn ground(&mut self, g: &GroundDamage) -> Result<(), Abort> {
        if self.player.is_none() {
            return Ok(());
        }
        let (x, y) = (g.position.x as i32, g.position.y as i32); // Java's (int): NaN is 0
        if !(0..MAP_SIZE as i32).contains(&x) || !(0..MAP_SIZE as i32).contains(&y) {
            return Err(Abort("ground damage outside the map array"));
        }
        let tile = i32::from(self.tile(x as usize, y as usize));
        let dmg = self.data.tiles.get(&tile).map_or(-1, |t| t.damage);
        self.taken(None, Projectile::new(dmg, true), if tile > 0 { -tile } else { 0 });
        Ok(())
    }

    /// Java `updatePlayerCrucibleBonus`, after ticks and updates.
    pub(crate) fn crucible_update(&mut self) {
        if let Some(me) = self.player.and_then(|id| self.entities.get(&id)) {
            let me = me.clone();
            self.damage.crucible.update(&me);
        }
    }

    pub(crate) fn crucible_response(&mut self, jsons: &[String]) {
        self.damage.crucible.response(jsons);
    }

    /// A player in view left it: every object they hit notes its hp then (Java `addPlayerDrop`).
    pub(crate) fn player_left(&mut self, dropped: i32) -> Result<(), Abort> {
        let hit: Vec<i32> = self.entity_hit_list.iter().copied().filter(|k| !self.dropped.contains(k)).collect();
        for k in hit {
            let Some(e) = self.entities.get(&k) else { continue };
            let d = Dropped { hp: e.value(stats::HP).unwrap_or(0), max: e.value(stats::MAX_HP).unwrap_or(0), name: self.display_name(e)? };
            self.entities.get_mut(&k).expect("hit object").player_dropped.insert(dropped, d);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rng_as_java() {
        // Java: new RNG(12345): 207482415, 1790989824, 2035175616
        let mut r = Rng::new(12345);
        assert_eq!([r.roll(), r.roll(), r.roll()], [207_482_415, 1_790_989_824, 2_035_175_616]);
        let mut r = Rng::new(-7);
        let v = r.roll();
        assert!(v >= 0);
    }

    #[test]
    fn defense() {
        assert_eq!(damage_with_defense(100, false, 30, [0, 0]), 70);
        assert_eq!(damage_with_defense(100, false, 95, [0, 0]), 10, "at least a tenth");
        assert_eq!(damage_with_defense(100, true, 95, [0, 0]), 100);
        assert_eq!(damage_with_defense(100, false, 30, [0x200_0000, 0]), 55, "armored: 1.5 times the defense");
        assert_eq!(damage_with_defense(100, false, 30, [0x100_0000, 0]), 0, "invulnerable");
        assert_eq!(damage_with_defense(100, false, 30, [0, 0x40]), 87, "cursed");
    }

    #[test]
    fn crucible_bonuses() {
        let mut c = Crucible::default();
        c.response(&[r#"{"array":[{"id":"A","x":{"bonuses":[{"type":5,"amount":1.2},{"type":1,"amount":0.5}]}},{"id":7,"bonuses":[{"type":"5","amount":"1.5"}]}]}"#.into()]);
        assert_eq!((c.damage.get("A"), c.damage.get("7"), c.loot.get("A")), (Some(&1.2), Some(&1.5), Some(&0.5)));
        c.id128 = Some("A".into());
        c.id155 = Some("7".into());
        assert!((c.multiplier() - 1.8).abs() < 1e-12);
        // a type Java cannot read stops reading, keeping what came before
        c.response(&[r#"{"array":[{"id":"B","bonuses":[{"type":5,"amount":2}]},{"id":"C","bonuses":[{"type":true}]},{"id":"D","bonuses":[{"type":5,"amount":3}]}]}"#.into()]);
        assert_eq!((c.damage.get("B"), c.damage.get("D"), c.damage.get("A")), (Some(&2.0), None, None));
    }
}
