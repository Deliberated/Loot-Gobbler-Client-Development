//! Your player profile (Java: `lootgobbler.PlayerProfile`): name, guild and rank, for the Loot Gobbler server.
//!
//! Not yet: the ST sets and exaltations it also tracks, syncing, the file.

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Profile {
    pub ign: String,
    /// "" = not in a guild (or not seen yet).
    pub guild: String,
    /// The game's value: 0 initiate, 10 member, 20 officer, 30 leader, 40 founder.
    pub guild_rank: i32,
    pub updated_at: i64,
    /// When each completed ST set was completed (from the collection); None until counted.
    pub st_sets_done: Option<Vec<i64>>,
    /// How many exaltation changes of the last days it carries (from the exalts record); None until copied.
    pub exalt_changes: Option<usize>,
    /// Set when the file should be written.
    pub dirty: bool,
}

impl Profile {
    /// Java `update`: what the game shows of you; unknown parts stay as they were.
    pub fn update(&mut self, ign: Option<&str>, guild: Option<&str>, rank: Option<i32>, now: i64) {
        let ign = match ign {
            Some(i) if !i.chars().all(char::is_whitespace) => i.to_string(),
            _ => self.ign.clone(),
        };
        let guild = guild.map_or(self.guild.clone(), |g| g.trim().to_string());
        let rank = rank.unwrap_or(self.guild_rank);
        if ign == self.ign && guild == self.guild && rank == self.guild_rank {
            return;
        }
        self.ign = ign;
        self.guild = guild;
        self.guild_rank = rank;
        self.updated_at = now;
        self.dirty = true;
    }

    /// Java `trackExalts` and `trackStSets` at startup: copies what the exalts record and the collection hold (both
    /// not ported yet: nothing), which counts as a change the first time.
    pub fn start(&mut self, now: i64) {
        if self.exalt_changes.is_none() {
            self.exalt_changes = Some(0);
            self.updated_at = now;
            self.dirty = true;
        }
        if self.st_sets_done.as_deref() != Some(&[]) {
            self.st_sets_done = Some(Vec::new());
            self.updated_at = now;
            self.dirty = true;
        }
    }

    /// Java `trackStSets`'s count: when each ST set was completed (all four pieces in the collection; the latest
    /// first find of them), sorted. A change counts as an update.
    pub fn count_st_sets(
        &mut self,
        sets: &[lg_gamedata::st_sets::StSet],
        collection: &crate::collection::Collection,
        zone: &crate::localtime::Zone,
        now: i64,
    ) {
        let mut done = Vec::new();
        for s in sets {
            if s.owned(&|id| collection.contains(id)) != 4 {
                continue;
            }
            let at = s
                .pieces
                .iter()
                .map(|id| collection.entry(*id).and_then(|e| e.first.as_deref()).and_then(|f| zone.parse_local(f)).unwrap_or(0))
                .max()
                .unwrap_or(0);
            done.push(at);
        }
        done.sort_unstable();
        if self.st_sets_done.as_ref() == Some(&done) {
            return;
        }
        self.st_sets_done = Some(done);
        self.updated_at = now;
        self.dirty = true;
    }

    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let sts = self
            .st_sets_done
            .as_ref()
            .map_or("null".into(), |v| format!("[{}]", v.iter().map(i64::to_string).collect::<Vec<_>>().join(",")));
        let ex = self.exalt_changes.map_or("null".into(), |n| n.to_string());
        format!(
            "profile ign={} guild={} rank={} at={} sts={sts} exalts={ex}\n",
            q(&self.ign),
            q(&self.guild),
            self.guild_rank,
            self.updated_at
        )
    }
}
