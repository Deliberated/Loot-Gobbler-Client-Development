//! A character's statistics as the character list sends them (`PCStats`, Java: `RealmCharacterStats.decode`): of
//! them the app keeps how often each dungeon was completed.
//!
//! The string is the game's base64 variant of: a little-endian flag, then (if not 0) 16 bytes saying which statistics
//! follow, then those as compressed ints, in Java's fixed order ([`crate::pc_stats_table::PARSE_ORDER`]).

use crate::pc_stats_table::{DUNGEON_COUNTS, PARSE_ORDER};

/// Java `decode`, for the dungeon counts (in Java's `dungeonStats` order): None where Java throws before it fills them
/// (a malformed string, too short for the flag). A read that runs out after the flag keeps what was read.
pub fn dungeon_counts(pc_stats: &str) -> Option<Vec<i32>> {
    let data = lg_gamedata::enchants::six_bit_bytes(pc_stats).ok()?;
    let flag = i32::from_le_bytes(data.get(..4)?.try_into().expect("4 bytes"));
    let mut counts = vec![0i32; DUNGEON_COUNTS];
    if flag != 0 {
        let _ = read(&data[4..], &mut counts); // Java catches what goes wrong here
    }
    Some(counts)
}

fn read(data: &[u8], counts: &mut [i32]) -> Option<()> {
    let mut at = 0;
    let mut byte = || -> Option<u8> {
        let b = *data.get(at)?;
        at += 1;
        Some(b)
    };
    let mut bits = [false; 128];
    for i in 0..16 {
        let b = byte()?;
        for k in 0..8 {
            bits[i * 8 + k] = b & (1 << k) != 0;
        }
    }
    for &(id, dungeon) in PARSE_ORDER {
        if id == -1 {
            continue;
        }
        if !*bits.get(usize::try_from(id).ok()?)? {
            continue; // an id past the bits throws in Java: the rest is not read
        }
        // BufferReader.readCompressedInt, Java's int arithmetic
        let mut u = byte()?;
        let negative = u & 64 != 0;
        let mut shift = 6u32;
        let mut value = i32::from(u & 63);
        while u & 128 != 0 {
            u = byte()?;
            value |= i32::from(u & 127).wrapping_shl(shift);
            shift += 7;
        }
        if negative {
            value = value.wrapping_neg();
        }
        if dungeon >= 0 {
            counts[dungeon as usize] = value;
        }
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes() {
        assert_eq!(dungeon_counts("AAAAAA=="), Some(vec![0; DUNGEON_COUNTS]), "flag 0: nothing follows");
        assert_eq!(dungeon_counts("AA"), None, "too short");
        assert_eq!(dungeon_counts("AAA"), None, "malformed");
        // flag 1, the bits cut short: what was read stays (nothing)
        assert_eq!(dungeon_counts("AQAAAAE="), Some(vec![0; DUNGEON_COUNTS]));
    }
}
