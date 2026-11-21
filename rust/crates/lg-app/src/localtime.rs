//! Local time as the Java app writes it (`LocalDateTime.now()` in the system's time zone): the zone read from the
//! system's zone file (`/etc/localtime`, TZif), with its rule for the years after its last listed change.
//!
//! On a system without one (Windows, for now), times are UTC.

use std::path::Path;

/// A time zone: its changes, and the rule after them.
#[derive(Debug, Clone, Default)]
pub struct Zone {
    /// (UTC second it starts, offset in seconds), sorted.
    changes: Vec<(i64, i32)>,
    /// The offset before the first change.
    first: i32,
    rule: Option<Rule>,
}

#[derive(Debug, Clone, Copy)]
struct Rule {
    std: i32,
    dst: Option<(i32, When, When)>,
}

/// `Mm.w.d/time`: month, week (5 = last), weekday (0 = Sunday), seconds after local midnight.
#[derive(Debug, Clone, Copy)]
struct When {
    month: u32,
    week: u32,
    weekday: u32,
    secs: i64,
}

impl Zone {
    /// The system's zone (`/etc/localtime`), or UTC.
    pub fn system() -> Zone {
        Zone::from_file(Path::new("/etc/localtime")).unwrap_or_default()
    }

    pub fn from_file(p: &Path) -> Option<Zone> {
        Zone::parse(&std::fs::read(p).ok()?)
    }

    /// A TZif file (version 2 or later: the 64-bit part and the rule after it).
    pub fn parse(b: &[u8]) -> Option<Zone> {
        if b.get(..4)? != b"TZif" {
            return None;
        }
        let be32 = |b: &[u8], at: usize| -> Option<u32> { Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?)) };
        let counts = |at: usize| -> Option<[usize; 6]> {
            let mut c = [0; 6];
            for (i, v) in c.iter_mut().enumerate() {
                *v = be32(b, at + 20 + 4 * i)? as usize;
            }
            Some(c)
        };
        let [isut, isstd, leap, time, typ, chars] = counts(0)?;
        let v1 = 44 + time * 5 + typ * 6 + chars + leap * 8 + isstd + isut;
        let version = *b.get(4)?;
        let (base, size) = if version >= b'2' { (v1, 8) } else { (0, 4) };
        let [isut, isstd, leap, time, typ, chars] = counts(base)?;
        let data = base + 44;
        let mut times = Vec::with_capacity(time);
        for i in 0..time {
            let at = data + i * size;
            let t = if size == 8 { i64::from_be_bytes(b.get(at..at + 8)?.try_into().ok()?) } else { i64::from(be32(b, at)? as i32) };
            times.push(t);
        }
        let idx = data + time * size;
        let types_at = idx + time;
        let offset_of = |k: usize| -> Option<(i32, bool)> {
            let at = types_at + k * 6;
            Some((be32(b, at)? as i32, *b.get(at + 4)? != 0))
        };
        let mut changes = Vec::with_capacity(time);
        for (i, t) in times.iter().enumerate() {
            changes.push((*t, offset_of(usize::from(*b.get(idx + i)?))?.0));
        }
        let first = (0..typ).filter_map(offset_of).find(|(_, dst)| !dst).or_else(|| offset_of(0)).map_or(0, |o| o.0);
        let after = types_at + typ * 6 + chars + leap * (size + 4) + isstd + isut;
        let rule = if size == 8 {
            b.get(after..).and_then(|f| std::str::from_utf8(f).ok()).and_then(|f| f.trim_matches('\n').lines().next()).and_then(parse_rule)
        } else {
            None
        };
        Some(Zone { changes, first, rule })
    }

    /// The offset from UTC at a UTC time, seconds.
    pub fn offset(&self, utc_secs: i64) -> i32 {
        match self.changes.last() {
            Some(&(last, _)) if utc_secs >= last && self.rule.is_some() => self.rule.map_or(0, |r| r.offset(utc_secs)),
            None if self.rule.is_some() => self.rule.map_or(0, |r| r.offset(utc_secs)),
            _ => match self.changes.partition_point(|&(t, _)| t <= utc_secs) {
                0 => self.first,
                n => self.changes[n - 1].1,
            },
        }
    }

    /// Java `LocalDateTime.parse(s).atZone(systemDefault()).toInstant().toEpochMilli()`: in the autumn overlap the
    /// earlier moment, in the spring gap shifted on. None if it doesn't read.
    pub fn parse_local(&self, s: &str) -> Option<i64> {
        let (date, time) = s.split_once('T')?;
        let mut d = date.split('-');
        let (y, m, dd): (i64, u32, u32) = (d.next()?.parse().ok()?, d.next()?.parse().ok()?, d.next()?.parse().ok()?);
        let mut t = time.split(':');
        let (h, mi): (i64, i64) = (t.next()?.parse().ok()?, t.next()?.parse().ok()?);
        let (sec, ms) = match t.next() {
            None => (0, 0),
            Some(x) => match x.split_once('.') {
                None => (x.parse::<i64>().ok()?, 0),
                Some((s, f)) => (s.parse::<i64>().ok()?, format!("{f:0<3}")[..3].parse::<i64>().ok()?),
            },
        };
        if !(1..=12).contains(&m) || !(1..=31).contains(&dd) || !(0..24).contains(&h) || !(0..60).contains(&mi) || !(0..60).contains(&sec) {
            return None;
        }
        let local = days_from_civil(y, m, dd) * 86_400 + h * 3600 + mi * 60 + sec;
        let (before, after) = (self.offset(local - 86_400 - 14 * 3600), self.offset(local + 86_400 + 14 * 3600));
        let fits = |o: i32| self.offset(local - i64::from(o)) == o;
        let utc = match (fits(before), fits(after)) {
            (true, true) => local - i64::from(before.max(after)),
            (true, false) => local - i64::from(before),
            (false, true) => local - i64::from(after),
            (false, false) => local - i64::from(before.min(after)),
        };
        Some(utc * 1000 + ms)
    }

    /// This moment in local time: year, month, day, hour, minute, second.
    pub fn fields(&self, utc_ms: i64) -> (i64, u32, u32, i64, i64, i64) {
        let secs = utc_ms.div_euclid(1000);
        let local = secs + i64::from(self.offset(secs));
        let (days, rem) = (local.div_euclid(86_400), local.rem_euclid(86_400));
        let (y, m, d) = civil(days);
        (y, m, d, rem / 3600, rem / 60 % 60, rem % 60)
    }

    /// Java `LocalDateTime.now().withNano(0).toString()` for this moment (ms since 1970).
    pub fn local_date_time(&self, utc_ms: i64) -> String {
        let secs = utc_ms.div_euclid(1000);
        let local = secs + i64::from(self.offset(secs));
        let (days, rem) = (local.div_euclid(86_400), local.rem_euclid(86_400));
        let (y, m, d) = civil(days);
        let (h, mi, s) = (rem / 3600, rem / 60 % 60, rem % 60);
        let date = if (0..=9999).contains(&y) { format!("{y:04}-{m:02}-{d:02}") } else { format!("{y:+05}-{m:02}-{d:02}") };
        if s == 0 { format!("{date}T{h:02}:{mi:02}") } else { format!("{date}T{h:02}:{mi:02}:{s:02}") }
    }
}

/// Java `Instant.ofEpochMilli(ms).toString()`: UTC, seconds always, milliseconds when not 0.
pub fn instant(utc_ms: i64) -> String {
    let secs = utc_ms.div_euclid(1000);
    let ms = utc_ms.rem_euclid(1000);
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    let (y, m, d) = civil(days);
    let date = if (0..=9999).contains(&y) { format!("{y:04}-{m:02}-{d:02}") } else { format!("{y:+05}-{m:02}-{d:02}") };
    let frac = if ms == 0 { String::new() } else { format!(".{ms:03}") };
    format!("{date}T{:02}:{:02}:{:02}{frac}Z", rem / 3600, rem / 60 % 60, rem % 60)
}

/// Days since 1970 -> (year, month, day) (Howard Hinnant's algorithm).
pub(crate) fn civil(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// (year, month, day) -> days since 1970.
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = i64::from(if m > 2 { m - 3 } else { m + 9 });
    let doy = (153 * mp + 2) / 5 + i64::from(d) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

impl Rule {
    fn offset(&self, utc: i64) -> i32 {
        let Some((dst, start, end)) = self.dst else { return self.std };
        let (y, _, _) = civil((utc + i64::from(self.std)).div_euclid(86_400));
        // the changes this year, in UTC: the start given in standard time, the end in summer time
        let s = start.local(y) - i64::from(self.std);
        let e = end.local(y) - i64::from(dst);
        let summer = if s < e { utc >= s && utc < e } else { utc >= s || utc < e };
        if summer { dst } else { self.std }
    }
}

impl When {
    /// Seconds since 1970 of this moment in year `y`, local time.
    fn local(&self, y: i64) -> i64 {
        let first = days_from_civil(y, self.month, 1);
        let wd_first = (first + 4).rem_euclid(7) as u32; // 1970-01-01 was a Thursday
        let mut day = 1 + (self.weekday + 7 - wd_first) % 7 + (self.week.saturating_sub(1)) * 7;
        let len = days_from_civil(if self.month == 12 { y + 1 } else { y }, if self.month == 12 { 1 } else { self.month + 1 }, 1) - first;
        while i64::from(day) > len {
            day -= 7;
        }
        (first + i64::from(day) - 1) * 86_400 + self.secs
    }
}

/// A POSIX TZ string, e.g. `CET-1CEST,M3.5.0,M10.5.0/3` (only the `Mm.w.d` form of changes).
fn parse_rule(s: &str) -> Option<Rule> {
    let mut p = s;
    let name = |p: &mut &str| -> Option<()> {
        if let Some(r) = p.strip_prefix('<') {
            let end = r.find('>')?;
            *p = &r[end + 1..];
        } else {
            let n = p.chars().take_while(char::is_ascii_alphabetic).count();
            if n < 3 {
                return None;
            }
            *p = &p[n..];
        }
        Some(())
    };
    let time = |p: &mut &str| -> Option<i64> {
        let (sign, r) = match p.chars().next()? {
            '-' => (-1, &p[1..]),
            '+' => (1, &p[1..]),
            _ => (1, *p),
        };
        let n = r.chars().take_while(|c| c.is_ascii_digit() || *c == ':').count();
        if n == 0 {
            return None;
        }
        let mut secs = 0;
        for (i, part) in r[..n].split(':').enumerate() {
            secs += part.parse::<i64>().ok()? * [3600, 60, 1].get(i)?;
        }
        *p = &r[n..];
        Some(sign * secs)
    };
    name(&mut p)?;
    let std = -time(&mut p)? as i32;
    if p.is_empty() {
        return Some(Rule { std, dst: None });
    }
    name(&mut p)?;
    let dst = if p.starts_with(',') { std + 3600 } else { -time(&mut p)? as i32 };
    let when = |p: &mut &str| -> Option<When> {
        let r = p.strip_prefix(",M")?;
        let n = r.find([',', '/']).unwrap_or(r.len());
        let mut f = r[..n].split('.').map(|x| x.parse::<u32>().ok());
        let (month, week, weekday) = (f.next()??, f.next()??, f.next()??);
        *p = &r[n..];
        let secs = if let Some(r) = p.strip_prefix('/') {
            *p = r;
            time(p)?
        } else {
            7200
        };
        Some(When { month, week, weekday, secs })
    };
    let start = when(&mut p)?;
    let end = when(&mut p)?;
    Some(Rule { std, dst: Some((dst, start, end)) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn berlin_by_its_rule() {
        let z = Zone { changes: Vec::new(), first: 3600, rule: parse_rule("CET-1CEST,M3.5.0,M10.5.0/3") };
        // 2026-11-18 18:40 UTC: winter, +1
        assert_eq!(z.local_date_time(1_795_027_200_000), "2026-11-18T19:40");
        // 2026-07-01 12:00:05 UTC: summer, +2
        assert_eq!(z.local_date_time(1_782_907_205_000), "2026-07-01T14:00:05");
        // the change: 2026-03-29 01:00 UTC
        assert_eq!(z.offset(1_774_745_999), 3600);
        assert_eq!(z.offset(1_774_746_000), 7200);
        // and back: 2026-10-25 01:00 UTC
        assert_eq!(z.offset(1_792_890_000 - 1), 7200);
        assert_eq!(z.offset(1_792_890_000), 3600);
    }

    #[test]
    fn the_system_zone_reads() {
        let z = Zone::system();
        let _ = z.local_date_time(0);
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(days_from_civil(2026, 11, 18), 20_775);
    }
}

#[cfg(test)]
mod system_check {
    /// Prints the system zone's local times, for checking against `date` by hand.
    #[test]
    #[ignore]
    fn print() {
        let z = super::Zone::system();
        for t in [1_795_027_200_000i64, 1_782_907_205_000, 1_900_000_000_000, 4_102_444_800_000] {
            println!("{t} {}", z.local_date_time(t));
        }
    }
}
