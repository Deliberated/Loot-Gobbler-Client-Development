//! The Tinkerer's daily quests (Java: `lootgobbler.TinkererQuests`), as the game last sent them.
//!
//! Not yet: the file.

use lg_protocol::packets::data::Quest;

#[derive(Debug, Default)]
pub struct Tinkerer {
    pub quests: Vec<Quest>,
    /// When the game last sent them (ms), 0 = never.
    pub seen_at: i64,
    pub dirty: bool,
}

impl Tinkerer {
    /// Java `received`.
    pub fn received(&mut self, quests: &[Quest], now: i64) {
        self.quests = quests.to_vec();
        self.seen_at = now;
        self.dirty = true;
    }
}
