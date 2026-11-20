//! The live capture's libpcap path, checked on a capture file (live capturing needs rights tests do not have): the
//! frames libpcap gives through the game's filter make the same game packets as the file read directly.

use lg_capture::live;
use lg_replay::{Item, Session, replay};

#[test]
fn libpcap_frames_make_the_same_packets() {
    let s = lg_replay::fight::fight(3);
    let path = std::env::temp_dir().join(format!("lg-live-{}.pcap", std::process::id()));
    std::fs::write(&path, &s.pcap).unwrap();
    let frames = match live::read_file(path.to_str().unwrap(), "tcp port 2050") {
        Ok(f) => f,
        Err(live::Error::NoLibrary(e)) => {
            eprintln!("skipped: no libpcap here ({e})");
            return;
        }
        Err(e) => panic!("{e}"),
    };
    std::fs::remove_file(&path).unwrap();
    assert!(!frames.is_empty());
    let mut session = Session::new(frames[0].link_type);
    let mut items = Vec::new();
    for f in &frames {
        session.frame(f.micros, &f.data, &mut items);
    }
    let live: Vec<_> = items.iter().filter_map(|i| if let Item::Packet { packet, .. } = i { Some(packet.clone()) } else { None }).collect();
    let direct: Vec<_> = replay(&s.pcap).unwrap().packets().cloned().collect();
    assert_eq!(live, direct);
}

#[test]
fn interfaces_are_listed() {
    match live::interfaces() {
        Ok(list) => eprintln!("{} interfaces, {} usable", list.len(), list.iter().filter(|i| i.usable()).count()),
        Err(e) => eprintln!("skipped: {e}"),
    }
}
