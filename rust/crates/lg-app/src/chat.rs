//! The chat log (Java `ChatGUI.updateChat` into `ChatLogPanel`): every chat line while the app runs, by channel,
//! kept in memory only (the newest 5000). Lines with a blocked spam phrase are dropped. Sounds and pings are the
//! window's part.

use std::collections::VecDeque;
use std::path::Path;

const MAX_LINES: usize = 5000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Channel {
    Guild,
    Party,
    Pm,
    Public,
    System,
}

impl Channel {
    pub fn label(self) -> &'static str {
        match self {
            Channel::Guild => "Guild",
            Channel::Party => "Party",
            Channel::Pm => "PM",
            Channel::Public => "Public",
            Channel::System => "System",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Line {
    /// "HH:mm:ss", local time.
    pub time: String,
    pub channel: Channel,
    /// Who wrote it ("From Name" / "To Name" for private messages); empty for system lines.
    pub name: String,
    pub text: String,
}

#[derive(Debug, Default)]
pub struct Chat {
    pub lines: VecDeque<Line>,
    /// Phrases bots use: lines containing one are dropped.
    pub blocked: Vec<String>,
}

impl Chat {
    /// The blocked phrases kept next to the app: `block.txt` (one per line) and the RealmShark list saved last
    /// (`chat-spam-keywords.json`, a JSON array of strings).
    pub fn load_blocked(&mut self, dir: &Path) {
        if let Ok(s) = std::fs::read_to_string(dir.join("block.txt")) {
            self.blocked.extend(s.lines().map(str::to_string));
        }
        if let Ok(s) = std::fs::read_to_string(dir.join("chat-spam-keywords.json"))
            && let Ok(lg_world::json::Json::Array(v)) = lg_world::json::parse(&s)
        {
            self.blocked.extend(v.into_iter().filter_map(|x| if let lg_world::json::Json::String(t) = x { Some(t) } else { None }));
        }
    }

    /// One chat packet (Java `updateChat`): the channel from its recipient, "From"/"To" for your private messages,
    /// Umi's riddles answered underneath.
    pub fn text(&mut self, me: Option<&str>, name: &str, recipient: &str, text: &str, time: &str) {
        if self.blocked.iter().any(|b| text.contains(b.as_str())) {
            return;
        }
        let mut who = name.split(',').next().unwrap_or("").to_string();
        let mut direction = "";
        let channel = if recipient.contains("*Guild*") {
            Channel::Guild
        } else if recipient.contains("*Party*") {
            Channel::Party
        } else if !recipient.trim().is_empty() {
            if let Some(me) = me {
                if recipient == me {
                    direction = "From ";
                } else if who == me {
                    who = recipient.split(',').next().unwrap_or("").to_string();
                    direction = "To ";
                }
            }
            Channel::Pm
        } else {
            Channel::Public
        };
        self.add(Line { time: time.to_string(), channel, name: format!("{direction}{who}"), text: text.to_string() });
        let answer = match (name, text) {
            ("#Village Girl Umi", "I've been intrigued by folktales from foreign lands recently.") => Some("The Happy Prince"),
            ("#Village Girl Umi", "The delicious smells coming from the festival stalls are making me hungry...") => Some("Mushroom"),
            ("#Village Girl Umi", "How did you find tonight's performance? It looked extremely fun, I couldn't help cheering you on!") => {
                Some("Carosburg")
            }
            _ => None,
        };
        if let Some(a) = answer {
            self.add(Line { time: time.to_string(), channel, name: "Umi Response".into(), text: a.into() });
        }
    }

    /// A line from the app itself.
    pub fn system(&mut self, text: &str, time: &str) {
        self.add(Line { time: time.to_string(), channel: Channel::System, name: String::new(), text: text.trim().to_string() });
    }

    fn add(&mut self, l: Line) {
        self.lines.push_back(l);
        if self.lines.len() > MAX_LINES {
            self.lines.pop_front();
        }
    }

    pub fn clear(&mut self) {
        self.lines.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn channels_and_directions() {
        let mut c = Chat::default();
        c.text(Some("Me"), "Bob,12", "*Guild*", "hi", "10:00:00");
        c.text(Some("Me"), "Bob", "Me", "psst", "10:00:01");
        c.text(Some("Me"), "Me", "Bob,3", "back", "10:00:02");
        c.text(
            Some("Me"),
            "#Village Girl Umi",
            "",
            "The delicious smells coming from the festival stalls are making me hungry...",
            "10:00:03",
        );
        let got: Vec<(Channel, &str)> = c.lines.iter().map(|l| (l.channel, l.name.as_str())).collect();
        assert_eq!(
            got,
            vec![
                (Channel::Guild, "Bob"),
                (Channel::Pm, "From Bob"),
                (Channel::Pm, "To Bob"),
                (Channel::Public, "#Village Girl Umi"),
                (Channel::Public, "Umi Response")
            ]
        );
        c.blocked.push("buy gold".into());
        c.text(None, "Bot", "", "cheap! buy gold now", "10:00:04");
        assert_eq!(c.lines.len(), 5);
    }
}
