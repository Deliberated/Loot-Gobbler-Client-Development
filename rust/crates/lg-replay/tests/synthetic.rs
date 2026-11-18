//! The whole pipeline on made-up sessions: what comes out must be what was sent, from the cipher's first known
//! position on.

use lg_protocol::Direction;
use lg_protocol::aligner::{AlignNote, MOVE, NEWTICK};
use lg_protocol::{Note, Packet};
use lg_replay::synth::{Options, Plain, session};
use lg_replay::{Item, replay};

/// What the decoder can give of `sent`: it skips the first packet (the capture's first short segment), needs two
/// ticks to find the cipher's position, and gives everything after the second.
fn expected(sent: &[Plain]) -> &[Plain] {
    let tick = |p: &&Plain| p.id == NEWTICK || p.id == MOVE;
    let second = sent.iter().enumerate().skip(1).filter(|(_, p)| tick(p)).nth(1).expect("two ticks").0;
    &sent[second + 1..]
}

fn got(packets: &[&Packet], d: Direction) -> Vec<Plain> {
    packets.iter().filter(|p| p.direction == d).map(|p| Plain { id: p.id, body: p.body.clone() }).collect()
}

#[test]
fn every_packet_comes_out_as_sent() {
    for seed in 1..=8 {
        for disorder in [false, true] {
            let s = session(&Options { seed, disorder, ..Default::default() });
            let r = replay(&s.pcap).unwrap();
            let packets: Vec<&Packet> = r.packets().collect();
            assert_eq!(got(&packets, Direction::Incoming), expected(&s.incoming), "incoming, seed {seed}, disorder {disorder}");
            assert_eq!(got(&packets, Direction::Outgoing), expected(&s.outgoing), "outgoing, seed {seed}, disorder {disorder}");
            let problems: Vec<_> = r
                .items
                .iter()
                .filter(|i| {
                    matches!(
                        i,
                        Item::Gap { .. }
                            | Item::Note { note: Note::BadSize(_) | Note::Align(AlignNote::Lost { .. } | AlignNote::Failed), .. }
                    )
                })
                .collect();
            assert!(problems.is_empty(), "seed {seed}: {problems:?}");
        }
    }
}

#[test]
fn finds_its_feet_again_after_lost_bytes() {
    let s = session(&Options { seed: 3, ticks: 400, lose: Some(40), ..Default::default() });
    let r = replay(&s.pcap).unwrap();
    assert!(r.items.iter().any(|i| matches!(i, Item::Gap { direction: Direction::Incoming, .. })), "the gap is noticed");
    let synced = r
        .items
        .iter()
        .filter(|i| matches!(i, Item::Note { direction: Direction::Incoming, note: Note::Align(AlignNote::Synced { .. }), .. }))
        .count();
    assert!(synced >= 2, "the cipher's position is found again ({synced} times)");
    // the last packets arrive as sent again
    let packets: Vec<&Packet> = r.packets().collect();
    let incoming = got(&packets, Direction::Incoming);
    let tail = &s.incoming[s.incoming.len() - 50..];
    assert_eq!(&incoming[incoming.len() - 50..], tail);
    // the other direction lost nothing
    assert_eq!(got(&packets, Direction::Outgoing), expected(&s.outgoing));
}

#[test]
fn a_made_up_fight_comes_out_as_sent() {
    for seed in 1..=4 {
        let s = lg_replay::fight::fight(seed);
        let r = replay(&s.pcap).unwrap();
        let packets: Vec<&Packet> = r.packets().collect();
        assert_eq!(got(&packets, Direction::Incoming), expected(&s.incoming), "incoming, seed {seed}");
        assert_eq!(got(&packets, Direction::Outgoing), expected(&s.outgoing), "outgoing, seed {seed}");
        // and the world gets its fight: the map, you, damage on the enemies
        let mut w = lg_world::World::new(lg_gamedata::GameData::default());
        for p in &packets {
            if let Some(Ok(d)) = lg_protocol::packets::decode(p.id, &p.body) {
                let _ = w.packet(&d.packet);
            }
        }
        assert_eq!(w.map.as_ref().map(|m| m.name.as_str()), Some("Second Room"));
        assert!(w.damage.rng.is_some());
        assert!(w.entities.values().any(|e| !e.damage_list.is_empty()), "seed {seed}");
    }
}
