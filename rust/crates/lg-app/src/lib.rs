//! Loot Gobbler's own records, built from the world as the packets come (Java: the `lootgobbler` package).
//!
//! [`App`] hands each packet on as the Java app's `Register` does: first to the world (`TomatoPacketCapture`), then
//! to the app's own listeners, in the order the app registers them; where one throws, the rest are skipped.

pub mod characters;
pub mod profile;

use lg_protocol::packets::Decoded;

/// The packets the app takes: those the world is fed, and those the app's own listeners are registered for (Java:
/// `ItemUse.install`): forging, pets, drops, purchases, rewards, trades, quests.
pub const FED: &[u8] = &[
    101, 25, 30, 75, 90, 35, 103, 64, 62, 13, 46, 12, 42, 10, 92, 166, 44, 67, 114, 117, 55, 95, 6, 34, 88, 183, 118, 119, 16, 19, 22, 93,
    172, 149, 150, 115, 86, 36, 14, 58, 96,
];
use lg_world::{Abort, World};

pub struct App {
    pub world: World,
    pub characters: characters::Characters,
    pub profile: profile::Profile,
}

impl App {
    /// The app started at `now` (ms since 1970; 0 for a replay, whose clock only starts with its packets).
    pub fn new(world: World, now: i64) -> App {
        let mut profile = profile::Profile::default();
        profile.start(now);
        App { world, characters: characters::Characters::default(), profile }
    }

    /// One packet captured at `now` (ms since 1970).
    pub fn packet(&mut self, p: &Decoded, now: i64) -> Result<(), Abort> {
        self.world.set_time(now);
        match p {
            // TomatoPacketCapture: the world's tick, then (DpsGUI.updateNewTickPacket) the app's per-tick work
            Decoded::NewTick(_) => {
                self.world.tick_part(p)?;
                self.characters.live(&self.world, now, &mut self.profile)?;
                self.world.after_tick();
            }
            // the world (DungeonVisits.died, not ported yet), then CharacterStore's listener
            Decoded::Death(d) => {
                self.world.packet(p)?;
                self.characters.died(d.char_id, now);
            }
            _ => self.world.packet(p)?,
        }
        Ok(())
    }

    /// Everything, for the comparison with Java: the world, then the records.
    pub fn dump(&self) -> String {
        let mut s = self.world.dump();
        s.push_str(&self.characters.dump());
        s.push_str(&self.profile.dump());
        s
    }
}
