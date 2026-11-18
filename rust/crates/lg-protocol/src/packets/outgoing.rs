//! Packets the game client sends (Java: `packets.outgoing.*`). Same naming rules as [`super::incoming`].

use super::data::*;
use crate::dump::Bytes;
use crate::reader::Reader;
use crate::{code_enum, record};

code_enum! {
    /// How an item is used (Java: `UseItemType`). Java's "INVALID" (-1) can never match a code, so it is left out.
    UseItemType {
        Default = 0 => "DEFAULT",
        Start = 1 => "START",
        End = 2 => "END",
    }
}

record! {
    /// USEITEM (13): the player uses an item.
    UseItem(r) {
        time: i32 = r.i32()?,
        slot_object: SlotObject = SlotObject::read(r)?,
        use_item_position: WorldPos = WorldPos::read(r)?,
        use_item_type: Option<UseItemType> = UseItemType::from_code(r.i8()?.into()),
        use_item_flag: i32 = r.i32()?,
    }
}

code_enum! {
    /// Java: `PetUpgradeType`.
    PetUpgradeType {
        PetYard = 1 => "PetYard",
        FeedPet = 2 => "FeedPet",
        FusePet = 3 => "FusePet",
    }
}

code_enum! {
    /// Java: `PaymentType`.
    PaymentType {
        Invalid = -1 => "Invalid",
        Gold = 0 => "Gold",
        Fame = 1 => "Fame",
        GuildFame = 2 => "GuildFame",
        FortuneTokens = 3 => "FortuneTokens",
    }
}

record! {
    /// PETUPGRADEREQUEST (16): feeding or fusing pets.
    PetUpgradeRequest(r) {
        pet_trans_type: Option<PetUpgradeType> = PetUpgradeType::from_code(r.i8()?.into()),
        p_id_one: i32 = r.i32()?,
        p_id_two: i32 = r.i32()?,
        object_id: i32 = r.i32()?,
        payment_type: Option<PaymentType> = PaymentType::from_code(r.i8()?.into()),
        slot_objects: Vec<SlotObject> = r.list16(SlotObject::read)?,
    }
}

record! {
    /// INVDROP (19): the player drops an item.
    InvDrop(r) {
        slot_object: SlotObject = SlotObject::read(r)?,
        quick_slot: i8 = r.i8()?,
    }
}

record! {
    /// ENEMYHIT (25): the player's shot hit an enemy.
    EnemyHit(r) {
        time: i32 = r.i32()?,
        bullet_id: i16 = r.i16()?,
        shooter_id: i32 = r.i32()?,
        target_id: i32 = r.i32()?,
        kill: bool = r.bool()?,
        main_id: i32 = r.i32()?,
    }
}

record! {
    /// PLAYERSHOOT (30): the player fires.
    PlayerShoot(r) {
        time: i32 = r.i32()?,
        bullet_id: i16 = r.i16()?,
        weapon_id: u16 = r.u16()?,
        projectile_id: i8 = r.i8()?,
        starting_pos: WorldPos = WorldPos::read(r)?,
        angle: f32 = r.f32()?,
        is_burst: bool = r.bool()?,
        pattern_idx: i8 = r.i8()?,
        attack_type: i8 = r.i8()?,
        player_position: WorldPos = WorldPos::read(r)?,
    }
}

record! {
    /// ACCEPTTRADE (36): the player accepts the trade.
    AcceptTrade(r) {
        client_offer: Vec<bool> = r.list16(Reader::bool)?,
        partner_offer: Vec<bool> = r.list16(Reader::bool)?,
    }
}

record! {
    /// INVSWAP (55): the player moves an item.
    InvSwap(r) {
        time: i32 = r.i32()?,
        player_world_pos: WorldPos = WorldPos::read(r)?,
        slot_from: SlotObject = SlotObject::read(r)?,
        slot_to: SlotObject = SlotObject::read(r)?,
    }
}

record! {
    /// QUEST_REDEEM (58): the player hands in a daily quest.
    QuestRedeem(r) {
        quest_id_string: String = r.string()?,
        quest_id_int: i32 = r.i32()?,
        slots: Vec<SlotObject> = r.list16(SlotObject::read)?,
    }
}

record! {
    /// MOVE (62): where the player went since the last tick.
    Move(r) {
        tick_id: i32 = r.i32()?,
        time: i32 = r.i32()?,
        records: Vec<MoveRecord> = r.list16(MoveRecord::read)?,
    }
}

record! {
    /// HELLO (74): the client logs in to a game server. Carries the login token: never print it.
    Hello(r) {
        game_id: i32 = r.i32()?,
        build_version: String = r.string()?,
        access_token: String = r.string()?,
        key_time: i32 = r.i32()?,
        key: Bytes = Bytes(r.byte_array()?),
        user_platform: String = r.string()?,
        play_platform: String = r.string()?,
        platform_token: String = r.string()?,
        client_token: String = r.string()?,
        user_token: String = r.string()?,
    }
}

record! {
    /// GROUNDDAMAGE (103): the player stands on damaging ground.
    GroundDamage(r) {
        time: i32 = r.i32()?,
        position: WorldPos = WorldPos::read(r)?,
    }
}

record! {
    /// REDEEM_EXALTATION_REWARD (115): the player claims an exaltation reward.
    RedeemExaltationReward(r) {
        item_id: i32 = r.i32()?,
    }
}

record! {
    /// FORGE_REQUEST (118): the player forges or dismantles items.
    ForgeRequest(r) {
        result_item_type: i32 = r.i32()?,
        dismantled_items: Vec<SlotObject> = { let n = r.i32()?; r.list(n, SlotObject::read)? },
    }
}

record! {
    /// CLAIM_BATTLE_PASS (149): the player claims a battle pass reward (Java: `ClaimBattlePassItemPacket`).
    ClaimBattlePassItem(r) {
        unknown_string: String = r.string()?,
        unknown: i8 = r.i8()?,
    }
}

record! {
    /// CLAIM_MISSION (163): the player claims a season mission.
    ClaimMission(r) {
        season_id: i32 = r.i32()?,
        mission_positional_idx: i8 = r.i8()?,
        request_id: i8 = r.i8()?,
        mask: i16 = r.i16()?,
    }
}
