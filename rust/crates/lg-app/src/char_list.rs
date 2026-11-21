//! The character list from the game's servers, into your characters (Java: `CharacterStore.importList`,
//! `importItemData`, `buryMissing`), and when it is asked for (`tokenReady`, `fetchAll`, `enteredNexus`).

use crate::characters::{Char, Characters};
use crate::collection::{self, Collection, Find, Seen};
use crate::holdings::{self, Holdings};
use crate::java::{self, Thrown};
use crate::patterns::{self, Attr};
use crate::realm_character::RealmCharacter;
use lg_gamedata::GameData;
use std::collections::BTreeMap;

/// Be gentle with the game servers: at most one list this often.
pub(crate) const MIN_FETCH_MS: i64 = 10_000;
const FAVORITE_TAGS: [&str; 6] = ["Favorite", "Favourite", "IsFavorite", "Favorited", "Locked", "IsLocked"];
pub const EXTENDER_ITEM: i32 = 65280;

/// A character's collectible copies, by item.
type Copies = Vec<(i32, Vec<Find>)>;

/// Asking for the list with the login token.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Login {
    /// The game sent a token at least once (Java: `fetcher` is set).
    pub fetcher: bool,
    /// The token the list was last asked with, and the one whose list came in.
    pub fetched_token: Option<String>,
    pub list_token: Option<String>,
    pub last_fetch: i64,
    pub fetching: bool,
}

/// Java `intTag`: a tag as a number, 0 when missing or not one; Java throws for a tag without a value.
fn int_tag(r: &RealmCharacter, name: &str) -> Result<i32, Thrown> {
    match r.raw_tags.iter().find(|(k, _)| k == name) {
        None => Ok(0),
        Some((_, None)) => Err(Thrown), // getOrDefault gives the null: null.trim()
        Some((_, Some(v))) => Ok(java::parse_int(java::trim(v)).unwrap_or(0)),
    }
}

fn favorite(r: &RealmCharacter) -> Option<bool> {
    FAVORITE_TAGS.iter().find_map(|t| r.tag(t)).map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
}

fn extender(r: &RealmCharacter) -> bool {
    r.raw_tags
        .iter()
        .any(|(k, v)| k.to_lowercase().contains("extender") && v.as_deref().is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true")))
        || r.equipment.as_ref().is_some_and(|e| e.contains(&EXTENDER_ITEM))
}

fn dungeons_done(r: &RealmCharacter) -> Option<BTreeMap<String, i32>> {
    let counts = r.dungeon_counts.as_ref()?;
    Some(lg_gamedata::char_stats::dungeon_names().zip(counts).filter(|(_, n)| **n > 0).map(|(name, n)| (name.to_string(), *n)).collect())
}

fn maxed(data: &GameData, class_type: i32, stats: [i32; 8]) -> i32 {
    data.class(class_type).map_or(0, |k| (0..8).filter(|&i| stats[i] >= k.max_stats[i]).count() as i32)
}

/// Java `intOr`.
fn int_or(s: &str, fallback: i32) -> i32 {
    java::parse_int(java::trim(s)).unwrap_or(fallback)
}

impl Characters {
    fn char_entry(&mut self, id: i32) -> &mut Char {
        if !self.chars.contains_key(&id) {
            self.order.push(id);
        }
        self.chars.entry(id).or_default()
    }

    /// Java `importList`: adds or updates every character of the list. The pets each has (for the UPE rules), in
    /// the list's order; Err where Java throws (what was done stays).
    pub fn import_list(&mut self, data: &GameData, list: &[RealmCharacter], now: i64) -> Result<Vec<(i32, bool)>, Thrown> {
        if !list.is_empty() && self.login.fetched_token.is_some() {
            self.login.list_token = self.login.fetched_token.clone();
        }
        let mut pets: Vec<(i32, bool)> = Vec::new();
        for r in list {
            let has_pet = r.pet_skin > 0 || r.pet_type > 0;
            match pets.iter_mut().find(|(k, _)| *k == r.char_id) {
                Some(p) => p.1 = has_pet,
                None => pets.push((r.char_id, has_pet)),
            }
            let class_type = i32::from(r.class_num);
            // in Java's order: what is set before a tag it cannot read stays
            let c = self.char_entry(r.char_id);
            c.char_id = r.char_id;
            c.class_type = class_type;
            c.class_name = data.class(class_type).map_or("Character".into(), |k| k.name.clone());
            let shown = int_tag(r, "TextureOverride")?;
            if shown > 0 {
                c.skin = shown;
            } else if r.skin > 0 {
                c.skin = r.skin;
            }
            c.tex1 = data.dye_texture(int_tag(r, "Tex1")?);
            c.tex2 = data.dye_texture(int_tag(r, "Tex2")?);
            if has_pet {
                c.pet_skin = if r.pet_skin > 0 { r.pet_skin } else { r.pet_type };
                c.pet_name = r.pet_name.clone();
            }
            c.level = r.level;
            if let Some(done) = dungeons_done(r) {
                c.dungeons = Some(done);
            }
            if r.fame > 0 {
                c.fame = r.fame;
            }
            c.seasonal = r.seasonal;
            c.stats_maxed = maxed(data, class_type, [r.hp, r.mp, r.atk, r.def, r.spd, r.dex, r.vit, r.wis]);
            let slots = int_tag(r, "BackpackSlots")?;
            c.backpack = r.backpack || slots > 0;
            c.extender = slots > 8 || extender(r);
            if let Some(e) = r.equipment.as_ref().filter(|e| e.len() >= 4) {
                let equip = e[..4].to_vec();
                if c.equipment.as_ref() != Some(&equip) {
                    c.enchants = None;
                    c.enchant_ids = None;
                }
                c.equipment = Some(equip);
            }
            if let Some(f) = favorite(r)
                && f != c.favorite
            {
                c.favorite = f;
                c.favorite_at = now;
            }
            match r.tag("Dead") {
                Some(d) if d.eq_ignore_ascii_case("true") => {
                    if c.alive {
                        c.alive_at = now;
                    }
                    c.alive = false;
                    c.died_by = Some("game".into());
                }
                Some(d) if d.eq_ignore_ascii_case("false") && c.died_by.as_deref() == Some("game") => {
                    c.alive = true;
                    c.died_by = None;
                    c.alive_at = now;
                }
                _ => {}
            }
        }
        self.save(now);
        Ok(pets)
    }

    /// Java `importItemData`: the enchantments of everything each character holds, from the raw list: its
    /// UniqueItemInfo's items matched to the slots of its Equipment by item type, in order. `live`: also counted as
    /// what you have (`Some`: the collection and holdings to tell); without, only the characters learn it (the
    /// list saved at the last login).
    pub fn import_item_data(
        &mut self,
        xml: &str,
        now: i64,
        live: Option<(&holdings::Ctx, &mut Collection, &mut Holdings)>,
    ) -> Result<(), Thrown> {
        let mut held: Vec<(i32, Copies)> = Vec::new();
        let mut seen: Vec<(i32, Seen)> = Vec::new();
        let mut listed: Vec<holdings::Listed> = Vec::new();
        let at = live.as_ref().map(|l| (l.0.now, l.0.time.to_string()));
        for (id, body) in patterns::chars(xml) {
            let id = java::parse_int(id)?;
            if !self.chars.contains_key(&id) {
                continue;
            }
            let body = patterns::without_pets(body);
            let slots: Option<Vec<i32>> =
                patterns::texts(&body, "Equipment").first().map(|e| java::split(e, ',').into_iter().map(|s| int_or(s, -1)).collect());
            let mut data: Vec<(i32, Vec<i32>)> = Vec::new();
            if let (Some(start), Some(end)) = (body.find("<UniqueItemInfo>"), body.find("</UniqueItemInfo>"))
                && end > start
            {
                for (t, code) in patterns::item_data(&body[start..end], Attr::TypeDigits) {
                    data.push((java::parse_int(t)?, lg_gamedata::enchants::enchant_ids(Some(java::trim(code)))));
                }
            }
            let Some(slots) = slots else { continue };
            let ids: Vec<Vec<i32>> = slots
                .iter()
                .map(|s| match data.iter().position(|d| d.0 == *s) {
                    Some(k) => data.remove(k).1,
                    None => Vec::new(),
                })
                .collect();
            let c = self.chars.get_mut(&id).expect("known");
            c.slots = Some(slots.clone());
            c.slot_enchant_ids = Some(ids.clone());
            if c.alive
                && let (Some(l), Some((t, time))) = (&live, &at)
            {
                let copies = collectibles(l.0.data, c, *t, time);
                match held.iter_mut().find(|h| h.0 == id) {
                    Some(h) => h.1 = copies, // Java's map: the later one replaces it
                    None => held.push((id, copies)),
                }
                listed.push(holdings::Listed {
                    char_id: id,
                    slots: Some(slots.clone()),
                    enchant_ids: Some(ids.clone()),
                    quick: c.quickslots.clone(),
                });
            }
            if live.is_some() {
                seen.retain(|x| x.0 != id);
                seen.push((id, Seen::character(c)));
            }
            if c.equipment.as_ref().is_some_and(|e| slots.len() >= 4 && e[..] == slots[..4]) {
                let four = ids[..4].to_vec();
                c.enchants = Some(four.iter().map(|x| x.len() as i32).collect());
                c.enchant_ids = Some(four);
            }
        }
        self.save(now);
        let Some((ctx, col, hold)) = live else { return Ok(()) };
        // every character's copies count like a live look at it; in Java's HashMap order of the characters
        let order = lg_world::fight::java_hash_order(&held.iter().map(|h| h.0).collect::<Vec<_>>());
        for id in order {
            let copies = &mut held.iter_mut().find(|h| h.0 == id).expect("held").1;
            let s = seen.iter().find(|x| x.0 == id).map(|x| x.1.clone());
            for (_, finds) in copies.iter_mut() {
                for f in finds {
                    f.seen = s.clone();
                }
            }
            if !copies.is_empty() {
                col.record_held(copies);
            }
        }
        // Holdings.get(): its first use seeds it from the characters as they are now
        hold.seed_once(ctx, &self.living());
        hold.characters(ctx, &listed);
        Ok(())
    }

    /// Java `buryMissing`: ties the listed characters to the list's account, and buries that account's characters
    /// missing from it (deleted, or died unseen), but none created after the list.
    pub fn bury_missing(&mut self, xml: &str, now: i64) -> Result<(), Thrown> {
        let Some(account) = list_account(xml) else { return Ok(()) };
        let mut listed = Vec::new();
        for (id, _) in patterns::chars(xml) {
            listed.push(java::parse_int(id)?);
        }
        if listed.is_empty() {
            return Ok(());
        }
        let next_id = next_char_id(xml)?;
        let mut changed = false;
        for id in &listed {
            if let Some(c) = self.chars.get_mut(id)
                && c.account.as_deref() != Some(account.as_str())
            {
                c.account = Some(account.clone());
                changed = true;
            }
        }
        for id in self.order.clone() {
            let c = self.chars.get_mut(&id).expect("known");
            if !c.alive || listed.contains(&c.char_id) || c.char_id >= next_id {
                continue;
            }
            if !c.account.as_deref().is_some_and(|a| a.to_lowercase() == account.to_lowercase()) {
                continue;
            }
            c.alive = false;
            c.died_by = Some("list".into());
            c.alive_at = now;
            changed = true;
        }
        if changed {
            self.save(now);
        }
        Ok(())
    }

    /// Java `tokenReady`: the game sent your login token; the first time per login, the whole list is asked for.
    /// Whether to fetch now.
    pub fn token_ready(&mut self, token: &str, now: i64) -> bool {
        self.login.fetcher = true;
        if self.login.fetched_token.as_deref() == Some(token) {
            return false;
        }
        self.login.fetched_token = Some(token.to_string());
        self.login.last_fetch = 0;
        self.fetch_all(now)
    }

    /// Java `enteredNexus`: unless this login's list came in already. Whether to fetch now.
    pub fn entered_nexus(&mut self, now: i64) -> bool {
        if !self.login.fetcher || self.login.fetched_token.is_none() || self.login.fetched_token == self.login.list_token {
            return false;
        }
        self.fetch_all(now)
    }

    /// Java `fetchAll`'s checks: one fetch at a time, at most every 10 s. Whether to fetch now (then
    /// [`Characters::fetched`] once it is done).
    pub fn fetch_all(&mut self, now: i64) -> bool {
        if !self.login.fetcher || self.login.fetching || self.login.last_fetch + MIN_FETCH_MS - now > 0 {
            return false;
        }
        self.login.fetching = true;
        self.login.last_fetch = now;
        true
    }

    /// The fetch started by `fetch_all` is done: how many characters came (the token it was asked with).
    pub fn fetched(&mut self, n: i32, token: Option<String>) {
        if n > 0 {
            self.login.list_token = token;
        }
        self.login.fetching = false;
    }

    /// The list saved at the last login, read on the first enchantment lookup (Java `slotEnchants`).
    pub fn read_saved_list(&mut self, now: i64) {
        if self.saved_list_read {
            return;
        }
        self.saved_list_read = true;
        if let Some(xml) = self.saved_list.clone() {
            let _ = self.import_item_data(&xml, now, None); // Java logs what goes wrong
        }
    }
}

/// Java `collectibles`: a character's collectible copies by its slots and quick slots, in Java's HashMap order.
fn collectibles(data: &GameData, c: &Char, now: i64, time: &str) -> Copies {
    let mut out: Copies = Vec::new();
    let mut put = |item: i32, f: Find| match out.iter_mut().find(|(i, _)| *i == item) {
        Some((_, v)) => v.push(f),
        None => out.push((item, vec![f])),
    };
    let collectible = |i: i32| i > 0 && data.items.item(i).is_some_and(|x| x.is_collectible());
    let slots = c.slots.as_deref().unwrap_or(&[]);
    for (i, &s) in slots.iter().enumerate() {
        if !collectible(s) {
            continue;
        }
        let ids = c.slot_enchant_ids.as_ref().and_then(|e| e.get(i)).cloned();
        // LootEvents.slotName: worn or not (no backpack)
        let mut f = Find::with_ids(collection::INVENTORY, ids, "", if i < 4 { "Equipped" } else { "Inventory" }, now, time);
        f.seasonal = Some(c.seasonal);
        put(s, f);
    }
    for &q in c.quickslots.as_deref().unwrap_or(&[]) {
        if !collectible(q) {
            continue;
        }
        let mut f = Find::of(collection::INVENTORY, -1, "", "Quick slot", now, time);
        f.seasonal = Some(c.seasonal);
        put(q, f);
    }
    // out.computeIfAbsent: new items at the head of their bucket
    let order = lg_world::fight::java_hash_order_computed(&out.iter().map(|o| o.0).collect::<Vec<_>>());
    order.into_iter().map(|k| out.iter().find(|o| o.0 == k).expect("item").clone()).collect()
}

/// Java `listAccount`: the account name of the list (its own Account block, not those inside the characters).
pub fn list_account(xml: &str) -> Option<String> {
    let mut rest = String::new();
    let mut from = 0;
    // CHAR_BLOCK.replaceAll(""): <Char id="\d+">.*?</Char>
    for (id, body) in patterns::chars(xml) {
        let start = id.as_ptr() as usize - xml.as_ptr() as usize - "<Char id=\"".len();
        let end = body.as_ptr() as usize - xml.as_ptr() as usize + body.len() + "</Char>".len();
        rest.push_str(&xml[from..start]);
        from = end;
    }
    rest.push_str(&xml[from..]);
    // <Account>.*?<Name>([^<]+)</Name>
    let mut search = 0;
    while let Some(i) = rest[search..].find("<Account>") {
        let at = search + i + "<Account>".len();
        // the first <Name> after it whose text ends in </Name>; failing that, the next <Account>
        let mut k = at;
        while let Some(j) = rest[k..].find("<Name>") {
            let v = k + j + "<Name>".len();
            let n = rest[v..].find('<').unwrap_or(rest.len() - v);
            if n > 0 && rest[v + n..].starts_with("</Name>") {
                return Some(java::trim(&rest[v..v + n]).to_string());
            }
            k = k + j + 1;
        }
        search = search + i + 1;
    }
    None
}

/// Java `nextCharId`: `<Chars\b[^>]*\bnextCharId="(\d+)"`; i32::MAX without one (Java throws past an int).
pub fn next_char_id(xml: &str) -> Result<i32, Thrown> {
    let mut from = 0;
    while let Some(i) = xml[from..].find("<Chars") {
        let at = from + i;
        let after = at + "<Chars".len();
        let word = |c: u8| c.is_ascii_alphanumeric() || c == b'_';
        if !xml.as_bytes().get(after).is_some_and(|c| word(*c))
            && let Some(gt) = xml[after..].find('>').map(|g| after + g).or(Some(xml.len()))
        {
            // greedy [^>]*: the last nextCharId=" in the tag with digits and a quote after it
            let tag = &xml[after..gt];
            let mut best = None;
            let mut k = 0;
            while let Some(j) = tag[k..].find("nextCharId=\"") {
                let p = k + j;
                let v = p + "nextCharId=\"".len();
                let n = tag.as_bytes()[v..].iter().take_while(|b| b.is_ascii_digit()).count();
                let before_ok = p == 0 || !word(tag.as_bytes()[p - 1]);
                if before_ok && n > 0 && tag.as_bytes().get(v + n) == Some(&b'"') {
                    best = Some(&tag[v..v + n]);
                }
                k = p + 1;
            }
            if let Some(v) = best {
                return java::parse_int(v);
            }
        }
        from = at + 1;
    }
    Ok(i32::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_and_next_id() {
        let xml = r#"<Chars maxNumChars="20" nextCharId="31"><Char id="5"><Account><Name>Pet</Name></Account></Char><Account><Name> Me </Name></Account></Chars>"#;
        assert_eq!(list_account(xml).as_deref(), Some("Me"));
        assert_eq!(next_char_id(xml), Ok(31));
        assert_eq!(next_char_id("<Chars>"), Ok(i32::MAX));
        assert_eq!(next_char_id(r#"<Chars nextCharId="99999999999">"#), Err(Thrown));
    }
}
