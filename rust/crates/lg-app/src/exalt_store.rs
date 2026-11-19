//! Exaltation progress per class (Java: `ExaltStore`): 8 values per class in the game's order (dexterity, speed,
//! vitality, wisdom, defense, attack, mana, life), and each value that went up in the last week, with when.
//!
//! A stat gains an exaltation at 5, 15, 30, 50 and 75 progress: 5 per stat, 40 per class at most.

use lg_world::json::{self, Json};
use std::collections::BTreeMap;
use std::path::Path;

pub const THRESHOLDS: [i32; 5] = [5, 15, 30, 50, 75];
const KEEP_CHANGES_MS: i64 = 7 * 24 * 3_600_000;

/// One stat's progress going up, and when this client saw it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Change {
    pub t: i64,
    pub class_type: i32,
    pub stat: i32,
    pub before: i32,
    pub after: i32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ExaltStore {
    /// By class.
    pub progress: BTreeMap<i32, Vec<i32>>,
    pub changes: Vec<Change>,
}

/// Exaltations earned from a stat's progress (0..5).
pub fn level(progress: i32) -> i32 {
    THRESHOLDS.iter().filter(|&&t| progress >= t).count() as i32
}

/// Total exaltations of a class (0..40).
pub fn total(progress: Option<&[i32]>) -> i32 {
    progress.map_or(0, |p| p.iter().take(8).map(|&v| level(v)).sum())
}

impl ExaltStore {
    /// What the Java app kept in its folder (`exalts.json`, and `exalt_changes.json` beside it), read only. A file
    /// it cannot read leaves that part empty, as Java does.
    pub fn import(dir: &Path) -> ExaltStore {
        let mut s = ExaltStore::default();
        let Ok(text) = std::fs::read_to_string(dir.join("exalts.json")) else { return s };
        if let Some(p) = parse_progress(&text) {
            s.progress = p;
        }
        if let Ok(text) = std::fs::read_to_string(dir.join("exalt_changes.json"))
            && let Some(c) = parse_changes(&text)
        {
            s.changes = c;
        }
        s
    }

    /// Java `update`: new values from the game replace those of each class given; values that went up are noted
    /// (not for a class seen for the first time), and notes older than a week go.
    pub fn update(&mut self, exalts: &[(i32, Vec<i32>)], now: i64) {
        let all: Vec<(i32, Option<Vec<i32>>)> = exalts.iter().map(|(c, v)| (*c, Some(v.clone()))).collect();
        self.update_all(&all, now);
    }

    /// The same for Tomato's whole map, where a class can be there without values (skipped, as in Java).
    pub fn update_all(&mut self, exalts: &[(i32, Option<Vec<i32>>)], now: i64) {
        if exalts.is_empty() {
            return;
        }
        for (cls, values) in exalts {
            let Some(values) = values.as_ref().filter(|v| v.len() >= 8) else { continue };
            if let Some(was) = self.progress.get(cls) {
                for (i, (&w, &v)) in was.iter().zip(values).enumerate() {
                    if v > w {
                        self.changes.push(Change { t: now, class_type: *cls, stat: i as i32, before: w, after: v });
                    }
                }
            }
            self.progress.insert(*cls, values.clone());
        }
        self.changes.retain(|c| now - c.t <= KEEP_CHANGES_MS);
    }

    pub fn progress(&self, class_type: i32) -> Option<&Vec<i32>> {
        self.progress.get(&class_type)
    }

    pub fn dump(&self) -> String {
        let p: Vec<String> =
            self.progress.iter().map(|(k, v)| format!("{k}=[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(","))).collect();
        let c: Vec<String> = self.changes.iter().map(change_line).collect();
        format!("exalts [{}] changes [{}]\n", p.join(","), c.join(","))
    }
}

pub fn change_line(c: &Change) -> String {
    format!("{}/{}/{}/{}/{}", c.t, c.class_type, c.stat, c.before, c.after)
}

/// Gson reading `TreeMap<Integer, int[]>`.
fn parse_progress(text: &str) -> Option<BTreeMap<i32, Vec<i32>>> {
    let Json::Object(o) = json::parse(text).ok()? else { return None };
    let mut out = BTreeMap::new();
    for (k, v) in o {
        let values = match v {
            Json::Null => continue,
            Json::Array(a) => a.iter().map(|x| x.as_int().ok()).collect::<Option<Vec<_>>>()?,
            _ => return None,
        };
        out.insert(k.parse().ok()?, values);
    }
    Some(out)
}

/// Gson reading `ArrayList<Change>` (missing fields are 0).
fn parse_changes(text: &str) -> Option<Vec<Change>> {
    changes_of(&json::parse(text).ok()?)
}

/// The same, from a JSON value already read.
pub(crate) fn changes_of(v: &Json) -> Option<Vec<Change>> {
    let Json::Array(a) = v else { return None };
    let mut out = Vec::new();
    for c in a {
        let int = |k: &str| c.get(k).map_or(Some(0), |v| v.as_int().ok());
        let t = match c.get("t") {
            None => 0,
            Some(v) => v.as_string().ok()?.parse::<i64>().ok()?,
        };
        out.push(Change { t, class_type: int("classType")?, stat: int("stat")?, before: int("before")?, after: int("after")? });
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels() {
        assert_eq!([level(0), level(5), level(14), level(15), level(75), level(99)], [0, 1, 1, 2, 5, 5]);
        assert_eq!(total(Some(&[75, 75, 75, 75, 75, 75, 75, 75, 75])), 40);
        assert_eq!(total(None), 0);
    }

    #[test]
    fn updates() {
        let mut s = ExaltStore::default();
        s.update(&[(768, vec![1, 2, 3, 4, 5, 6, 7, 8])], 1000);
        assert!(s.changes.is_empty(), "a class seen for the first time");
        s.update(&[(768, vec![2, 2, 3, 4, 5, 6, 7, 9]), (775, vec![1, 2])], 2000);
        assert_eq!(s.changes.len(), 2);
        assert_eq!(s.changes[1], Change { t: 2000, class_type: 768, stat: 7, before: 8, after: 9 });
        assert!(s.progress(775).is_none(), "fewer than 8 values");
        s.update(&[(768, vec![3, 2, 3, 4, 5, 6, 7, 9])], 2000 + KEEP_CHANGES_MS + 1);
        assert_eq!(s.changes.len(), 1, "older than a week");
    }

    #[test]
    fn reads_java_files() {
        assert_eq!(parse_progress(r#"{"768":[1,2,3],"775":null}"#), Some(BTreeMap::from([(768, vec![1, 2, 3])])));
        let c = parse_changes(r#"[{"t":1762990000000,"classType":768,"stat":2,"before":3,"after":4}]"#).unwrap();
        assert_eq!(c, vec![Change { t: 1_762_990_000_000, class_type: 768, stat: 2, before: 3, after: 4 }]);
    }
}
