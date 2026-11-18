//! lg-replay: prints the game packets in a recorded session.
//!
//!   lg-replay <session.pcap>            every packet, with time, direction, size and (for some) its content
//!   lg-replay <session.pcap> --oracle   one line per packet to compare with the Java client (oracle/compare.sh)
//!   lg-replay <session.pcap> --fields   every field of each packet that has a decoder, to compare the same way
//!   lg-replay decoded-ids               the ids of the packets that have a decoder, for oracle/compare.sh
//!   lg-replay <session.pcap> --stats    only the summary
//!   lg-replay synth <out.pcap> [seed]   writes a made-up session, for testing without a recording

use lg_protocol::aligner::AlignNote;
use lg_protocol::{Note, packets};
use lg_replay::{Item, oracle_line, replay, synth};
use std::collections::BTreeMap;
use std::io::{BufWriter, Write};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.as_slice() {
        [cmd, out, rest @ ..] if cmd == "synth" => {
            let seed = rest.first().and_then(|s| s.parse().ok()).unwrap_or(1);
            let s = synth::session(&synth::Options { seed, ..Default::default() });
            if let Err(e) = std::fs::write(out, &s.pcap) {
                eprintln!("cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            eprintln!("{out}: {} packets in, {} out (seed {seed})", s.incoming.len(), s.outgoing.len());
            ExitCode::SUCCESS
        }
        [cmd] if cmd == "decoded-ids" => {
            println!("{}", packets::DECODED_IDS.iter().map(u8::to_string).collect::<Vec<_>>().join(","));
            ExitCode::SUCCESS
        }
        [file, flags @ ..] if !file.starts_with('-') => {
            let has = |f: &str| flags.iter().any(|x| x == f);
            let mode = if has("--oracle") {
                Mode::Oracle
            } else if has("--fields") {
                Mode::Fields
            } else if has("--stats") {
                Mode::Stats
            } else {
                Mode::List
            };
            run(file, mode)
        }
        _ => {
            eprintln!(
                "usage: lg-replay <session.pcap> [--oracle | --fields | --stats]\n       lg-replay synth <out.pcap> [seed]\n       lg-replay decoded-ids"
            );
            ExitCode::from(2)
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    List,
    Oracle,
    Fields,
    Stats,
}

fn run(file: &str, mode: Mode) -> ExitCode {
    let oracle = mode == Mode::Oracle || mode == Mode::Fields;
    let stats_only = mode == Mode::Stats;
    let data = match std::fs::read(file) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("cannot read {file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let started = std::time::Instant::now();
    let r = match replay(&data) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{file}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let took = started.elapsed();
    let first = r.items.iter().map(item_micros).next().unwrap_or(0);
    let mut counts: BTreeMap<(&str, &str), usize> = BTreeMap::new();
    let mut notes = 0;
    let mut out = BufWriter::new(std::io::stdout().lock());
    for item in &r.items {
        match item {
            Item::Packet { micros, packet } => {
                *counts.entry((packet.direction.short(), packet.name())).or_default() += 1;
                if mode == Mode::Oracle {
                    line(&mut out, format_args!("{}", oracle_line(packet)));
                } else if mode == Mode::Fields {
                    if let Some(f) = packets::fields_line(packet.id, &packet.body) {
                        line(&mut out, format_args!("{} {} {} {f}", packet.direction.short(), packet.id, packet.name()));
                    }
                } else if !stats_only {
                    let about = packets::describe(packet.id, &packet.body).unwrap_or_default();
                    line(
                        &mut out,
                        format_args!(
                            "{:>10}  {:<3} {:<24} {:>6} B  {}",
                            time(*micros - first),
                            packet.direction.short(),
                            packet.name(),
                            packet.size(),
                            about
                        ),
                    );
                }
            }
            Item::Note { micros, direction, note } => {
                notes += 1;
                if !oracle && !stats_only {
                    line(&mut out, format_args!("{:>10}  {:<3} -- {}", time(*micros - first), direction.short(), describe_note(note)));
                }
            }
            Item::Gap { micros, direction, skipped } => {
                notes += 1;
                if !oracle && !stats_only {
                    line(
                        &mut out,
                        format_args!("{:>10}  {:<3} -- packets missing: skipped {skipped} bytes", time(*micros - first), direction.short()),
                    );
                }
            }
        }
    }
    if out.flush().is_err() {
        return ExitCode::SUCCESS; // the reader went away (e.g. `| head`)
    }
    drop(out);
    let total: usize = counts.values().sum();
    eprintln!("{file}: {} frames (link type {}), {total} game packets, {notes} notes, in {:.0?}", r.frames, r.link_type, took);
    if stats_only {
        let mut by_count: Vec<_> = counts.into_iter().collect();
        by_count.sort_by_key(|(_, n)| std::cmp::Reverse(*n));
        for ((dir, name), n) in by_count {
            eprintln!("  {n:>7}  {dir:<3} {name}");
        }
    }
    ExitCode::SUCCESS
}

/// One line of output; stops quietly when the reader went away (e.g. `| head`).
fn line(out: &mut impl Write, args: std::fmt::Arguments) {
    if writeln!(out, "{args}").is_err() {
        std::process::exit(0);
    }
}

fn item_micros(i: &Item) -> u64 {
    match i {
        Item::Packet { micros, .. } | Item::Note { micros, .. } | Item::Gap { micros, .. } => *micros,
    }
}

/// Time since the first item, as seconds with milliseconds.
fn time(micros: u64) -> String {
    format!("{}.{:03}s", micros / 1_000_000, micros % 1_000_000 / 1000)
}

fn describe_note(n: &Note) -> String {
    match n {
        Note::Align(AlignNote::Synced { offset, tick }) => format!("cipher in step (offset {offset}, tick {tick})"),
        Note::Align(AlignNote::Lost { got, expected }) => format!("cipher out of step: tick {got}, expected {expected}"),
        Note::Align(AlignNote::Failed) => "cipher position not found".into(),
        Note::BadSize(s) => format!("impossible packet size {s}: stream out of step"),
    }
}
