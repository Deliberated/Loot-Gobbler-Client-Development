//! The game world as the packets tell it (Java: the core of `TomatoData` and `Entity`): the map you are in, every
//! object in view with its stats, which one is you, the players, loot bags and objects that left the view.
//!
//! It is fed the packets the app feeds `TomatoData` ([`FED`]), and follows Java step by step, including where Java
//! throws partway through a packet: the rest of that packet is then not handled ([`Abort`]), and the world stays as
//! far as it got. The parts of `TomatoData` that are not ported yet (damage, loot attribution, the app's own records)
//! leave the world untouched.
//!
//! When the sniffer starts inside a map, the game never says which object is you nor which map it is: you are found
//! by the stats only your own character gets, by standing where your client said it moved, or by using your ability;
//! the map by the enemies coming into view.

pub mod damage;
pub mod fight;
mod json;
pub mod loot;
pub mod stats;

use lg_gamedata::GameData;
use lg_protocol::packets::data::{ObjectStatus, Stat, WorldPos};
use lg_protocol::packets::{Decoded, MapInfo, Move, NewTick, Notification, NotificationEffect, Update, UseItem, VaultContent};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fmt::Write as _;

/// The packets the app hands to `TomatoData` (Java: `Tomato.packetRegister`), HELLO left out: on HELLO the app logs
/// in to the game's servers, which this never does.
pub const FED: &[u8] = &[101, 25, 30, 75, 90, 35, 103, 64, 62, 13, 46, 12, 42, 10, 92, 166, 44, 67, 114, 117, 55, 95, 6, 34, 88, 183];

/// What the world tells the app while it handles a packet, where Java's `TomatoData` calls into the app (in the
/// order it does): the app takes them right after the packet, those before a throw too.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// A map is left (Java `setNewRealm`, before `clear`): its fight data, as it was.
    Left(Box<fight::LeftMap>),
    /// The map you are in was told by its enemies (Java `guessMap`): it is in `map` now.
    Guessed,
    /// A new map (Java `setNewRealm`): the vault or not, and its name as the app shows it (`LootEvents.mapName`).
    Entered { vault: bool, map: String },
    /// What the game sent of your character: on entering a map (every stat), and in ticks (only those that
    /// changed); `pack`: your backpack slots stat then (Java `LootEvents.playerStatus`).
    PlayerStatus { char_id: i32, pack: Option<i32>, status: ObjectStatus, entering: bool },
    /// What the game sent of another object (Java `LootEvents.objectStatus`).
    ObjectStatus { id: i32, status: ObjectStatus },
    /// A bag matched to who dropped it (Java `LootEvents.bagDropped`): the bag as it is now, your seasonal stat.
    BagDropped { map: String, bag: Box<Entity>, seasonal: Option<bool> },
}

/// Java threw here: the rest of the packet was not handled.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Abort(pub &'static str);

/// Java's stat array has room for stat types 0 to 199; a higher one throws.
const STAT_SLOTS: usize = 200;
/// Java's map tile array: 2048 by 2048.
const MAP_SIZE: usize = 2048;
/// The Shatters' king fight crystals.
const CRYSTALS: [i32; 4] = [46721, 46771, 29501, 33656];
/// Different enemies of one dungeon needed to say you are in it (a boss alone is enough).
const GUESS_VOTES: usize = 3;
/// Where your client said it moved lately: this many positions are kept.
const OWN_POSITIONS: usize = 30;

/// One object in view (Java: `Entity`).
#[derive(Debug, Clone, PartialEq)]
pub struct Entity {
    pub id: i32,
    /// 0 until the game says (an object first seen in a tick, e.g. you when the sniffer started inside a map).
    pub object_type: i32,
    pub pos: Option<WorldPos>,
    /// By stat type: the latest value the game sent.
    pub stats: Vec<Option<Stat>>,
    pub is_user: bool,
    pub is_player: bool,
    pub char_id: i32,
    /// Life, mana, attack, defense, speed, dexterity, vitality, wisdom without boosts: for players.
    pub base_stats: Option<[i32; 8]>,
    /// The object type's name, when it came into view.
    pub name: Option<String>,
    /// Every hit it took.
    pub damage_list: Vec<damage::DamageRecord>,
    /// Its damage taken, summed per player.
    pub damage_player: BTreeMap<i32, damage::DamageRecord>,
    /// Its hp when a player who hit it left the view, by that player.
    pub player_dropped: BTreeMap<i32, damage::Dropped>,
    /// The clock (Java `timePc`) at its first and last hit; -1 before (Java `firstDamageTaken`, `lastDamageTaken`).
    pub first_damage: i64,
    pub last_damage: i64,
    /// The players in `damage_player`, in the order they first hit it (for Java's `HashMap` order).
    pub damage_player_order: Vec<i32>,
    /// Chancellor Dammah was countered (its damage counts again).
    pub dammah_countered: bool,
}

impl Entity {
    fn new(id: i32) -> Entity {
        Entity {
            id,
            object_type: 0,
            pos: None,
            stats: vec![None; STAT_SLOTS],
            is_user: false,
            is_player: false,
            char_id: 0,
            base_stats: None,
            name: None,
            damage_list: Vec::new(),
            damage_player: BTreeMap::new(),
            player_dropped: BTreeMap::new(),
            first_damage: -1,
            last_damage: -1,
            damage_player_order: Vec::new(),
            dammah_countered: false,
        }
    }

    /// Java `updateDamageTaken`: a hit at this clock (which is -1 between a map change and its first tick).
    pub(crate) fn damage_taken_at(&mut self, time_pc: i64) {
        if self.first_damage == -1 {
            self.first_damage = time_pc;
        }
        self.last_damage = time_pc;
    }

    pub fn stat(&self, stat_type: u8) -> Option<&Stat> {
        self.stats.get(usize::from(stat_type)).and_then(Option::as_ref)
    }

    /// A number stat's value.
    pub fn value(&self, stat_type: u8) -> Option<i32> {
        self.stat(stat_type).map(|s| s.stat_value)
    }

    /// A string stat's value.
    pub fn text(&self, stat_type: u8) -> Option<&str> {
        self.stat(stat_type).and_then(|s| s.string_stat_value.as_deref())
    }

    /// Java `Stat.setStats`: stat by stat, throwing at a type with no room (those before it are kept).
    fn set_stats(&mut self, stats: &[Stat]) -> Result<(), Abort> {
        for s in stats {
            let slot = self.stats.get_mut(usize::from(s.stat_type_num)).ok_or(Abort("stat type beyond the stat array"))?;
            *slot = Some(s.clone());
        }
        Ok(())
    }

    /// Java `calculateBaseStats`: throws (NullPointerException) while a stat is missing.
    fn calculate_base_stats(&self) -> Result<[i32; 8], Abort> {
        use stats::*;
        let pairs = [
            (MAX_HP, MAX_HP_BOOST),
            (MAX_MP, MAX_MP_BOOST),
            (ATTACK, ATTACK_BOOST),
            (DEFENSE, DEFENSE_BOOST),
            (SPEED, SPEED_BOOST),
            (DEXTERITY, DEXTERITY_BOOST),
            (VITALITY, VITALITY_BOOST),
            (WISDOM, WISDOM_BOOST),
        ];
        let mut base = [0; 8];
        for (b, (stat, boost)) in base.iter_mut().zip(pairs) {
            let missing = Abort("a base stat not sent yet");
            *b = self.value(stat).ok_or(missing)?.wrapping_sub(self.value(boost).ok_or(missing)?);
        }
        Ok(base)
    }

    /// Java `Entity.updateStats`, the parts that change the world.
    fn update_stats(&mut self, status: &ObjectStatus) -> Result<(), Abort> {
        self.set_stats(&status.stats)?;
        self.pos = Some(status.pos);
        if !status.stats.is_empty() {
            if self.is_user {
                // fame from the experience: Java parses it as a number and throws if it isn't one
                if let Some(exp) = self.text(stats::EXP)
                    && exp.parse::<i64>().is_err()
                {
                    return Err(Abort("experience not a number"));
                }
                // the base stats go to the character list (not kept here); missing ones are caught in Java
            } else if self.is_player {
                self.base_stats = Some(self.calculate_base_stats()?);
            }
        }
        Ok(())
    }

    /// Java `setUser`.
    fn set_user(&mut self, char_id: i32) {
        self.is_user = true;
        self.char_id = char_id;
        self.base_stats = Some(self.calculate_base_stats().unwrap_or([0; 8]));
    }

    /// Java `isPlayer()`: another player.
    fn set_player(&mut self) -> Result<(), Abort> {
        self.is_player = true;
        self.base_stats = Some(self.calculate_base_stats()?);
        Ok(())
    }
}

/// The map you are in.
#[derive(Debug, Clone, PartialEq)]
pub struct Map {
    pub name: String,
    pub display_name: String,
    pub width: i32,
    pub height: i32,
    pub seed: i32,
}

/// The world (Java: `TomatoData`).
pub struct World {
    pub map: Option<Map>,
    /// The map was guessed from its enemies (the sniffer started inside it).
    pub map_guessed: bool,
    map_guess_failed: bool,
    /// Your object id; -1 while not known.
    pub world_player_id: i32,
    pub char_id: i32,
    /// The server's time of the last tick, in milliseconds; -1 after a map change.
    pub time: i64,
    /// Your object, once it is in view.
    pub player: Option<i32>,
    pub entities: HashMap<i32, Entity>,
    pub player_list: BTreeSet<i32>,
    /// The players in view now.
    pub player_list_updated: BTreeSet<i32>,
    /// Objects that left the view (they stay in `entities`).
    pub dropped: BTreeSet<i32>,
    pub crystals: BTreeSet<i32>,
    pub loot_bags: BTreeSet<i32>,
    loot_tick_toggle: usize,
    loot_tick_bags: [Vec<loot::Held>; 2],
    /// The objects hit in this map, in the order first hit (Java `entityHitList`, a `HashMap`: see
    /// [`fight::java_hash_order`]).
    entity_hit_list: HashSet<i32>,
    hit_order: Vec<i32>,
    /// The game's notifications of players dying in this map (Java `deathNotifications`).
    pub death_notifications: Vec<Notification>,
    /// Java `timePc`: the clock at the last tick; 0 before the first, -1 after a map change until the next tick.
    pub time_pc: i64,
    killed: Vec<loot::Held>,
    tiles: Vec<u16>,
    petyard: bool,
    own_positions: Vec<WorldPos>,
    standing_id: i32,
    /// Enemy type -> the one dungeon it is in, for guessing the map: worked out at the first guess (Java keeps it).
    enemy_dungeons: Option<HashMap<i32, String>>,
    guess_votes: BTreeMap<String, BTreeSet<i32>>,
    /// The character statistics string last decoded (kept across maps).
    character_stats: Option<String>,
    /// Shots, the damage roll, crucible bonuses.
    pub damage: damage::DamageState,
    /// The bosses' loot windows.
    windows: loot::Windows,
    /// Per dungeon what you hit and what dropped (Java: `dungeon.stats`).
    pub dungeon_stats: loot::DungeonStats,
    /// Bags matched to who dropped them, in order: for the app's records to take.
    pub drops: Vec<loot::Drop>,
    /// What happened for the app, since it last took them.
    pub events: Vec<Event>,
    /// Now, ms since 1970: the capture time of the packet being handled ([`World::set_time`]).
    now: i64,
    /// The clock at the map's first and last tick.
    first_tick_ms: Option<i64>,
    last_tick_ms: Option<i64>,
    /// The players on Java's security panel (`ParsePanelGUI`): showing them reads stats that throw when missing.
    panel: BTreeSet<i32>,
    data: GameData,
}

impl World {
    /// A world knowing the game data.
    pub fn new(data: GameData) -> World {
        World {
            map: None,
            map_guessed: false,
            map_guess_failed: false,
            world_player_id: -1,
            char_id: 0,
            time: 0,
            player: None,
            entities: HashMap::new(),
            player_list: BTreeSet::new(),
            player_list_updated: BTreeSet::new(),
            dropped: BTreeSet::new(),
            crystals: BTreeSet::new(),
            loot_bags: BTreeSet::new(),
            loot_tick_toggle: 0,
            loot_tick_bags: [Vec::new(), Vec::new()],
            entity_hit_list: HashSet::new(),
            hit_order: Vec::new(),
            death_notifications: Vec::new(),
            time_pc: 0,
            killed: Vec::new(),
            tiles: vec![0; MAP_SIZE * MAP_SIZE],
            petyard: false,
            own_positions: Vec::new(),
            standing_id: -1,
            enemy_dungeons: None,
            guess_votes: BTreeMap::new(),
            character_stats: None,
            damage: damage::DamageState::new(),
            panel: BTreeSet::new(),
            windows: loot::Windows::new(),
            dungeon_stats: loot::DungeonStats::default(),
            drops: Vec::new(),
            events: Vec::new(),
            now: 0,
            first_tick_ms: None,
            last_tick_ms: None,
            data,
        }
    }

    /// The time the next packets were captured, ms since 1970: what "now" means for the world (as for the Java app,
    /// whose clock is the computer's while it captures live).
    pub fn set_time(&mut self, ms: i64) {
        self.now = ms;
    }

    pub fn now(&self) -> i64 {
        self.now
    }

    pub fn data(&self) -> &GameData {
        &self.data
    }

    /// Your object, once it is in view.
    pub fn me(&self) -> Option<&Entity> {
        self.player.and_then(|id| self.entities.get(&id))
    }

    /// The tile type at a map position (0: not seen).
    pub fn tile(&self, x: usize, y: usize) -> u16 {
        if x < MAP_SIZE && y < MAP_SIZE { self.tiles[x * MAP_SIZE + y] } else { 0 }
    }

    /// A tick's own part (Java `updateNewTick`); the app's per-tick work comes between it and [`World::after_tick`].
    pub fn tick_part(&mut self, p: &Decoded) -> Result<(), Abort> {
        if let Decoded::NewTick(t) = p { self.new_tick(t) } else { Ok(()) }
    }

    /// What `TomatoPacketCapture` does after a tick: your crucible bonus.
    pub fn after_tick(&mut self) {
        self.crucible_update();
    }

    /// One packet, as `TomatoPacketCapture` hands it to `TomatoData`.
    pub fn packet(&mut self, p: &Decoded) -> Result<(), Abort> {
        match p {
            Decoded::Move(m) => self.moved(m),
            Decoded::NewTick(_) => {
                self.tick_part(p)?;
                self.after_tick();
            }
            Decoded::Update(u) => {
                self.update(u)?;
                self.crucible_update();
            }
            Decoded::MapInfo(m) => self.new_map(m),
            Decoded::CreateSuccess(c) => {
                self.world_player_id = c.object_id;
                self.char_id = c.char_id;
                // the character's statistics (dungeons completed...), decoded when they changed: Java throws when the
                // string is too short or malformed
                if self.character_stats.as_deref() != Some(c.str.as_str()) {
                    self.character_stats = Some(c.str.clone());
                    if six_bit_bytes(&c.str)?.len() < 4 {
                        return Err(Abort("character statistics too short"));
                    }
                }
                // then the Pet Yard and Daily Quest Room check, which reads the map: Java throws without one
                if self.map.is_none() {
                    return Err(Abort("CREATE_SUCCESS without a map"));
                }
                if self.map.as_ref().is_some_and(|m| m.display_name == "Pet Yard") {
                    self.petyard = true;
                }
            }
            Decoded::UseItem(u) => self.used_item(u),
            Decoded::VaultContent(v) => self.vault(v)?,
            Decoded::PlayerShoot(p) => self.player_shoot(p)?,
            Decoded::ServerPlayerShoot(p) => self.server_player_shoot(p),
            Decoded::EnemyHit(h) => self.enemy_hit(h)?,
            Decoded::Damage(d) => self.damage_packet(d)?,
            Decoded::PlayerHit(h) => self.player_hit(h),
            Decoded::EnemyShoot(e) => self.enemy_shoot(e),
            Decoded::Aoe(a) => self.aoe(a)?,
            Decoded::GroundDamage(g) => self.ground(g)?,
            Decoded::Text(t) => {
                let seed = self.map.as_ref().map_or(-1, |m| m.seed);
                self.windows.text(t, seed);
                if t.text == damage::DAMMAH_COUNTERED
                    && let Some(e) = self.entities.get_mut(&t.object_id)
                {
                    e.dammah_countered = true;
                }
            }
            Decoded::CrucibleResponse(c) => self.crucible_response(&c.crucible_jsons),
            Decoded::Notification(n) if n.effect == NotificationEffect::PlayerDeath => self.death_notifications.push(n.clone()),
            _ => {}
        }
        Ok(())
    }

    fn entity(&mut self, id: i32) -> &mut Entity {
        self.entities.entry(id).or_insert_with(|| Entity::new(id))
    }

    /// Whether the map is unknown, as on starting inside one (or only guessed).
    fn map_unknown(&self) -> bool {
        self.map.is_none() || self.map_guessed
    }

    /// Java `setNewRealm` and `clear`.
    fn new_map(&mut self, m: &MapInfo) {
        self.map_guessed = false;
        self.own_positions.clear();
        self.standing_id = -1;
        self.map_guess_failed = false;
        self.guess_votes.clear();
        self.loot_new_map();
        // clear(): what the app's run history and dungeon visit read of the map left
        let left = fight::LeftMap {
            map: self.map.clone(),
            entities: std::mem::take(&mut self.entities),
            hit_order: std::mem::take(&mut self.hit_order),
            deaths: std::mem::take(&mut self.death_notifications),
            player: self.player,
            dungeon_time: self.dungeon_time_ms(),
        };
        self.events.push(Event::Left(Box::new(left)));
        self.world_player_id = -1;
        self.char_id = -1;
        self.time = -1;
        self.player = None;
        self.entities.clear();
        self.player_list.clear();
        self.crystals.clear();
        self.player_list_updated.clear();
        self.dropped.clear();
        self.loot_bags.clear();
        self.entity_hit_list.clear();
        self.hit_order.clear();
        self.death_notifications.clear();
        self.time_pc = -1;
        self.tiles.fill(0);
        self.petyard = false;
        self.first_tick_ms = None;
        self.last_tick_ms = None;
        self.damage.new_map(m.fp);
        self.panel.clear();
        // petYardCheck
        if m.display_name == "Pet Yard" {
            self.petyard = true;
        }
        self.map = Some(Map { name: m.name.clone(), display_name: m.display_name.clone(), width: m.width, height: m.height, seed: m.fp });
        let vault = m.display_name == "{s.vault}";
        self.events.push(Event::Entered { vault, map: map_name(self.map.as_ref()) });
    }

    /// Java `updatePlayersPos`.
    fn moved(&mut self, m: &Move) {
        if self.player.is_none() && self.world_player_id <= 0 && self.map_unknown() {
            self.own_positions.extend(m.records.iter().map(|r| r.pos));
            let over = self.own_positions.len().saturating_sub(OWN_POSITIONS);
            self.own_positions.drain(..over);
        }
        if let (Some(id), Some(last)) = (self.player, m.records.last())
            && let Some(e) = self.entities.get_mut(&id)
        {
            e.pos = Some(last.pos);
        }
    }

    /// Java `updateNewTick`.
    fn new_tick(&mut self, t: &NewTick) -> Result<(), Abort> {
        self.time = i64::from(t.server_real_time_ms);
        let now = self.now;
        self.last_tick_ms = Some(now);
        self.first_tick_ms.get_or_insert(now);
        self.time_pc = now;
        for status in &t.status {
            let id = status.object_id;
            self.entity(id).update_stats(status)?;
            self.panel_update(id)?;
            if self.world_player_id <= 0 && self.map_unknown() && (only_yours(status) || self.stands_where_you_are(id, status)) {
                self.found_yourself(id);
            }
            if id == self.world_player_id {
                let pack = self.entities.get(&id).and_then(|e| e.value(stats::BACKPACK_SLOTS));
                self.events.push(Event::PlayerStatus { char_id: self.char_id, pack, status: status.clone(), entering: false });
            } else {
                self.events.push(Event::ObjectStatus { id, status: status.clone() });
            }
        }
        self.loot_tick();
        Ok(())
    }

    /// Java `update`.
    fn update(&mut self, u: &Update) -> Result<(), Abort> {
        for t in &u.tiles {
            let (x, y) = (usize::try_from(t.x), usize::try_from(t.y));
            match (x, y) {
                (Ok(x), Ok(y)) if x < MAP_SIZE && y < MAP_SIZE => self.tiles[x * MAP_SIZE + y] = t.r#type,
                _ => return Err(Abort("tile outside the map array")),
            }
        }
        for o in &u.new_objects {
            self.entity_update(i32::from(o.object_type), &o.status)?;
        }
        for &id in &u.drops {
            self.crystals.remove(&id);
            self.dropped.insert(id);
            self.damage.minion_owner.remove(&id);
            if self.entities.get(&id).is_some_and(|e| self.data.is_player_class(e.object_type)) {
                self.player_left(id)?;
            }
            if self.entity_hit_list.contains(&id) {
                self.killed.push(loot::Held::Live(id));
            }
            self.player_list_updated.remove(&id);
            self.panel.remove(&id);
        }
        Ok(())
    }

    /// Java `entityUpdate`.
    fn entity_update(&mut self, object_type: i32, status: &ObjectStatus) -> Result<(), Abort> {
        let id = status.object_id;
        let new = !self.entities.contains_key(&id);
        let e = self.entity(id);
        e.update_stats(status)?;
        self.panel_update(id)?;
        let e = self.entity(id);
        e.object_type = object_type;
        if object_type != -1 {
            let name = self.data.object_name(object_type).map(str::to_string);
            self.entity(id).name = name;
        }
        self.events.push(Event::ObjectStatus { id, status: status.clone() });
        if new && self.map_unknown() && !self.map_guess_failed {
            self.guess_map(object_type);
        }
        if self.petyard {
            // a pet in the Pet Yard: the app's pet list (not ported yet)
        } else if CRYSTALS.contains(&object_type) {
            self.crystals.insert(id);
        } else if lg_gamedata::is_loot_drop_bag(object_type) && !self.loot_bags.contains(&id) {
            self.loot_bags.insert(id);
            self.loot_tick_bags[self.loot_tick_toggle].push(loot::Held::Live(id));
        } else if self.data.is_player_class(object_type) {
            self.player_list.insert(id);
            self.player_list_updated.insert(id);
            let char_id = self.char_id;
            let me = id == self.world_player_id;
            let e = self.entity(id);
            if me {
                e.set_user(char_id);
                let pack = e.value(stats::BACKPACK_SLOTS);
                self.player = Some(id);
                self.events.push(Event::PlayerStatus { char_id, pack, status: status.clone(), entering: true });
                // Java MyInfoGUI.updatePlayer: with your dexterity sent, it reads attack, wisdom, the exaltation
                // bonus and the four equipment slots, throwing while one is missing
                let me = &self.entities[&id];
                if me.stat(stats::DEXTERITY).is_some() {
                    let needed = [stats::ATTACK, stats::WISDOM, stats::EXALTATION_BONUS_DAMAGE, 8, 9, 10, 11];
                    if needed.iter().any(|t| me.stat(*t).is_none()) {
                        return Err(Abort("your character shown without all its stats"));
                    }
                }
            } else {
                e.set_player()?;
            }
            self.panel_add(id)?;
        }
        Ok(())
    }

    /// Java `ParsePanelGUI.addPlayer`: the player's box reads the equipment, the enchant codes, the level, the name
    /// and the skin; a missing one (or an enchant code Java cannot read) throws.
    fn panel_add(&mut self, id: i32) -> Result<(), Abort> {
        let missing = Abort("a player shown without all their stats");
        let e = &self.entities[&id];
        for t in stats::INVENTORY_0..stats::INVENTORY_0 + 4 {
            e.value(t).ok_or(missing)?;
        }
        for code in lg_gamedata::enchants::slot_codes(e.text(stats::UNIQUE_DATA)) {
            if !lg_gamedata::enchants::parse_ok(&code) {
                return Err(Abort("an enchant code Java cannot read"));
            }
        }
        e.value(stats::LEVEL).ok_or(missing)?;
        self.display_name(e)?;
        e.value(stats::SKIN_ID).ok_or(missing)?;
        self.panel.insert(id);
        Ok(())
    }

    /// Java `ParsePanelGUI.update`: a shown player's stats changed; the box reads the equipment again.
    fn panel_update(&self, id: i32) -> Result<(), Abort> {
        if self.panel.contains(&id) {
            let e = &self.entities[&id];
            for t in stats::INVENTORY_0..stats::INVENTORY_0 + 4 {
                e.value(t).ok_or(Abort("a shown player without their equipment"))?;
            }
        }
        Ok(())
    }

    /// Java `usedItem`: your ability or a quick slot tells which object you are, while the map started unknown.
    fn used_item(&mut self, u: &UseItem) {
        let s = &u.slot_object;
        if self.world_player_id > 0 || !self.map_unknown() || s.object_id <= 0 {
            return;
        }
        if s.slot_id != 1 && s.slot_id < 1_000_000 {
            return; // an item used from a bag or chest names that container
        }
        self.entity(s.object_id);
        self.found_yourself(s.object_id);
    }

    /// Java `vaultPacketUpdate`: reads your seasonal stat, throwing while it is not known.
    fn vault(&mut self, _v: &VaultContent) -> Result<(), Abort> {
        if let Some(me) = self.me()
            && me.stat(stats::SEASONAL).is_none()
        {
            return Err(Abort("vault update before your seasonal stat"));
        }
        Ok(())
    }

    /// Java `foundYourself`. The app also gives the object your last played class and your name when the game
    /// didn't (not ported yet: the character list).
    fn found_yourself(&mut self, id: i32) {
        self.world_player_id = id;
        self.player = Some(id);
        self.own_positions.clear();
        let char_id = self.char_id;
        self.entity(id).set_user(char_id);
        self.player_list.insert(id);
        self.player_list_updated.insert(id);
    }

    /// Java `standsWhereYouAre`: the object stands exactly where your client said it moved, a second time in a row.
    fn stands_where_you_are(&mut self, id: i32, status: &ObjectStatus) -> bool {
        if self.own_positions.is_empty() {
            return false;
        }
        let there = self.own_positions.iter().any(|p| p.x == status.pos.x && p.y == status.pos.y);
        if !there {
            return false;
        }
        if self.standing_id == id {
            return true;
        }
        self.standing_id = id;
        false
    }

    /// Java `guessMap`: one more enemy came into view while the map is unknown; enough of one dungeon's and it is
    /// that one.
    fn guess_map(&mut self, object_type: i32) {
        if self.map_guessed {
            return;
        }
        if self.enemy_dungeons.is_none() {
            self.enemy_dungeons = Some(self.enemies_by_dungeon());
        }
        let Some(dungeon) = self.enemy_dungeons.as_ref().and_then(|e| e.get(&object_type)).cloned() else { return };
        let votes = self.guess_votes.entry(dungeon.clone()).or_default();
        votes.insert(object_type);
        let boss = self.data.object(object_type).is_some_and(|o| o.has_label("BOSS"));
        if !boss && votes.len() < GUESS_VOTES {
            return;
        }
        self.map_guessed = true;
        self.map = Some(Map { name: dungeon.clone(), display_name: dungeon, width: 0, height: 0, seed: -1 });
        self.events.push(Event::Guessed);
    }

    /// The whole state as text, as `oracle/WorldOracle.java` writes Java's: one line per part and per object.
    pub fn dump(&self) -> String {
        let mut b = String::new();
        let ids = |s: &BTreeSet<i32>| format!("[{}]", s.iter().map(i32::to_string).collect::<Vec<_>>().join(","));
        let _ = writeln!(
            b,
            "map {} guessed={} me={} char={} time={} player={}",
            self.map.as_ref().map_or("null".into(), |m| quote(&m.name)),
            self.map_guessed,
            self.world_player_id,
            self.char_id,
            self.time,
            self.player.map_or("null".into(), |p| p.to_string())
        );
        let _ = writeln!(b, "players {}", ids(&self.player_list));
        let _ = writeln!(b, "updated {}", ids(&self.player_list_updated));
        let _ = writeln!(b, "dropped {}", ids(&self.dropped));
        let _ = writeln!(b, "crystals {}", ids(&self.crystals));
        let _ = writeln!(b, "bags {}", ids(&self.loot_bags));
        let deaths: Vec<String> = self.death_notifications.iter().map(|n| n.message.as_deref().map_or("null".into(), quote)).collect();
        let _ = writeln!(
            b,
            "fight timePc={} first={} hits=[{}] deaths=[{}]",
            self.time_pc,
            self.first_tick_ms.unwrap_or(-1),
            self.hit_list().iter().map(i32::to_string).collect::<Vec<_>>().join(","),
            deaths.join(",")
        );
        let held = |l: &[loot::Held]| {
            l.iter()
                .map(|h| match h {
                    loot::Held::Live(id) => id.to_string(),
                    loot::Held::Copy(e) => e.id.to_string(),
                })
                .collect::<Vec<_>>()
                .join(",")
        };
        let w = &self.windows;
        let _ = writeln!(
            b,
            "loot toggle={} bags=[{}]/[{}] killed=[{}] window={}",
            self.loot_tick_toggle,
            held(&self.loot_tick_bags[0]),
            held(&self.loot_tick_bags[1]),
            held(&self.killed),
            w.describe()
        );
        let ds = &self.dungeon_stats;
        let _ = writeln!(b, "dungeonstats info={}", ds.info.as_deref().map_or("null".into(), quote));
        for (name, i) in &ds.data {
            let damaged: Vec<String> = i.damaged.iter().map(|(t, n)| format!("{t}:{n}")).collect();
            let loot: Vec<String> = i
                .loot
                .iter()
                .map(|(t, items)| format!("{t}:{{{}}}", items.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(",")))
                .collect();
            let _ = writeln!(b, "dungeon {} entered={} damaged=[{}] loot=[{}]", quote(name), i.entered, damaged.join(","), loot.join(","));
        }
        let c = &self.damage.crucible;
        let mut mult: Vec<_> = c.damage.iter().map(|(k, v)| format!("{}:{:016x}", quote(k), v.to_bits())).collect();
        mult.sort();
        let opt = |s: &Option<String>| s.as_deref().map_or("null".into(), quote);
        let _ = writeln!(
            b,
            "rng {} crucible {} {} [{}]",
            self.damage.rng.map_or("null".into(), |r| r.seed.to_string()),
            opt(&c.id128),
            opt(&c.id155),
            mult.join(",")
        );
        let mut all: Vec<&Entity> = self.entities.values().collect();
        all.sort_by_key(|e| e.id);
        for e in all {
            let pos = e.pos.map_or("null".into(), |p| format!("{:08x},{:08x}", p.x.to_bits(), p.y.to_bits()));
            let base = e.base_stats.map_or("null".into(), |b| format!("[{}]", b.iter().map(i32::to_string).collect::<Vec<_>>().join(",")));
            let stats: Vec<String> = e
                .stats
                .iter()
                .flatten()
                .map(|s| {
                    let v = s.string_stat_value.as_deref().map_or(s.stat_value.to_string(), quote);
                    format!("{}:{v}/{}", s.stat_type_num, s.stat_value_two)
                })
                .collect();
            let _ = writeln!(
                b,
                "entity {} type={} pos={pos} user={} player={} char={} base={base} stats={}",
                e.id,
                e.object_type,
                e.is_user,
                e.is_player,
                e.char_id,
                stats.join(",")
            );
            let fought = e.first_damage != -1;
            if e.name.is_some() || fought || e.dammah_countered || !e.damage_list.is_empty() || !e.player_dropped.is_empty() {
                let records: Vec<String> = e.damage_list.iter().map(record).collect();
                let sums: Vec<String> = e.damage_player.iter().map(|(k, r)| format!("{k}:{}", record(r))).collect();
                let dropped: Vec<String> =
                    e.player_dropped.iter().map(|(k, d)| format!("{k}:{}/{}/{}", d.hp, d.max, opt(&d.name))).collect();
                let _ = writeln!(
                    b,
                    "  name={} fought={}/{} dammah={} damage=[{}] byplayer=[{}] dropped=[{}]",
                    opt(&e.name),
                    e.first_damage,
                    e.last_damage,
                    e.dammah_countered,
                    records.join(","),
                    sums.join(","),
                    dropped.join(",")
                );
            }
        }
        b
    }
}

/// Java `onlyYours`: stats the game sends for your own character only (inventory past the equipment, gold, quick
/// slots, experience).
fn only_yours(status: &ObjectStatus) -> bool {
    use stats::*;
    status.stats.iter().any(|s| {
        (INVENTORY_4..=INVENTORY_11).contains(&s.stat_type_num)
            || [CREDITS, POTION_ONE_TYPE, NEXT_LEVEL_EXP, EXP].contains(&s.stat_type_num)
    })
}

/// Java `PcStatsDecoder.sixBitStringToBytes`.
fn six_bit_bytes(s: &str) -> Result<Vec<u8>, Abort> {
    lg_gamedata::enchants::six_bit_bytes(s).map_err(|_| Abort("character statistics malformed"))
}

/// One damage record, as `oracle/WorldOracle.java` writes Java's.
fn record(r: &damage::DamageRecord) -> String {
    let opt = |s: &Option<String>| s.as_deref().map_or("null".into(), quote);
    let num = |v: Option<i32>| v.map_or("null".into(), |v| v.to_string());
    let list = |v: &[String]| format!("[{}]", v.join(","));
    format!(
        "{{o={},tm={},src={},t={},n={},inv={},ench={},ss={},sv={},d={},cd={},ch={},f={}{}{}}}",
        num(r.owner),
        r.time,
        r.source_type,
        r.owner_object_type,
        opt(&r.owner_name),
        r.owner_inventory.map_or("null".into(), |i| list(&i.map(|v| v.to_string()))),
        r.owner_enchants.as_ref().map_or("null".into(), |e| list(&e.clone().map(|c| quote(&c)))),
        num(r.owner_scaling_stat.map(i32::from)),
        num(r.owner_scaling_value),
        r.damage,
        r.counter_damage,
        r.counter_hits,
        u8::from(r.oryx_guard),
        u8::from(r.chancellor_dammah),
        u8::from(r.walled_garden_reflectors)
    )
}

/// Java `LootEvents.mapName`: the name shown, or the map's own when that is a game text key.
pub fn map_name(m: Option<&Map>) -> String {
    match m {
        None => "Unknown".into(),
        Some(m) if !m.display_name.is_empty() && !m.display_name.starts_with('{') => m.display_name.clone(),
        Some(m) => m.name.clone(),
    }
}

fn quote(s: &str) -> String {
    lg_protocol::dump::to_string(&s.to_string())
}

#[cfg(test)]
mod tests;
