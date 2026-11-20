//! The enemy a helper object stands for (Java `EnemySprites`). Bosses and minions do much of their damage through
//! invisible or effect-only objects that carry the real enemy's name as their display name, so the name leads back
//! to a sprite worth showing.

use std::collections::{HashMap, HashSet};
use std::path::Path;

#[derive(Debug, Default, Clone)]
pub struct EnemySprites {
    /// The game file (its index in the xml folder) that first defines each object.
    file_of: HashMap<i32, usize>,
    /// Size 0: never drawn.
    sizeless: HashSet<i32>,
    /// Lower-case name -> visible enemies of that name, in the list's order.
    by_name: HashMap<String, Vec<i32>>,
    /// Bosses, minions, quests.
    combatants: HashSet<i32>,
}

impl EnemySprites {
    /// From the extracted assets (ObjectID.list and xml/); empty when they are missing.
    pub fn load(assets: &Path) -> EnemySprites {
        let mut e = EnemySprites::default();
        let mut files: Vec<_> =
            std::fs::read_dir(assets.join("xml")).map(|d| d.filter_map(Result::ok).map(|e| e.path()).collect()).unwrap_or_default();
        files.retain(|p: &std::path::PathBuf| p.to_string_lossy().to_lowercase().ends_with(".xml"));
        for (i, f) in files.iter().enumerate() {
            let Ok(xml) = std::fs::read_to_string(f) else { continue };
            let mut rest = xml.as_str();
            while let Some(at) = find_object(rest) {
                rest = &rest[at..];
                let Some(head_end) = rest.find('>') else { break };
                let attrs = &rest[7..head_end];
                let Some(close) = rest[head_end..].find("</Object>") else { break };
                let body = &rest[head_end + 1..head_end + close];
                rest = &rest[head_end + close + 9..];
                let Some(t) = type_attr(attrs) else { continue };
                e.file_of.entry(t).or_insert(i);
                if no_size(body) {
                    e.sizeless.insert(t);
                }
            }
        }
        if let Ok(list) = std::fs::read_to_string(assets.join("ObjectID.list")) {
            for line in list.lines() {
                let f: Vec<&str> = line.split(';').collect();
                if f.len() < 8 || f[5].is_empty() || f[5].ends_with(",invisible") {
                    continue;
                }
                let character = f[2] == "Character";
                if !character && f[2] != "GameObject" {
                    continue;
                }
                let combatant = character && f[6].split(',').any(|l| matches!(l, "BOSS" | "MINIBOSS" | "MINION" | "QUEST" | "TRACKLOOT"));
                if !combatant && !format!(",{},", f[6]).contains(",ENEMY,") {
                    continue;
                }
                let name = if f[1].is_empty() { f[7] } else { f[1] }.to_lowercase().trim().to_string();
                let Ok(id) = f[0].parse::<i32>() else { continue };
                if e.sizeless.contains(&id) {
                    continue;
                }
                e.by_name.entry(name).or_default().push(id);
                if combatant {
                    e.combatants.insert(id);
                }
            }
        }
        e
    }

    /// Whether the game never draws the object (its size is 0).
    pub fn hidden(&self, t: i32) -> bool {
        self.sizeless.contains(&t)
    }

    /// Enemies the object may stand for: those of the same game file first, then bosses and minions, then the
    /// nearest id; by its display name, then with trailing words cut off. Empty if none.
    pub fn candidates(&self, t: i32, name: Option<&str>) -> Vec<i32> {
        let Some(name) = name else { return Vec::new() };
        let mut key = name.to_lowercase().trim().to_string();
        loop {
            if let Some(found) = self.by_name.get(&key) {
                let mut out = found.clone();
                if let Some(i) = out.iter().position(|x| *x == t) {
                    out.remove(i);
                }
                if !out.is_empty() {
                    let own = self.file_of.get(&t);
                    out.sort_by_key(|a| {
                        (own.is_none() || own != self.file_of.get(a), !self.combatants.contains(a), (i64::from(*a) - i64::from(t)).abs())
                    });
                    return out;
                }
            }
            match key.rfind(' ') {
                Some(cut) if cut > 0 => key.truncate(cut),
                _ => return Vec::new(),
            }
        }
    }
}

/// The next `<Object` tag (not `<Objects`).
fn find_object(s: &str) -> Option<usize> {
    let mut from = 0;
    while let Some(i) = s[from..].find("<Object") {
        let at = from + i;
        match s.as_bytes().get(at + 7) {
            Some(c) if c.is_ascii_alphanumeric() || *c == b'_' => from = at + 7,
            _ => return Some(at),
        }
    }
    None
}

fn type_attr(attrs: &str) -> Option<i32> {
    let i = attrs.find("type=\"0x")?;
    let hex = &attrs[i + 8..];
    let end = hex.find('"')?;
    i32::from_str_radix(&hex[..end], 16).ok().or_else(|| u32::from_str_radix(&hex[..end], 16).ok().map(|v| v as i32))
}

/// `<Size>0</Size>`, spaces allowed around the 0.
fn no_size(body: &str) -> bool {
    body.match_indices("<Size>").any(|(i, _)| {
        let r = body[i + 6..].trim_start();
        r.starts_with('0') && r[1..].trim_start().starts_with("</Size>")
    })
}
