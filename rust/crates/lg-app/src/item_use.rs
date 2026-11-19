//! Items leaving you (Java: `lootgobbler.ItemUse`): forged, fed to a pet, dropped on the ground, used up; and what
//! the UPE rules hear of rewards, trades and quests.

use crate::collection::{self, Collection, Find};
use crate::loot_events::LootEvents;
use crate::upe::{self, Upe};
use lg_gamedata::GameData;
use lg_protocol::packets::data::SlotObject;
use std::collections::HashMap;

/// An item and the copy of it seen in that slot.
#[derive(Debug, Clone)]
struct Used {
    id: i32,
    copy: Option<Find>,
}

#[derive(Debug, Default)]
pub struct ItemUse {
    /// The items of the last forge request, until its result.
    forging: Vec<Used>,
    /// slot -> item of a use request, until its answer.
    using: HashMap<i32, Used>,
}

pub const USE_REQUEST: u8 = 195;
pub const USE_RESULT: u8 = 196;

fn used(data: &GameData, loot: &LootEvents, slots: &[SlotObject]) -> Vec<Used> {
    slots
        .iter()
        .filter(|s| data.items.item(s.object_type).is_some_and(|i| i.is_collectible()))
        .map(|s| Used { id: s.object_type, copy: loot.copy_in_slot(s.slot_id, s.object_type) })
        .collect()
}

fn remove(c: &mut Collection, items: &[Used], source: &str, r#where: &str, now: i64, time: &str) {
    for u in items {
        c.record_removed(u.id, source, r#where, u.copy.as_ref(), now, time);
    }
}

impl ItemUse {
    #[allow(clippy::too_many_arguments)]
    pub fn forge_request(&mut self, data: &GameData, loot: &LootEvents, u: &mut Upe, playing: i32, p: &lg_protocol::packets::ForgeRequest) {
        self.forging = used(data, loot, &p.dismantled_items);
        let slots: Vec<i32> = p.dismantled_items.iter().map(|s| s.slot_id).collect();
        u.forge_request(playing, &slots);
    }

    #[allow(clippy::too_many_arguments)]
    pub fn forge_result(
        &mut self,
        c: &upe::Ctx,
        col: &mut Collection,
        u: &mut Upe,
        playing: i32,
        p: &lg_protocol::packets::ForgeResult,
        time: &str,
    ) {
        let used = std::mem::take(&mut self.forging);
        let slots: Vec<i32> = p.results.iter().map(|s| s.slot_id).collect();
        let types: Vec<i32> = p.results.iter().map(|s| s.object_type).collect();
        u.forge_result(c, playing, p.success, Some((&slots, &types)));
        if !p.success {
            return;
        }
        remove(col, &used, collection::FORGED, "Forge", c.now, time);
    }

    pub fn pet_upgrade(
        &mut self,
        data: &GameData,
        loot: &LootEvents,
        col: &mut Collection,
        p: &lg_protocol::packets::PetUpgradeRequest,
        now: i64,
        time: &str,
    ) {
        if p.pet_trans_type != Some(lg_protocol::packets::PetUpgradeType::FeedPet) {
            return;
        }
        let u = used(data, loot, &p.slot_objects);
        remove(col, &u, collection::FED, "Pet", now, time);
    }

    pub fn dropped(
        &mut self,
        data: &GameData,
        loot: &LootEvents,
        col: &mut Collection,
        p: &lg_protocol::packets::InvDrop,
        now: i64,
        time: &str,
    ) {
        let u = used(data, loot, std::slice::from_ref(&p.slot_object));
        remove(col, &u, collection::DROPPED, "Ground", now, time);
    }

    /// Java `raw`: the use request (195) and its answer (196), unknown to RealmShark: forging or feeding from the
    /// inventory.
    #[allow(clippy::too_many_arguments)]
    pub fn raw(
        &mut self,
        c: &upe::Ctx,
        loot: &LootEvents,
        col: &mut Collection,
        u: &mut Upe,
        playing: i32,
        id: u8,
        body: &[u8],
        time: &str,
    ) {
        let mut at = 0;
        let byte = |at: &mut usize| -> Option<u8> {
            let b = *body.get(*at)?;
            *at += 1;
            Some(b)
        };
        let int = |at: &mut usize| -> i32 {
            let v = i32::from_be_bytes(body[*at..*at + 4].try_into().expect("4 bytes"));
            *at += 4;
            v
        };
        if id == USE_REQUEST {
            let Some(count) = byte(&mut at) else { return }; // Java throws here: nothing more happens
            let mut slots = vec![0; usize::from(count)];
            let mut i = 0;
            while i < usize::from(count) && body.len() - at >= 12 {
                int(&mut at);
                let (slot, item) = (int(&mut at), int(&mut at));
                slots[i] = slot;
                if c.data.items.item(item).is_some_and(|it| it.is_collectible()) {
                    self.using.insert(slot, Used { id: item, copy: loot.copy_in_slot(slot, item) });
                }
                i += 1;
            }
            u.forge_request(playing, &slots);
        } else if id == USE_RESULT {
            let Some(ok) = byte(&mut at) else { return };
            let Some(count) = byte(&mut at) else { return };
            let ok = ok == 1;
            let mut gone = Vec::new();
            let mut i = 0;
            while i < usize::from(count) && body.len() - at >= 12 {
                int(&mut at);
                let (slot, now) = (int(&mut at), int(&mut at));
                let was = self.using.remove(&slot);
                if let Some(w) = was.filter(|w| ok && now != w.id) {
                    gone.push(w);
                }
                i += 1;
            }
            u.forge_result(c, playing, ok, None);
            remove(col, &gone, collection::CONSUMED, "Forge or pet", c.now, time);
        }
    }
}
