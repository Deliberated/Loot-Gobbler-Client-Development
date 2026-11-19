//! What happens to your items (Java: `lootgobbler.LootEvents`): bags dropped for you, vault visits, what you carry,
//! chests opened, moves in the vault; into the collection and the holdings.

use crate::characters::Characters;
use crate::collection::{self, Collection, Find, Seen};
use crate::holdings::{self, Ctx, Holdings, Live, UNKNOWN};
use lg_gamedata::enchants::enchant_ids;
use lg_protocol::packets::data::Stat;
use lg_world::{Entity, stats};
use std::collections::{BTreeMap, HashMap, HashSet};

const INVENTORY_INTERVAL_MS: i64 = 5_000;
const QUICKSLOTS: [u8; 3] = [stats::POTION_ONE_TYPE, stats::POTION_TWO_TYPE, stats::POTION_THREE_TYPE];
const QUICKSLOT_FIRST: usize = 1000;

#[derive(Debug, Default)]
pub struct LootEvents {
    /// Copies of a vault visit, until its last packet (Java's `vaultVisit`).
    vault_visit: Vec<(i32, Vec<Find>)>,
    vault_pending: bool,
    last_inventory_scan: i64,
    /// slot -> chest item, as of the last scan; and whose.
    chest_slots: HashMap<usize, i32>,
    chest_char: i32,
    slot_copies: HashMap<usize, Find>,
    slot_items: HashMap<usize, i32>,
    /// Bags that dropped for you: items taken out of them are drops.
    pub loot_bags: HashSet<i32>,
    /// The last move you asked for.
    last_swap: Option<lg_protocol::packets::InvSwap>,
}

/// The records LootEvents writes into, and the moment.
pub struct Records<'a> {
    pub ctx: Ctx<'a>,
    pub characters: &'a Characters,
    pub collection: &'a mut Collection,
    pub holdings: &'a mut Holdings,
}

fn is_seasonal(p: &Entity) -> bool {
    p.value(stats::SEASONAL) == Some(1)
}

/// Java's `split(",", -1)`.
fn all_parts(s: &str) -> Vec<&str> {
    s.split(',').collect()
}

/// Java's `split(",")`: trailing empty parts dropped.
fn java_split(s: &str) -> Vec<&str> {
    let mut v: Vec<&str> = s.split(',').collect();
    while v.len() > 1 && v.last() == Some(&"") {
        v.pop();
    }
    if v == [""] && !s.is_empty() {
        v.clear();
    }
    v
}

/// LootEvents' own slot names: worn or not.
fn slot_name(i: usize) -> &'static str {
    if i < 4 { "Equipped" } else { "Inventory" }
}

fn bag_name(data: &lg_gamedata::GameData, kind: i32) -> String {
    let _ = data;
    match lg_gamedata::LOOT_BAGS.iter().find(|(id, _)| *id == kind) {
        None => "Loot".into(),
        Some((_, n)) => n.strip_prefix("B.").map_or(n.to_string(), |r| format!("Boosted {r}")),
    }
}

impl LootEvents {
    /// Java `copyInSlot`: the copy seen in a slot at the last scan, if that item is still there.
    pub fn copy_in_slot(&self, slot: i32, item: i32) -> Option<Find> {
        let s = usize::try_from(slot).ok()?;
        (self.slot_items.get(&s) == Some(&item)).then(|| self.slot_copies.get(&s).cloned()).flatten()
    }

    /// Java `isLootBag`.
    fn is_loot_bag(&self, w: &lg_world::World, obj: i32) -> bool {
        self.loot_bags.contains(&obj)
            || w.entities.get(&obj).is_some_and(|e| lg_gamedata::LOOT_BAGS.iter().any(|(id, _)| *id == e.object_type))
    }

    /// Java `invSwap`: a move you asked for.
    pub fn inv_swap(&mut self, r: &mut Records, p: &lg_protocol::packets::InvSwap, me: Option<&Entity>) {
        self.last_swap = Some(p.clone());
        let (pid, cid) = me.map_or((0, 0), |m| (m.id, m.char_id));
        let (f, t) = (&p.slot_from, &p.slot_to);
        r.holdings.swap(r.ctx.now, pid, cid, (f.object_id, f.slot_id, f.object_type), (t.object_id, t.slot_id, t.object_type));
    }

    /// Java `invResult`: the game's answer to a move; then where items of your character came from (UPE runs).
    pub fn inv_result(
        &mut self,
        r: &mut Records,
        u: &mut crate::upe::Upe,
        uc: &crate::upe::Ctx,
        w: &lg_world::World,
        p: &lg_protocol::packets::InvResult,
    ) {
        let (f, t) = (&p.slot_from, &p.slot_to);
        r.holdings.swap_result(
            &r.ctx,
            p.result,
            p.result_type,
            (f.object_id, f.slot_id, f.object_type),
            (t.object_id, t.slot_id, t.object_type),
        );
        let Some(me) = w.me() else { return };
        if !p.result {
            return;
        }
        if p.result_type == 1 {
            for s in [f, t] {
                let (Some(sec), Some(asked)) = (r.holdings.section(s.object_id).cloned(), self.last_swap.as_ref()) else { continue };
                let was = if asked.slot_from.object_id == s.object_id && asked.slot_from.slot_id == s.slot_id {
                    Some(&asked.slot_from)
                } else if asked.slot_to.object_id == s.object_id && asked.slot_to.slot_id == s.slot_id {
                    Some(&asked.slot_to)
                } else {
                    None
                };
                if let Some(was) = was {
                    u.used(uc, me.char_id, -1, was.object_type, Some(&sec));
                }
            }
            return;
        }
        let (a, b) = (f.object_id, t.object_id);
        let (sa, sb) = (r.holdings.section(a).cloned(), r.holdings.section(b).cloned());
        u.moved(
            uc,
            me.char_id,
            me.id,
            (a, f.slot_id, f.object_type),
            (b, t.slot_id, t.object_type),
            sa.as_deref(),
            self.is_loot_bag(w, a),
            sb.as_deref(),
            self.is_loot_bag(w, b),
        );
    }

    /// Java `itemUsed`: an item used, for the UPE rules.
    pub fn item_used(
        &self,
        r: &Records,
        u: &mut crate::upe::Upe,
        uc: &crate::upe::Ctx,
        me: Option<&Entity>,
        p: &lg_protocol::packets::UseItem,
    ) {
        let Some(me) = me else { return };
        let obj = p.slot_object.object_id;
        let section = if obj == me.id { None } else { r.holdings.section(obj).cloned() };
        u.used(uc, me.char_id, p.slot_object.slot_id, p.slot_object.object_type, section.as_deref());
    }

    /// Java `tradeStart`.
    pub fn trade_start(u: &mut crate::upe::Upe, p: &lg_protocol::packets::TradeStart) {
        let items = |v: &[lg_protocol::packets::data::TradeItem]| v.iter().map(|i| i.item).collect::<Vec<_>>();
        let ench =
            |v: &[lg_protocol::packets::data::TradeItem]| v.iter().map(|i| Some(enchant_ids(Some(&i.unique_data)))).collect::<Vec<_>>();
        u.trade_start(&p.partner_name, items(&p.client_items), ench(&p.client_items), items(&p.partner_items), ench(&p.partner_items));
    }

    /// Java `vaultPacket`: a vault packet (there can be several per visit).
    pub fn vault_packet(&mut self, r: &mut Records, p: &lg_protocol::packets::VaultContent, player: Option<&Entity>) {
        if r.holdings.vault_packet(&r.ctx, p) {
            self.save_vault(r, player);
        }
        let seasonal = player.map(is_seasonal);
        self.add_container(r, &p.vault_contents, &p.vault_chest_enchants, "Vault chest", seasonal);
        self.add_container(r, &p.gift_contents, &p.gift_chest_enchants, "Gift chest", seasonal);
        if p.last_vault_packet {
            match player {
                Some(me) => self.vault_done(r, me),
                None => self.vault_pending = true,
            }
        } else if let Some(me) = player {
            self.scan_inventory(r, me);
        }
    }

    /// Java `saveVault`: the visit's season, from the character's stat or the character list.
    fn save_vault(&mut self, r: &mut Records, player: Option<&Entity>) {
        let Some(p) = player else { return };
        let stat = p.value(stats::SEASONAL);
        let me = r.characters.get(p.char_id);
        let seasonal = match (stat, me) {
            (Some(v), _) => Some(v == 1),
            (None, Some(c)) => Some(c.seasonal),
            (None, None) => None,
        };
        let mut how = if stat.is_some() { "from the character's stat".to_string() } else { "from the character list".to_string() };
        if let (Some(v), Some(c)) = (stat, me)
            && c.seasonal != (v == 1)
        {
            how.push_str(" (the character list said otherwise)");
        }
        r.holdings.vault_season(&r.ctx, seasonal, &how);
    }

    fn vault_done(&mut self, r: &mut Records, player: &Entity) {
        self.vault_pending = false;
        let seasonal = r.characters.get(player.char_id).map_or(is_seasonal(player), |c| c.seasonal);
        for (_, finds) in &mut self.vault_visit {
            for f in finds {
                f.seasonal = Some(seasonal);
            }
        }
        for (id, finds) in self.inventory_finds(r, player) {
            match self.vault_visit.iter_mut().find(|(i, _)| *i == id) {
                Some((_, v)) => v.extend(finds),
                None => self.vault_visit.push((id, finds)),
            }
        }
        r.collection.record_held(&self.vault_visit);
        self.vault_visit.clear();
    }

    /// Java `inventoryTick`, every tick: a vault waiting for its season, and your inventory every 5 s.
    pub fn inventory_tick(&mut self, r: &mut Records, player: Option<&Entity>) {
        if let Some(p) = player
            && r.holdings.vault_waiting()
        {
            self.save_vault(r, Some(p));
        }
        if self.vault_pending
            && let Some(p) = player
        {
            self.vault_done(r, p);
        }
        let Some(p) = player else { return };
        if r.ctx.now - self.last_inventory_scan < INVENTORY_INTERVAL_MS {
            return;
        }
        self.last_inventory_scan = r.ctx.now;
        self.scan_inventory(r, p);
    }

    fn scan_inventory(&mut self, r: &mut Records, player: &Entity) {
        let chests_before = self.chest_slots.clone();
        let char_before = self.chest_char;
        let held = self.inventory_finds(r, player);
        if char_before == player.char_id {
            self.opened_chests(r, &chests_before);
        }
        if !held.is_empty() {
            r.collection.record_held(&held);
        }
    }

    /// Java `inventoryFinds`: the collectible copies you carry, slot by slot.
    fn inventory_finds(&mut self, r: &mut Records, player: &Entity) -> Vec<(i32, Vec<Find>)> {
        let db = &r.ctx.data.items;
        let mut held: Vec<(i32, Vec<Find>)> = Vec::new();
        let (mut copies, mut items, mut chests) = (HashMap::new(), HashMap::new(), HashMap::new());
        let codes = player.text(stats::UNIQUE_DATA).map(all_parts).unwrap_or_default();
        let me = r.characters.get(player.char_id);
        let here = me.map(Seen::character);
        let seasonal = me.map_or(is_seasonal(player), |c| c.seasonal);
        let push = |held: &mut Vec<(i32, Vec<Find>)>, id: i32, f: Find| match held.iter_mut().find(|(i, _)| *i == id) {
            Some((_, v)) => v.push(f),
            None => held.push((id, vec![f])),
        };
        for i in 0..28usize {
            let t = if i >= 12 { stats::BACKPACK_0 + (i - 12) as u8 } else { stats::INVENTORY_0 + i as u8 };
            let Some(v) = player.value(t).filter(|&v| v >= 1) else { continue };
            let item = db.item(v);
            if item.is_some_and(|it| it.chest) {
                chests.insert(i, v);
            }
            if !item.is_some_and(|it| it.is_collectible()) {
                continue;
            }
            let mut ids = (i < codes.len()).then(|| enchant_ids(Some(codes[i])));
            if ids.is_none() {
                ids = r.characters.slot_enchants(player.char_id, i, v);
            }
            let (now, time) = (r.ctx.now, r.ctx.time);
            let mut f = match ids {
                Some(ids) => Find::with_ids(collection::INVENTORY, Some(ids), "", slot_name(i), now, time),
                None => Find::of(collection::INVENTORY, -1, "", slot_name(i), now, time),
            };
            f.seasonal = Some(seasonal);
            f.seen = here.clone();
            push(&mut held, v, f.clone());
            copies.insert(i, f);
            items.insert(i, v);
        }
        for (k, t) in QUICKSLOTS.iter().enumerate() {
            let Some(v) = player.value(*t).filter(|&v| v >= 1) else { continue };
            if !db.item(v).is_some_and(|it| it.is_collectible()) {
                continue;
            }
            let mut f = Find::of(collection::INVENTORY, -1, "", "Quick slot", r.ctx.now, r.ctx.time);
            f.seasonal = Some(seasonal);
            f.seen = here.clone();
            push(&mut held, v, f.clone());
            copies.insert(QUICKSLOT_FIRST + k, f);
            items.insert(QUICKSLOT_FIRST + k, v);
        }
        self.slot_copies = copies;
        self.slot_items = items;
        self.chest_slots = chests;
        self.chest_char = player.char_id;
        held
    }

    /// Java `openedChests`: a chest gone from a slot and a collectible in its place: it was opened.
    fn opened_chests(&mut self, r: &mut Records, before: &HashMap<usize, i32>) {
        let mut gone: HashMap<i32, i32> = HashMap::new();
        for c in before.values() {
            *gone.entry(*c).or_insert(0) += 1;
        }
        for c in self.chest_slots.values() {
            *gone.entry(*c).or_insert(0) -= 1;
        }
        let mut slots: Vec<(&usize, &i32)> = before.iter().collect();
        slots.sort();
        for (slot, chest) in slots {
            let (Some(&now), Some(copy)) = (self.slot_items.get(slot), self.slot_copies.get(slot)) else { continue };
            if gone.get(chest).copied().unwrap_or(0) <= 0 {
                continue;
            }
            *gone.entry(*chest).or_insert(0) -= 1;
            let from = r.ctx.data.items.item(*chest).map_or("Chest".to_string(), |c| c.name.clone());
            let mut f = match &copy.enchant_ids {
                Some(ids) => Find::with_ids(collection::DROP, Some(ids.clone()), &from, "", r.ctx.now, r.ctx.time),
                None => Find::of(collection::DROP, -1, &from, "", r.ctx.now, r.ctx.time),
            };
            f.seasonal = copy.seasonal;
            r.collection.record(now, f);
        }
    }

    /// Java `playerStatus`: what the game sent of your character; only the slots sent change what you have.
    pub fn player_status(&mut self, r: &mut Records, char_id: i32, pack: Option<i32>, stats_sent: &[Stat], entering: bool) {
        if char_id <= 0 {
            return;
        }
        let before = r.holdings.slots(&format!("char:{char_id}"), holdings::SLOTS).map(Vec::len);
        let pack = pack.unwrap_or_else(|| before.map_or(0, |n| (n as i32 - 12).max(0)));
        if let Some(seen) = live_slots(stats_sent, 12 + pack.clamp(0, 16) as usize, entering) {
            r.holdings.live(&r.ctx, char_id, &seen);
        }
    }

    /// Java `objectStatus`: what a vault chest shows.
    pub fn object_status(&mut self, r: &mut Records, id: i32, stats_sent: &[Stat]) {
        if !r.holdings.is_vault_object(id) {
            return;
        }
        let shown: BTreeMap<i32, i32> = stats_sent
            .iter()
            .filter(|s| (stats::INVENTORY_0..stats::INVENTORY_0 + 12).contains(&s.stat_type_num))
            .map(|s| (i32::from(s.stat_type_num - stats::INVENTORY_0), s.stat_value))
            .collect();
        r.holdings.chest_view(&r.ctx, id, &shown);
    }

    /// Java `bagDropped`: the collectibles in a bag that dropped for you.
    pub fn bag_dropped(&mut self, r: &mut Records, map: &str, bag: &Entity, seasonal: Option<bool>) {
        self.loot_bags.insert(bag.id);
        let codes = bag.text(stats::UNIQUE_DATA).map(java_split);
        let bag_name = bag_name(r.ctx.data, bag.object_type);
        for i in 0..8u8 {
            let Some(v) = bag.value(stats::INVENTORY_0 + i).filter(|&v| v >= 1) else { continue };
            let ids = enchant_ids(codes.as_ref().and_then(|c| c.get(usize::from(i)).copied()));
            if !r.ctx.data.items.item(v).is_some_and(|it| it.is_collectible()) {
                continue;
            }
            let mut f = Find::with_ids(collection::DROP, Some(ids), map, &bag_name, r.ctx.now, r.ctx.time);
            f.seasonal = seasonal;
            r.collection.record(v, f);
        }
    }

    /// Java `addContainer`: a vault chest's collectible copies, into the visit.
    fn add_container(&mut self, r: &mut Records, ids: &[i32], enchants: &str, label: &str, seasonal: Option<bool>) {
        if !plausible(r, ids) {
            return;
        }
        let codes = all_parts(enchants);
        let aligned = codes.len() == ids.len();
        for (i, &id) in ids.iter().enumerate() {
            if id < 1 || !r.ctx.data.items.item(id).is_some_and(|it| it.is_collectible()) {
                continue;
            }
            let mut f = if aligned {
                Find::with_ids(collection::VAULT, Some(enchant_ids(Some(codes[i]))), "Vault", label, r.ctx.now, r.ctx.time)
            } else {
                Find::of(collection::VAULT, -1, "Vault", label, r.ctx.now, r.ctx.time)
            };
            f.seasonal = seasonal;
            f.seen = Some(Seen::vault(label));
            match self.vault_visit.iter_mut().find(|(i, _)| *i == id) {
                Some((_, v)) => v.push(f),
                None => self.vault_visit.push((id, vec![f])),
            }
        }
    }
}

/// Java `LootEvents.plausible`: nine in ten of the items known.
fn plausible(r: &Records, ids: &[i32]) -> bool {
    let used = ids.iter().filter(|&&i| i >= 1).count();
    let known = ids.iter().filter(|&&i| i >= 1 && r.ctx.data.items.item(i).is_some()).count();
    known as f64 >= used as f64 * 0.9
}

/// Java `liveSlots`: the slots, enchant codes and quick slots among the stats sent.
fn live_slots(stats_sent: &[Stat], n: usize, entering: bool) -> Option<Live> {
    let mut items = vec![UNKNOWN; n];
    let mut quick = [UNKNOWN; 3];
    let mut codes: Option<&str> = None;
    let (mut any, mut any_quick) = (false, false);
    for s in stats_sent {
        let t = s.stat_type_num;
        let i = if (stats::INVENTORY_0..stats::INVENTORY_0 + 12).contains(&t) {
            Some(usize::from(t - stats::INVENTORY_0))
        } else if (stats::BACKPACK_0..stats::BACKPACK_0 + 16).contains(&t) {
            Some(12 + usize::from(t - stats::BACKPACK_0))
        } else {
            None
        };
        if let Some(i) = i.filter(|&i| i < n) {
            items[i] = if s.stat_value < 1 { -1 } else { s.stat_value };
            any = true;
            continue;
        }
        if t == stats::UNIQUE_DATA {
            codes = s.string_stat_value.as_deref();
            continue;
        }
        for (k, q) in QUICKSLOTS.iter().enumerate() {
            if t == *q {
                if s.stat_value < 1 && entering {
                    continue;
                }
                quick[k] = if s.stat_value < 1 { -1 } else { s.stat_value };
                any_quick = true;
            }
        }
    }
    let mut ench = vec![None; n];
    if let Some(c) = codes {
        for (i, code) in all_parts(c).into_iter().enumerate().take(n) {
            ench[i] = Some(enchant_ids(Some(code)));
        }
    }
    if !any && !any_quick && codes.is_none() {
        return None;
    }
    Some(Live { items, enchant_ids: ench, quick: any_quick.then(|| quick.to_vec()) })
}
