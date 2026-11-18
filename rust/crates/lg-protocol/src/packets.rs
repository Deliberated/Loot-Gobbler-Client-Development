//! Decoders for some packets, the first of many (Java: `packets.incoming.*`, `packets.outgoing.*`).

use crate::reader::{Reader, Truncated};

/// NEWTICK (10): the server's regular update.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NewTick {
    pub tick_id: i32,
    pub tick_time: i32,
    pub server_real_time_ms: u32,
    pub server_last_rtt_ms: u16,
    /// How many objects changed (their details are not decoded yet).
    pub statuses: i16,
}

impl NewTick {
    pub fn decode(body: &[u8]) -> Result<NewTick, Truncated> {
        let mut r = Reader::new(body);
        Ok(NewTick {
            tick_id: r.i32()?,
            tick_time: r.i32()?,
            server_real_time_ms: r.u32()?,
            server_last_rtt_ms: r.u16()?,
            statuses: r.i16()?,
        })
    }
}

/// TEXT (44): a chat line, from a player or the game.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Text {
    pub name: String,
    pub object_id: i32,
    pub num_stars: i16,
    pub bubble_time: u8,
    pub recipient: String,
    pub text: String,
    pub clean_text: String,
    pub is_supporter: bool,
    pub star_background: i32,
}

impl Text {
    pub fn decode(body: &[u8]) -> Result<Text, Truncated> {
        let mut r = Reader::new(body);
        Ok(Text {
            name: r.string()?,
            object_id: r.i32()?,
            num_stars: r.i16()?,
            bubble_time: r.u8()?,
            recipient: r.string()?,
            text: r.string()?,
            clean_text: r.string()?,
            is_supporter: r.bool()?,
            star_background: r.i32()?,
        })
    }

    /// The fields in the order they are sent: for writing test captures.
    pub fn encode(&self) -> Vec<u8> {
        let mut b = Vec::new();
        let s = |b: &mut Vec<u8>, s: &str| {
            b.extend((s.len() as u16).to_be_bytes());
            b.extend(s.as_bytes());
        };
        s(&mut b, &self.name);
        b.extend(self.object_id.to_be_bytes());
        b.extend(self.num_stars.to_be_bytes());
        b.push(self.bubble_time);
        s(&mut b, &self.recipient);
        s(&mut b, &self.text);
        s(&mut b, &self.clean_text);
        b.push(u8::from(self.is_supporter));
        b.extend(self.star_background.to_be_bytes());
        b
    }
}

/// A short description of a packet's content for the replay's output, if it has a decoder.
pub fn describe(id: u8, body: &[u8]) -> Option<String> {
    match id {
        10 => Some(match NewTick::decode(body) {
            Ok(t) => format!("tick={} time={} statuses={}", t.tick_id, t.tick_time, t.statuses),
            Err(e) => format!("(cut off at byte {})", e.at),
        }),
        44 => Some(match Text::decode(body) {
            Ok(t) => {
                format!("{}{}: {}", t.name, if t.recipient.is_empty() { String::new() } else { format!(" -> {}", t.recipient) }, t.text)
            }
            Err(e) => format!("(cut off at byte {})", e.at),
        }),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_round_trip() {
        let t = Text {
            name: "Gobbler".into(),
            object_id: 77,
            num_stars: 85,
            bubble_time: 5,
            recipient: String::new(),
            text: "loot!".into(),
            clean_text: "loot!".into(),
            is_supporter: true,
            star_background: 0,
        };
        assert_eq!(Text::decode(&t.encode()).unwrap(), t);
        assert_eq!(describe(44, &t.encode()).unwrap(), "Gobbler: loot!");
    }

    #[test]
    fn newtick_fields() {
        let mut b = 42i32.to_be_bytes().to_vec();
        b.extend(200i32.to_be_bytes());
        b.extend(1000u32.to_be_bytes());
        b.extend(30u16.to_be_bytes());
        b.extend(0i16.to_be_bytes());
        let t = NewTick::decode(&b).unwrap();
        assert_eq!((t.tick_id, t.tick_time, t.statuses), (42, 200, 0));
    }
}
