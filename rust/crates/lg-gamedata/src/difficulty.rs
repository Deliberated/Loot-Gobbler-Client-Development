//! How hard each dungeon is, as the game rates it (Java: `DungeonDifficulty`): only the dungeon keys carry it, so
//! each key's `DungeonDifficulty` goes to the dungeon of the portal it opens (portals name their dungeon in
//! `DungeonName`); plus the fixed ratings for places no key opens.

use std::collections::HashMap;
use std::path::Path;

const WITHOUT_KEY: [(&str, f64); 9] = [
    ("Oryx's Sanctuary", 9.5),
    ("Court of Oryx", 3.5),
    ("Oryx's Castle", 4.5),
    ("Oryx's Chamber", 4.5),
    ("Wine Cellar", 6.0),
    ("Crystal Cavern", 7.5),
    ("The Void", 8.5),
    ("Advanced Kogbold Steamworks", 9.0),
    ("Cultist Hideout", 7.0),
];

const SET: [(&str, f64); 20] = [
    ("Realm of the Mad God", 5.0),
    ("Random Realm", 5.0),
    ("Vault", 0.0),
    ("Legacy Heroic Abyss of Demons", 5.0),
    ("Legacy Bilgewater's Grotto", 5.5),
    ("Consolation of Draconis", 0.0),
    ("The Inner Sanctum", 5.0),
    ("The Inner Workings", 0.0),
    ("Hidden Interregnum", 7.0),
    ("The Ivory Wyvern", 6.0),
    ("Remnant of the Void", 8.5),
    ("LOD Rock Dragon", 6.0),
    ("Exalted Kitchen", 0.0),
    ("Chicken Chamber", 0.0),
    ("Oryx Mania Sanctuary", 9.5),
    ("Oryx Horde", 7.0),
    ("Rainbow Road", 0.0),
    ("Santa Workshop", 0.0),
    ("Dreamscape Labyrinth", 0.0),
    ("Wine Cellar Short", 6.0),
];

/// The text between `start` and the next `end` after `at`, and where it ends.
fn between<'a>(s: &'a str, start: &str, end: &str) -> Option<&'a str> {
    let i = s.find(start)? + start.len();
    let j = s[i..].find(end)?;
    Some(&s[i..i + j])
}

/// Every `<Object ... id="X" ...>body</Object>`: id and body (Java's `OBJECT` pattern).
fn objects(xml: &str) -> Vec<(&str, &str)> {
    let mut out = Vec::new();
    let mut rest = xml;
    while let Some(i) = rest.find("<Object ") {
        rest = &rest[i + 8..];
        let Some(gt) = rest.find('>') else { break };
        let tag = &rest[..gt];
        let Some(body_end) = rest.find("</Object>") else { break };
        if let Some(id) = between(tag, "id=\"", "\"")
            && gt < body_end
        {
            out.push((id, &rest[gt + 1..body_end]));
            rest = &rest[body_end + 9..];
        }
    }
    out
}

/// Reads the ratings from the game data's XML files.
pub fn load(xml_dir: &Path) -> HashMap<String, f64> {
    let mut portal_to_dungeon: Vec<(String, String)> = Vec::new();
    let mut key_portals: Vec<(String, f64)> = Vec::new();
    let mut files: Vec<_> = std::fs::read_dir(xml_dir).map(|d| d.filter_map(Result::ok).map(|e| e.path()).collect()).unwrap_or_default();
    files.retain(|p: &std::path::PathBuf| p.to_string_lossy().ends_with(".xml"));
    for f in files {
        let Ok(xml) = std::fs::read_to_string(&f) else { continue };
        let (names, keys) = (xml.contains("<DungeonName>"), xml.contains("<DungeonDifficulty>"));
        if !names && !keys {
            continue;
        }
        for (id, body) in objects(&xml) {
            if names
                && let Some(n) = between(body, "<DungeonName>", "</DungeonName>").filter(|n| !n.contains('<'))
                && !portal_to_dungeon.iter().any(|(p, _)| p == id)
            {
                portal_to_dungeon.push((id.to_string(), n.trim().to_string()));
            }
            if keys
                && let Some(d) = between(body, "<DungeonDifficulty>", "</DungeonDifficulty>").and_then(|d| d.parse::<f64>().ok())
                && let Some(p) = portal_of(body)
                && !key_portals.iter().any(|(k, _)| *k == p)
            {
                key_portals.push((p, d));
            }
        }
    }
    let mut out: HashMap<String, f64> = HashMap::new();
    for (portal, difficulty) in &key_portals {
        let dungeon = portal_to_dungeon
            .iter()
            .find(|(p, _)| p == portal)
            .map(|(_, d)| d.clone())
            .or_else(|| portal.strip_suffix(" Portal").map(str::to_string));
        if let Some(d) = dungeon
            && *difficulty > 0.0
        {
            out.entry(d).or_insert(*difficulty);
        }
    }
    for (d, v) in WITHOUT_KEY {
        out.entry(d.to_string()).or_insert(v);
    }
    // parts and old versions of a dungeon count as the dungeon
    for (_, dungeon) in &portal_to_dungeon {
        if out.contains_key(dungeon) {
            continue;
        }
        let base = if let Some(b) = dungeon.strip_prefix("Legacy ") {
            Some(b.to_string())
        } else if dungeon.contains("Haunted Cemetery") {
            Some("Haunted Cemetery".to_string())
        } else {
            None
        };
        if let Some(b) = base
            && let Some(v) = out.get(&b).copied()
        {
            out.insert(dungeon.clone(), v);
        }
    }
    if let Some(t) = out.get("Tomb of the Ancients").copied() {
        out.entry("Tomb of the Ancients (Heroic)".into()).or_insert(t + 1.5);
    }
    for (d, v) in SET {
        out.insert(d.to_string(), v);
    }
    out
}

/// `<Activate id="X" ...>CreatePortal</Activate>`: the portal a key opens.
fn portal_of(body: &str) -> Option<String> {
    let mut rest = body;
    while let Some(i) = rest.find("<Activate id=\"") {
        rest = &rest[i + 14..];
        let q = rest.find('"')?;
        let id = &rest[..q];
        let gt = rest.find('>')?;
        if rest[gt + 1..].starts_with("CreatePortal</Activate>") {
            return Some(id.to_string());
        }
    }
    None
}
