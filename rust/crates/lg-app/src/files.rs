//! The app's own files, in the Java app's formats and names (so the folder is the Java app's): each written when
//! its record changed, as Java does (`save()`), as `new Gson().toJson(...)` writes it.

use crate::App;
use crate::gson_out::{self, Obj, array, ints};
use std::path::Path;

fn opt_double(d: Option<f64>) -> Option<String> {
    d.map(gson_out::double)
}

fn ints2(v: &[Vec<i32>]) -> String {
    array(v.iter().map(|x| ints(x)))
}

fn chars(c: &crate::characters::Characters) -> String {
    let mut o = Obj::new();
    for ch in c.all() {
        let samples = array(ch.samples.iter().map(|s| Obj::new().int("t", s.t).int("fame", s.fame).end()));
        let dungeons = ch.dungeons.as_ref().map(|d| gson_out::map(d.iter().map(|(k, v)| (k.clone(), Some(v.to_string())))));
        let v = Obj::new()
            .int("charId", ch.char_id)
            .int("classType", ch.class_type)
            .str("className", &ch.class_name)
            .int("skin", ch.skin)
            .int("tex1", ch.tex1)
            .int("tex2", ch.tex2)
            .int("petSkin", ch.pet_skin)
            .opt_str("petName", ch.pet_name.as_deref())
            .int("level", ch.level)
            .int("maxHp", ch.max_hp)
            .int("fame", ch.fame)
            .int("statsMaxed", ch.stats_maxed)
            .bool("seasonal", ch.seasonal)
            .bool("crucible", ch.crucible)
            .bool("ritual", ch.ritual)
            .bool("favorite", ch.favorite)
            .raw("crucibleLoot", opt_double(ch.crucible_loot))
            .raw("ritualLoot", opt_double(ch.ritual_loot))
            .int("lootDropUntil", ch.loot_drop_until)
            .int("lootTierUntil", ch.loot_tier_until)
            .int("favoriteAt", ch.favorite_at)
            .bool("backpack", ch.backpack)
            .bool("extender", ch.extender)
            .bool("alive", ch.alive)
            .opt_str("diedBy", ch.died_by.as_deref())
            .opt_str("account", ch.account.as_deref())
            .int("aliveAt", ch.alive_at)
            .int("lastPlayed", ch.last_played)
            .raw("equipment", ch.equipment.as_deref().map(ints))
            .raw("enchants", ch.enchants.as_deref().map(ints))
            .raw("enchantIds", ch.enchant_ids.as_deref().map(ints2))
            .raw("slots", ch.slots.as_deref().map(ints))
            .raw("quickslots", ch.quickslots.as_deref().map(ints))
            .raw("slotEnchantIds", ch.slot_enchant_ids.as_deref().map(ints2))
            .raw("dungeons", dungeons)
            .raw("samples", Some(samples))
            .end();
        o = o.raw(&ch.char_id.to_string(), Some(v));
    }
    o.end()
}

fn seen(s: &crate::collection::Seen) -> String {
    Obj::new()
        .int("charId", s.char_id)
        .str("place", &s.place)
        .int("classType", s.class_type)
        .int("skin", s.skin)
        .int("tex1", s.tex1)
        .int("tex2", s.tex2)
        .bool("seasonal", s.seasonal)
        .end()
}

fn find(f: &crate::collection::Find) -> String {
    Obj::new()
        .str("time", &f.time)
        .int("at", f.at)
        .str("source", &f.source)
        .int("enchants", f.enchants)
        .str("where", &f.r#where)
        .str("bag", &f.bag)
        .raw("enchantIds", f.enchant_ids.as_deref().map(ints))
        .raw("seasonal", f.seasonal.map(|b| b.to_string()))
        .raw("seen", f.seen.as_ref().map(seen))
        .end()
}

fn collection(c: &crate::collection::Collection) -> String {
    let mut o = Obj::new();
    for (id, e) in c.all() {
        let v = Obj::new()
            .opt_str("first", e.first.as_deref())
            .opt_str("last", e.last.as_deref())
            .int("count", e.count)
            .raw("history", e.history.as_ref().map(|h| array(h.iter().map(find))))
            .end();
        o = o.raw(&id.to_string(), Some(v));
    }
    o.end()
}

impl App {
    /// Writes the files of the records that changed (Java: each record's `save()`), into `dir`.
    pub fn save_changed(&mut self, dir: &Path) -> std::io::Result<()> {
        let write = |name: &str, text: String| -> std::io::Result<()> {
            // as Java: a temporary file moved over the old one
            let tmp = dir.join(format!("{name}.tmp"));
            std::fs::write(&tmp, text)?;
            std::fs::rename(&tmp, dir.join(name))
        };
        if std::mem::take(&mut self.characters.dirty) {
            write("characters.json", chars(&self.characters))?;
        }
        if std::mem::take(&mut self.collection.dirty) {
            // the collection's Gson prints prettily
            write("collection.json", gson_out::pretty(&collection(&self.collection)))?;
        }
        if std::mem::take(&mut self.holdings.dirty) {
            write("holdings.json", holdings(&mut self.holdings))?;
        }
        self.append_journal(dir)?;
        if std::mem::take(&mut self.profile.dirty) {
            write("profile.json", profile(&self.profile))?;
        }
        if std::mem::take(&mut self.upe.dirty) {
            write("upe.json", upe(&mut self.upe))?;
        }
        if std::mem::take(&mut self.dps_history.dirty) {
            write("dps_history.json", runs(&self.dps_history))?;
        }
        if std::mem::take(&mut self.dungeon_visits.dirty) {
            write("dungeon_visits.json", visits(&self.dungeon_visits))?;
        }
        if std::mem::take(&mut self.exalts.dirty) {
            let progress = gson_out::map(self.exalts.progress.iter().map(|(k, v)| (k.to_string(), Some(ints(v)))));
            write("exalts.json", progress)?;
            write("exalt_changes.json", array(self.exalts.changes.iter().map(exalt_change)))?;
        }
        if std::mem::take(&mut self.amulet_saves.dirty) {
            write("amulet_saves.json", amulet(&self.amulet_saves))?;
        }
        if std::mem::take(&mut self.pets.dirty) {
            write("pets.json", pets(&self.pets))?;
        }
        if std::mem::take(&mut self.tinkerer.dirty) {
            write("tinkerer-quests.json", tinkerer(&self.tinkerer))?;
        }
        if let Some(raw) = self.friends.raw.take() {
            std::fs::write(dir.join("friends-list.xml"), raw)?; // the last answer as it came
        }
        if std::mem::take(&mut self.friends.dirty) {
            let lines: String = self.friends.names.iter().map(|n| format!("{n}{LINE_END}")).collect();
            std::fs::write(dir.join("friends.txt"), lines)?;
        }
        if std::mem::take(&mut self.missions.dirty) {
            if let Some(s) = &self.missions.seasons_file {
                std::fs::write(dir.join("missions-seasons.json"), s)?;
            }
            if let Some(p) = &self.missions.player_file {
                std::fs::write(dir.join("missions-player.xml"), p)?;
            }
        }
        if std::mem::take(&mut self.world.dungeon_stats.dirty) {
            std::fs::write(dir.join("dungeon.stats"), dungeon_stats(&self.world.dungeon_stats))?;
        }
        if std::mem::take(&mut self.characters.saved_list_dirty)
            && let Some(x) = &self.characters.saved_list
        {
            std::fs::write(dir.join("char-list.xml"), x)?;
        }
        Ok(())
    }
}

impl App {
    /// Taking over the Java app's folder: every file it had is written into the app's own folder (a full copy, in the
    /// same formats), the journal's history included.
    pub fn copy_from_java(&mut self, java_dir: &Path) {
        let has = |name: &str| java_dir.join(name).exists();
        self.characters.dirty |= has("characters.json");
        self.collection.dirty |= has("collection.json");
        self.holdings.dirty |= has("holdings.json");
        if has("holdings-journal.txt") {
            self.holdings.journal_written = 0;
        }
        self.profile.dirty |= has("profile.json");
        self.upe.dirty |= has("upe.json");
        self.dps_history.dirty |= has("dps_history.json");
        self.dungeon_visits.dirty |= has("dungeon_visits.json");
        self.exalts.dirty |= has("exalts.json");
        self.amulet_saves.dirty |= has("amulet_saves.json");
        self.pets.dirty |= has("pets.json");
        self.tinkerer.dirty |= has("tinkerer-quests.json");
        self.friends.dirty |= has("friends.txt");
        self.missions.dirty |= has("missions-seasons.json") || has("missions-player.xml");
        self.world.dungeon_stats.dirty |= has("dungeon.stats");
        self.characters.saved_list_dirty |= has("char-list.xml");
    }

    /// The holdings journal's new lines, appended as Java appends each (the file moved aside past 2 MB).
    fn append_journal(&mut self, dir: &Path) -> std::io::Result<()> {
        use std::io::Write as _;
        let h = &mut self.holdings;
        if h.journal_written >= h.journal.len() {
            return Ok(());
        }
        let path = dir.join("holdings-journal.txt");
        for line in &h.journal[h.journal_written..] {
            if std::fs::metadata(&path).is_ok_and(|m| m.len() > 2_000_000) {
                std::fs::rename(&path, dir.join("holdings-journal.old.txt"))?;
            }
            let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&path)?;
            write!(f, "{line}{LINE_END}")?;
        }
        h.journal_written = h.journal.len();
        Ok(())
    }
}

/// The keys of a map in the order Java's `HashMap` would have them put: those already noted, then new ones (in
/// ascending order: those first put between two saves, which Java orders by when they were put).
pub(crate) fn note_keys<K: Clone + Ord + PartialEq>(order: &mut Vec<K>, present: impl Iterator<Item = K>) {
    let mut present: Vec<K> = present.collect();
    present.sort();
    order.retain(|k| present.contains(k));
    for k in present {
        if !order.contains(&k) {
            order.push(k);
        }
    }
}

fn exalt_change(c: &crate::exalt_store::Change) -> String {
    Obj::new().int("t", c.t).int("classType", c.class_type).int("stat", c.stat).int("before", c.before).int("after", c.after).end()
}

fn profile(p: &crate::profile::Profile) -> String {
    Obj::new()
        .str("ign", &p.ign)
        .str("guild", &p.guild)
        .int("guildRank", p.guild_rank)
        .int("updatedAt", p.updated_at)
        .raw("stSetsDone", p.st_sets_done.as_deref().map(gson_out::longs))
        .raw("exaltChanges", p.exalt_changes.as_ref().map(|c| array(c.iter().map(exalt_change))))
        .end()
}

fn runs(h: &crate::dps_history::DpsHistory) -> String {
    array(h.runs.iter().map(|r| {
        let bosses = array(r.bosses.iter().map(|b| {
            let players = array(b.players.iter().map(|p| {
                Obj::new()
                    .str("name", &p.name)
                    .int("classType", p.class_type)
                    .int("damage", p.damage)
                    .int("taken", p.taken)
                    .bool("died", p.died)
                    .bool("me", p.me)
                    .opt_str("guild", p.guild.as_deref())
                    .int("skin", p.skin)
                    .int("tex1", p.tex1)
                    .int("tex2", p.tex2)
                    .end()
            }));
            Obj::new()
                .opt_str("name", b.name.as_deref())
                .int("id", b.id)
                .int("objectType", b.object_type)
                .raw("loadout", b.loadout.map(|l| ints(&l)))
                .int("maxHp", b.max_hp)
                .int("endHp", b.end_hp)
                .int("fightMs", b.fight_ms)
                .int("fightStart", b.fight_start)
                .raw("players", Some(players))
                .end()
        }));
        Obj::new()
            .str("dungeon", &r.dungeon)
            .int("start", r.start)
            .int("durationMs", r.duration_ms)
            .bool("guildHall", r.guild_hall)
            .bool("hidden", r.hidden)
            .raw("bosses", Some(bosses))
            .end()
    }))
}

fn visits(d: &crate::dungeon_visits::DungeonVisits) -> String {
    array(d.visits.iter().map(|v| {
        let exalts =
            array(v.exalts.iter().map(|e| {
                Obj::new().int("classType", e.class_type).int("stat", e.stat).int("before", e.before).int("after", e.after).end()
            }));
        let phases = array(v.mv_phases.iter().map(|p| {
            Obj::new()
                .int("boss", p.boss)
                .str("title", &p.title)
                .int("startMs", p.start_ms)
                .int("durationMs", p.duration_ms)
                .bool("lantern", p.lantern)
                .int("lanternMs", p.lantern_ms)
                .int("rangeMs", p.range_ms)
                .int("nearMs", p.near_ms)
                .int("flames", p.flames)
                .int("beats", p.beats)
                .int("beatsMax", p.beats_max)
                .end()
        }));
        let look = v.look.as_ref().map(|l| {
            Obj::new()
                .str("player", &l.player)
                .int("classType", l.class_type)
                .int("skin", l.skin)
                .int("tex1", l.tex1)
                .int("tex2", l.tex2)
                .end()
        });
        Obj::new()
            .str("dungeon", &v.dungeon)
            .int("start", v.start)
            .int("durationMs", v.duration_ms)
            .int("runStart", v.run_start)
            .bool("late", v.late)
            .bool("hidden", v.hidden)
            .raw("exalts", Some(exalts))
            .raw("hitTime", Some(ints(&v.hit_time)))
            .raw("hitDamage", Some(ints(&v.hit_damage)))
            .raw("hitEnemy", Some(ints(&v.hit_enemy)))
            .int("maxHp", v.max_hp)
            .raw("dealt", Some(ints(&v.dealt)))
            .int("lanternActiveMs", v.lantern_active_ms)
            .int("lanternRangeMs", v.lantern_range_ms)
            .int("lanternNearMs", v.lantern_near_ms)
            .raw("amuletSaves", Some(ints(&v.amulet_saves)))
            .raw("quickItem", Some(ints(&v.quick_item)))
            .raw("quickTime", Some(ints(&v.quick_time)))
            .int("diedAt", v.died_at)
            .opt_str("killedBy", v.killed_by.as_deref())
            .raw("moved", Some(ints(&v.moved)))
            .raw("mvPhases", Some(phases))
            .bool("judged", v.judged)
            .bool("victory", v.victory)
            .raw("look", look)
            .end()
    }))
}

fn amulet(a: &crate::amulet_saves::AmuletSaves) -> String {
    array(a.saves.iter().map(|s| {
        Obj::new()
            .int("t", s.t)
            .int("charId", s.char_id)
            .str("amulet", &s.amulet)
            .str("dungeon", &s.dungeon)
            .str("message", &s.message)
            .bool("removed", s.removed)
            .end()
    }))
}

fn pets(p: &crate::pet_store::PetStore) -> String {
    let bag = |b: &crate::pet_store::Saddlebag| {
        Obj::new().raw("items", Some(ints(&b.items))).raw("enchantIds", Some(ints2(&b.enchant_ids))).end()
    };
    gson_out::map(p.pets.iter().map(|(k, p)| {
        let abilities =
            array(p.abilities.iter().map(|a| Obj::new().int("type", a.kind).int("power", a.power).int("points", a.points).end()));
        let v = Obj::new()
            .int("instanceId", p.instance_id)
            .str("name", &p.name)
            .int("type", p.kind)
            .int("skin", p.skin)
            .int("rarity", p.rarity)
            .int("maxPower", p.max_power)
            .raw("abilities", Some(abilities))
            .raw("saddlebag", p.saddlebag.as_ref().map(bag))
            .raw("seasonalSaddlebag", p.seasonal_saddlebag.as_ref().map(bag))
            .int("lastSeen", p.last_seen)
            .end();
        (k.to_string(), Some(v))
    }))
}

fn tinkerer(t: &crate::tinkerer::Tinkerer) -> String {
    array(t.quests.iter().map(|q| {
        Obj::new()
            .str("id", &q.id)
            .str("name", &q.name)
            .str("description", &q.description)
            .str("expiration", &q.expiration)
            .int("category", q.category)
            .raw("requirements", Some(ints(&q.requirements)))
            .raw("rewards", Some(ints(&q.rewards)))
            .bool("completed", q.completed)
            .bool("itemOfChoice", q.item_of_choice)
            .bool("repeatable", q.repeatable)
            .end()
    }))
}

fn dungeon_stats(d: &lg_world::loot::DungeonStats) -> String {
    let info = |name: &str, i: &lg_world::loot::DungeonInfo| {
        let damaged = gson_out::map(i.damaged.iter().map(|(k, v)| (k.to_string(), Some(v.to_string()))));
        let loot = gson_out::map(i.loot.iter().map(|(k, l)| {
            let list = gson_out::map(l.iter().map(|(a, b)| (a.to_string(), Some(b.to_string()))));
            (k.to_string(), Some(Obj::new().raw("lootList", Some(list)).end()))
        }));
        Obj::new()
            .str("name", name)
            .int("enteredDungeon", i.entered)
            .int("totalTime", i.total_time_ms)
            .raw("entityDamaged", Some(damaged))
            .raw("entityLoot", Some(loot))
            .end()
    };
    let data = gson_out::map(d.data.iter().map(|(k, i)| (k.clone(), Some(info(k, i)))));
    let current = d.info.as_ref().and_then(|n| d.data.get(n).map(|i| info(n, i)));
    Obj::new().raw("data", Some(data)).raw("info", current).end()
}

fn slot(s: &crate::holdings::Slot) -> String {
    Obj::new().int("item", s.item).raw("enchantIds", s.enchant_ids.as_deref().map(ints)).end()
}

fn holdings(h: &mut crate::holdings::Holdings) -> String {
    let places = gson_out::map(h.java_place_order().into_iter().map(|k| {
        let p = &h.places[&k];
        let sections = gson_out::map(p.sections.iter().map(|(n, s)| (n.clone(), Some(array(s.iter().map(slot))))));
        let v = Obj::new().raw("sections", Some(sections)).int("seenAt", p.seen_at).opt_str("source", p.source.as_deref()).end();
        (k, Some(v))
    }));
    let order = if h.gone_linked { h.gone_keys.clone() } else { lg_world::fight::java_order_after(&h.gone_ops) };
    let gone = gson_out::map(order.into_iter().filter(|k| h.gone.contains_key(k)).map(|k| {
        let t = h.gone[&k];
        (k, Some(t.to_string()))
    }));
    Obj::new().int("version", 1).raw("places", Some(places)).raw("gone", Some(gone)).end()
}

fn upe(u: &mut crate::upe::Upe) -> String {
    note_keys(&mut u.run_keys, u.runs.keys().copied());
    note_keys(&mut u.mission_keys, u.missions.keys().cloned());
    let titem = |t: &crate::upe::TradeItem| {
        Obj::new().int("item", t.item).raw("enchants", t.enchants.as_deref().map(ints)).bool("clean", t.clean).end()
    };
    let run_order = if u.runs_linked { u.run_keys.clone() } else { lg_world::fight::java_hash_order(&u.run_keys) };
    let runs = gson_out::map(run_order.into_iter().map(|id| {
        let r = &u.runs[&id];
        let gathered = array(r.gathered.iter().map(|g| {
            Obj::new()
                .int("item", g.item)
                .raw("enchants", g.enchants.as_deref().map(ints))
                .int("at", g.at)
                .str("how", &g.how)
                .str("where", &g.r#where)
                .end()
        }));
        let trades = array(r.trades.iter().map(|t| {
            Obj::new()
                .int("at", t.at)
                .int("charId", t.char_id)
                .str("me", &t.me)
                .str("partner", &t.partner)
                .str("mode", &t.mode)
                .bool("standing", t.standing)
                .raw("gave", Some(array(t.gave.iter().map(titem))))
                .raw("got", Some(array(t.got.iter().map(titem))))
                .str("status", &t.status)
                .opt_str("why", t.why.as_deref())
                .end()
        }));
        let v = Obj::new()
            .int("charId", r.char_id)
            .str("mode", &r.mode)
            .bool("fresh", r.fresh)
            .bool("marked", r.marked)
            .int("markedAt", r.marked_at)
            .opt_str("broken", r.broken.as_deref())
            .int("brokenAt", r.broken_at)
            .raw("notes", Some(array(r.notes.iter().map(|n| gson_out::string(n)))))
            .int("lastSeen", r.last_seen)
            .raw("items", Some(ints(&r.items)))
            .raw("enchants", Some(array(r.enchants.iter().map(|e| e.as_deref().map_or("null".into(), ints)))))
            .raw("origin", Some(array(r.origin.iter().map(|o| gson_out::string(o)))))
            .raw("since", Some(gson_out::longs(&r.since)))
            .int("level", r.level)
            .raw("baseStats", r.base_stats.map(|b| ints(&b)))
            .raw("allowed", Some(ints2(&r.allowed)))
            .raw("gathered", Some(gathered))
            .bool("guildLocked", r.guild_locked)
            .raw("trades", Some(trades))
            .raw("t0Start", r.t0_start.map(|b| b.to_string()))
            .bool("pet", r.pet)
            .bool("noPet", r.no_pet)
            .end();
        (id.to_string(), Some(v))
    }));
    let left = array(u.left.iter().map(|l| {
        Obj::new()
            .int("item", l.item)
            .raw("enchants", Some(ints(&l.enchants)))
            .int("from", l.from)
            .int("to", l.to)
            .str("vault", &l.vault)
            .end()
    }));
    let keys: Vec<(String, i32)> = u.mission_keys.iter().map(|k| (k.clone(), lg_world::fight::java_string_hash(k))).collect();
    let mission_order = if u.runs_linked { u.mission_keys.clone() } else { lg_world::fight::java_order(&keys, false) };
    let missions = gson_out::map(mission_order.into_iter().map(|k| {
        let m = &u.missions[&k];
        (
            k,
            Some(
                Obj::new()
                    .int("charId", m.char_id)
                    .bool("fromZero", m.from_zero)
                    .bool("clean", m.clean)
                    .int("startedAt", m.started_at)
                    .end(),
            ),
        )
    }));
    Obj::new().int("version", 1).raw("runs", Some(runs)).raw("left", Some(left)).raw("missions", Some(missions)).end()
}

/// Java's line ends, as `System.lineSeparator()`.
pub const LINE_END: &str = if cfg!(windows) { "\r\n" } else { "\n" };

/// The files the app keeps; one of them in a folder means the folder holds the app's data.
pub const KEPT: [&str; 17] = [
    "characters.json",
    "collection.json",
    "holdings.json",
    "holdings-journal.txt",
    "profile.json",
    "upe.json",
    "dps_history.json",
    "dungeon_visits.json",
    "exalts.json",
    "exalt_changes.json",
    "amulet_saves.json",
    "pets.json",
    "tinkerer-quests.json",
    "friends.txt",
    "missions-seasons.json",
    "missions-player.xml",
    "dungeon.stats",
];

/// The app's own folder: next to the program, as the Java app keeps its files next to itself.
pub fn own_folder() -> std::io::Result<std::path::PathBuf> {
    let exe = std::env::current_exe()?;
    Ok(exe.parent().map_or_else(|| std::path::PathBuf::from("."), Path::to_path_buf))
}

/// Whether a folder holds the app's data.
pub fn holds_data(dir: &Path) -> bool {
    KEPT.iter().any(|f| dir.join(f).exists())
}

impl App {
    /// The app on starting: its own folder's data; on a first start (nothing there yet) the Java app's folder taken
    /// over (read only) and written into its own at once. Nothing at all: a new app.
    pub fn start(world: lg_world::World, now: i64, own: &Path, java: Option<&Path>) -> std::io::Result<App> {
        if holds_data(own) {
            return Ok(App::importing(world, now, Some(own)));
        }
        let Some(java) = java.filter(|j| holds_data(j)) else {
            let mut app = App::importing(world, now, None);
            app.save_changed(own)?;
            return Ok(app);
        };
        let mut app = App::importing(world, now, Some(java));
        app.copy_from_java(java);
        app.save_changed(own)?;
        Ok(app)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_start_takes_the_java_folder_over() {
        let base = std::env::temp_dir().join(format!("lg-files-{}", std::process::id()));
        let (java, own) = (base.join("java"), base.join("own"));
        std::fs::create_dir_all(&java).unwrap();
        std::fs::create_dir_all(&own).unwrap();
        let chars = r#"{"7":{"charId":7,"classType":768,"className":"Rogue","skin":0,"tex1":0,"tex2":0,"petSkin":0,"level":20,"maxHp":0,"fame":5,"statsMaxed":0,"seasonal":false,"crucible":false,"ritual":false,"favorite":false,"lootDropUntil":0,"lootTierUntil":0,"favoriteAt":0,"backpack":false,"extender":false,"alive":true,"aliveAt":0,"lastPlayed":0,"samples":[]}}"#;
        std::fs::write(java.join("characters.json"), chars).unwrap();
        std::fs::write(java.join("holdings-journal.txt"), "line one\n").unwrap();
        let world = || lg_world::World::new(lg_gamedata::GameData::default());
        let app = App::start(world(), 0, &own, Some(&java)).unwrap();
        assert!(app.characters.get(7).is_some());
        assert_eq!(std::fs::read_to_string(own.join("characters.json")).unwrap(), chars, "the same file, byte for byte");
        assert_eq!(std::fs::read_to_string(own.join("holdings-journal.txt")).unwrap(), format!("line one{LINE_END}"));
        // the next start reads its own folder; the Java folder is left as it was
        std::fs::write(java.join("characters.json"), "{}").unwrap();
        let again = App::start(world(), 0, &own, Some(&java)).unwrap();
        assert!(again.characters.get(7).is_some());
        assert_eq!(std::fs::read_to_string(own.join("holdings-journal.txt")).unwrap(), format!("line one{LINE_END}"), "not copied twice");
        std::fs::remove_dir_all(&base).unwrap();
    }
}
