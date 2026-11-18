//! The pieces packets are built from (Java: `packets.data.*`).

use crate::reader::Reader;

/// Declares a record read field by field in wire order: the struct, `read` and its [`Dump`](crate::dump::Dump).
/// Each field's expression may use the fields before it.
#[macro_export]
macro_rules! record {
    (
        $(#[$m:meta])*
        $name:ident($r:ident) {
            $( $(#[$fm:meta])* $f:ident : $t:ty = $e:expr ),* $(,)?
        }
    ) => {
        $(#[$m])*
        #[derive(Debug, Clone, PartialEq)]
        pub struct $name {
            $( $(#[$fm])* pub $f: $t ),*
        }

        impl $name {
            pub fn read($r: &mut $crate::reader::Reader) -> $crate::reader::Result<Self> {
                $( let $f: $t = $e; )*
                Ok($name { $($f),* })
            }
        }

        $crate::dump_record!($name { $($f),* });
    };
}

/// A small enum with its wire code and Java name. `from_code` gives None for codes Java has no value for.
#[macro_export]
macro_rules! code_enum {
    ($(#[$m:meta])* $name:ident { $($v:ident = $c:expr => $java:literal),* $(,)? }) => {
        $(#[$m])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
        pub enum $name { $($v),* }

        impl $name {
            pub fn from_code(code: i32) -> Option<Self> {
                $( if code == $c { return Some($name::$v); } )*
                None
            }

            pub fn code(self) -> i32 {
                match self { $($name::$v => $c),* }
            }

            pub fn java_name(self) -> &'static str {
                match self { $($name::$v => $java),* }
            }
        }

        impl $crate::dump::Dump for $name {
            fn dump(&self, out: &mut String) {
                out.push_str(self.java_name());
            }
        }
    };
}

record! {
    /// A position in the world, in tiles (Java: `WorldPosData`).
    #[derive(Copy)]
    WorldPos(r) {
        x: f32 = r.f32()?,
        y: f32 = r.f32()?,
    }
}

record! {
    /// One map tile (Java: `GroundTileData`).
    GroundTile(r) {
        x: i16 = r.i16()?,
        y: i16 = r.i16()?,
        r#type: u16 = r.u16()?,
    }
}

/// Stat types whose value is a string, not a number (Java: `StatData.isStringStat`).
pub const STRING_STATS: [u8; 16] = [6, 31, 38, 54, 62, 71, 72, 78, 80, 82, 115, 121, 127, 128, 147, 155];

record! {
    /// One stat of an object: a number or a string, and a second number (Java: `StatData`).
    Stat(r) {
        stat_type_num: u8 = r.u8()?,
        /// 0 for a string stat.
        stat_value: i32 = if STRING_STATS.contains(&stat_type_num) { 0 } else { r.compressed_int()? },
        /// Only for a string stat.
        string_stat_value: Option<String> =
            if STRING_STATS.contains(&stat_type_num) { Some(r.string()?) } else { None },
        stat_value_two: i32 = r.compressed_int()?,
    }
}

record! {
    /// An object's id, position and changed stats (Java: `ObjectStatusData`).
    ObjectStatus(r) {
        object_id: i32 = r.compressed_int()?,
        pos: WorldPos = WorldPos::read(r)?,
        stats: Vec<Stat> = r.list_compressed(Stat::read)?,
    }
}

record! {
    /// An object coming into view: its type and its full status (Java: `ObjectData`).
    NewObject(r) {
        object_type: u16 = r.u16()?,
        status: ObjectStatus = ObjectStatus::read(r)?,
    }
}

record! {
    /// An inventory slot of some object (Java: `SlotObjectData`).
    SlotObject(r) {
        object_id: i32 = r.i32()?,
        slot_id: i32 = r.i32()?,
        object_type: i32 = r.i32()?,
    }
}

record! {
    /// One fame bonus on death (Java: `FameData`).
    Fame(r) {
        achievement: String = r.string()?,
        fame_level: i32 = r.compressed_int()?,
        fame_added: i32 = r.compressed_int()?,
    }
}

record! {
    /// An item in a trade window (Java: `TradeItemData`).
    TradeItem(r) {
        item: i32 = r.i32()?,
        slot_type: i32 = r.i32()?,
        tradeable: bool = r.bool()?,
        included: bool = r.bool()?,
        unique_data: String = r.string()?,
    }
}

record! {
    /// A daily quest (Java: `QuestData`).
    Quest(r) {
        id: String = r.string()?,
        name: String = r.string()?,
        description: String = r.string()?,
        expiration: String = r.string()?,
        category: i32 = r.i32()?,
        unknown_int: i32 = r.i32()?,
        requirements: Vec<i32> = r.list16(Reader::i32)?,
        rewards: Vec<i32> = r.list16(Reader::i32)?,
        completed: bool = r.bool()?,
        item_of_choice: bool = r.bool()?,
        repeatable: bool = r.bool()?,
    }
}

record! {
    /// Where the player was at a moment (Java: `MoveRecord`).
    MoveRecord(r) {
        time: i32 = r.i32()?,
        pos: WorldPos = WorldPos::read(r)?,
    }
}

/// For tests: the bytes of a compressed int.
pub fn compressed(v: i32) -> Vec<u8> {
    let mut n = v.unsigned_abs();
    let mut b = vec![(n & 63) as u8 | if v < 0 { 64 } else { 0 }];
    n >>= 6;
    while n > 0 {
        *b.last_mut().expect("one byte") |= 128;
        b.push((n & 127) as u8);
        n >>= 7;
    }
    b
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dump::to_string;

    #[test]
    fn compressed_round_trip() {
        for v in [0, 1, 63, 64, -64, 8191, -100_000, i32::MAX, -i32::MAX] {
            assert_eq!(Reader::new(&compressed(v)).compressed_int().unwrap(), v, "{v}");
        }
    }

    #[test]
    fn number_and_string_stats() {
        let mut b = vec![0];
        b.extend(compressed(-500));
        b.extend(compressed(-1));
        b.push(31);
        b.extend([0, 2, b'h', b'i']);
        b.extend(compressed(7));
        let mut r = Reader::new(&b);
        let hp = Stat::read(&mut r).unwrap();
        let name = Stat::read(&mut r).unwrap();
        assert!(r.done());
        assert_eq!((hp.stat_value, hp.string_stat_value, hp.stat_value_two), (-500, None, -1));
        assert_eq!(to_string(&name), r#"{stattypenum=31,statvalue=0,statvaluetwo=7,stringstatvalue="hi"}"#);
    }
}
