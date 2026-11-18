//! Decoders for the packets the Loot Gobbler client reads (Java: `packets.incoming.*`, `packets.outgoing.*`), and
//! what the Java client does with a decoded packet: drop it if decoding fails, keep it if bytes are left over.

pub mod data;
pub mod incoming;
pub mod outgoing;

use crate::dump::{Dump, to_string};
use crate::reader::{DecodeError, Reader};
pub use incoming::*;
pub use outgoing::*;

macro_rules! packets {
    ($($id:literal => $v:ident),* $(,)?) => {
        /// A decoded packet.
        #[derive(Debug, Clone, PartialEq)]
        pub enum Decoded {
            $($v($v)),*
        }

        /// The packet ids that have a decoder.
        pub const DECODED_IDS: &[u8] = &[$($id),*];

        fn read(id: u8, r: &mut Reader) -> Option<Result<Decoded, DecodeError>> {
            match id {
                $($id => Some($v::read(r).map(Decoded::$v)),)*
                _ => None,
            }
        }

        impl Dump for Decoded {
            fn dump(&self, out: &mut String) {
                match self {
                    $(Decoded::$v(p) => p.dump(out)),*
                }
            }
        }
    };
}

packets! {
    6 => QuestFetchResponse,
    10 => NewTick,
    12 => ServerPlayerShoot,
    13 => UseItem,
    14 => TradeAccepted,
    16 => PetUpgradeRequest,
    19 => InvDrop,
    22 => BuyResult,
    25 => EnemyHit,
    30 => PlayerShoot,
    34 => TradeDone,
    35 => EnemyShoot,
    36 => AcceptTrade,
    42 => Update,
    44 => Text,
    45 => Reconnect,
    46 => Death,
    55 => InvSwap,
    58 => QuestRedeem,
    62 => Move,
    64 => Aoe,
    67 => Notification,
    74 => Hello,
    75 => Damage,
    86 => TradeStart,
    88 => TradeRequested,
    90 => PlayerHit,
    92 => MapInfo,
    93 => ClaimDailyRewardResponse,
    95 => InvResult,
    96 => QuestRedeemResponse,
    101 => CreateSuccess,
    103 => GroundDamage,
    114 => ExaltationUpdate,
    115 => RedeemExaltationReward,
    117 => VaultContent,
    118 => ForgeRequest,
    119 => ForgeResult,
    149 => ClaimBattlePassItem,
    150 => ClaimBpMilestoneResult,
    163 => ClaimMission,
    165 => MissionProgress,
    166 => Stasis,
    172 => ChestRewardResult,
    183 => CrucibleResponse,
}

/// A packet as the app gets it.
#[derive(Debug, Clone, PartialEq)]
pub struct Decode {
    pub packet: Decoded,
    /// Bytes after the last field. Java logs "partly parsed" and still uses the packet.
    pub left_over: usize,
}

/// Decodes a packet's content. None if this packet type has no decoder; an error if Java would drop it.
pub fn decode(id: u8, body: &[u8]) -> Option<Result<Decode, DecodeError>> {
    let mut r = Reader::new(body);
    let packet = read(id, &mut r)?;
    Some(packet.map(|packet| Decode { packet, left_over: r.remaining() }))
}

/// The oracle's line for a packet with a decoder: `ok <fields>`, `partial <fields>` (bytes left over) or `failed`.
pub fn fields_line(id: u8, body: &[u8]) -> Option<String> {
    Some(match decode(id, body)? {
        Ok(d) if d.left_over == 0 => format!("ok {}", to_string(&d.packet)),
        Ok(d) => format!("partial {}", to_string(&d.packet)),
        Err(_) => "failed".into(),
    })
}

/// A short description of a packet's content for the replay's output, if it has a decoder.
pub fn describe(id: u8, body: &[u8]) -> Option<String> {
    let d = match decode(id, body)? {
        Ok(d) => d,
        Err(e) => return Some(format!("(not readable: {e})")),
    };
    let about = match &d.packet {
        Decoded::NewTick(t) => format!("tick={} time={} objects={}", t.tick_id, t.tick_time, t.status.len()),
        Decoded::Update(u) => format!("tiles={} new={} gone={}", u.tiles.len(), u.new_objects.len(), u.drops.len()),
        Decoded::Text(t) => {
            format!("{}{}: {}", t.name, if t.recipient.is_empty() { String::new() } else { format!(" -> {}", t.recipient) }, t.text)
        }
        Decoded::MapInfo(m) => format!("{} ({}x{})", m.name, m.width, m.height),
        Decoded::Notification(n) => format!("{:?} {}", n.effect, n.message.as_deref().unwrap_or("")),
        Decoded::CreateSuccess(c) => format!("object={} char={}", c.object_id, c.char_id),
        Decoded::Reconnect(c) => format!("to {:?}", c.name),
        Decoded::Death(x) => format!("char={} killed by {} fame={}", x.char_id, x.killed_by, x.total_fame),
        Decoded::VaultContent(v) => format!(
            "vault={} gifts={} potions={} materials={} spoils={}{}",
            v.vault_contents.len(),
            v.gift_contents.len(),
            v.potion_contents.len(),
            v.material_contents.len(),
            v.seasonal_spoil_content.len(),
            if v.partial { " (partial)" } else { "" }
        ),
        Decoded::Move(m) => format!("tick={} records={}", m.tick_id, m.records.len()),
        Decoded::Hello(h) => format!("game={} build={}", h.game_id, h.build_version),
        p => {
            let s = to_string(p);
            if s.len() > 110 { format!("{}…", &s[..s.floor_char_boundary(110)]) } else { s }
        }
    };
    Some(if d.left_over > 0 { format!("{about} ({} bytes left over)", d.left_over) } else { about })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_round_trip() {
        let t = Text {
            name: "Gobbler".into(),
            object_id: 77,
            num_stars: 85,
            bubble_time: 5,
            recipient: String::new(),
            text: "loot!".into(),
            clean_text: "loot!".into(),
            is_supporter: true,
            star_background: 0,
        };
        assert_eq!(decode(44, &t.encode()).unwrap().unwrap(), Decode { packet: Decoded::Text(t.clone()), left_over: 0 });
        assert_eq!(describe(44, &t.encode()).unwrap(), "Gobbler: loot!");
    }

    #[test]
    fn newtick_fields() {
        let mut b = 42i32.to_be_bytes().to_vec();
        b.extend(200i32.to_be_bytes());
        b.extend(1000u32.to_be_bytes());
        b.extend(30u16.to_be_bytes());
        b.extend(1i16.to_be_bytes());
        b.extend(data::compressed(9)); // object 9
        b.extend(1.5f32.to_be_bytes());
        b.extend(2.0f32.to_be_bytes());
        b.extend(data::compressed(0)); // no stats
        let Some(Ok(Decode { packet: Decoded::NewTick(t), left_over: 0 })) = decode(10, &b) else { panic!() };
        assert_eq!((t.tick_id, t.tick_time, t.status[0].object_id, t.status[0].pos.x), (42, 200, 9, 1.5));
    }

    #[test]
    fn left_over_and_failed() {
        assert_eq!(fields_line(150, &[1]).unwrap(), "ok {itemredeemed=true}");
        assert_eq!(fields_line(150, &[1, 9]).unwrap(), "partial {itemredeemed=true}");
        assert_eq!(fields_line(150, &[]).unwrap(), "failed");
        assert!(fields_line(1, &[]).is_none());
        assert_eq!(describe(150, &[1, 9]).unwrap(), "{itemredeemed=true} (1 bytes left over)");
    }

    #[test]
    fn every_id_is_in_the_type_table() {
        for &id in DECODED_IDS {
            assert!(crate::packet_name(id).is_some(), "{id}");
        }
    }
}
