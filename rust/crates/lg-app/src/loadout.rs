//! The suggested loadout (Java: `lootgobbler.enchant` and `CharacterDetail.ideal`): the game's enchantments and the
//! pools they roll from, what they add, the best enchantments for an item (`EnchantPlanner`), a weapon's damage per
//! second (`WeaponDps`), and per slot the item and copy that give the most of the stats you aim for.
//!
//! Checked against Java with `oracle/SuggestOracle.java` (`lg-replay suggest`): the same picks, copies, planned
//! enchantments and scores.

// the loops follow Java's, whose indexes matter (subattack positions, slot numbers); the planner's calls carry its many
// inputs as Java's do
#![allow(clippy::needless_range_loop, clippy::too_many_arguments)]

use lg_gamedata::GameData;
use lg_gamedata::items::{Item, Shot};
use lg_gamedata::xml::{self, Element};
use std::collections::{BTreeMap, HashMap, HashSet};

pub const STATS: [&str; 8] = ["MAXHP", "MAXMP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"];
pub const DPS: &str = "DPS";
pub const LOOT: &str = "LOOT";
pub const HP_REGEN: &str = "HPREGEN";
pub const MP_REGEN: &str = "MPREGEN";
const OTHER_STATS: f64 = 0.1;
const CANDIDATES: usize = 40;
pub const HP_PER_VIT: f64 = 0.24;
pub const MP_PER_WIS: f64 = 0.12;
const DEFAULT_POOL: &str = "Default Enchantment Pool";

fn stat_index(s: &str) -> Option<usize> {
    STATS.iter().position(|x| *x == s)
}

/// One effect of an enchantment (Java `Effect`).
#[derive(Debug, Clone, PartialEq)]
pub enum Effect {
    StatBonus { stat: String, amount: f64 },
    RelativeStat { stat: String, of: String, percent: f64 },
    Reward { kind: String, amount: f64 },
    Regen { stat: String, percent: bool, amount: f64 },
    WeaponMod { what: String, factor: f64, index: i32 },
    Proc { trigger: String, action: String },
    Other { mutator: String, action: String, attributes: BTreeMap<String, String> },
}

/// One enchantment (Java `Enchantment`).
#[derive(Debug, Clone, PartialEq)]
pub struct Enchantment {
    pub kind: i32,
    pub key: String,
    pub family: String,
    pub level: i32,
    pub name: String,
    pub weight: i32,
    pub labels: Vec<String>,
    pub excludes: Vec<String>,
    pub item_needs: Vec<String>,
    pub item_excludes: Vec<String>,
    pub item_ids_excluded: Vec<String>,
    pub effects: Vec<Effect>,
}

impl Enchantment {
    fn has(&self, l: &str) -> bool {
        self.labels.iter().any(|x| x == l)
    }

    pub fn rollable(&self) -> bool {
        self.has("ROLLABLE") && self.weight > 0
    }

    pub fn fits(&self, item: &Item) -> bool {
        if !self.item_needs.iter().all(|l| item.labels.contains(l)) {
            return false;
        }
        if self.item_excludes.iter().any(|l| item.labels.contains(l)) {
            return false;
        }
        self.item_ids_excluded.is_empty()
            || !(self.item_ids_excluded.contains(&item.name)
                || self.item_ids_excluded.contains(&item.id.to_string())
                || self.item_ids_excluded.contains(&format!("0x{:x}", item.id)))
    }
}

/// A pool's rule (Java `Pool.Rule`).
#[derive(Debug, Clone, PartialEq)]
struct Rule {
    include: Vec<String>,
    exclude: Vec<String>,
}

impl Rule {
    fn matches(&self, labels: &[String]) -> bool {
        self.include.iter().any(|l| labels.contains(l)) && !self.exclude.iter().any(|l| labels.contains(l))
    }
}

#[derive(Debug, Clone, PartialEq)]
struct Pool {
    entries: Vec<Rule>,
    ids: HashSet<String>,
}

impl Pool {
    fn contains(&self, e: &Enchantment) -> bool {
        self.ids.contains(&e.key) || self.entries.iter().any(|r| r.matches(&e.labels))
    }
}

/// Every enchantment, in the game file's order, and the pools (Java `Enchantments`).
#[derive(Debug, Clone, Default)]
pub struct Enchantments {
    pub all: Vec<Enchantment>,
    by_type: HashMap<i32, usize>,
    pools: HashMap<String, Pool>,
    units: [f64; 8],
    extra: HashMap<String, f64>,
}

fn direct<'a>(e: &'a Element, tag: Option<&str>) -> Vec<&'a Element> {
    e.children().filter(|c| tag.is_none_or(|t| c.name == t)).collect()
}

fn text(e: &Element, tag: &str) -> String {
    direct(e, Some(tag)).first().map_or(String::new(), |c| c.text_content().trim().to_string())
}

fn set(csv: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for p in csv.split(',') {
        let t = p.trim();
        if !t.is_empty() && !out.iter().any(|x| x == t) {
            out.push(t.to_string());
        }
    }
    out
}

fn decode(s: &str) -> Option<i32> {
    let t = s.trim();
    if t.is_empty() { None } else { lg_gamedata::decode_int_pub(t) }
}

fn number(s: Option<&str>) -> f64 {
    s.map(str::trim).filter(|t| !t.is_empty()).and_then(|t| t.parse::<f64>().ok()).unwrap_or(0.0)
}

fn effect(m: &Element) -> Effect {
    let tag = m.name.as_str();
    let action = m.text_content().trim().to_string();
    let a: BTreeMap<String, String> = m.attrs.iter().cloned().collect();
    let amount = number(a.get("amount").map(String::as_str));
    if tag == "ActivateOnEquip" {
        match action.as_str() {
            "IncrementStat" => return Effect::StatBonus { stat: a.get("stat").cloned().unwrap_or_default(), amount },
            "BonusStatRelative" => {
                return Effect::RelativeStat {
                    stat: a.get("stat").cloned().unwrap_or_default(),
                    of: a.get("statRelativeTo").cloned().unwrap_or_default(),
                    percent: amount,
                };
            }
            "LootBonus" | "XPBonus" | "DustBonus" => return Effect::Reward { kind: action, amount },
            "FlatRegen" | "PercentageRegen" => {
                return Effect::Regen {
                    stat: a.get("stat").cloned().unwrap_or_default(),
                    percent: action.starts_with("Percentage"),
                    amount,
                };
            }
            _ => {}
        }
    } else if let Some(what) = tag.strip_prefix("Multiply") {
        let index = a.get("projectileId").or_else(|| a.get("subAttackIndex"));
        return Effect::WeaponMod {
            what: what.to_string(),
            factor: number(Some(&action)),
            index: index.map_or(-1, |i| number(Some(i)) as i32),
        };
    } else if let Some(t) = tag.strip_prefix("AddOn").and_then(|t| t.strip_suffix("Activate")) {
        return Effect::Proc { trigger: t.to_string(), action };
    }
    Effect::Other { mutator: tag.to_string(), action, attributes: a }
}

impl Enchantments {
    /// Reads `enchantments.xml` and `enchantmentLists.xml` from a game data folder (Java `Enchantments.load`).
    pub fn load(xml_dir: &std::path::Path) -> Enchantments {
        let mut out = Enchantments::default();
        if let Ok(s) = std::fs::read_to_string(xml_dir.join("enchantments.xml"))
            && let Ok(root) = xml::parse(&s)
        {
            for e in direct(&root, Some("Enchantment")) {
                let Some(kind) = decode(e.attr_or_empty("type")) else { continue };
                let key = e.attr_or_empty("id").to_string();
                let labels = set(&text(e, "EnchantmentLabels"));
                let mut level = 0;
                for i in 1..=4 {
                    if labels.iter().any(|l| *l == format!("TIER{i}")) {
                        level = i;
                    }
                }
                let family = if level > 0 {
                    match key.rfind('_') {
                        Some(i) if !key[i + 1..].is_empty() && key[i + 1..].bytes().all(|b| b.is_ascii_digit()) => key[..i].to_string(),
                        _ => key.clone(),
                    }
                } else {
                    key.clone()
                };
                let name = text(e, "DisplayId");
                let effects = direct(e, Some("Mutators")).first().map_or(Vec::new(), |m| direct(m, None).into_iter().map(effect).collect());
                let en = Enchantment {
                    kind,
                    family,
                    level,
                    name: if name.is_empty() { key.clone() } else { name },
                    key,
                    weight: decode(&text(e, "Weight")).unwrap_or(0),
                    labels,
                    excludes: set(&text(e, "IncompatibleWithEnchantmentLabels")),
                    item_needs: set(&text(e, "CompatibleWithItemLabels")),
                    item_excludes: set(&text(e, "IncompatibleWithItemLabels")),
                    item_ids_excluded: set(&text(e, "IncompatibleWithItemIds")),
                    effects,
                };
                match out.by_type.get(&kind) {
                    Some(&i) => out.all[i] = en,
                    None => {
                        out.by_type.insert(kind, out.all.len());
                        out.all.push(en);
                    }
                }
            }
        }
        if let Ok(s) = std::fs::read_to_string(xml_dir.join("enchantmentLists.xml"))
            && let Ok(root) = xml::parse(&s)
        {
            for l in direct(&root, Some("EnchantmentList")) {
                let mut pool = Pool { entries: Vec::new(), ids: HashSet::new() };
                for c in direct(l, None) {
                    match c.name.as_str() {
                        "EnchantmentEntryLabel" => pool.entries.push(Rule {
                            include: set(c.attr_or_empty("includeLabelsOR")),
                            exclude: set(c.attr_or_empty("excludeLabelsOR")),
                        }),
                        "EnchantmentEntry" => {
                            pool.ids.insert(c.attr_or_empty("id").to_string());
                        }
                        _ => {}
                    }
                }
                out.pools.insert(l.attr_or_empty("id").to_string(), pool);
            }
        }
        out.pools
            .entry(DEFAULT_POOL.into())
            .or_insert_with(|| Pool { entries: vec![Rule { include: vec!["ROLLABLE".into()], exclude: vec![] }], ids: HashSet::new() });
        // the most one rollable flat single-stat enchantment gives each stat; loot and flat regeneration likewise
        let mut u = [0.0f64; 8];
        for e in &out.all {
            if e.rollable() && !e.has("UNIQUE") {
                for f in &e.effects {
                    match f {
                        Effect::Reward { kind, amount } if kind == "LootBonus" => {
                            let v = out.extra.entry("LOOT".into()).or_insert(*amount);
                            *v = v.max(*amount);
                        }
                        Effect::Regen { stat, percent: false, amount } => {
                            let v = out.extra.entry(stat.clone()).or_insert(*amount);
                            *v = v.max(*amount);
                        }
                        _ => {}
                    }
                }
            }
            if !e.rollable() || !e.has("SINGLESTAT") || !e.has("FLATBONUS") || e.has("UNIQUE") {
                continue;
            }
            for f in &e.effects {
                if let Effect::StatBonus { stat, amount } = f
                    && let Some(i) = stat_index(stat)
                {
                    u[i] = u[i].max(*amount);
                }
            }
        }
        for v in &mut u {
            if *v <= 0.0 {
                *v = 1.0;
            }
        }
        out.units = u;
        out
    }

    pub fn by_type(&self, kind: i32) -> Option<usize> {
        self.by_type.get(&kind).copied()
    }

    pub fn get(&self, i: usize) -> &Enchantment {
        &self.all[i]
    }

    /// Java `rollableOn`: what the enchanter can roll on the item, in file order.
    pub fn rollable_on(&self, item: &Item) -> Vec<usize> {
        let Some(slots) = &item.enchant_slots else { return Vec::new() };
        let Some(pool) = self.pools.get(&slots.pool).or_else(|| self.pools.get(DEFAULT_POOL)) else { return Vec::new() };
        (0..self.all.len()).filter(|&i| self.all[i].rollable() && self.all[i].fits(item) && pool.contains(&self.all[i])).collect()
    }

    /// Java `EnchantPlanner.of`: the enchantments with these numbers (-1 and unknown left out).
    pub fn of(&self, types: Option<&[i32]>) -> Vec<usize> {
        types.unwrap_or(&[]).iter().filter(|t| **t >= 0).filter_map(|t| self.by_type(*t)).collect()
    }

    /// Java `conflicts`.
    pub fn conflicts(&self, a: usize, b: usize) -> bool {
        let (x, y) = (&self.all[a], &self.all[b]);
        a == b || x.family == y.family || x.excludes.iter().any(|l| y.has(l)) || y.excludes.iter().any(|l| x.has(l))
    }

    /// Java `gains`: what the enchantments add per stat (relative ones count from the gear's bonus).
    pub fn gains(&self, enchants: &[usize], gear: &[f64; 8]) -> [f64; 8] {
        let mut g = [0.0; 8];
        for &e in enchants {
            for f in &self.all[e].effects {
                match f {
                    Effect::StatBonus { stat, amount } => {
                        if let Some(i) = stat_index(stat) {
                            g[i] += amount;
                        }
                    }
                    Effect::RelativeStat { stat, of, percent } => {
                        if let (Some(i), Some(o)) = (stat_index(stat), stat_index(of)) {
                            g[i] += gear[o] * percent / 100.0;
                        }
                    }
                    _ => {}
                }
            }
        }
        g
    }

    pub fn score(&self, gains: &[f64; 8], chosen: &HashSet<String>) -> f64 {
        let mut s = 0.0;
        for i in 0..8 {
            let v = gains[i] / self.units[i];
            s += if chosen.contains(STATS[i]) { v } else { OTHER_STATS * v };
        }
        s
    }

    pub fn loot(&self, enchants: &[usize]) -> f64 {
        enchants
            .iter()
            .flat_map(|e| &self.all[*e].effects)
            .map(|f| {
                if let Effect::Reward { kind, amount } = f
                    && kind == "LootBonus"
                {
                    *amount
                } else {
                    0.0
                }
            })
            .sum()
    }

    fn regen_of(&self, enchants: &[usize], stat: &str, percent: bool) -> f64 {
        enchants
            .iter()
            .flat_map(|e| &self.all[*e].effects)
            .map(|f| {
                if let Effect::Regen { stat: s, percent: p, amount } = f
                    && s == stat
                    && *p == percent
                {
                    *amount
                } else {
                    0.0
                }
            })
            .sum()
    }

    pub fn flat_regen(&self, enchants: &[usize], stat: &str) -> f64 {
        self.regen_of(enchants, stat, false)
    }

    pub fn percent_regen(&self, enchants: &[usize], stat: &str) -> f64 {
        self.regen_of(enchants, stat, true)
    }

    /// Life / mana per second with this maximum and vitality / wisdom, and these enchantments.
    pub fn regen(&self, stat: &str, max: f64, attribute: f64, enchants: &[usize]) -> f64 {
        let base = if stat == "HP" { 1.0 + HP_PER_VIT * attribute } else { 0.5 + MP_PER_WIS * attribute };
        base + self.flat_regen(enchants, stat) + self.percent_regen(enchants, stat) * max
    }

    fn extra_unit(&self, what: &str) -> f64 {
        let v = self.extra.get(what).copied().unwrap_or(1.0);
        if v > 0.0 { v } else { 1.0 }
    }

    /// Java `extras`: loot and regeneration, when chosen, in units of one enchantment's worth.
    pub fn extras(&self, gains: &[f64; 8], picks: &[usize], chosen: &HashSet<String>, aim: Option<&Aim>) -> f64 {
        let mut s = 0.0;
        if chosen.contains(LOOT) {
            s += self.loot(picks) / self.extra_unit("LOOT");
        }
        let (hp, mp) = aim.map_or((0.0, 0.0), |a| (a.hp, a.mp));
        if chosen.contains(HP_REGEN) {
            let g = HP_PER_VIT * gains[6]
                + self.flat_regen(picks, "HP")
                + self.percent_regen(picks, "HP") * (hp + gains[0])
                + aim.map_or(0.0, |a| a.hp_percent) * gains[0];
            s += g / self.extra_unit("HP");
        }
        if chosen.contains(MP_REGEN) {
            let g = MP_PER_WIS * gains[7]
                + self.flat_regen(picks, "MP")
                + self.percent_regen(picks, "MP") * (mp + gains[1])
                + aim.map_or(0.0, |a| a.mp_percent) * gains[1];
            s += g / self.extra_unit("MP");
        }
        s
    }

    /// Java `score(gains, picks, chosen, aim)`.
    pub fn score_with(&self, data: &GameData, gains: &[f64; 8], picks: &[usize], chosen: &HashSet<String>, aim: Option<&Aim>) -> f64 {
        let mut s = self.score(gains, chosen);
        if let Some(a) = aim
            && chosen.contains(DPS)
        {
            s += a.value(self, data, gains, picks);
        }
        s + self.extras(gains, picks, chosen, aim)
    }

    /// Java `EnchantPlanner.best`: the enchantments raising the chosen stats the most on the item, keeping `locked`.
    pub fn best(
        &self,
        data: &GameData,
        item: &Item,
        slots: usize,
        chosen: &HashSet<String>,
        gear: &[f64; 8],
        aim: Option<&Aim>,
        locked: &[usize],
    ) -> Plan {
        if slots == 0 || chosen.is_empty() {
            return Plan::default();
        }
        let mut keep: Vec<usize> = Vec::new();
        for &e in locked {
            if !keep.contains(&e) && keep.len() < slots {
                keep.push(e);
            }
        }
        let none = self.score_with(data, &[0.0; 8], &[], chosen, aim);
        let mut single: Vec<(usize, f64)> = Vec::new();
        for e in self.rollable_on(item) {
            let s = self.score_with(data, &self.gains(&[e], gear), &[e], chosen, aim) - none;
            if s > 1e-9 {
                single.push((e, s));
            }
        }
        single.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        let mut cand = Vec::new();
        let mut families = HashSet::new();
        for (e, _) in &single {
            if cand.len() >= CANDIDATES {
                break;
            }
            if keep.contains(e) || keep.iter().any(|k| self.conflicts(*k, *e)) {
                continue;
            }
            if families.insert(self.all[*e].family.clone()) {
                cand.push(*e);
            }
        }
        let mut best = Plan::default();
        if !keep.is_empty() {
            let g = self.gains(&keep, gear);
            best = Plan { score: self.score_with(data, &g, &keep, chosen, aim) - none, picks: keep.clone(), gains: g };
        }
        let mut picked = keep.clone();
        self.search(data, &cand, 0, &mut picked, slots.min(4), chosen, gear, aim, none, &mut best);
        best
    }

    #[allow(clippy::too_many_arguments)]
    fn search(
        &self,
        data: &GameData,
        cand: &[usize],
        from: usize,
        picked: &mut Vec<usize>,
        slots: usize,
        chosen: &HashSet<String>,
        gear: &[f64; 8],
        aim: Option<&Aim>,
        none: f64,
        best: &mut Plan,
    ) {
        if !picked.is_empty() {
            let g = self.gains(picked, gear);
            let s = self.score_with(data, &g, picked, chosen, aim) - none;
            if s > best.score + 1e-9 {
                *best = Plan { picks: picked.clone(), gains: g, score: s };
            }
        }
        if picked.len() == slots {
            return;
        }
        for i in from..cand.len() {
            let e = cand[i];
            if picked.iter().any(|p| self.conflicts(*p, e)) {
                continue;
            }
            picked.push(e);
            self.search(data, cand, i + 1, picked, slots, chosen, gear, aim, none, best);
            picked.pop();
        }
    }
}

/// Enchantments for an item, what they add, how good that is (Java `Plan`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Plan {
    pub picks: Vec<usize>,
    pub gains: [f64; 8],
    pub score: f64,
}

/// What aiming for DPS and regeneration starts from (Java `EnchantPlanner.Aim`).
#[derive(Debug, Clone)]
pub struct Aim {
    pub weapon: Option<i32>,
    pub weapon_enchants: Vec<usize>,
    pub att: f64,
    pub dex: f64,
    pub def: f64,
    pub on_weapon: bool,
    pub hp: f64,
    pub mp: f64,
    pub vit: f64,
    pub wis: f64,
    pub hp_percent: f64,
    pub mp_percent: f64,
}

impl Aim {
    pub fn on_weapon(&self, item: i32) -> Aim {
        Aim { weapon: Some(item), weapon_enchants: Vec::new(), on_weapon: true, ..self.clone() }
    }

    fn value(&self, en: &Enchantments, data: &GameData, gains: &[f64; 8], picks: &[usize]) -> f64 {
        let e = if self.on_weapon { picks.to_vec() } else { self.weapon_enchants.clone() };
        self.value_of(en, data, self.weapon, &e, gains)
    }

    /// Java `valueOf`: DPS with weapon w and these gains against DPS as the aim starts, in units of attack.
    pub fn value_of(&self, en: &Enchantments, data: &GameData, w: Option<i32>, e: &[usize], gains: &[f64; 8]) -> f64 {
        let item = |id: Option<i32>| id.and_then(|i| data.items.item(i));
        if !is_weapon(item(w)) {
            return 0.0;
        }
        let aim_weapon = is_weapon(item(self.weapon));
        let (r, r_ench) = if aim_weapon { (self.weapon, self.weapon_enchants.as_slice()) } else { (w, e) };
        let base = if aim_weapon { dps(en, data, item(self.weapon), &self.weapon_enchants, self.att, self.dex, self.def) } else { 0.0 };
        let unit = dps(en, data, item(r), r_ench, self.att + en.units[2], self.dex, self.def)
            - dps(en, data, item(r), r_ench, self.att, self.dex, self.def);
        if unit <= 1e-9 {
            return 0.0;
        }
        (dps(en, data, item(w), e, self.att + gains[2], self.dex + gains[5], self.def) - base) / unit
    }
}

// ---- weapon DPS (Java `WeaponDps`) ------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
struct Line {
    projectile: i32,
    shot: Shot,
    count: i32,
    rate: f64,
    burst: i32,
    burst_delay: f64,
    burst_min: f64,
}

impl Line {
    fn shots_per_second(&self, dex: f64) -> f64 {
        if self.burst > 0 && self.burst_delay > 0.0 {
            let t = (dex / 75.0).clamp(0.0, 1.0);
            let period = self.burst_delay + (self.burst_min - self.burst_delay) * t;
            return if period <= 0.0 { 0.0 } else { f64::from(self.count) * f64::from(self.burst) * self.rate / period };
        }
        f64::from(self.count) * self.rate * (1.5 + 6.5 * dex / 75.0)
    }

    fn dps(&self, att: f64, dex: f64, def: f64) -> f64 {
        let hit = (self.shot.min + self.shot.max) / 2.0 * (0.5 + att / 50.0);
        let per = if self.shot.armor_piercing { hit } else { (hit - def).max(hit * 0.1) };
        per * self.shots_per_second(dex)
    }
}

pub fn is_weapon(item: Option<&Item>) -> bool {
    item.is_some_and(|i| i.labels.contains("WEAPON") && i.weapon.as_ref().is_some_and(|w| !w.shots.is_empty()))
}

fn attacks(item: &Item) -> Vec<lg_gamedata::items::Volley> {
    let w = item.weapon.as_ref().expect("a weapon");
    if w.volleys.is_empty() {
        return vec![lg_gamedata::items::Volley {
            projectile: 0,
            count: w.num_projectiles,
            rate_of_fire: w.rate_of_fire,
            burst: 0,
            burst_delay: 0.0,
            burst_min_delay: 0.0,
        }];
    }
    w.volleys
        .iter()
        .map(|v| if v.rate_of_fire.is_nan() { lg_gamedata::items::Volley { rate_of_fire: w.rate_of_fire, ..*v } } else { *v })
        .collect()
}

fn line(from: &Item, v: &lg_gamedata::items::Volley) -> Option<Line> {
    let shot = *from.weapon.as_ref()?.shots.get(&v.projectile)?;
    Some(Line {
        projectile: v.projectile,
        shot,
        count: v.count,
        rate: v.rate_of_fire,
        burst: v.burst,
        burst_delay: v.burst_delay,
        burst_min: v.burst_min_delay,
    })
}

/// Java `WeaponDps.dps`: damage per second against this defense.
pub fn dps(en: &Enchantments, data: &GameData, weapon: Option<&Item>, enchants: &[usize], att: f64, dex: f64, def: f64) -> f64 {
    let Some(w) = weapon.filter(|w| is_weapon(Some(w))) else { return 0.0 };
    let mut out: Vec<Option<Line>> = attacks(w).iter().map(|v| line(w, v)).collect();
    let parse = |s: Option<&String>, fallback: i32| s.and_then(|s| s.trim().parse::<f64>().ok()).map_or(fallback, |v| v as i32);
    for &e in enchants {
        for f in &en.all[e].effects {
            let Effect::Other { mutator, action, attributes } = f else { continue };
            let (set, add) = (mutator == "SetSubAttack", mutator == "AddSubAttack");
            if !set && !add {
                continue;
            }
            let Some(bx) = attributes.get("subAttackObject").and_then(|k| data.items.by_key(k)) else { continue };
            let at = parse(attributes.get("subAttackIndex"), -1);
            let which = parse(Some(action), 0);
            if bx.weapon.is_none() || at < 0 {
                continue;
            }
            let theirs = attacks(bx);
            if which < 0 || which as usize >= theirs.len() {
                continue;
            }
            let l = line(bx, &theirs[which as usize]);
            let at = at as usize;
            while out.len() < at {
                out.push(None);
            }
            if set && at < out.len() {
                out[at] = l;
            } else {
                out.insert(at.min(out.len()), l);
            }
        }
    }
    for &e in enchants {
        for f in &en.all[e].effects {
            let Effect::WeaponMod { what, factor, index } = f else { continue };
            for i in 0..out.len() {
                let Some(mut l) = out[i] else { continue };
                match what.as_str() {
                    "MinDamage" | "MaxDamage" => {
                        if *index >= 0 && *index != l.projectile {
                            continue;
                        }
                        if what == "MinDamage" {
                            l.shot.min *= factor;
                        } else {
                            l.shot.max *= factor;
                        }
                        out[i] = Some(l);
                    }
                    "RateOfFire" => {
                        if *index >= 0 && *index as usize != i {
                            continue;
                        }
                        l.rate *= factor;
                        out[i] = Some(l);
                    }
                    _ => {}
                }
            }
        }
    }
    out.iter().flatten().map(|l| l.dps(att, dex, def)).sum()
}

// ---- picking per slot (Java `CharacterDetail`) ------------------------------------------------------------------

/// The item and copy suggested for a slot (Java `Pick`).
#[derive(Debug, Clone, PartialEq)]
pub struct Pick {
    pub id: i32,
    /// Its enchantments as the copy has them (-1 = empty slot); None: not known.
    pub enchants: Option<Vec<i32>>,
    pub r#where: String,
    pub plan: Plan,
}

impl Pick {
    pub fn equipped(&self) -> bool {
        self.r#where == "equipped"
    }

    pub fn slots(&self, data: &GameData) -> usize {
        match (&self.enchants, data.items.item(self.id)) {
            (Some(e), Some(it)) if it.enchant_slots.is_some() => e.len(),
            _ => 0,
        }
    }
}

/// A copy of an item somewhere (Java `Holdings.Held`).
#[derive(Debug, Clone)]
pub struct Held {
    pub char_id: i32,
    pub vault: Option<String>,
    pub slot: String,
    pub enchant_ids: Option<Vec<i32>>,
    pub seasonal: Option<bool>,
}

/// The items' own stats per slot type... what an item adds per stat (Java `addBonuses`).
pub fn bonuses(item: Option<&Item>) -> [f64; 8] {
    let mut b = [0.0; 8];
    for s in item.iter().flat_map(|i| &i.on_equip) {
        if let Some(i) = stat_index(&s.stat) {
            b[i] += f64::from(s.amount);
        }
    }
    b
}

/// Everything `best_pick` needs to know about the character and where copies are.
pub struct PickInput<'a> {
    pub data: &'a GameData,
    pub en: &'a Enchantments,
    pub char_id: i32,
    pub seasonal: bool,
    pub equipment: &'a [i32],
    pub enchant_ids: Option<&'a [Vec<i32>]>,
    pub slots: Option<&'a [i32]>,
    pub slot_enchant_ids: Option<&'a [Vec<i32>]>,
    /// Every copy of an item you have (Java `Holdings.everyCopy`, sorted as Java sorts it), and its "where".
    pub copies: &'a dyn Fn(i32) -> Vec<(Held, String)>,
}

/// Java `wornEnchants`.
pub fn worn_enchants(input: &PickInput, slot: usize) -> Option<Vec<i32>> {
    input.enchant_ids.and_then(|e| e.get(slot)).cloned()
}

/// Java `bestPick`: the best item and copy for a slot.
#[allow(clippy::too_many_arguments)]
pub fn best_pick(
    input: &PickInput,
    slot: usize,
    slot_type: i32,
    pool: &[i32],
    chosen: &HashSet<String>,
    gear: &[f64; 8],
    aim: &Aim,
    scope: usize,
    locked_item: i32,
    prefer: Option<&dyn Fn(&Item) -> bool>,
) -> Pick {
    let data = input.data;
    let en = input.en;
    let current = input.equipment.get(slot).copied().unwrap_or(-1);
    let worn = worn_enchants(input, slot);
    let dps_chosen = chosen.contains(DPS);
    let weapon_slot = slot == 0;
    let keep = Pick { id: current, enchants: worn, r#where: "equipped".into(), plan: Plan::default() };
    let mut pool: Vec<i32> = pool.to_vec();
    let mut prefer = prefer;
    if locked_item > 0 && scope != 0 {
        if locked_item == current {
            return keep;
        }
        pool = vec![locked_item];
    } else if let Some(pf) = prefer
        && scope != 0
    {
        let preferred: Vec<i32> =
            pool.iter().copied().filter(|id| data.items.item(*id).is_some_and(|it| it.slot_type == slot_type && pf(it))).collect();
        if preferred.is_empty() {
            prefer = None;
        } else {
            pool = preferred;
        }
    }
    let restricted = (locked_item > 0 || prefer.is_some()) && scope != 0;
    if !restricted && (chosen.is_empty() || scope == 0) {
        return keep;
    }
    if restricted
        && let Some(pf) = prefer
        && data.items.item(current).is_some_and(pf)
        && chosen.is_empty()
    {
        return keep;
    }
    let all_stats: HashSet<String> = STATS.iter().map(|s| s.to_string()).collect();
    let mut best: Option<Pick> = None;
    let (mut best_score, mut best_all) = (0.0f64, 0.0f64);
    let mut planned: HashMap<String, Plan> = HashMap::new();
    for &id in &pool {
        let Some(it) = data.items.item(id).filter(|it| it.slot_type == slot_type) else { continue };
        let own = bonuses(Some(it));
        let mut copies: Vec<Pick> = Vec::new();
        if id == current {
            copies.push(keep.clone());
        }
        if let Some(slots) = input.slots {
            for i in 4..slots.len() {
                if slots[i] != id {
                    continue;
                }
                let e = input.slot_enchant_ids.and_then(|s| s.get(i)).cloned();
                copies.push(Pick {
                    id,
                    enchants: e,
                    r#where: if i < 12 { "inventory".into() } else { "backpack".into() },
                    plan: Plan::default(),
                });
            }
        }
        for (h, w) in (input.copies)(id) {
            if h.char_id == input.char_id || scope == 1 {
                continue;
            }
            if h.seasonal.is_some_and(|s| s != input.seasonal) {
                continue;
            }
            copies.push(Pick { id, enchants: h.enchant_ids.clone(), r#where: w, plan: Plan::default() });
        }
        if copies.is_empty() && scope == 2 {
            copies.push(Pick { id, enchants: None, r#where: String::new(), plan: Plan::default() });
        }
        for p in copies {
            let mut total = own;
            let mut plan = Plan::default();
            let n = p.slots(data);
            if n > 0 {
                let key = format!("{id}/{n}");
                plan = match planned.get(&key) {
                    Some(pl) => pl.clone(),
                    None => {
                        let a = if weapon_slot { aim.on_weapon(id) } else { aim.clone() };
                        let pl = en.best(data, it, n, chosen, gear, Some(&a), &[]);
                        planned.insert(key, pl.clone());
                        pl
                    }
                };
                for i in 0..8 {
                    total[i] += plan.gains[i];
                }
            }
            let mut score = en.score(&total, chosen);
            let all = en.score(&total, &all_stats);
            let picks = if plan.picks.is_empty() { en.of(p.enchants.as_deref()) } else { plan.picks.clone() };
            score += en.extras(&total, &picks, chosen, Some(aim));
            if dps_chosen {
                score += if weapon_slot {
                    aim.value_of(en, data, Some(id), &picks, &total)
                } else {
                    aim.value_of(en, data, aim.weapon, &aim.weapon_enchants, &total)
                };
            }
            let tie = (score - best_score).abs() <= 1e-9;
            let better = match &best {
                None => true,
                Some(b) => {
                    let bs = b.slots(data);
                    score > best_score + 1e-9
                        || (tie && n > bs)
                        || (tie && n == bs && (all > best_all + 1e-9 || ((all - best_all).abs() <= 1e-9 && p.equipped())))
                }
            };
            if better {
                best = Some(Pick { plan, ..p });
                best_score = score;
                best_all = all;
            }
        }
    }
    if restricted {
        return best.unwrap_or(keep);
    }
    match best {
        Some(b) if best_score > 0.0 => b,
        _ => keep,
    }
}

/// Java `finalEnchants`: the planned enchantments, and in the slots they leave free what the copy has (no clash).
pub fn final_enchants(en: &Enchantments, data: &GameData, pk: &Pick) -> Vec<usize> {
    let has = en.of(pk.enchants.as_deref());
    let mut out = pk.plan.picks.clone();
    if out.is_empty() {
        return has;
    }
    for e in has {
        if out.len() >= pk.slots(data) {
            break;
        }
        if out.contains(&e) || out.iter().any(|o| en.conflicts(*o, e)) {
            continue;
        }
        out.push(e);
    }
    out
}

/// Java `total`: the class's maximum plus what every slot but `skip` adds.
pub fn total(max: Option<&[i32; 8]>, items: &[[f64; 8]; 4], enchants: &[[f64; 8]; 4], skip: Option<usize>, stat: usize) -> f64 {
    let mut t = max.map_or(0.0, |m| f64::from(m[stat]));
    for s in 0..4 {
        if Some(s) == skip {
            continue;
        }
        t += items[s][stat] + enchants[s][stat];
    }
    t
}

/// Java `aim`: what aiming starts from when choosing for a slot (everything but that slot).
#[allow(clippy::too_many_arguments)]
pub fn aim_for(
    en: &Enchantments,
    max: Option<&[i32; 8]>,
    items: &[[f64; 8]; 4],
    enchants: &[[f64; 8]; 4],
    lists: &[Vec<usize>; 4],
    slot: usize,
    weapon: Option<i32>,
    weapon_enchants: &[usize],
    def: f64,
) -> Aim {
    let others: Vec<usize> = (0..4).filter(|i| *i != slot).flat_map(|i| lists[i].iter().copied()).collect();
    let t = |s: usize| total(max, items, enchants, Some(slot), s);
    Aim {
        weapon,
        weapon_enchants: weapon_enchants.to_vec(),
        att: t(2),
        dex: t(5),
        def,
        on_weapon: false,
        hp: t(0),
        mp: t(1),
        vit: t(6),
        wis: t(7),
        hp_percent: en.percent_regen(&others, "HP"),
        mp_percent: en.percent_regen(&others, "MP"),
    }
}

/// The whole suggestion (the first part of Java `ideal`): per slot the pick with its planned enchantments.
#[allow(clippy::too_many_arguments)]
pub fn suggest(
    input: &PickInput,
    class: &lg_gamedata::Class,
    pool: &[i32],
    chosen: &HashSet<String>,
    scope: usize,
    def: f64,
    locked_items: &[i32; 4],
    locked_enchants: &dyn Fn(usize, &Pick) -> Vec<usize>,
    second_chance: usize,
) -> [Pick; 4] {
    let data = input.data;
    let en = input.en;
    let types = class.slot_types.unwrap_or([0; 4]);
    let max = Some(&class.max_stats);
    let worn: Vec<Option<&Item>> =
        (0..4).map(|s| input.equipment.get(s).copied().filter(|i| *i > 0).and_then(|i| data.items.item(i))).collect();
    let mut now = [0.0; 8];
    let mut worn_items = [[0.0; 8]; 4];
    for s in 0..4 {
        worn_items[s] = bonuses(worn[s]);
        for i in 0..8 {
            now[i] += worn_items[s][i];
        }
    }
    let mut worn_lists: [Vec<usize>; 4] = Default::default();
    let mut worn_ench = [[0.0; 8]; 4];
    for s in 0..4 {
        worn_lists[s] = en.of(worn_enchants(input, s).as_deref());
        worn_ench[s] = en.gains(&worn_lists[s], &now);
    }
    let worn_weapon = worn[0].map(|i| i.id);
    let mut picks: Vec<Pick> = Vec::new();
    let mut suggested = [0.0; 8];
    for s in 0..4 {
        let a = aim_for(en, max, &worn_items, &worn_ench, &worn_lists, s, worn_weapon, &worn_lists[0], def);
        let amulet = |it: &Item| it.key.starts_with("Amulet of Restoration");
        let proc_armor = |it: &Item| it.second_chance;
        let prefer: Option<&dyn Fn(&Item) -> bool> = if second_chance >= 1 && s == 3 {
            Some(&amulet)
        } else if second_chance >= 2 && s == 2 {
            Some(&proc_armor)
        } else {
            None
        };
        let pk = best_pick(input, s, types[s], pool, chosen, &now, &a, scope, locked_items[s], prefer);
        let b = bonuses(data.items.item(pk.id));
        for i in 0..8 {
            suggested[i] += b[i];
        }
        picks.push(pk);
    }
    let mut sug_items = [[0.0; 8]; 4];
    let mut sug_ench = [[0.0; 8]; 4];
    let mut sug_lists: [Vec<usize>; 4] = Default::default();
    for s in 0..4 {
        sug_lists[s] = if picks[s].plan.picks.is_empty() { en.of(picks[s].enchants.as_deref()) } else { picks[s].plan.picks.clone() };
        sug_items[s] = bonuses(data.items.item(picks[s].id));
        sug_ench[s] =
            if picks[s].plan.picks.is_empty() { en.gains(&en.of(picks[s].enchants.as_deref()), &suggested) } else { picks[s].plan.gains };
    }
    let sug_weapon_ench = sug_lists[0].clone();
    let pick0 = (picks[0].id > 0).then_some(picks[0].id);
    for s in 0..4 {
        let a = aim_for(en, max, &sug_items, &sug_ench, &sug_lists, s, pick0, &sug_weapon_ench, def);
        let pk = picks[s].clone();
        let locked = if scope == 0 && pk.equipped() { locked_enchants(s, &pk) } else { Vec::new() };
        let n = pk.slots(data);
        let plan = match data.items.item(pk.id) {
            Some(it) if n > 0 => {
                let a = if s == 0 { a.on_weapon(pk.id) } else { a };
                en.best(data, it, n, chosen, &suggested, Some(&a), &locked)
            }
            _ => Plan::default(),
        };
        picks[s] = Pick { plan, ..pk };
    }
    [picks[0].clone(), picks[1].clone(), picks[2].clone(), picks[3].clone()]
}

/// The settings the suggestion reads (Java: `idealLoadoutStats`, `idealScope`, `idealEnemyDef`, `idealSecondChance`,
/// `idealItemLocks`, `idealLocks`).
#[derive(Debug, Clone, Default)]
pub struct Settings {
    pub chosen: Vec<String>,
    pub scope: usize,
    pub enemy_def: f64,
    pub second_chance: usize,
    pub item_locks: String,
    pub enchant_locks: String,
}

impl Settings {
    pub fn from(props: &crate::properties::Properties) -> Settings {
        let get = |k: &str| props.get(k);
        let chosen = get("idealLoadoutStats").map(|v| {
            let mut out: Vec<String> = Vec::new();
            for p in v.split(',') {
                let t = p.trim();
                if !t.is_empty() && !out.iter().any(|x| x == t) {
                    out.push(t.into());
                }
            }
            out
        });
        let scope = match get("idealScope") {
            Some("current") => 0,
            Some("local") => 1,
            _ => 2,
        };
        let int = |k: &str| get(k).and_then(|v| v.trim().parse::<i64>().ok());
        Settings {
            chosen: chosen.unwrap_or_default(),
            scope,
            enemy_def: int("idealEnemyDef").map_or(0.0, |v| v.clamp(0, 100) as f64),
            second_chance: int("idealSecondChance").map_or(0, |v| v.clamp(0, 2) as usize),
            item_locks: get("idealItemLocks").unwrap_or("").to_string(),
            enchant_locks: get("idealLocks").unwrap_or("").to_string(),
        }
    }

    /// Java `lockedItem`.
    pub fn locked_item(&self, char_id: i32, slot: usize) -> i32 {
        locks(&self.item_locks, char_id, slot).first().copied().unwrap_or(-1)
    }

    /// Java `lockSet`.
    pub fn locked_enchants(&self, char_id: i32, slot: usize) -> Vec<i32> {
        locks(&self.enchant_locks, char_id, slot)
    }
}

fn locks(v: &str, char_id: i32, slot: usize) -> Vec<i32> {
    let mut out = Vec::new();
    for part in v.split(';') {
        let f: Vec<&str> = part.split(':').collect();
        if f.len() != 3 {
            continue;
        }
        if let (Ok(c), Ok(s), Ok(x)) = (f[0].parse::<i32>(), f[1].parse::<usize>(), f[2].parse::<i32>())
            && c == char_id
            && s == slot
        {
            out.push(x);
        }
    }
    out
}

/// Java `where`: where a copy is, for the line after its name.
pub fn place_of(app: &crate::App, c: &crate::characters::Char, h: &Held) -> String {
    if h.char_id == 0 {
        let place = if h.slot == crate::holdings::VAULT_CHEST { "vault".to_string() } else { h.slot.to_lowercase() };
        let seasonal = h.vault.as_deref() == Some(crate::holdings::SEASONAL_VAULT) && !place.starts_with("seasonal");
        return if seasonal { format!("seasonal {place}") } else { place };
    }
    if h.char_id == c.char_id {
        return h.slot.to_lowercase();
    }
    match app.characters.get(h.char_id) {
        Some(o) => format!("on {}", o.class_name),
        None => "on another character".into(),
    }
}

/// The living characters and whether each is seasonal (Java `Holdings.living`).
pub fn living(app: &crate::App) -> HashMap<i32, bool> {
    app.characters.living().iter().map(|l| (l.char_id, l.seasonal)).collect()
}

/// Java `CollectionStore.held` for every item at once: copies you hold (Holdings once it knows, else the
/// collection's count).
pub fn held_counts(app: &crate::App, living: &HashMap<i32, bool>) -> impl Fn(i32) -> i32 {
    let counts = app.holdings.known().then(|| {
        let time = app.zone.local_date_time(app.world.now());
        let ctx = crate::holdings::Ctx { data: app.world.data(), now: app.world.now(), time: &time };
        app.holdings.counts(&ctx, living)
    });
    move |id| match &counts {
        Some(c) => c.get(&id).copied().unwrap_or(0),
        None => app.collection.entry(id).map_or(0, |e| e.held()),
    }
}

/// Java `pool`: the items to choose from: what the character carries, everything you have on its side, and the
/// finds of items you hold on its side.
pub fn pool(app: &crate::App, c: &crate::characters::Char) -> Vec<i32> {
    let living = living(app);
    let mut ids: Vec<i32> = Vec::new();
    let add = |id: i32, ids: &mut Vec<i32>| {
        if !ids.contains(&id) {
            ids.push(id);
        }
    };
    for &id in c.slots.iter().flatten() {
        if id > 0 {
            add(id, &mut ids);
        }
    }
    for id in app.holdings.items_held(&living, Some(c.seasonal)) {
        add(id, &mut ids);
    }
    let held = held_counts(app, &living);
    for (id, e) in app.collection.all() {
        if held(id) == 0 {
            continue;
        }
        for f in e.history.iter().flatten() {
            if f.is_removal() {
                continue;
            }
            let side = match f.seasonal {
                None => !c.seasonal,
                Some(s) => s == c.seasonal,
            };
            if side {
                add(id, &mut ids);
                break;
            }
        }
    }
    ids
}

/// The whole suggestion for a character with these settings (Java `ideal`'s picks).
pub fn suggestion(app: &crate::App, en: &Enchantments, c: &crate::characters::Char, settings: &Settings) -> Option<[Pick; 4]> {
    let data = app.world.data();
    let class = data.classes.iter().find(|k| k.id == c.class_type)?;
    class.slot_types?;
    let living = living(app);
    let copies = |id: i32| -> Vec<(Held, String)> {
        app.holdings
            .every_copy(&living, id)
            .into_iter()
            .map(|h| {
                let w = place_of(app, c, &h);
                (h, w)
            })
            .collect()
    };
    let equipment = c.equipment.clone().unwrap_or_default();
    let input = PickInput {
        data,
        en,
        char_id: c.char_id,
        seasonal: c.seasonal,
        equipment: &equipment,
        enchant_ids: c.enchant_ids.as_deref(),
        slots: c.slots.as_deref(),
        slot_enchant_ids: c.slot_enchant_ids.as_deref(),
        copies: &copies,
    };
    let chosen: HashSet<String> = settings.chosen.iter().cloned().collect();
    let pool = pool(app, c);
    let items = [0, 1, 2, 3].map(|s| settings.locked_item(c.char_id, s));
    let locked = |slot: usize, pk: &Pick| -> Vec<usize> {
        let set = settings.locked_enchants(c.char_id, slot);
        en.of(pk.enchants.as_deref()).into_iter().filter(|e| set.contains(&en.get(*e).kind)).collect()
    };
    Some(suggest(&input, class, &pool, &chosen, settings.scope, settings.enemy_def, &items, &locked, settings.second_chance))
}
