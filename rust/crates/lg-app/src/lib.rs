//! Loot Gobbler's own records, built from the world as the packets come (Java: the `lootgobbler` package).
//!
//! [`App`] hands each packet on as the Java app's `Register` does: first to the world (`TomatoPacketCapture`), then
//! to the app's own listeners, in the order the app registers them; where one throws, the rest are skipped. What the
//! world tells the app while it works ([`lg_world::Event`]) is taken right after, in order.

pub mod characters;
pub mod collection;
pub mod dps_history;
pub mod dungeon_visits;
pub mod exalt_store;
pub mod holdings;
pub mod item_use;
pub mod localtime;
pub mod loot_events;
pub mod moonlight;
pub mod profile;
pub mod tinkerer;
pub mod upe;

use lg_protocol::packets::Decoded;
use lg_world::{Abort, Event, World};

/// The packets the app takes: those the world is fed, and those the app's own listeners are registered for (Java:
/// `ItemUse.install`): forging, pets, drops, purchases, rewards, trades, quests.
pub const FED: &[u8] = &[
    101, 25, 30, 75, 90, 35, 103, 64, 62, 13, 46, 12, 42, 10, 92, 166, 44, 67, 114, 117, 55, 95, 6, 34, 88, 183, 118, 119, 16, 19, 22, 93,
    172, 149, 150, 115, 86, 36, 14, 58, 96,
];

/// Packets unknown to the packet table the app still reads, raw (Java: `PacketDebug.problem` -> `ItemUse.raw`).
pub const RAW: &[u8] = &[item_use::USE_REQUEST, item_use::USE_RESULT];

pub struct App {
    pub world: World,
    pub characters: characters::Characters,
    pub profile: profile::Profile,
    pub collection: collection::Collection,
    pub holdings: holdings::Holdings,
    pub loot_events: loot_events::LootEvents,
    pub upe: upe::Upe,
    pub item_use: item_use::ItemUse,
    pub tinkerer: tinkerer::Tinkerer,
    pub dps_history: dps_history::DpsHistory,
    pub dungeon_visits: dungeon_visits::DungeonVisits,
    pub exalts: exalt_store::ExaltStore,
    pub moonlight: moonlight::Moonlight,
    /// Tomato's exaltation progress per class (`RealmCharacter.exalts`): from the login's fetch (not ported yet),
    /// then from the game's exaltation packets.
    pub realm_exalts: std::collections::HashMap<i32, Vec<i32>>,
    pub zone: localtime::Zone,
}

/// Borrows what LootEvents writes into; seeding the holdings on their first use, as Java's `Holdings.get()` does.
macro_rules! records {
    ($app:ident, $time:expr) => {
        records!($app, $time, true)
    };
    ($app:ident, $time:expr, $seed:expr) => {{
        let ctx = holdings::Ctx { data: $app.world.data(), now: $app.world.now(), time: $time };
        if $seed {
            $app.holdings.seed_once(&ctx, &$app.characters.living());
        }
        loot_events::Records { ctx, characters: &$app.characters, collection: &mut $app.collection, holdings: &mut $app.holdings }
    }};
}

macro_rules! upe_ctx {
    ($app:ident) => {
        upe::Ctx { data: $app.world.data(), zone: &$app.zone, now: $app.world.now() }
    };
}

impl App {
    /// The app started at `now` (ms since 1970; 0 for a replay, whose clock only starts with its packets).
    pub fn new(world: World, now: i64) -> App {
        App::importing(world, now, None)
    }

    /// The same, taking over what the Java app kept in its folder (only read, never changed): so far the exalts.
    pub fn importing(world: World, now: i64, java_data: Option<&std::path::Path>) -> App {
        let exalts = java_data.map(exalt_store::ExaltStore::import).unwrap_or_default();
        let mut profile = profile::Profile::default();
        profile.start(&exalts.changes, now);
        App {
            world,
            characters: characters::Characters::default(),
            profile,
            collection: collection::Collection::default(),
            holdings: holdings::Holdings::default(),
            loot_events: loot_events::LootEvents::default(),
            upe: upe::Upe::default(),
            item_use: item_use::ItemUse::default(),
            tinkerer: tinkerer::Tinkerer::default(),
            dps_history: dps_history::DpsHistory::default(),
            dungeon_visits: dungeon_visits::DungeonVisits::default(),
            exalts,
            moonlight: moonlight::Moonlight::default(),
            realm_exalts: std::collections::HashMap::new(),
            zone: localtime::Zone::system(),
        }
    }

    fn playing(&self) -> i32 {
        self.characters.playing().map_or(0, |c| c.char_id)
    }

    /// One packet captured at `now` (ms since 1970).
    pub fn packet(&mut self, p: &Decoded, now: i64) -> Result<(), Abort> {
        self.world.set_time(now);
        let r = self.handle(p, now);
        if r.is_err() {
            self.events(now);
        }
        self.after(now);
        r
    }

    /// A packet the packet table doesn't know (Java hands 195 and 196 to `ItemUse.raw`).
    pub fn raw(&mut self, id: u8, body: &[u8], now: i64) {
        self.world.set_time(now);
        let time = self.zone.local_date_time(now);
        let playing = self.playing();
        let c = upe_ctx!(self);
        self.item_use.raw(&c, &self.loot_events, &mut self.collection, &mut self.upe, playing, id, body, &time);
        self.after(now);
    }

    /// What the records' listeners do after a change (Java: the UPE rules hear of vault changes as they happen, the
    /// profile counts the ST sets on a thread of its own).
    fn after(&mut self, now: i64) {
        self.vault_changes();
        if std::mem::take(&mut self.collection.changed) {
            let sets = &self.world.data().st_sets;
            self.profile.count_st_sets(sets, &self.collection, &self.zone, now);
        }
    }

    fn vault_changes(&mut self) {
        let c = upe_ctx!(self);
        for v in std::mem::take(&mut self.holdings.vault_changes) {
            let held = |v: Vec<holdings::Slot>| v.into_iter().map(|s| (s.item, s.enchant_ids)).collect::<Vec<_>>();
            self.upe.vault_changed(&c, &v.key, v.before_at, &held(v.before), v.now_at, &held(v.now));
        }
    }

    fn handle(&mut self, p: &Decoded, now: i64) -> Result<(), Abort> {
        let time = self.zone.local_date_time(now);
        match p {
            // TomatoPacketCapture: the world's tick, then (DpsGUI.updateNewTickPacket) the app's per-tick work
            Decoded::NewTick(_) => {
                self.world.tick_part(p)?;
                self.events(now);
                // DpsGUI.updateNewTickPacket: the run history's live snapshot, the visit's hits, then the rest
                let f = self.world.fight();
                self.dps_history.live_tick(&f, now);
                self.dungeon_visits.hits(&f, &self.characters, false, now);
                self.moonlight.tick(&self.world, &mut self.dungeon_visits, now);
                let me = self.world.me().cloned();
                let mut r = records!(self, &time, me.is_some());
                self.loot_events.inventory_tick(&mut r, me.as_ref());
                self.vault_changes();
                let look = self.characters.live(&self.world, now, &mut self.profile)?;
                if let Some(l) = look {
                    let c = upe_ctx!(self);
                    self.upe.seen(&c, l.char_id, l.level, &l.slots, l.enchants.as_ref(), l.base, l.first);
                }
                self.world.after_tick();
            }
            // TomatoData.vaultPacketUpdate: LootEvents first
            Decoded::VaultContent(v) => {
                let me = self.world.me().cloned();
                let mut r = records!(self, &time);
                self.loot_events.vault_packet(&mut r, v, me.as_ref());
                self.vault_changes();
                self.world.packet(p)?;
                self.events(now);
            }
            Decoded::InvSwap(s) => {
                self.world.packet(p)?;
                self.events(now);
                let me = self.world.me().cloned();
                let mut r = records!(self, &time);
                self.loot_events.inv_swap(&mut r, s, me.as_ref());
            }
            Decoded::InvResult(s) => {
                self.world.packet(p)?;
                self.events(now);
                let c = upe::Ctx { data: self.world.data(), zone: &self.zone, now };
                let mut r = loot_events::Records {
                    ctx: holdings::Ctx { data: self.world.data(), now, time: &time },
                    characters: &self.characters,
                    collection: &mut self.collection,
                    holdings: &mut self.holdings,
                };
                r.holdings.seed_once(&r.ctx, &self.characters.living());
                self.loot_events.inv_result(&mut r, &mut self.upe, &c, &self.world, s);
            }
            // TomatoPacketCapture: the world, DungeonVisits, LootEvents for the UPE rules
            Decoded::UseItem(u) => {
                self.world.packet(p)?;
                self.events(now);
                self.dungeon_visits.item_used(&self.world.fight(), u, now);
                let me = self.world.me().cloned();
                // Java looks the container up (Holdings.get()) only for an item used from something else than you
                let lookup = me.as_ref().is_some_and(|m| u.slot_object.object_id != m.id);
                let c = upe::Ctx { data: self.world.data(), zone: &self.zone, now };
                let r = records!(self, &time, lookup);
                self.loot_events.item_used(&r, &mut self.upe, &c, me.as_ref(), u);
            }
            Decoded::TradeDone(t) => {
                self.world.packet(p)?;
                self.events(now);
                if t.code == Some(lg_protocol::packets::TradeResult::Successful)
                    && let Some(me) = self.world.me()
                {
                    let name = self.world.display_name(me)?;
                    let cid = me.char_id;
                    let c = upe_ctx!(self);
                    self.upe.traded(&c, cid, name.as_deref());
                }
            }
            // the world, DungeonVisits.died, then CharacterStore's listener
            Decoded::Death(d) => {
                self.world.packet(p)?;
                self.events(now);
                self.dungeon_visits.died(&self.world.fight(), &self.characters, &d.killed_by, now);
                self.characters.died(d.char_id, now);
            }
            Decoded::QuestFetchResponse(q) => {
                self.world.packet(p)?;
                self.events(now);
                self.tinkerer.received(&q.quests, now);
            }
            // ItemUse's listeners
            Decoded::ForgeRequest(f) => {
                let playing = self.playing();
                self.item_use.forge_request(self.world.data(), &self.loot_events, &mut self.upe, playing, f);
            }
            Decoded::ForgeResult(f) => {
                let playing = self.playing();
                let c = upe_ctx!(self);
                self.item_use.forge_result(&c, &mut self.collection, &mut self.upe, playing, f, &time);
            }
            Decoded::PetUpgradeRequest(f) => {
                self.item_use.pet_upgrade(self.world.data(), &self.loot_events, &mut self.collection, f, now, &time);
            }
            Decoded::InvDrop(f) => {
                self.item_use.dropped(self.world.data(), &self.loot_events, &mut self.collection, f, now, &time);
            }
            Decoded::BuyResult(b) => {
                if b.result == 0 {
                    self.upe.rewarded(now);
                }
            }
            Decoded::ClaimDailyRewardResponse(_)
            | Decoded::ChestRewardResult(_)
            | Decoded::ClaimBattlePassItem(_)
            | Decoded::ClaimBpMilestoneResult(_)
            | Decoded::RedeemExaltationReward(_) => self.upe.rewarded(now),
            Decoded::TradeStart(t) => loot_events::LootEvents::trade_start(&mut self.upe, t),
            Decoded::AcceptTrade(a) => self.upe.trade_offers(&a.client_offer, &a.partner_offer),
            Decoded::TradeAccepted(a) => self.upe.trade_offers(&a.client_offer, &a.partner_offer),
            Decoded::QuestRedeem(q) => {
                let slots: Vec<i32> = q.slots.iter().map(|s| s.slot_id).collect();
                let quest = self.tinkerer.quests.iter().find(|x| x.id == q.quest_id_string);
                let playing = self.playing();
                self.upe.quest_hand_in(
                    playing,
                    q.quest_id_int,
                    &slots,
                    quest.map(|x| x.rewards.as_slice()),
                    quest.is_some_and(|x| x.item_of_choice),
                );
            }
            Decoded::QuestRedeemResponse(q) => self.upe.quest_answer(q.ok, now),
            // TomatoData.updatePlayersPos: the way you walked, once you are known
            Decoded::Move(m) => {
                self.world.packet(p)?;
                self.events(now);
                if self.world.player.is_some() && !m.records.is_empty() {
                    let way: Vec<_> = m.records.iter().map(|r| r.pos).collect();
                    self.dungeon_visits.moved(&way, now);
                }
            }
            Decoded::ExaltationUpdate(x) => {
                self.world.packet(p)?;
                self.events(now);
                self.exalt_update(x, now);
            }
            // TomatoData.notification: MoonlightLog first, then DungeonVisits (the world keeps the deaths)
            Decoded::Notification(n) => {
                self.world.packet(p)?;
                self.events(now);
                self.moonlight.notification(&self.world, &mut self.dungeon_visits, n, now);
                if n.effect == lg_protocol::packets::NotificationEffect::Victory {
                    self.dungeon_visits.victory();
                }
            }
            _ => {
                self.world.packet(p)?;
                self.events(now);
            }
        }
        Ok(())
    }

    /// Java `TomatoData.exaltUpdate`: the game's new progress for a class, against what Tomato has (else what was
    /// kept from earlier; nothing known: ignored).
    fn exalt_update(&mut self, x: &lg_protocol::packets::ExaltationUpdate, now: i64) {
        let cls = i32::from(x.obj_type);
        let Some(before) = self.realm_exalts.get(&cls).or_else(|| self.exalts.progress(cls)).cloned() else { return };
        let update = vec![
            x.dexterity_progress,
            x.speed_progress,
            x.vitality_progress,
            x.wisdom_progress,
            x.defense_progress,
            x.attack_progress,
            x.mana_progress,
            x.health_progress,
        ];
        if before == update {
            return;
        }
        self.dungeon_visits.exalt_progress(cls, &before, &update, now);
        self.realm_exalts.insert(cls, update.clone());
        self.exalts.update(&[(cls, update)], now);
        self.profile.track_exalts(&self.exalts.changes, now);
    }

    /// What the world told while it worked, in order.
    fn events(&mut self, now: i64) {
        let time = self.zone.local_date_time(now);
        for e in std::mem::take(&mut self.world.events) {
            match e {
                // setNewRealm: the visit's last hits, then clear() saves the run of the map left
                Event::Left(left) => {
                    let f = left.fight(self.world.data());
                    self.dungeon_visits.hits(&f, &self.characters, true, now);
                    if f.map.is_some_and(|m| dps_history::is_logged_dungeon(&m.display_name)) || dps_history::is_guild_hall(f.map) {
                        self.dps_history.record(&f, now);
                    }
                }
                Event::Guessed => {
                    let (data, m) = (self.world.data(), self.world.map.as_ref());
                    self.dungeon_visits.entered_late(data, m, &self.characters, &self.dps_history, now);
                    let name = m.map_or(String::new(), |m| m.name.clone());
                    self.moonlight.entered(&name);
                }
                Event::MoonAdded { id, object_type } => self.moonlight.added(&self.world, &mut self.dungeon_visits, id, object_type, now),
                Event::MoonRemoved { id } => self.moonlight.removed(id),
                Event::MoonConditions { id, conditions } => {
                    self.moonlight.conditions(&self.world, &mut self.dungeon_visits, id, &conditions, now)
                }
                Event::Entered { vault, map } => {
                    let (data, m) = (self.world.data(), self.world.map.as_ref());
                    self.dungeon_visits.entered(data, m, &self.characters, &self.dps_history, now);
                    let name = m.map_or(String::new(), |m| m.name.clone());
                    self.moonlight.entered(&name);
                    let r = records!(self, &time);
                    r.holdings.entered(&r.ctx, vault);
                    self.upe.in_vault(vault);
                    self.upe.set_where(&map);
                }
                Event::PlayerStatus { char_id, pack, status, entering } => {
                    if char_id <= 0 {
                        continue;
                    }
                    let mut r = records!(self, &time);
                    self.loot_events.player_status(&mut r, char_id, pack, &status.stats, entering);
                }
                Event::ObjectStatus { id, status } => {
                    let mut r = records!(self, &time);
                    self.loot_events.object_status(&mut r, id, &status.stats);
                }
                Event::BagDropped { map, bag, seasonal } => {
                    let mut r = records!(self, &time, false);
                    self.loot_events.bag_dropped(&mut r, &map, &bag, seasonal);
                }
            }
            self.vault_changes();
        }
    }

    /// Everything, for the comparison with Java: the world, then the records.
    pub fn dump(&self) -> String {
        let mut s = self.world.dump();
        s.push_str(&self.characters.dump());
        s.push_str(&self.profile.dump());
        s.push_str(&self.collection.dump());
        s.push_str(&self.holdings.dump());
        s.push_str(&self.upe.dump());
        s.push_str(&self.exalts.dump());
        s.push_str(&self.dps_history.dump());
        s.push_str(&self.dungeon_visits.dump());
        s.push_str(&self.moonlight.dump());
        s
    }
}
