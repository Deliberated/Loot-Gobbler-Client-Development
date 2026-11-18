//! Loot Gobbler's own records, built from the world as the packets come (Java: the `lootgobbler` package).
//!
//! [`App`] hands each packet on as the Java app's `Register` does: first to the world (`TomatoPacketCapture`), then
//! to the app's own listeners, in the order the app registers them; where one throws, the rest are skipped. What the
//! world tells the app while it works ([`lg_world::Event`]) is taken right after, in order.

pub mod characters;
pub mod collection;
pub mod holdings;
pub mod localtime;
pub mod loot_events;
pub mod profile;

use lg_protocol::packets::Decoded;
use lg_world::{Abort, Event, World};

/// The packets the app takes: those the world is fed, and those the app's own listeners are registered for (Java:
/// `ItemUse.install`): forging, pets, drops, purchases, rewards, trades, quests.
pub const FED: &[u8] = &[
    101, 25, 30, 75, 90, 35, 103, 64, 62, 13, 46, 12, 42, 10, 92, 166, 44, 67, 114, 117, 55, 95, 6, 34, 88, 183, 118, 119, 16, 19, 22, 93,
    172, 149, 150, 115, 86, 36, 14, 58, 96,
];

pub struct App {
    pub world: World,
    pub characters: characters::Characters,
    pub profile: profile::Profile,
    pub collection: collection::Collection,
    pub holdings: holdings::Holdings,
    pub loot_events: loot_events::LootEvents,
    pub zone: localtime::Zone,
}

/// Borrows what LootEvents writes into, seeding the holdings on their first use as Java's `Holdings.get()` does.
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

impl App {
    /// The app started at `now` (ms since 1970; 0 for a replay, whose clock only starts with its packets).
    pub fn new(world: World, now: i64) -> App {
        let mut profile = profile::Profile::default();
        profile.start(now);
        App {
            world,
            characters: characters::Characters::default(),
            profile,
            collection: collection::Collection::default(),
            holdings: holdings::Holdings::default(),
            loot_events: loot_events::LootEvents::default(),
            zone: localtime::Zone::system(),
        }
    }

    /// One packet captured at `now` (ms since 1970).
    pub fn packet(&mut self, p: &Decoded, now: i64) -> Result<(), Abort> {
        self.world.set_time(now);
        let r = self.handle(p, now);
        if !r.is_ok() {
            self.events(now);
        }
        if std::mem::take(&mut self.collection.changed) {
            let sets = &self.world.data().st_sets;
            self.profile.count_st_sets(sets, &self.collection, &self.zone, now);
        }
        r
    }

    fn handle(&mut self, p: &Decoded, now: i64) -> Result<(), Abort> {
        let time = self.zone.local_date_time(now);
        match p {
            // TomatoPacketCapture: the world's tick, then (DpsGUI.updateNewTickPacket) the app's per-tick work
            Decoded::NewTick(_) => {
                self.world.tick_part(p)?;
                self.events(now);
                let me = self.world.me().cloned();
                let mut r = records!(self, &time, me.is_some());
                self.loot_events.inventory_tick(&mut r, me.as_ref());
                self.characters.live(&self.world, now, &mut self.profile)?;
                self.world.after_tick();
            }
            // TomatoData.vaultPacketUpdate: LootEvents first
            Decoded::VaultContent(v) => {
                let me = self.world.me().cloned();
                let mut r = records!(self, &time);
                self.loot_events.vault_packet(&mut r, v, me.as_ref());
                self.world.packet(p)?;
                self.events(now);
            }
            Decoded::InvSwap(s) => {
                self.world.packet(p)?;
                self.events(now);
                let (pid, cid) = self.world.me().map_or((0, 0), |m| (m.id, m.char_id));
                let r = records!(self, &time);
                let (f, t) = (&s.slot_from, &s.slot_to);
                r.holdings.swap(now, pid, cid, (f.object_id, f.slot_id, f.object_type), (t.object_id, t.slot_id, t.object_type));
            }
            Decoded::InvResult(s) => {
                self.world.packet(p)?;
                self.events(now);
                let r = records!(self, &time);
                let (f, t) = (&s.slot_from, &s.slot_to);
                r.holdings.swap_result(
                    &r.ctx,
                    s.result,
                    s.result_type,
                    (f.object_id, f.slot_id, f.object_type),
                    (t.object_id, t.slot_id, t.object_type),
                );
            }
            // the world (DungeonVisits.died, not ported yet), then CharacterStore's listener
            Decoded::Death(d) => {
                self.world.packet(p)?;
                self.events(now);
                self.characters.died(d.char_id, now);
            }
            _ => {
                self.world.packet(p)?;
                self.events(now);
            }
        }
        Ok(())
    }

    /// What the world told while it worked, in order.
    fn events(&mut self, now: i64) {
        let time = self.zone.local_date_time(now);
        for e in std::mem::take(&mut self.world.events) {
            match e {
                Event::Entered { vault, .. } => {
                    let r = records!(self, &time);
                    r.holdings.entered(&r.ctx, vault);
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
        }
    }

    /// Everything, for the comparison with Java: the world, then the records.
    pub fn dump(&self) -> String {
        let mut s = self.world.dump();
        s.push_str(&self.characters.dump());
        s.push_str(&self.profile.dump());
        s.push_str(&self.collection.dump());
        s.push_str(&self.holdings.dump());
        s
    }
}
