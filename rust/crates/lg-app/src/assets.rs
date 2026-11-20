//! Whether the game data must be extracted again on starting (Java: Loot Gobbler's `AssetCheck` in front of
//! RealmShark's `AssetExtractor`): only when the game's `resources.assets` really changed. After an extraction its
//! size, file time and a checksum are kept (`assetsSource` in `realmShark.properties`); a new file time with the
//! same checksum needs none. RealmShark's own stamp (`lastModifiedTime`: "<file time>-<app version>") is kept as
//! Java keeps it, so either app finds the folder up to date.
//!
//! Java asks before extracting ("Extract / Ignore"); this extracts (the window, once there, will ask).

use crate::localtime::{Zone, civil};
use crate::properties::Properties;
use std::path::Path;

const KEY: &str = "assetsSource";
const UPSTREAM_KEY: &str = "lastModifiedTime";
/// Java's `FORMAT`: raised when the extraction itself changes.
const FORMAT: i32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The assets there are from this game file.
    UpToDate,
    /// Extracted now.
    Extracted,
    /// The game was not found: the assets there (if any) stay.
    NoGame,
}

/// Java `FileTime.toString`: `2026-11-16T16:29:43.123456789Z`, the fraction without its trailing zeros.
pub fn file_time(t: std::time::SystemTime) -> String {
    let (secs, nanos) = match t.duration_since(std::time::UNIX_EPOCH) {
        Ok(d) => (d.as_secs() as i64, d.subsec_nanos()),
        Err(e) => {
            let d = e.duration();
            let (s, n) = (-(d.as_secs() as i64), d.subsec_nanos());
            if n == 0 { (s, 0) } else { (s - 1, 1_000_000_000 - n) }
        }
    };
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    let (y, m, d) = civil(days);
    let mut s = format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}", rem / 3600, rem / 60 % 60, rem % 60);
    if nanos != 0 {
        let f = format!("{nanos:09}");
        s.push('.');
        s.push_str(f.trim_end_matches('0'));
    }
    s.push('Z');
    s
}

/// CRC-32C of the whole file (Java: `java.util.zip.CRC32C`).
pub fn checksum(path: &Path) -> std::io::Result<u32> {
    use std::io::Read as _;
    static TABLE: std::sync::OnceLock<[[u32; 256]; 8]> = std::sync::OnceLock::new();
    let t = TABLE.get_or_init(|| {
        let mut t = [[0u32; 256]; 8];
        for (n, e) in t[0].iter_mut().enumerate() {
            let mut c = n as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0x82F6_3B78 ^ (c >> 1) } else { c >> 1 };
            }
            *e = c;
        }
        for n in 0..256 {
            for k in 1..8 {
                t[k][n] = (t[k - 1][n] >> 8) ^ t[0][(t[k - 1][n] & 0xff) as usize];
            }
        }
        t
    });
    let mut f = std::fs::File::open(path)?;
    let mut buf = vec![0u8; 1 << 20];
    let mut crc = !0u32;
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        let (blocks, rest) = buf[..n].as_chunks::<8>();
        for b in blocks {
            let lo = crc ^ u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            crc = t[7][(lo & 0xff) as usize]
                ^ t[6][(lo >> 8 & 0xff) as usize]
                ^ t[5][(lo >> 16 & 0xff) as usize]
                ^ t[4][(lo >> 24) as usize]
                ^ t[3][b[4] as usize]
                ^ t[2][b[5] as usize]
                ^ t[1][b[6] as usize]
                ^ t[0][b[7] as usize];
        }
        for &b in rest {
            crc = (crc >> 8) ^ t[0][((crc ^ u32::from(b)) & 0xff) as usize];
        }
    }
    Ok(!crc)
}

/// Java `AssetCheck.run`, the extraction chosen: the assets in `dir/assets` from the game file, extracted if
/// they are missing or from another game file. `version` is the app's, for RealmShark's stamp.
pub fn check(
    dir: &Path,
    props: &mut Properties,
    game: Option<&Path>,
    version: &str,
    now_ms: i64,
    zone: &Zone,
    progress: &mut dyn FnMut(&str),
) -> Result<Outcome, String> {
    let Some(game) = game else { return Ok(Outcome::NoGame) };
    let meta = std::fs::metadata(game).map_err(|e| format!("{}: {e}", game.display()))?;
    let time = file_time(meta.modified().map_err(|e| e.to_string())?);
    let size = meta.len();
    let stamp = format!("{time}-{version}");
    let assets = dir.join("assets");
    let extracted = assets.join("ObjectID.list").exists() && assets.join("TileID.list").exists();
    let io = |e: std::io::Error| e.to_string();
    if extracted && up_to_date(props, game, &time, size, now_ms, zone)? {
        props.set(UPSTREAM_KEY, &stamp, now_ms, zone).map_err(io)?;
        return Ok(Outcome::UpToDate);
    }
    props.set(UPSTREAM_KEY, "", now_ms, zone).map_err(io)?;
    lg_gamedata::extract::extract(game, &assets, progress)?;
    props.set(UPSTREAM_KEY, &stamp, now_ms, zone).map_err(io)?;
    remember(props, &time, size, checksum(game).map_err(io)?, now_ms, zone)?;
    Ok(Outcome::Extracted)
}

/// Java `upToDate`: the same size and time as kept, or else the same checksum (then the new time is kept). Assets
/// extracted before the check existed count if RealmShark's stamp names this file time.
fn up_to_date(props: &mut Properties, game: &Path, time: &str, size: u64, now_ms: i64, zone: &Zone) -> Result<bool, String> {
    let io = |e: std::io::Error| e.to_string();
    if let Some(saved) = props.get(KEY).map(|s| crate::java::split(s, '|').into_iter().map(String::from).collect::<Vec<_>>())
        && saved.len() == 4
    {
        // Java's Long.parseLong throws on a bad size: the check fails, and Java's app stops starting; here it
        // extracts again
        if saved[0] != FORMAT.to_string() || saved[2].parse::<i64>().ok() != Some(size as i64) {
            return Ok(false);
        }
        if saved[1] == time {
            return Ok(true);
        }
        let crc = checksum(game).map_err(io)?;
        let same = format!("{crc:x}") == saved[3];
        if same {
            remember(props, time, size, crc, now_ms, zone)?;
        }
        return Ok(same);
    }
    if props.get(UPSTREAM_KEY).is_some_and(|u| u.starts_with(&format!("{time}-"))) {
        let crc = checksum(game).map_err(io)?;
        remember(props, time, size, crc, now_ms, zone)?;
        return Ok(true);
    }
    Ok(false)
}

fn remember(props: &mut Properties, time: &str, size: u64, crc: u32, now_ms: i64, zone: &Zone) -> Result<(), String> {
    props.set(KEY, &format!("{FORMAT}|{time}|{size}|{crc:x}"), now_ms, zone).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, UNIX_EPOCH};

    #[test]
    fn file_times_as_java() {
        assert_eq!(file_time(UNIX_EPOCH + Duration::new(1_763_310_583, 123_450_000)), "2025-11-16T16:29:43.12345Z");
        assert_eq!(file_time(UNIX_EPOCH + Duration::new(1_763_310_583, 0)), "2025-11-16T16:29:43Z");
    }

    #[test]
    fn crc32c() {
        let p = std::env::temp_dir().join(format!("lg-crc-{}", std::process::id()));
        std::fs::write(&p, b"123456789").unwrap();
        assert_eq!(checksum(&p).unwrap(), 0xE306_9283);
        std::fs::remove_file(&p).unwrap();
    }
}
