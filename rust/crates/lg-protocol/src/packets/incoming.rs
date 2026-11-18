//! Packets from the game server (Java: `packets.incoming.*`). Field names follow Java's, so the oracle can compare
//! them; types follow how Java reads them (a Java `byte` is `i8` here, an unsigned short read into an `int` is `u16`).

use super::data::*;
use crate::dump::Bytes;
use crate::reader::{Reader, Result};
use crate::{code_enum, dump_record, record};

record! {
    /// NEWTICK (10): the server's regular update of objects in view.
    NewTick(r) {
        tick_id: i32 = r.i32()?,
        tick_time: i32 = r.i32()?,
        server_real_time_ms: u32 = r.u32()?,
        server_last_time_rtt_ms: u16 = r.u16()?,
        status: Vec<ObjectStatus> = r.list16(ObjectStatus::read)?,
    }
}

record! {
    /// UPDATE (42): tiles and objects coming into view, objects leaving it.
    Update(r) {
        pos: WorldPos = WorldPos::read(r)?,
        level_type: i8 = r.i8()?,
        tiles: Vec<GroundTile> = r.list_compressed(GroundTile::read)?,
        new_objects: Vec<NewObject> = r.list_compressed(NewObject::read)?,
        /// Object ids that left the view.
        drops: Vec<i32> = r.list_compressed(Reader::compressed_int)?,
    }
}

record! {
    /// TEXT (44): a chat line, from a player or the game.
    Text(r) {
        name: String = r.string()?,
        object_id: i32 = r.i32()?,
        num_stars: i16 = r.i16()?,
        bubble_time: u8 = r.u8()?,
        recipient: String = r.string()?,
        text: String = r.string()?,
        clean_text: String = r.string()?,
        is_supporter: bool = r.bool()?,
        star_background: i32 = r.i32()?,
    }
}

impl Text {
    /// The fields in the order they are sent: for writing test captures.
    pub fn encode(&self) -> Vec<u8> {
        let mut b = Vec::new();
        let s = |b: &mut Vec<u8>, s: &str| {
            b.extend((s.len() as u16).to_be_bytes());
            b.extend(s.as_bytes());
        };
        s(&mut b, &self.name);
        b.extend(self.object_id.to_be_bytes());
        b.extend(self.num_stars.to_be_bytes());
        b.push(self.bubble_time);
        s(&mut b, &self.recipient);
        s(&mut b, &self.text);
        s(&mut b, &self.clean_text);
        b.push(u8::from(self.is_supporter));
        b.extend(self.star_background.to_be_bytes());
        b
    }
}

record! {
    /// RECONNECT (45): go to another server or map.
    Reconnect(r) {
        name: String = r.string()?,
        host: String = r.string()?,
        port: u16 = r.u16()?,
        game_id: i32 = r.i32()?,
        key_time: i32 = r.i32()?,
        key: Bytes = Bytes(r.byte_array()?),
    }
}

/// DEATH (46): a character died. Java keeps what it could read when the part after `total_fame` is cut off.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Death {
    pub account_id: String,
    pub char_id: i32,
    pub killed_by: String,
    pub gravestone_type: i32,
    pub total_fame: i32,
    pub unknown1: i32,
    pub unknown2: i32,
    /// None if that part was cut off.
    pub fame_data: Option<Vec<Fame>>,
    pub stats: Option<String>,
}
dump_record!(Death { account_id, char_id, killed_by, gravestone_type, total_fame, unknown1, unknown2, fame_data, stats });

impl Death {
    pub fn read(r: &mut Reader) -> Result<Self> {
        let mut d = Death {
            account_id: r.string()?,
            char_id: r.compressed_int()?,
            killed_by: r.string()?,
            gravestone_type: r.i32()?,
            total_fame: r.compressed_int()?,
            ..Default::default()
        };
        let _ = (|| -> Result<()> {
            d.unknown1 = r.compressed_int()?;
            d.unknown2 = r.compressed_int()?;
            d.fame_data = Some(r.list_compressed(Fame::read)?);
            d.stats = Some(r.string()?);
            Ok(())
        })();
        Ok(d)
    }
}

code_enum! {
    /// How a notification is shown (Java: `NotificationEffectType`).
    NotificationEffect {
        StatIncrease = 0 => "StatIncrease",
        ServerMessage = 1 => "ServerMessage",
        ErrorMessage = 2 => "ErrorMessage",
        StickyMessage = 3 => "StickyMessage",
        Global = 4 => "Global",
        Queue = 5 => "Queue",
        ObjectText = 6 => "ObjectText",
        PlayerDeath = 7 => "PlayerDeath",
        PortalOpened = 8 => "PortalOpened",
        TeleportationError = 9 => "TeleportationError",
        PlayerCallout = 10 => "PlayerCallout",
        ProgressBar = 11 => "ProgressBar",
        Behavior = 12 => "Behavior",
        Emote = 13 => "Emote",
        Victory = 14 => "Victory",
        MissionRefresh = 15 => "MissionRefresh",
        MissionsProgressOnlyRefresh = 16 => "MissionsProgressOnlyRefresh",
        BlueprintUnlock = 20 => "BlueprintUnlock",
        WithIcon = 21 => "WithIcon",
        FameBonus = 22 => "FameBonus",
        ForgeFire = 23 => "ForgeFire",
    }
}

/// NOTIFICATION (67): a message on screen. Which fields are sent depends on `effect`; the others stay 0 / None.
#[derive(Debug, Clone, PartialEq)]
pub struct Notification {
    pub effect: NotificationEffect,
    pub extra: i8,
    pub object_id: i32,
    pub message: Option<String>,
    pub ui_extra: i32,
    pub realm_queue_message_type: i32,
    pub queue_pos: i32,
    pub color: i32,
    pub picture_type: i32,
    pub sender_object_id: i32,
    pub number_of_stars: i32,
    pub progress_max: i32,
    pub progress_value: i16,
    pub emote_type: i32,
}
dump_record!(Notification {
    effect,
    extra,
    object_id,
    message,
    ui_extra,
    realm_queue_message_type,
    queue_pos,
    color,
    picture_type,
    sender_object_id,
    number_of_stars,
    progress_max,
    progress_value,
    emote_type
});

impl Notification {
    pub fn read(r: &mut Reader) -> Result<Self> {
        use NotificationEffect as E;
        let code = r.i8()?;
        // Java switches on null for an unknown type and throws: the packet is dropped
        let effect = E::from_code(code.into()).ok_or(r.fail("unknown notification type"))?;
        let mut n = Notification {
            effect,
            extra: r.i8()?,
            object_id: 0,
            message: None,
            ui_extra: 0,
            realm_queue_message_type: 0,
            queue_pos: 0,
            color: 0,
            picture_type: 0,
            sender_object_id: 0,
            number_of_stars: 0,
            progress_max: 0,
            progress_value: 0,
            emote_type: 0,
        };
        match effect {
            E::StatIncrease | E::ServerMessage | E::ErrorMessage | E::StickyMessage | E::TeleportationError => {
                n.message = Some(r.string()?);
            }
            E::Global => {
                n.message = Some(r.string()?);
                n.ui_extra = r.i16()?.into();
            }
            E::Queue => {
                n.realm_queue_message_type = r.i32()?;
                n.queue_pos = r.i16()?.into();
            }
            E::ObjectText => {
                n.message = Some(r.string()?);
                n.object_id = r.i32()?;
                n.color = r.i32()?;
            }
            E::PlayerDeath | E::PortalOpened => {
                n.message = Some(r.string()?);
                n.picture_type = r.i32()?;
            }
            E::PlayerCallout => {
                n.message = Some(r.string()?);
                n.sender_object_id = r.i32()?;
                n.number_of_stars = r.i16()?.into();
            }
            E::ProgressBar => {
                if n.extra != 0 {
                    if n.extra & 3 != 0 {
                        n.message = Some(r.string()?);
                    }
                    n.progress_max = r.i32()?;
                    n.progress_value = r.i16()?;
                }
            }
            E::Behavior => {
                n.message = Some(r.string()?);
                n.picture_type = r.i32()?;
                n.color = r.i32()?;
            }
            E::Emote => {
                n.object_id = r.i32()?;
                n.emote_type = r.i32()?;
            }
            _ => {}
        }
        Ok(n)
    }
}

/// MAPINFO (92): the map the player enters.
#[derive(Debug, Clone, PartialEq)]
pub struct MapInfo {
    pub width: i32,
    pub height: i32,
    pub name: String,
    pub display_name: String,
    pub realm_name: String,
    pub fp: i32,
    pub background: i32,
    pub difficulty: f32,
    pub allow_player_teleport: bool,
    pub no_save: bool,
    pub show_displays: bool,
    pub max_player_count: i16,
    pub game_opened_time: i32,
    pub version_number: String,
    pub unknown1: i16,
    pub view_distance: i16,
    pub unknown2: bool,
    pub unknown_int: i32,
    /// The modifiers string cut at `;` (up to four parts), with the dungeon grade taken off after a `|`.
    pub dungeon_modifiers: Option<String>,
    pub dungeon_modifiers2: Option<String>,
    pub dungeon_modifiers3: Option<String>,
    pub dungeon_modifiers4: Option<String>,
    pub dungeon_grade: Option<String>,
    pub bg_color: i16,
    /// -1 when not sent.
    pub max_realm_score: i32,
    pub current_realm_score: i32,
}
dump_record!(MapInfo {
    width,
    height,
    name,
    display_name,
    realm_name,
    fp,
    background,
    difficulty,
    allow_player_teleport,
    no_save,
    show_displays,
    max_player_count,
    game_opened_time,
    version_number,
    unknown1,
    view_distance,
    unknown2,
    unknown_int,
    dungeon_modifiers,
    dungeon_modifiers2,
    dungeon_modifiers3,
    dungeon_modifiers4,
    dungeon_grade,
    bg_color,
    max_realm_score,
    current_realm_score
});

impl MapInfo {
    pub fn read(r: &mut Reader) -> Result<Self> {
        let width = r.i32()?;
        let height = r.i32()?;
        let name = r.string()?;
        let display_name = r.string()?;
        let realm_name = r.string()?;
        let fp = r.i32()?;
        let background = r.i32()?;
        let difficulty = r.f32()?;
        let allow_player_teleport = r.bool()?;
        let no_save = r.bool()?;
        let show_displays = r.bool()?;
        let max_player_count = r.i16()?;
        let game_opened_time = r.i32()?;
        let version_number = r.string()?;
        let unknown1 = r.i16()?;
        let view_distance = r.i16()?;
        let unknown2 = r.bool()?;
        let unknown_int = r.i32()?;
        let (modifiers, dungeon_grade) = split_modifiers(&r.string()?);
        let [dungeon_modifiers, dungeon_modifiers2, dungeon_modifiers3, dungeon_modifiers4] = modifiers;
        let bg_color = r.i16()?;
        let (max_realm_score, current_realm_score) = if r.remaining() >= 8 { (r.i32()?, r.i32()?) } else { (-1, -1) };
        Ok(MapInfo {
            width,
            height,
            name,
            display_name,
            realm_name,
            fp,
            background,
            difficulty,
            allow_player_teleport,
            no_save,
            show_displays,
            max_player_count,
            game_opened_time,
            version_number,
            unknown1,
            view_distance,
            unknown2,
            unknown_int,
            dungeon_modifiers,
            dungeon_modifiers2,
            dungeon_modifiers3,
            dungeon_modifiers4,
            dungeon_grade,
            bg_color,
            max_realm_score,
            current_realm_score,
        })
    }
}

/// Java's `MapInfoPacket` modifier split: up to four `;` parts; the grade is a fifth part starting with `|`, or else
/// what follows the first `|` in the last part that has one (looking from the fourth part back).
fn split_modifiers(s: &str) -> ([Option<String>; 4], Option<String>) {
    let parts: Vec<&str> = s.split(';').collect();
    let mut m: [Option<String>; 4] = std::array::from_fn(|i| parts.get(i).map(|p| p.to_string()));
    if parts.len() > 4 && parts[4].starts_with('|') {
        return (m, Some(parts[4][1..].to_string()));
    }
    for slot in m.iter_mut().rev() {
        if let Some(p) = slot.as_deref()
            && let Some((before, grade)) = p.split_once('|')
        {
            let grade = grade.to_string();
            *slot = Some(before.to_string());
            return (m, Some(grade));
        }
    }
    (m, None)
}

record! {
    /// INVRESULT (95): whether an item move worked.
    InvResult(r) {
        result: bool = r.bool()?,
        result_type: i8 = r.i8()?,
        slot_from: SlotObject = SlotObject::read(r)?,
        slot_to: SlotObject = SlotObject::read(r)?,
        condition1: i32 = r.i32()?,
        condition2: i32 = r.i32()?,
    }
}

record! {
    /// CREATE_SUCCESS (101): the player's character is in the map.
    CreateSuccess(r) {
        object_id: i32 = r.i32()?,
        char_id: i32 = r.i32()?,
        str: String = r.string()?,
    }
}

record! {
    /// EXALTATION_BONUS_CHANGED (114): exaltation progress of one class.
    ExaltationUpdate(r) {
        obj_type: i16 = r.i16()?,
        dexterity_progress: i32 = r.compressed_int()?,
        speed_progress: i32 = r.compressed_int()?,
        vitality_progress: i32 = r.compressed_int()?,
        wisdom_progress: i32 = r.compressed_int()?,
        defense_progress: i32 = r.compressed_int()?,
        attack_progress: i32 = r.compressed_int()?,
        mana_progress: i32 = r.compressed_int()?,
        health_progress: i32 = r.compressed_int()?,
    }
}

/// VAULT_UPDATE (117): the contents of the vault and the other storage chests. Java keeps what it could read and sets
/// `partial` when the packet is cut off or a list is implausibly long.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct VaultContent {
    pub last_vault_packet: bool,
    pub vault_chest_object_id: i32,
    pub material_chest_object_id: i32,
    pub gift_chest_object_id: i32,
    pub potion_storage_object_id: i32,
    pub seasonal_spoil_chest_object_id: i32,
    pub vault_contents: Vec<i32>,
    pub material_contents: Vec<i32>,
    pub gift_contents: Vec<i32>,
    pub potion_contents: Vec<i32>,
    pub seasonal_spoil_content: Vec<i32>,
    pub vault_upgrade_cost: i16,
    pub material_upgrade_cost: i16,
    pub potion_upgrade_cost: i16,
    pub current_potion_max: i16,
    pub next_potion_max: i16,
    pub unknown_short: i16,
    pub vault_chest_enchants: String,
    pub gift_chest_enchants: String,
    pub spoils_chest_enchants: String,
    pub partial: bool,
}
dump_record!(VaultContent {
    last_vault_packet,
    vault_chest_object_id,
    material_chest_object_id,
    gift_chest_object_id,
    potion_storage_object_id,
    seasonal_spoil_chest_object_id,
    vault_contents,
    material_contents,
    gift_contents,
    potion_contents,
    seasonal_spoil_content,
    vault_upgrade_cost,
    material_upgrade_cost,
    potion_upgrade_cost,
    current_potion_max,
    next_potion_max,
    unknown_short,
    vault_chest_enchants,
    gift_chest_enchants,
    spoils_chest_enchants,
    partial
});

impl VaultContent {
    pub fn read(r: &mut Reader) -> Result<Self> {
        fn array(r: &mut Reader) -> Result<Vec<i32>> {
            let n = r.compressed_int()?;
            if !(0..=5000).contains(&n) {
                return Err(r.fail("implausible array length"));
            }
            r.list(n, Reader::compressed_int)
        }
        let mut v = VaultContent::default();
        let read = (|| -> Result<()> {
            v.last_vault_packet = r.bool()?;
            v.vault_chest_object_id = r.compressed_int()?;
            v.material_chest_object_id = r.compressed_int()?;
            v.gift_chest_object_id = r.compressed_int()?;
            v.potion_storage_object_id = r.compressed_int()?;
            v.seasonal_spoil_chest_object_id = r.compressed_int()?;
            v.vault_contents = array(r)?;
            v.material_contents = array(r)?;
            v.gift_contents = array(r)?;
            v.potion_contents = array(r)?;
            v.seasonal_spoil_content = array(r)?;
            v.vault_upgrade_cost = r.i16()?;
            v.material_upgrade_cost = r.i16()?;
            v.potion_upgrade_cost = r.i16()?;
            v.current_potion_max = r.i16()?;
            v.next_potion_max = r.i16()?;
            v.unknown_short = r.i16()?;
            v.vault_chest_enchants = r.string()?;
            v.gift_chest_enchants = r.string()?;
            v.spoils_chest_enchants = r.string()?;
            Ok(())
        })();
        v.partial = read.is_err();
        Ok(v)
    }
}

record! {
    /// FORGE_RESULT (119): what the forge made.
    ForgeResult(r) {
        success: bool = r.bool()?,
        results: Vec<SlotObject> = { let n = r.i8()?; r.list(n.into(), SlotObject::read)? },
    }
}

record! {
    /// CRUCIBLE_RESPONSE (183): crucible settings, as JSON.
    CrucibleResponse(r) {
        crucible_ids: Vec<i32> = r.list16(Reader::i32)?,
        crucible_jsons: Vec<String> = r.list16(Reader::string)?,
    }
}

record! {
    /// TRADEREQUESTED (88): someone asks to trade.
    TradeRequested(r) {
        name: String = r.string()?,
    }
}

record! {
    /// TRADESTART (86): a trade window opens.
    TradeStart(r) {
        client_items: Vec<TradeItem> = r.list16(TradeItem::read)?,
        partner_name: String = r.string()?,
        partner_items: Vec<TradeItem> = r.list16(TradeItem::read)?,
        object_id: i32 = r.i32()?,
        unknown: i8 = r.i8()?,
    }
}

code_enum! {
    /// How a trade ended (Java: `TradeResult`).
    TradeResult {
        Successful = 0 => "Successful",
        PlayerCancelled = 1 => "PlayerCancelled",
    }
}

record! {
    /// TRADEDONE (34): a trade ended.
    TradeDone(r) {
        /// None for a code Java doesn't know.
        code: Option<TradeResult> = TradeResult::from_code(r.i32()?),
        description: String = r.string()?,
    }
}

record! {
    /// TRADEACCEPTED (14): the trade partner accepted these offers.
    TradeAccepted(r) {
        client_offer: Vec<bool> = r.list16(Reader::bool)?,
        partner_offer: Vec<bool> = r.list16(Reader::bool)?,
    }
}

record! {
    /// BUYRESULT (22): a purchase worked or not.
    BuyResult(r) {
        result: i32 = r.i32()?,
        result_string: String = r.string()?,
    }
}

record! {
    /// SERVERPLAYERSHOOT (12): a shot the server fires for a player. The last three fields are only in newer
    /// versions, and stay 0 when missing.
    ServerPlayerShoot(r) {
        bullet_id: i16 = r.i16()?,
        owner_id: i32 = r.i32()?,
        container_type: i32 = r.i32()?,
        starting_pos: WorldPos = WorldPos::read(r)?,
        angle: f32 = r.f32()?,
        damage: i16 = r.i16()?,
        summoner_id: i32 = r.i32()?,
        bullet_type: i8 = if r.remaining() > 0 { r.i8()? } else { 0 },
        bullet_count: i8 = if r.remaining() > 0 { r.i8()? } else { 0 },
        angles_between_bullets: f32 = if r.remaining() > 0 { r.f32()? } else { 0.0 },
    }
}

record! {
    /// QUEST_REDEEM_RESPONSE (96): a daily quest was handed in, or not.
    QuestRedeemResponse(r) {
        ok: bool = r.bool()?,
        message: String = r.string()?,
    }
}

record! {
    /// QUEST_FETCH_RESPONSE (6): the daily quests.
    QuestFetchResponse(r) {
        quests: Vec<Quest> = r.list16(Quest::read)?,
        next_refresh_price: i16 = r.i16()?,
    }
}

record! {
    /// LOGIN_REWARD_MSG (93): a daily login reward (Java: `ClaimDailyRewardResponse`).
    ClaimDailyRewardResponse(r) {
        item_id: i32 = r.i32()?,
        quantity: i32 = r.i32()?,
        gold: i32 = r.i32()?,
    }
}

/// ENEMYSHOOT (35): an enemy fires. `num_shots` and `angle_inc` are only sent for more than one shot (255 and 0 when
/// missing); if one is there the other must be too.
#[derive(Debug, Clone, PartialEq)]
pub struct EnemyShoot {
    pub bullet_id: i16,
    pub owner_id: i32,
    pub bullet_type: u8,
    pub starting_pos: WorldPos,
    pub angle: f32,
    pub damage: i16,
    pub num_shots: u8,
    pub angle_inc: f32,
}
dump_record!(EnemyShoot { bullet_id, owner_id, bullet_type, starting_pos, angle, damage, num_shots, angle_inc });

impl EnemyShoot {
    pub fn read(r: &mut Reader) -> Result<Self> {
        let bullet_id = r.i16()?;
        let owner_id = r.i32()?;
        let bullet_type = r.u8()?;
        let starting_pos = WorldPos::read(r)?;
        let angle = r.f32()?;
        let damage = r.i16()?;
        let (num_shots, angle_inc) = if r.remaining() > 0 { (r.u8()?, r.f32()?) } else { (255, 0.0) };
        Ok(EnemyShoot { bullet_id, owner_id, bullet_type, starting_pos, angle, damage, num_shots, angle_inc })
    }
}

record! {
    /// DAMAGE (75): something took damage.
    Damage(r) {
        target_id: i32 = r.i32()?,
        effects: Vec<u8> = { let n = r.u8()?; r.list(n.into(), Reader::u8)? },
        damage_amount: u16 = r.u16()?,
        damage_properties: bool = r.bool()?,
        bullet_id: u16 = r.u16()?,
        object_id: i32 = r.i32()?,
    }
}

record! {
    /// CLAIM_BP_MILESTONE_RESULT (150): a battle pass reward was claimed (Java: `ClaimBPMilestoneResultPacket`).
    ClaimBpMilestoneResult(r) {
        item_redeemed: bool = r.bool()?,
    }
}

record! {
    /// CHEST_REWARD_RESULT (172): what a reward chest held.
    ChestRewardResult(r) {
        contents: Vec<i32> = r.list16(Reader::i32)?,
    }
}

record! {
    /// AOE (64): an area attack lands.
    Aoe(r) {
        pos: WorldPos = WorldPos::read(r)?,
        radius: f32 = r.f32()?,
        damage: u16 = r.u16()?,
        effect: u8 = r.u8()?,
        duration: f32 = r.f32()?,
        orig_type: u16 = r.u16()?,
        color: i32 = r.i32()?,
        armor_piercing: bool = r.bool()?,
    }
}

record! {
    /// STASIS (166): an object is put in stasis.
    Stasis(r) {
        entity_id: i32 = r.i32()?,
        unknown_byte_array: Bytes = Bytes(r.bytes(12)?.to_vec()),
        stasis_duration: f32 = r.f32()?,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dump::to_string;

    fn s(b: &mut Vec<u8>, s: &str) {
        b.extend((s.len() as u16).to_be_bytes());
        b.extend(s.as_bytes());
    }

    #[test]
    fn modifiers_split_as_java() {
        let m = |s: &str| {
            let (m, g) = split_modifiers(s);
            (m.map(|p| p.unwrap_or_else(|| "-".into())).join(","), g)
        };
        assert_eq!(m(""), (",-,-,-".to_string(), None));
        assert_eq!(m("a;b"), ("a,b,-,-".to_string(), None));
        assert_eq!(m("a;b|S"), ("a,b,-,-".to_string(), Some("S".into())));
        assert_eq!(m("a|x;b|S|T"), ("a|x,b,-,-".to_string(), Some("S|T".into())));
        assert_eq!(m("a;b;c;d;|A"), ("a,b,c,d".to_string(), Some("A".into())));
        assert_eq!(m("a;b;c;d;e|A"), ("a,b,c,d".to_string(), None));
    }

    #[test]
    fn notification_by_effect() {
        let mut b = vec![6, 0];
        s(&mut b, "hello");
        b.extend(7i32.to_be_bytes());
        b.extend((-1i32).to_be_bytes());
        let n = Notification::read(&mut Reader::new(&b)).unwrap();
        assert_eq!((n.effect, n.message.as_deref(), n.object_id, n.color), (NotificationEffect::ObjectText, Some("hello"), 7, -1));
        assert_eq!(Notification::read(&mut Reader::new(&[17, 0])).unwrap_err().why, "unknown notification type");
        // a progress bar without anything else
        let n = Notification::read(&mut Reader::new(&[11, 0])).unwrap();
        assert!(n.message.is_none());
    }

    #[test]
    fn vault_cut_off_keeps_what_was_read() {
        let mut b = vec![1];
        b.extend(compressed(500));
        b.extend([0, 0, 0, 0]);
        b.extend(compressed(2));
        b.extend(compressed(2591));
        b.extend(compressed(-1));
        let mut r = Reader::new(&b);
        let v = VaultContent::read(&mut r).unwrap();
        assert!(v.partial && v.last_vault_packet && r.done());
        assert_eq!((v.vault_chest_object_id, v.vault_contents.clone()), (500, vec![2591, -1]));
        // a list longer than 5000 is not believed
        let mut b = vec![0, 0, 0, 0, 0, 0];
        b.extend(compressed(5001));
        assert!(VaultContent::read(&mut Reader::new(&b)).unwrap().partial);
    }

    #[test]
    fn death_cut_off_after_fame() {
        let mut b = Vec::new();
        s(&mut b, "acc");
        b.extend(compressed(12));
        s(&mut b, "Pirate");
        b.extend(0i32.to_be_bytes());
        b.extend(compressed(1234));
        b.extend(compressed(5));
        let d = Death::read(&mut Reader::new(&b)).unwrap();
        assert_eq!((d.char_id, d.total_fame, d.unknown1, d.fame_data.is_none()), (12, 1234, 5, true));
        assert!(to_string(&d).contains("famedata=null"));
    }

    #[test]
    fn enemy_shoot_needs_both_extras() {
        let mut b = Vec::new();
        b.extend(1i16.to_be_bytes());
        b.extend(2i32.to_be_bytes());
        b.push(3);
        b.extend([0; 12]);
        b.extend(10i16.to_be_bytes());
        assert_eq!(EnemyShoot::read(&mut Reader::new(&b)).unwrap().num_shots, 255);
        b.push(4);
        assert!(EnemyShoot::read(&mut Reader::new(&b)).is_err());
        b.extend(0.5f32.to_be_bytes());
        let e = EnemyShoot::read(&mut Reader::new(&b)).unwrap();
        assert_eq!((e.num_shots, e.angle_inc), (4, 0.5));
    }
}
