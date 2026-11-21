//! Your player profile (Java: `lootgobbler.PlayerProfile`): name, guild and rank, for the Loot Gobbler server.

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
    /// The exaltation changes of the last week (copied from the exalts record); None until copied.
    pub exalt_changes: Option<Vec<crate::exalt_store::Change>>,
    /// Set when the file should be written.
    pub dirty: bool,
}

impl Profile {
    /// What the Java app kept (`profile.json`), read only. Unreadable: the default.
    pub fn import(dir: &std::path::Path) -> Profile {
        std::fs::read_to_string(dir.join("profile.json")).ok().and_then(|t| Profile::parse(&t)).flatten().unwrap_or_default()
    }

    /// Gson reading `Data`: None where it throws, Some(None) for null.
    fn parse(text: &str) -> Option<Option<Profile>> {
        use crate::gson;
        use lg_world::json::Json;
        let v = lg_world::json::parse(text).ok()?;
        let read = || -> Option<Profile> {
            if v == Json::Null {
                return None;
            }
            let Json::Object(_) = v else { return None };
            let mut p = Profile::default();
            if let Some(x) = gson::string(v.get("ign")).ok()? {
                p.ign = x.unwrap_or_default();
            }
            if let Some(x) = gson::string(v.get("guild")).ok()? {
                p.guild = x.unwrap_or_default();
            }
            if let Some(x) = gson::int(v.get("guildRank")).ok()? {
                p.guild_rank = x;
            }
            if let Some(x) = gson::long(v.get("updatedAt")).ok()? {
                p.updated_at = x;
            }
            p.st_sets_done = match v.get("stSetsDone") {
                None | Some(Json::Null) => None,
                Some(Json::Array(a)) => Some(a.iter().map(|x| gson::long(Some(x)).ok().flatten()).collect::<Option<Vec<_>>>()?),
                Some(_) => return None,
            };
            p.exalt_changes = match v.get("exaltChanges") {
                None | Some(Json::Null) => None,
                Some(c) => Some(crate::exalt_store::changes_of(c)?),
            };
            Some(p)
        };
        if v == Json::Null { Some(None) } else { read().map(Some) }
    }

    /// Java `mergeSync`: the other PC's profile, if it was updated later. True if it was taken.
    pub fn merge_sync(&mut self, remote: &lg_world::json::Json) -> Result<bool, crate::java::Thrown> {
        let Some(theirs) = Profile::parse(&remote.write()).ok_or(crate::java::Thrown)? else { return Ok(false) };
        if theirs.updated_at <= self.updated_at {
            return Ok(false);
        }
        *self = Profile { dirty: true, ..theirs };
        Ok(true)
    }

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

    /// Java `trackExalts` and `trackStSets` at startup: copies what the exalts record and the collection hold (the
    /// collection: nothing yet), which counts as a change the first time.
    pub fn start(&mut self, exalt_changes: &[crate::exalt_store::Change], now: i64) {
        self.track_exalts(exalt_changes, now);
        if self.st_sets_done.as_deref() != Some(&[]) {
            self.st_sets_done = Some(Vec::new());
            self.updated_at = now;
            self.dirty = true;
        }
    }

    /// Java `trackExalts`' copy, whenever the exalts record changed: a different list counts as an update.
    pub fn track_exalts(&mut self, changes: &[crate::exalt_store::Change], now: i64) {
        if self.exalt_changes.as_deref() == Some(changes) {
            return;
        }
        self.exalt_changes = Some(changes.to_vec());
        self.updated_at = now;
        self.dirty = true;
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
        let ex = self.exalt_changes.as_ref().map_or("null".into(), |n| n.len().to_string());
        format!(
            "profile ign={} guild={} rank={} at={} sts={sts} exalts={ex}\n",
            q(&self.ign),
            q(&self.guild),
            self.guild_rank,
            self.updated_at
        )
    }
}
