//! The Tinkerer's daily quests (Java: `lootgobbler.TinkererQuests`), as the game last sent them.
//!
//! Not yet: writing the file.

use lg_protocol::packets::data::Quest;

#[derive(Debug, Default)]
pub struct Tinkerer {
    pub quests: Vec<Quest>,
    /// When the game last sent them (ms), 0 = never.
    pub seen_at: i64,
    pub dirty: bool,
}

/// When a file was last changed, ms since 1970 (Java: `Files.getLastModifiedTime(...).toMillis()`).
pub(crate) fn modified_ms(path: &std::path::Path) -> Option<i64> {
    let t = std::fs::metadata(path).ok()?.modified().ok()?;
    let d = t.duration_since(std::time::UNIX_EPOCH).ok()?;
    i64::try_from(d.as_millis()).ok()
}

impl Tinkerer {
    /// What the Java app kept (`tinkerer-quests.json`; seen when the file was written), read only.
    pub fn import(dir: &std::path::Path) -> Tinkerer {
        use crate::gson;
        let path = dir.join("tinkerer-quests.json");
        let mut t = Tinkerer::default();
        let Ok(text) = std::fs::read_to_string(&path) else { return t };
        let quest = |q: &lg_world::json::Json| -> Result<Quest, crate::java::Thrown> {
            let q = gson::object(q)?;
            let st = |k: &str| gson::string(q.get(k)).map(|s| s.map_or(String::new(), Option::unwrap_or_default));
            let b = |k: &str| gson::boolean(q.get(k)).map(|x| x.unwrap_or(false));
            let ints = |k: &str| gson::ints(q.get(k)).map(|x| x.flatten().unwrap_or_default());
            Ok(Quest {
                id: st("id")?,
                name: st("name")?,
                description: st("description")?,
                expiration: st("expiration")?,
                category: gson::int(q.get("category"))?.unwrap_or(0),
                unknown_int: 0,
                requirements: ints("requirements")?,
                rewards: ints("rewards")?,
                completed: b("completed")?,
                item_of_choice: b("itemOfChoice")?,
                repeatable: b("repeatable")?,
            })
        };
        if let Ok(q) = gson::root_list(&text, quest) {
            t.quests = q;
            t.seen_at = modified_ms(&path).unwrap_or(0);
        }
        t
    }

    /// Java `received`.
    pub fn received(&mut self, quests: &[Quest], now: i64) {
        self.quests = quests.to_vec();
        self.seen_at = now;
        self.dirty = true;
    }
}
