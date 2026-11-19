//! What the app asks the game's web API with your login token (Java: `TomatoData.updateToken`,
//! `charListHttpRequest`, `characterListUpdate`, `CharacterStore.saveRawList`, `updateExalts`, `Friends.fetch`,
//! `Missions.tokenReady`).
//!
//! Java asks on threads of their own; here each fetch runs to its end where Java starts it (the comparison with
//! Java runs Java's that way too). Missions only ask for now: their answers are read in a later step.

use crate::realm_character::{self, Exalts};
use crate::{App, holdings};
use lg_world::Abort;

const FRIENDS_EVERY_MS: i64 = 10 * 60_000;
const MISSIONS_EVERY_MS: i64 = 5 * 60_000;

/// Missions, as far as ported: when they were asked for (Java: `Missions.token`, `asked`, `fetching`), and the
/// answers kept.
#[derive(Debug, Default)]
pub struct Missions {
    pub token: Option<String>,
    pub asked: i64,
    pub fetching: bool,
    pub seasons: Option<String>,
    pub player: Option<String>,
}

impl App {
    /// HELLO: the game sent your login token (Java `updateToken`). Aborts where Java throws (a refused exaltation
    /// answer).
    pub(crate) fn hello(&mut self, token: &str, now: i64) -> Result<(), Abort> {
        self.token = Some(token.to_string());
        if self.characters.token_ready(token, now) {
            self.fetch_char_list(now);
        }
        self.fetch_friends(token, now);
        self.missions.token = Some(token.to_string());
        if now - self.missions.asked >= MISSIONS_EVERY_MS {
            self.fetch_missions(now);
        }
        self.update_exalts(token, now)
    }

    /// `CharacterStore.fetchAll`'s thread: the list, and the token it was asked with if it came.
    pub(crate) fn fetch_char_list(&mut self, now: i64) {
        let token = self.characters.login.fetched_token.clone();
        let n = self.char_list_request(now);
        self.characters.fetched(n, token);
    }

    /// Java `charListHttpRequest`: the whole list (with the vault; failing that, without), read into the records.
    /// How many characters came (0: none, refused, or Java threw while reading it).
    pub(crate) fn char_list_request(&mut self, now: i64) -> i32 {
        let Some(token) = self.token.clone() else { return 0 };
        let full = self.web.request(&token, "char/list", "&muleDump=true");
        let answer = match full {
            Some(f) if !f.contains("<Error") => Some(f),
            _ => self.web.request(&token, "char/list", ""),
        };
        let Some(answer) = answer else { return 0 };
        let list = match realm_character::char_list(&answer, &mut self.realm_exalts) {
            Ok(Some(l)) if !l.is_empty() => l,
            _ => return 0,
        };
        if self.character_list_update(&list, now).is_err() {
            return 0;
        }
        self.save_raw_list(&answer, now);
        list.len() as i32
    }

    /// Java `characterListUpdate`: into the characters (and the UPE rules' pet check); then Tomato's pet of the
    /// character you play, which throws for one whose pet has no abilities.
    fn character_list_update(&mut self, list: &[realm_character::RealmCharacter], now: i64) -> Result<(), crate::java::Thrown> {
        let pets = self.characters.import_list(self.world.data(), list, now)?;
        let c = crate::upe::Ctx { data: self.world.data(), zone: &self.zone, now };
        self.upe.pets(&c, &pets, now);
        let me = self.world.char_id;
        if me != -1
            && let Some(r) = list.iter().find(|r| r.char_id == me)
            && r.pet_abilities.is_none()
        {
            return Err(crate::java::Thrown); // makePet: currentChar.petAbilitys[0]
        }
        Ok(())
    }

    /// Java `saveRawList`: the enchantments of what each character holds, the characters gone from the list, the
    /// vault, the pets; each on its own (what throws is only logged), then the list is kept for the next start.
    fn save_raw_list(&mut self, xml: &str, now: i64) {
        if crate::java::is_blank(xml) {
            return;
        }
        let time = self.zone.local_date_time(now);
        let ctx = holdings::Ctx { data: self.world.data(), now, time: &time };
        let _ = self.characters.import_item_data(xml, now, Some((&ctx, &mut self.collection, &mut self.holdings)));
        let _ = self.characters.bury_missing(xml, now);
        self.holdings.seed_once(&ctx, &self.characters.living());
        self.holdings.listed_vault(&ctx, holdings::vault_from_char_list(xml));
        let _ = self.pets.import_char_list(xml, now);
        self.characters.saved_list = Some(xml.to_string()); // char-list.xml, read at the first lookup if not yet
        self.vault_changes();
    }

    /// Java `updateExalts`: once, the account's exaltations; progress beyond what was kept from earlier was earned
    /// since (credited to the dungeon visits), then kept.
    fn update_exalts(&mut self, token: &str, now: i64) -> Result<(), Abort> {
        if self.updated_exalts {
            return Ok(());
        }
        self.updated_exalts = true;
        let Some(answer) = self.web.request(token, "account/listPowerUpStats", "") else {
            return Err(Abort("a refused exaltation answer (Java parses null)"));
        };
        let ok =
            realm_character::exalts_answer(&answer, &mut self.realm_exalts).map_err(|_| Abort("an exaltation answer Java cannot read"))?;
        if !ok {
            return Ok(());
        }
        // new HashMap<>(RealmCharacter.exalts): its order
        let keys: Vec<(i32, i32)> = self.realm_exalts.keys().map(|&k| (k, k)).collect();
        let order = lg_world::fight::java_bucket_order(&keys, lg_world::fight::java_buckets_copied(keys.len()));
        for cls in order {
            let value = self.realm_exalts[&cls].clone();
            if let (Some(kept), Some(v)) = (self.exalts.progress(cls).cloned(), value)
                && kept != v
            {
                self.dungeon_visits.exalt_progress(cls, &kept, &v, now);
            }
        }
        let all: Vec<(i32, Option<Vec<i32>>)> = self.realm_exalts.iter().map(|(k, v)| (*k, v.clone())).collect();
        self.exalts.update_all(&all, now);
        self.profile.track_exalts(&self.exalts.changes, now);
        Ok(())
    }

    /// Java `Friends.fetch`: at most every 10 minutes.
    fn fetch_friends(&mut self, token: &str, now: i64) {
        if now - self.friends_asked < FRIENDS_EVERY_MS {
            return;
        }
        self.friends_asked = now;
        let Some(xml) = self.web.request(token, "friends/getList", "") else { return };
        if crate::java::is_blank(&xml) || xml.contains("<Error") {
            return;
        }
        self.friends.answered(&xml);
    }

    /// Java `Missions.fetch`: the missions and your progress.
    fn fetch_missions(&mut self, now: i64) {
        let Some(t) = self.missions.token.clone() else { return };
        if self.missions.fetching {
            return;
        }
        self.missions.fetching = true;
        self.missions.asked = now;
        let seasons = self.web.request(&t, "missions/getClientSeasons", "");
        let player = self.web.request(&t, "missions/getPlayerMissions", "");
        if let (Some(s), Some(p)) = (seasons, player)
            && !s.contains("<Error")
            && !p.contains("<Error")
        {
            self.missions.seasons = Some(s);
            self.missions.player = Some(p);
        }
        self.missions.fetching = false;
    }

    /// The login part of the dump, as `oracle/WorldOracle.java` writes Java's (tokens as checksums).
    pub(crate) fn login_dump(&self) -> String {
        let crc = |t: &Option<String>| t.as_ref().map_or("null".into(), |t| format!("#{:08x}", lg_protocol::dump::crc32(t.as_bytes())));
        let l = &self.characters.login;
        let ex: Vec<String> = self
            .realm_exalts
            .iter()
            .map(|(k, v)| {
                format!(
                    "{k}={}",
                    v.as_ref().map_or("null".into(), |v| format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(",")))
                )
            })
            .collect();
        let friends: Vec<&str> = self.friends.names.iter().map(String::as_str).collect();
        format!(
            "login token={} fetcher={} fetched={} list={} last={} fetching={} exaltsAsked={} savedList={}/{}\nrealm exalts [{}]\nfriends [{}] asked={}\nmissions asked={} fetching={} seasons={} player={}\nweb [{}]\n",
            crc(&self.token),
            l.fetcher,
            crc(&l.fetched_token),
            crc(&l.list_token),
            l.last_fetch,
            l.fetching,
            self.updated_exalts,
            self.characters.saved_list.as_ref().map_or("null".into(), |s| format!("{:08x}", lg_protocol::dump::crc32(s.as_bytes()))),
            self.characters.saved_list_read,
            ex.join(","),
            friends.join(","),
            self.friends_asked,
            self.missions.asked,
            self.missions.fetching,
            self.missions.seasons.as_ref().map_or("null".into(), |s| format!("{:08x}", lg_protocol::dump::crc32(s.as_bytes()))),
            self.missions.player.as_ref().map_or("null".into(), |s| format!("{:08x}", lg_protocol::dump::crc32(s.as_bytes()))),
            self.web.requests().join(",")
        )
    }
}

/// Tomato's exaltations, for the type's users.
pub type RealmExalts = Exalts;
