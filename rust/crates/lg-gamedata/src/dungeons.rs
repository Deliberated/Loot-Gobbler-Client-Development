//! Dungeons in the game's files (Java: `ParseDungeon`, `GameFileEnemies`): the portals and the dungeons they lead
//! to, the dungeon modifiers, and which enemies belong to exactly one dungeon (for telling the map when the sniffer
//! starts inside one).

use crate::xml::{self, Element, JavaNode};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::Path;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Dungeons {
    /// Portal object type -> the dungeon's name.
    pub portal_names: HashMap<i32, String>,
    /// The dungeon's name -> a portal leading there.
    pub portal_by_name: HashMap<String, i32>,
    pub modifier_names: HashMap<i16, String>,
    pub modifier_by_name: HashMap<String, i32>,
    /// Enemy type -> the one dungeon it belongs to by the game's files.
    pub enemies: HashMap<i32, String>,
}

/// Java `Integer.decode`.
fn decode_int(s: &str) -> Option<i32> {
    let (neg, body) = match s.strip_prefix('-') {
        Some(b) => (true, b),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    let v = if let Some(h) = body.strip_prefix("0x").or_else(|| body.strip_prefix("0X")).or_else(|| body.strip_prefix('#')) {
        i64::from_str_radix(h, 16).ok()?
    } else if body.len() > 1 && body.starts_with('0') {
        i64::from_str_radix(&body[1..], 8).ok()?
    } else {
        body.parse::<i64>().ok()?
    };
    i32::try_from(if neg { -v } else { v }).ok()
}

/// `x.children.get(0).value`, throwing (None) without children.
fn first_child_value<'a>(n: &JavaNode<'a>) -> Result<Option<&'a str>, ()> {
    match n.element {
        Some(e) => e.java_children().first().map(|c| c.value).ok_or(()),
        None => Err(()),
    }
}

/// Java `textValue`: the first non-blank child value, trimmed.
fn text_value(n: &JavaNode) -> Option<String> {
    n.element?.java_children().iter().filter_map(|c| c.value).map(str::trim).find(|v| !v.is_empty()).map(str::to_string)
}

fn root(dir: &Path, name: &str) -> Option<Element> {
    let s = std::fs::read(dir.join(name)).ok()?;
    xml::parse(&String::from_utf8_lossy(&s)).ok()
}

impl Dungeons {
    /// Reads them from an `assets/xml` folder, as Java does; the second part lists why parts stopped early.
    pub fn load(xml_dir: &Path) -> (Dungeons, Vec<String>) {
        let mut d = Dungeons::default();
        let mut problems = Vec::new();
        if let Err(e) = d.modifiers(xml_dir) {
            problems.push(e);
        }
        if let Err(e) = d.portals(xml_dir) {
            problems.push(e);
        }
        d.all_portals(xml_dir);
        if let Err(e) = d.game_file_enemies(xml_dir) {
            problems.push(e);
        }
        (d, problems)
    }

    /// Java `parseDungeonModifier`: mods.xml and mods2.xml. A bad type stops it (and the grade entries are not added).
    fn modifiers(&mut self, dir: &Path) -> Result<(), String> {
        for file in ["mods.xml", "mods2.xml"] {
            if !dir.join(file).is_file() {
                continue;
            }
            let r = root(dir, file).ok_or(format!("{file} unreadable; the rest is not read"))?;
            for n in r.java_children() {
                if n.name != "DungeonModifier" {
                    continue;
                }
                let Some(e) = n.element else { continue };
                let (mut name, mut id) = (None, 0i16);
                for i in e.java_children() {
                    if i.name == "id" {
                        name = i.value.map(str::to_string);
                    }
                    if i.name == "type" {
                        id = i
                            .value
                            .and_then(crate::enchants::decode_short)
                            .ok_or(format!("{file}: a modifier type; the rest is not read"))?;
                    }
                }
                for x in e.java_children() {
                    if x.name == "Description" {
                        first_child_value(&x).map_err(|_| format!("{file}: an empty description; the rest is not read"))?;
                    }
                }
                // Java's maps take null too; a modifier without id is kept under no name
                if let Some(name) = name {
                    self.modifier_names.insert(id, name.clone());
                    self.modifier_by_name.insert(name, i32::from(id));
                }
            }
        }
        for (k, v) in [("|S", -11), ("|A", -12), ("|B", -13), ("|C", -14), ("|D", -15)] {
            self.modifier_by_name.insert(k.into(), v);
        }
        Ok(())
    }

    /// Java `parseDungeonPortalId`: portals.xml.
    fn portals(&mut self, dir: &Path) -> Result<(), String> {
        if !dir.join("portals.xml").is_file() {
            return Ok(());
        }
        let r = root(dir, "portals.xml").ok_or("portals.xml unreadable")?;
        for n in r.java_children() {
            if n.name != "Object" {
                continue;
            }
            let Some(e) = n.element else { continue };
            let (mut id, mut name) = (0, None);
            for i in e.java_children() {
                if i.name == "type" {
                    id = i.value.and_then(decode_int).ok_or("portals.xml: a portal type; the rest is not read")?;
                }
            }
            for x in e.java_children() {
                if x.name == "DungeonName" {
                    name =
                        first_child_value(&x).map_err(|_| "portals.xml: an empty dungeon name; the rest is not read")?.map(str::to_string);
                }
            }
            if let (Some(name), true) = (name, id != 0) {
                self.portal_by_name.insert(name.clone(), id);
                self.portal_names.insert(id, name);
            }
        }
        self.portal_by_name.insert("Realm of the Mad God".into(), 1796);
        Ok(())
    }

    /// The `.xml` files of a folder, in the folder's own order (as Java's `File.listFiles`).
    fn xml_files(dir: &Path) -> Vec<std::path::PathBuf> {
        let Ok(rd) = std::fs::read_dir(dir) else { return Vec::new() };
        rd.flatten()
            .map(|e| e.path())
            .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.to_lowercase().ends_with(".xml")))
            .collect()
    }

    /// Java `parseAllPortalObjects`: every object with DungeonPortal and DungeonName in any of the game's files.
    fn all_portals(&mut self, dir: &Path) {
        for f in Self::xml_files(dir) {
            let Ok(bytes) = std::fs::read(&f) else { continue };
            let content = String::from_utf8_lossy(&bytes);
            if !content.contains("<DungeonName>") {
                continue;
            }
            let Ok(r) = xml::parse(&content) else { continue };
            for n in r.java_children() {
                if n.name != "Object" {
                    continue;
                }
                let Some(e) = n.element else { continue };
                let (mut portal, mut name, mut kind) = (false, None, 0);
                for i in e.java_children() {
                    match i.name {
                        "DungeonPortal" => portal = true,
                        "DungeonName" => name = text_value(&i),
                        "type" => {
                            if let Some(v) = i.value.and_then(decode_int) {
                                kind = v;
                            }
                        }
                        _ => {}
                    }
                }
                if let (true, Some(name), true) = (portal, name, kind != 0) {
                    self.portal_by_name.entry(name.clone()).or_insert(kind);
                    self.portal_names.insert(kind, name);
                }
            }
        }
    }

    /// Java `allDungeonNames`: every dungeon's name, sorted ignoring case.
    pub fn all_names(&self) -> Vec<String> {
        let mut seen = BTreeSet::new();
        let mut names: Vec<String> = Vec::new();
        let mut ids: Vec<&i32> = self.portal_names.keys().collect();
        ids.sort();
        for n in ids.into_iter().map(|i| &self.portal_names[i]) {
            if seen.insert(n.to_lowercase()) {
                names.push(n.clone());
            }
        }
        names.sort_by_key(|n| n.to_lowercase());
        names
    }

    /// Java `GameFileEnemies.load`: an enemy (an object with `<Enemy`) in a dungeon's own objects file belongs to that
    /// dungeon; one in the files of several dungeons tells nothing. Where Java throws (a type number too big, a file
    /// not UTF-8) it stops, keeping what it found, shared ones included.
    fn game_file_enemies(&mut self, dir: &Path) -> Result<(), String> {
        let mut dungeons = HashMap::new();
        for n in self.all_names() {
            dungeons.insert(squash(&n), n);
        }
        let mut shared = HashSet::new();
        for f in Self::xml_files(dir) {
            let file = f.file_name().and_then(|n| n.to_str()).unwrap_or("").replace(".xml", "");
            let Some(dungeon) = dungeons.get(&squash(strip_suffix(&file))).cloned() else { continue };
            let content = std::fs::read(&f)
                .map_err(|e| e.to_string())
                .and_then(|b| String::from_utf8(b).map_err(|e| e.to_string()))
                .map_err(|e| format!("{}: {e}; the rest is not read", f.display()))?;
            for (t, body) in objects(&content) {
                if !body.contains("<Enemy") {
                    continue;
                }
                let kind = match t.strip_prefix("0x") {
                    Some(h) => i32::from_str_radix(h, 16),
                    None => t.parse(),
                }
                .map_err(|_| format!("{}: type {t} too big; the rest is not read", f.display()))?;
                match self.enemies.get(&kind) {
                    None => {
                        self.enemies.insert(kind, dungeon.clone());
                    }
                    Some(before) if *before != dungeon => {
                        shared.insert(kind);
                    }
                    Some(_) => {}
                }
            }
        }
        for k in shared {
            self.enemies.remove(&k);
        }
        Ok(())
    }
}

/// Java `squash`: lower case letters only, a leading "the" dropped.
pub fn squash(s: &str) -> String {
    let l: String = s.to_lowercase().chars().filter(char::is_ascii_lowercase).collect();
    if l.starts_with("the") && l.len() > 6 { l[3..].to_string() } else { l }
}

/// Java's `SUFFIX` replacement: `(Objects|Ground|Static|Mobs|Enemies|Men)\w*$` cut off, the leftmost match.
fn strip_suffix(name: &str) -> &str {
    let word = |c: char| c.is_ascii_alphanumeric() || c == '_';
    for (i, _) in name.char_indices() {
        let rest = &name[i..];
        for w in ["Objects", "Ground", "Static", "Mobs", "Enemies", "Men"] {
            if let Some(after) = rest.strip_prefix(w)
                && after.chars().all(word)
            {
                return &name[..i];
            }
        }
    }
    name
}

/// Java's `OBJECT` regex: `<Object\s[^>]*type="(0x[0-9a-fA-F]+|\d+)"[^>]*>(.*?)</Object>`: each object's type (the
/// last valid `type="..."` in its tag) and what is inside, up to the next `</Object>`.
fn objects(s: &str) -> Vec<(&str, &str)> {
    let mut out = Vec::new();
    let mut at = 0;
    while let Some(i) = s[at..].find("<Object") {
        let start = at + i;
        at = start + 1;
        let after = &s[start + 7..];
        if !after.starts_with(|c: char| c.is_whitespace()) {
            continue;
        }
        let Some(tag_len) = after.find('>') else { continue };
        let tag = &after[..tag_len];
        // the last type="..." with a valid value (the regex backtracks from the tag's end)
        let mut found = None;
        let mut k = tag.len();
        while let Some(p) = tag[..k].rfind("type=\"") {
            let v = &tag[p + 6..];
            if let Some(end) = v.find('"') {
                let val = &v[..end];
                let ok = match val.strip_prefix("0x") {
                    Some(h) => !h.is_empty() && h.chars().all(|c| c.is_ascii_hexdigit()),
                    None => !val.is_empty() && val.chars().all(|c| c.is_ascii_digit()),
                };
                if ok {
                    found = Some(val);
                    break;
                }
            }
            k = p;
        }
        let Some(t) = found else { continue };
        let body_start = start + 7 + tag_len + 1;
        let Some(close) = s[body_start..].find("</Object>") else { continue };
        out.push((t, &s[body_start..body_start + close]));
        at = body_start + close + "</Object>".len();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_and_suffixes() {
        assert_eq!(squash("The Shatters"), "shatters");
        assert_eq!(squash("Thesis"), "thesis", "too short to lose its 'the'");
        assert_eq!(squash("Theatre"), "atre");
        assert_eq!(strip_suffix("PirateCaveObjects"), "PirateCave");
        assert_eq!(strip_suffix("ShattersMobs2"), "Shatters");
        assert_eq!(strip_suffix("MenagerieObjects"), "", "the leftmost match: Men...");
        assert_eq!(strip_suffix("Pirate-Cave"), "Pirate-Cave");
    }

    #[test]
    fn objects_as_the_regex_finds_them() {
        let s = r#"<Objects><Object type="0x10" id="a"><Enemy/></Object><Object id='x' type="bad" type="12">b</Object>
<Objects2/><Object type="0x20"/><Object type="7">c</Object></Objects>"#;
        let o = objects(s);
        assert_eq!(o[0], ("0x10", "<Enemy/>"));
        assert_eq!(o[1], ("12", "b"));
        // a self-closing object reaches to the next close tag
        assert_eq!(o[2].0, "0x20");
        assert!(o[2].1.contains("<Object type=\"7\">c"));
        assert_eq!(o.len(), 3);
    }
}
