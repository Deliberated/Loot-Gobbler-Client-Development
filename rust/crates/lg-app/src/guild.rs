//! Your guild on the Loot Gobbler server (Java `GobblerServer`'s guild classes and `ItemScore`): the leaderboard,
//! the recent drops, a member's profile, and what a found item is worth.

use crate::server::{long, text};
use lg_world::json::Json;

/// How often a member found an item in one place with that many enchantments (-1 = unknown).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Tally {
    pub item_id: i32,
    pub r#where: String,
    pub enchants: i32,
    pub count: i64,
}

/// A guild member and their leaderboard numbers.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Member {
    pub id: i64,
    pub ign: String,
    pub guild_rank: i32,
    /// The last upload of any data (0 = never).
    pub last_active: i64,
    pub items: i64,
    pub finds: i64,
    pub fame: i64,
    pub exalts: i64,
    pub characters: i64,
    pub maxed: i64,
    pub points: i64,
    pub tally: Vec<Tally>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Leaderboard {
    pub guild: String,
    pub members: Vec<Member>,
}

/// One drop in the guild feed; `time` is the dropping player's local time.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct GuildDrop {
    pub user_id: i64,
    pub ign: String,
    pub item_id: i32,
    pub time: String,
    pub enchants: i32,
    pub r#where: String,
    pub bag: String,
    pub enchant_ids: Option<Vec<i32>>,
}

/// A member's profile: characters, exaltations, collection counts.
#[derive(Debug, Clone, Default)]
pub struct MemberProfile {
    pub guild: String,
    pub member: Member,
    pub characters: Vec<crate::characters::Char>,
    pub exalts: Vec<(String, Vec<i32>)>,
    /// Item id (as sent) -> copies found.
    pub collection: Vec<(String, i64)>,
}

/// One item in a caravan member's loot.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CaravanItem {
    pub item_id: i32,
    pub enchants: i32,
    pub bag: String,
    pub time: String,
    pub r#where: String,
}

/// A caravan member's most played character in the range.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CaravanCharacter {
    pub class_type: i32,
    pub class_name: String,
    pub skin: i32,
    pub tex1: i32,
    pub tex2: i32,
    pub fame: i64,
}

/// A guild member in the loot caravan.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CaravanMember {
    pub id: i64,
    pub ign: String,
    pub guild_rank: i32,
    /// Bag colour -> items found, in the server's order.
    pub by_bag: Vec<(String, i64)>,
    pub total: i64,
    /// Newest first, at most 120.
    pub items: Vec<CaravanItem>,
    pub tally: Vec<Tally>,
    pub last_drop: Option<String>,
    pub character: Option<CaravanCharacter>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Caravan {
    pub guild: String,
    pub range: String,
    pub members: Vec<CaravanMember>,
}

fn int(v: Option<&Json>, default: i32) -> i32 {
    match v {
        Some(Json::Number(_) | Json::String(_)) => long(v) as i32,
        _ => default,
    }
}

fn list<T>(v: Option<&Json>, f: impl Fn(&Json) -> T) -> Vec<T> {
    match v {
        Some(Json::Array(a)) => a.iter().map(f).collect(),
        _ => Vec::new(),
    }
}

fn opt_text(v: Option<&Json>) -> Option<String> {
    match v {
        Some(Json::String(s)) => Some(s.clone()),
        _ => None,
    }
}

pub fn tally_of(j: &Json) -> Tally {
    Tally {
        item_id: int(j.get("itemId"), 0),
        r#where: text(j.get("where")),
        enchants: int(j.get("enchants"), -1),
        count: long(j.get("count")),
    }
}

pub fn member_of(j: &Json) -> Member {
    Member {
        id: long(j.get("id")),
        ign: text(j.get("ign")),
        guild_rank: int(j.get("guildRank"), 0),
        last_active: long(j.get("lastActive")),
        items: long(j.get("items")),
        finds: long(j.get("finds")),
        fame: long(j.get("fame")),
        exalts: long(j.get("exalts")),
        characters: long(j.get("characters")),
        maxed: long(j.get("maxed")),
        points: long(j.get("points")),
        tally: list(j.get("tally"), tally_of),
    }
}

pub fn leaderboard_of(j: &Json) -> Leaderboard {
    Leaderboard { guild: text(j.get("guild")), members: list(j.get("members"), member_of) }
}

pub fn drops_of(j: &Json) -> Vec<GuildDrop> {
    list(j.get("drops"), |d| GuildDrop {
        user_id: long(d.get("userId")),
        ign: text(d.get("ign")),
        item_id: int(d.get("itemId"), 0),
        time: text(d.get("time")),
        enchants: int(d.get("enchants"), -1),
        r#where: text(d.get("where")),
        bag: text(d.get("bag")),
        enchant_ids: match d.get("enchantIds") {
            Some(Json::Array(a)) => Some(a.iter().map(|x| int(Some(x), 0)).collect()),
            _ => None,
        },
    })
}

pub fn profile_of(j: &Json) -> MemberProfile {
    let characters = match j.get("characters") {
        Some(c @ Json::Object(_)) => {
            crate::characters::parse_chars(&c.write()).map(|v| v.into_iter().map(|(_, c)| c).collect()).unwrap_or_default()
        }
        _ => Vec::new(),
    };
    let exalts = match j.get("exalts") {
        Some(Json::Object(o)) => o.iter().map(|(k, v)| (k.clone(), list(Some(v), |x| int(Some(x), 0)))).collect(),
        _ => Vec::new(),
    };
    let collection = match j.get("collection") {
        Some(Json::Object(o)) => o.iter().map(|(k, v)| (k.clone(), long(Some(v)))).collect(),
        _ => Vec::new(),
    };
    MemberProfile {
        guild: text(j.get("guild")),
        member: j.get("member").map(member_of).unwrap_or_default(),
        characters,
        exalts,
        collection,
    }
}

pub fn caravan_of(j: &Json) -> Caravan {
    Caravan {
        guild: text(j.get("guild")),
        range: text(j.get("range")),
        members: list(j.get("members"), |m| CaravanMember {
            id: long(m.get("id")),
            ign: text(m.get("ign")),
            guild_rank: int(m.get("guildRank"), 0),
            by_bag: match m.get("byBag") {
                Some(Json::Object(o)) => o.iter().map(|(k, v)| (k.clone(), long(Some(v)))).collect(),
                _ => Vec::new(),
            },
            total: long(m.get("total")),
            items: list(m.get("items"), |i| CaravanItem {
                item_id: int(i.get("itemId"), 0),
                enchants: int(i.get("enchants"), -1),
                bag: text(i.get("bag")),
                time: text(i.get("time")),
                r#where: text(i.get("where")),
            }),
            tally: list(m.get("tally"), tally_of),
            last_drop: opt_text(m.get("lastDrop")),
            character: m.get("character").filter(|c| matches!(c, Json::Object(_))).map(|c| CaravanCharacter {
                class_type: int(c.get("classType"), 0),
                class_name: text(c.get("className")),
                skin: int(c.get("skin"), 0),
                tex1: int(c.get("tex1"), 0),
                tex2: int(c.get("tex2"), 0),
                fame: long(c.get("fame")),
            }),
        }),
    }
}

/// Java's `PlayerProfile.rankName`.
pub fn rank_name(rank: i32) -> &'static str {
    match rank {
        40 => "Founder",
        30 => "Leader",
        20 => "Officer",
        10 => "Member",
        _ => "Initiate",
    }
}

// --- Java `ItemScore` ---

/// Java `Math.round(x * 10) / 10.0`.
fn tenths(x: f64) -> f64 {
    (x * 10.0 + 0.5).floor() / 10.0
}

/// The item's points when found in that place with that many enchantments (-1 = unknown, counts as 0).
pub fn score(data: &lg_gamedata::GameData, w: &crate::server::ScoreWeights, item: i32, r#where: &str, enchants: i32) -> f64 {
    let feed = data.items.item(item).map_or(0, |i| i.feed_power.max(0));
    let difficulty = data.difficulty.get(r#where).copied().unwrap_or(0.0);
    let rarity = enchants.clamp(0, 4);
    tenths(w.feed_power * (f64::from(feed) / 100.0) + w.difficulty * difficulty + w.rarity * f64::from(rarity))
}

/// The points of everything in a tally (each item rounded first, so the total matches the items shown).
pub fn total(data: &lg_gamedata::GameData, w: &crate::server::ScoreWeights, tally: &[Tally]) -> f64 {
    tenths(tally.iter().map(|t| t.count as f64 * score(data, w, t.item_id, &t.r#where, t.enchants)).sum())
}

fn grouped(n: i64) -> String {
    let s = n.abs().to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    if n < 0 { format!("-{out}") } else { out }
}

/// Java `ItemScore.format`: "12.5", or "24" for a whole number (thousands grouped).
pub fn format(points: f64) -> String {
    let t = tenths(points);
    if t == t.round() {
        grouped(t as i64)
    } else {
        let whole = t.trunc() as i64;
        let frac = ((t - t.trunc()).abs() * 10.0).round() as i64;
        format!("{}.{frac}", grouped(whole))
    }
}

/// Java `ItemScore.whole`: rounded to whole points, thousands grouped.
pub fn whole(points: f64) -> String {
    grouped((points + 0.5).floor() as i64)
}

/// Java `ItemScore.breakdown`: what an item's points are made of, as (name, value) lines.
pub fn breakdown(data: &lg_gamedata::GameData, item: i32, r#where: &str, enchants: i32) -> Vec<(String, String)> {
    const RARITY: [&str; 5] = ["Common", "Uncommon", "Rare", "Legendary", "Divine"];
    let fp = data.items.item(item).map_or(-1, |i| i.feed_power);
    let rarity = enchants.clamp(0, 4);
    vec![
        ("FP:".into(), if fp < 0 { "0".into() } else { grouped(i64::from(fp)) }),
        ("Difficulty:".into(), data.difficulty.get(r#where).map_or("Unknown (0)".into(), |d| format(*d))),
        ("Rarity:".into(), if enchants < 0 { "Unknown (0)".into() } else { format!("{} ({rarity})", RARITY[rarity as usize]) }),
    ]
}

/// Who cleared a quest map stage, and when (ms since 1970).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct QuestMapClear {
    pub user_id: i64,
    pub ign: String,
    pub at: i64,
}

/// A guild quest map: dungeons cleared in order by any member, drawn in a style and colour variant.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct QuestMap {
    pub id: i64,
    pub name: String,
    pub style: String,
    pub variant: i32,
    pub dungeons: Vec<String>,
    /// One per cleared stage, in order.
    pub clears: Vec<QuestMapClear>,
    pub starts_at: i64,
    pub ends_at: i64,
    pub completed_at: Option<i64>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct QuestMaps {
    pub guild: String,
    /// The server's clock.
    pub now: i64,
    pub current: Option<QuestMap>,
    /// Newest first.
    pub past: Vec<QuestMap>,
}

fn quest_map_of(j: &Json) -> QuestMap {
    QuestMap {
        id: long(j.get("id")),
        name: text(j.get("name")),
        style: text(j.get("style")),
        variant: int(j.get("variant"), 0),
        dungeons: list(j.get("dungeons"), |d| text(Some(d))),
        clears: list(j.get("clears"), |c| QuestMapClear { user_id: long(c.get("userId")), ign: text(c.get("ign")), at: long(c.get("at")) }),
        starts_at: long(j.get("startsAt")),
        ends_at: long(j.get("endsAt")),
        completed_at: j.get("completedAt").filter(|v| matches!(v, Json::Number(_))).map(|v| long(Some(v))),
    }
}

pub fn quest_maps_of(j: &Json) -> QuestMaps {
    QuestMaps {
        guild: text(j.get("guild")),
        now: long(j.get("now")),
        current: j.get("current").filter(|c| matches!(c, Json::Object(_))).map(quest_map_of),
        past: list(j.get("past"), quest_map_of),
    }
}

/// Your clear, kept on the map until the server has it (the upload takes a moment).
#[derive(Debug, Clone, Default)]
pub struct PendingClear {
    clear: QuestMapClear,
    map: i64,
    stage: usize,
    until: i64,
    pub uploaded: bool,
}

/// What the Guild tab shows (Java `GuildPanel`'s state): None until loaded.
#[derive(Debug, Default)]
pub struct GuildState {
    pub board: Option<Result<Leaderboard, crate::server::Error>>,
    pub drops: Option<Result<Vec<GuildDrop>, crate::server::Error>>,
    /// The member shown (clicked last) and their profile once loaded.
    pub member: Option<(i64, Option<Result<MemberProfile, crate::server::Error>>)>,
    pub(crate) loading: u8,
    /// This load failed: both show its error (Java `allOf`).
    pub(crate) failed: bool,
    /// A leaderboard waiting for the caravan's tallies (servers from before item scores).
    pub(crate) board_wait: Option<Leaderboard>,
    /// Bumped on each load, so views redraw.
    pub version: u64,
    /// The loot caravan (Java `CaravanView`): None until loaded; loading, asked again meanwhile, to load when shown.
    pub caravan: Option<Result<Caravan, crate::server::Error>>,
    pub(crate) caravan_loading: bool,
    pub(crate) caravan_again: bool,
    pub caravan_stale: bool,
    /// The quest maps (Java `QuestMapView`): None until loaded; the server's clock minus ours.
    pub quest_maps: Option<Result<QuestMaps, crate::server::Error>>,
    pub(crate) maps_loading: bool,
    pub(crate) maps_again: bool,
    pub maps_stale: bool,
    pub skew: i64,
    pub pending: Option<PendingClear>,
}

fn add_clear(m: &mut QuestMap, c: QuestMapClear) {
    m.clears.push(c);
    if m.clears.len() == m.dungeons.len() {
        m.completed_at = m.clears.last().map(|c| c.at);
    }
}

fn boss_damage(run: &crate::dps_history::Run) -> bool {
    run.bosses.iter().any(|b| b.players.iter().any(|p| p.me && p.damage > 0))
}

/// Java `bagName`: the bag colour the server files a drop under ("Boosted Orange" is Orange, unknown is Other).
pub fn bag_name(bag: &str) -> String {
    let b = bag.trim();
    let b = b.strip_prefix("Boosted ").map_or(b, str::trim);
    let mut c = b.chars();
    match c.next() {
        None => "Other".into(),
        Some(f) => f.to_uppercase().chain(c).collect(),
    }
}

impl GuildState {
    /// Java `QuestMapView.cleared`: a run of yours just ended; if it clears the current stage (the same rules as
    /// the server's: its dungeon, after the last clear, with your boss damage), the map moves on right away.
    pub fn cleared(&mut self, run: &crate::dps_history::Run, me_id: i64, me_ign: &str, now: i64) {
        let skew = self.skew;
        let Some(Ok(maps)) = &mut self.quest_maps else { return };
        let Some(m) = &mut maps.current else { return };
        if run.guild_hall || m.completed_at.is_some() {
            return;
        }
        let n = m.clears.len();
        if n >= m.dungeons.len() || now + skew >= m.ends_at {
            return;
        }
        let unlock = if n == 0 { m.starts_at } else { m.clears[n - 1].at };
        if run.start < unlock || !run.dungeon.eq_ignore_ascii_case(&m.dungeons[n]) || !boss_damage(run) {
            return;
        }
        let c = QuestMapClear { user_id: me_id, ign: me_ign.into(), at: run.start };
        self.pending = Some(PendingClear { clear: c.clone(), map: m.id, stage: n, until: now + 3 * 60_000, uploaded: false });
        add_clear(m, c);
        self.version += 1;
    }

    /// Java `keepPending`: a reload before your run reached the server would take your clear back: it stays on the
    /// map until then; after that the server's answer counts.
    pub(crate) fn keep_pending(&mut self, now: i64) {
        let Some(pc) = &self.pending else { return };
        let Some(Ok(maps)) = &mut self.quest_maps else { return };
        let Some(m) = &mut maps.current else { return };
        let theirs = m.clears.len() > pc.stage && m.clears[pc.stage].user_id != pc.clear.user_id;
        if pc.uploaded || theirs || m.id != pc.map || now > pc.until || m.clears.len() > pc.stage {
            self.pending = None; // the server has the final say now
        } else if m.completed_at.is_none() && m.clears.len() == pc.stage {
            add_clear(m, pc.clear.clone());
        }
    }

    /// Java `CaravanView.dropped`: one of your drops onto your wagon right away (the server counts it once the
    /// upload is in).
    pub fn dropped(&mut self, item_id: i32, find: &crate::collection::Find, me_id: i64, me_ign: &str) {
        let Some(Ok(c)) = &mut self.caravan else { return };
        let m = match c.members.iter().position(|x| x.id == me_id) {
            Some(i) => &mut c.members[i],
            None => {
                c.members.push(CaravanMember { id: me_id, ign: me_ign.into(), ..Default::default() });
                c.members.last_mut().expect("just added")
            }
        };
        let item = CaravanItem {
            item_id,
            bag: bag_name(&find.bag),
            time: find.time.clone(),
            r#where: find.r#where.clone(),
            enchants: find.enchant_ids.as_ref().map_or(find.enchants, |e| e.len() as i32),
        };
        if !m.tally.is_empty() || m.total == 0 {
            // older servers send no tally: the score comes from the items then
            match m.tally.iter_mut().find(|t| t.item_id == item_id && t.enchants == item.enchants && t.r#where == item.r#where) {
                Some(t) => t.count += 1,
                None => m.tally.push(Tally { item_id, r#where: item.r#where.clone(), enchants: item.enchants, count: 1 }),
            }
        }
        match m.by_bag.iter_mut().find(|(b, _)| *b == item.bag) {
            Some((_, n)) => *n += 1,
            None => m.by_bag.push((item.bag.clone(), 1)),
        }
        m.items.insert(0, item);
        m.items.truncate(120);
        m.total += 1;
        m.last_drop = Some(find.time.clone());
        self.version += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_as_java() {
        assert_eq!(
            (format(24.0), format(12.46), format(1234.5), whole(1234.5)),
            ("24".into(), "12.5".into(), "1,234.5".into(), "1,235".into())
        );
    }
}
