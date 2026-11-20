//! `realmShark.properties`, the Java app's settings file (Java: RealmShark's `PropertiesManager` over
//! `java.util.Properties`): read once on starting; each setting changed writes the whole file, keys sorted, with
//! Java's escapes and its date line, through a temporary file moved over the old one. Settings another program
//! wrote into the file since are kept (Loot Gobbler's patch to `PropertiesManager`).

use crate::files::LINE_END;
use crate::java;
use crate::localtime::{Zone, civil};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

pub const FILE: &str = "realmShark.properties";

#[derive(Debug, Default, Clone)]
pub struct Properties {
    /// Sorted as Java writes them (by the keys' characters).
    values: BTreeMap<String, String>,
    file: Option<PathBuf>,
}

impl Properties {
    /// The settings in `dir` (none if the file is missing or unreadable), saved there when changed.
    pub fn load(dir: &Path) -> Properties {
        let file = dir.join(FILE);
        let values = std::fs::read_to_string(&file).map(|t| java::properties(&t).into_iter().collect()).unwrap_or_default();
        Properties { values, file: Some(file) }
    }

    /// Settings kept in memory only.
    pub fn in_memory(text: &str) -> Properties {
        Properties { values: java::properties(text).into_iter().collect(), file: None }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.values.get(key).map(String::as_str)
    }

    /// Java `setProperties`: the setting changed and the file written.
    pub fn set(&mut self, key: &str, value: &str, now_ms: i64, zone: &Zone) -> std::io::Result<()> {
        let Some(file) = self.file.clone() else {
            self.values.insert(key.into(), value.into());
            return Ok(());
        };
        if let Ok(text) = std::fs::read_to_string(&file) {
            for (k, v) in java::properties(&text) {
                self.values.entry(k).or_insert(v);
            }
        }
        self.values.insert(key.into(), value.into());
        let tmp = file.with_file_name(format!("{FILE}.tmp"));
        std::fs::write(&tmp, self.store(now_ms, zone))?;
        std::fs::rename(&tmp, &file)
    }

    /// `Properties.store(writer, "Realm shark properties")`.
    pub fn store(&self, now_ms: i64, zone: &Zone) -> String {
        let mut s = format!("#Realm shark properties{LINE_END}#{}{LINE_END}", java_date(now_ms, zone));
        for (k, v) in &self.values {
            s.push_str(&escape(k, true));
            s.push('=');
            s.push_str(&escape(v, false));
            s.push_str(LINE_END);
        }
        s
    }
}

/// `Properties.saveConvert` writing to a `Writer` (no `\u` escapes).
fn escape(s: &str, key: bool) -> String {
    let mut o = String::with_capacity(s.len());
    for (i, c) in s.chars().enumerate() {
        match c {
            '\\' => o.push_str("\\\\"),
            ' ' if i == 0 || key => o.push_str("\\ "),
            '\t' => o.push_str("\\t"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\u{c}' => o.push_str("\\f"),
            '=' | ':' | '#' | '!' => {
                o.push('\\');
                o.push(c);
            }
            c => o.push(c),
        }
    }
    o
}

/// `Date.toString`: `Fri Nov 20 02:34:25 GMT+01:00 2026` (Java names the zone, e.g. CET; here its offset).
fn java_date(now_ms: i64, zone: &Zone) -> String {
    let secs = now_ms.div_euclid(1000);
    let off = zone.offset(secs);
    let local = secs + i64::from(off);
    let (days, rem) = (local.div_euclid(86_400), local.rem_euclid(86_400));
    let (y, m, d) = civil(days);
    const DAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let zone_name = if off == 0 {
        "GMT".to_string()
    } else {
        format!("GMT{}{:02}:{:02}", if off < 0 { '-' } else { '+' }, off.abs() / 3600, off.abs() / 60 % 60)
    };
    format!(
        "{} {} {d:02} {:02}:{:02}:{:02} {zone_name} {y}",
        DAYS[days.rem_euclid(7) as usize],
        MONTHS[m as usize - 1],
        rem / 3600,
        rem / 60 % 60,
        rem % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What Java 25 wrote for the same settings (the date line aside).
    #[test]
    fn stores_as_java() {
        let mut p = Properties::in_memory("");
        let z = Zone::default();
        for (k, v) in [("zeta", "1"), ("alpha", "x=y:z #!"), ("B key", " lead\ttab\nnl\\ é€"), ("#c", "!v"), ("Alpha", "2")] {
            p.set(k, v, 0, &z).unwrap();
        }
        let s = p.store(0, &z);
        let body: Vec<&str> = s.split(LINE_END).collect();
        assert_eq!(body[0], "#Realm shark properties");
        assert_eq!(body[1], "#Thu Jan 01 00:00:00 GMT 1970");
        assert_eq!(&body[2..], ["\\#c=\\!v", "Alpha=2", "B\\ key=\\ lead\\ttab\\nnl\\\\ é€", "alpha=x\\=y\\:z \\#\\!", "zeta=1", ""]);
        let back = Properties::in_memory(&s);
        assert_eq!(back.get("B key"), Some(" lead\ttab\nnl\\ é€"));
        assert_eq!(back.get("alpha"), Some("x=y:z #!"));
    }
}
