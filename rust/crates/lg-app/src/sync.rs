//! The records kept in sync with the Loot Gobbler server (Java: each store's `Syncable` side): one JSON document per
//! kind, in the shape of its file. Merging never loses progress: whatever either side has is kept.

use crate::App;
use crate::files;
use lg_world::json::Json;

/// The kinds in Java's order (`ServerSync.kinds`): the server document's name and the Server tab's label.
pub const KINDS: [(&str, &str); 10] = [
    ("collection", "Collection"),
    ("characters", "Characters"),
    ("exalts", "Exaltations"),
    ("pets", "Pets"),
    ("dps_history", "DPS history"),
    ("profile", "Profile"),
    ("dungeon_visits", "Dungeon visits"),
    ("amulet_saves", "Amulet saves"),
    ("holdings", "Inventory & vaults"),
    ("upe", "UPE runs"),
];

impl App {
    /// Java `exportSync`: the kind's data as the server keeps it (the file's shape, compact).
    pub fn sync_export(&mut self, kind: &str) -> String {
        match kind {
            "collection" => files::collection(&self.collection),
            "characters" => files::chars(&self.characters),
            "exalts" => crate::gson_out::map(self.exalts.progress.iter().map(|(k, v)| (k.to_string(), Some(crate::gson_out::ints(v))))),
            "pets" => files::pets(&self.pets),
            "dps_history" => files::runs(&self.dps_history),
            "profile" => files::profile(&self.profile),
            "dungeon_visits" => self.dungeon_visits.export_sync(&self.dps_history),
            "amulet_saves" => files::amulet(&self.amulet_saves),
            "holdings" => files::holdings_json(&self.holdings),
            "upe" => files::upe(&mut self.upe),
            _ => "null".into(),
        }
    }

    /// Java `mergeSync`: a document from the server (or another PC) merged into the local data. True if the local
    /// data changed; Err where Java's Gson would throw (the sync run fails).
    pub fn sync_merge(&mut self, kind: &str, remote: &Json, now: i64) -> Result<bool, crate::java::Thrown> {
        let changed = match kind {
            "collection" => self.collection.merge_sync(remote)?,
            "characters" => self.characters.merge_sync(remote)?,
            "exalts" => self.exalts.merge_sync(remote)?,
            "pets" => self.pets.merge_sync(remote)?,
            "dps_history" => self.dps_history.merge_sync(remote)?,
            "profile" => self.profile.merge_sync(remote)?,
            "dungeon_visits" => self.dungeon_visits.merge_sync(remote, &self.amulet_saves)?,
            "amulet_saves" => self.amulet_saves.merge_sync(remote, &mut self.dungeon_visits, now)?,
            "holdings" => {
                let time = self.zone.local_date_time(now);
                let ctx = crate::holdings::Ctx { data: self.world.data(), now, time: &time };
                let changed = self.holdings.merge_sync(&ctx, remote)?;
                self.vault_changes();
                changed
            }
            "upe" => self.upe.merge_sync(remote),
            _ => false,
        };
        Ok(changed)
    }

    /// Java `syncCount`: how many entries the kind has, for the Server tab.
    pub fn sync_count(&self, kind: &str) -> usize {
        match kind {
            "collection" => self.collection.all().count(),
            "characters" => self.characters.all().count(),
            "exalts" => self.exalts.progress.len(),
            "pets" => self.pets.pets.len(),
            "dps_history" => self.dps_history.runs.len(),
            "profile" => usize::from(!self.profile.ign.is_empty()),
            "dungeon_visits" => self.dungeon_visits.visits.len(),
            "amulet_saves" => self.amulet_saves.count(),
            "holdings" => self.holdings.places.len(),
            "upe" => self.upe.runs.values().filter(|r| r.marked || r.eligible()).count(),
            _ => 0,
        }
    }
}
