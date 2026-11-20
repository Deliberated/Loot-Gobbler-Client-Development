//! The app's sounds (Java `Sound`, `ItemSound`): the bundled WAV files, decoded as Java does (16-bit mono), each
//! played on a thread of its own so they can overlap. The system's sound library is loaded when first needed
//! (Linux: ALSA's libasound.so.2; Windows: winmm), so building needs no audio headers; without it: silence.

use std::sync::OnceLock;

macro_rules! bundled {
    ($($name:literal),* $(,)?) => {
        /// The bundled sounds by file name (`sound/<name>` in the Java app's resources).
        fn bundled(name: &str) -> Option<&'static [u8]> {
            match name {
                $($name => Some(include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../src/main/resources/sound/", $name))),)*
                _ => None,
            }
        }
    };
}

bundled!(
    "party.wav",
    "guild.wav",
    "pm.wav",
    "keypop.wav",
    "whitebag.wav",
    "orangebag.wav",
    "redbag.wav",
    "goldbag.wav",
    "eggbag.wav",
    "bluebag.wav",
    "trade.wav",
    "custom.wav",
    "goblin-cackle.wav",
    "goblin-gobble.wav",
    "amulet_activation.wav",
    "amulet_ready_chain.wav",
    "amulet_ready_dangle.wav",
    "amulet_ready_pull.wav",
    "amulet_ready_clasp.wav",
    "amulet_ready_gleam.wav",
    "refinery_bubble.wav",
    "refinery_chime.wav",
    "refinery_ping.wav",
    "refinery_droplet.wav",
    "refinery_shell.wav",
    "alien_warp.wav",
    "alien_beacon.wav",
    "alien_saucer.wav",
    "alien_arpeggio.wav",
    "alien_signal.wav",
    "matrix_pulse.wav",
    "matrix_zip.wav",
    "matrix_shield.wav",
    "matrix_recharge.wav",
    "matrix_lock.wav",
    "matrix_break_pufferfish.wav",
    "matrix_break_core.wav",
    "matrix_break_collapse.wav",
    "matrix_break_shatter.wav",
    "matrix_break_brownout.wav",
    "matrix_break_reactor.wav",
);

/// A decoded sound: 16-bit mono samples at their rate.
#[derive(Debug, Clone, PartialEq)]
pub struct Pcm {
    pub rate: u32,
    pub samples: Vec<i16>,
}

/// A PCM WAV file (8, 16, 24 or 32 bit integers, any number of channels) mixed down to 16-bit mono.
pub fn decode(wav: &[u8]) -> Option<Pcm> {
    if wav.len() < 12 || &wav[..4] != b"RIFF" || &wav[8..12] != b"WAVE" {
        return None;
    }
    let (mut fmt, mut data) = (None, None);
    let mut i = 12;
    while i + 8 <= wav.len() {
        let size = u32::from_le_bytes(wav[i + 4..i + 8].try_into().ok()?) as usize;
        let body = wav.get(i + 8..(i + 8 + size).min(wav.len()))?;
        match &wav[i..i + 4] {
            b"fmt " => fmt = Some(body),
            b"data" => data = Some(body),
            _ => {}
        }
        i += 8 + size + (size & 1);
    }
    let (fmt, data) = (fmt?, data?);
    let u16_at = |o: usize| u16::from_le_bytes([fmt[o], fmt[o + 1]]);
    if fmt.len() < 16 || !matches!(u16_at(0), 1 | 0xFFFE) {
        return None;
    }
    let channels = usize::from(u16_at(2)).max(1);
    let rate = u32::from_le_bytes(fmt[4..8].try_into().ok()?);
    let bytes = usize::from(u16_at(14)) / 8;
    if !(1..=4).contains(&bytes) {
        return None;
    }
    let frame = bytes * channels;
    let sample = |b: &[u8]| -> f64 {
        match bytes {
            1 => (f64::from(b[0]) - 128.0) / 128.0,
            2 => f64::from(i16::from_le_bytes([b[0], b[1]])) / 32768.0,
            3 => f64::from(i32::from_le_bytes([0, b[0], b[1], b[2]]) >> 8) / 8_388_608.0,
            _ => f64::from(i32::from_le_bytes([b[0], b[1], b[2], b[3]])) / 2_147_483_648.0,
        }
    };
    let samples = data
        .chunks_exact(frame)
        .map(|f| {
            let sum: f64 = f.chunks_exact(bytes).map(sample).sum();
            ((sum / channels as f64) * 32767.0).round().clamp(-32768.0, 32767.0) as i16
        })
        .collect();
    Some(Pcm { rate, samples })
}

/// The bundled sound, decoded once.
fn sound(name: &str) -> Option<&'static Pcm> {
    static CACHE: OnceLock<std::sync::Mutex<std::collections::HashMap<String, &'static Pcm>>> = OnceLock::new();
    let cache = CACHE.get_or_init(Default::default);
    let mut c = cache.lock().ok()?;
    if let Some(p) = c.get(name) {
        return Some(p);
    }
    let pcm: &'static Pcm = Box::leak(Box::new(decode(bundled(name)?)?));
    c.insert(name.to_string(), pcm);
    Some(pcm)
}

/// Plays a bundled sound at a volume (0..100, Java's dB scale is the same amplitude), or a WAV file of yours.
pub fn play(name_or_file: &str, volume: i32) {
    if volume <= 0 {
        return;
    }
    let pcm = match sound(name_or_file) {
        Some(p) => p.clone(),
        None => match std::fs::read(name_or_file).ok().and_then(|b| decode(&b)) {
            Some(p) => p,
            None => return,
        },
    };
    let gain = f64::from(volume.min(100)) / 100.0;
    let samples: Vec<i16> = pcm.samples.iter().map(|s| (f64::from(*s) * gain).round() as i16).collect();
    std::thread::spawn(move || {
        if let Err(e) = output::play(pcm.rate, &samples) {
            eprintln!("cannot play a sound: {e}");
        }
    });
}

#[cfg(target_os = "linux")]
mod output {
    use libloading::{Library, Symbol};
    use std::ffi::{c_char, c_int, c_long, c_uint, c_ulong, c_void};
    use std::sync::OnceLock;

    fn lib() -> Result<&'static Library, String> {
        static LIB: OnceLock<Result<Library, String>> = OnceLock::new();
        LIB.get_or_init(|| unsafe { Library::new("libasound.so.2") }.map_err(|e| e.to_string())).as_ref().map_err(Clone::clone)
    }

    /// One sound to the default output, played to its end.
    pub fn play(rate: u32, samples: &[i16]) -> Result<(), String> {
        const PLAYBACK: c_int = 0;
        const FORMAT_S16_LE: c_int = 2;
        const ACCESS_RW_INTERLEAVED: c_int = 3;
        let l = lib()?;
        unsafe {
            let open: Symbol<unsafe extern "C" fn(*mut *mut c_void, *const c_char, c_int, c_int) -> c_int> =
                l.get(b"snd_pcm_open").map_err(|e| e.to_string())?;
            let set: Symbol<unsafe extern "C" fn(*mut c_void, c_int, c_int, c_uint, c_uint, c_int, c_uint) -> c_int> =
                l.get(b"snd_pcm_set_params").map_err(|e| e.to_string())?;
            let write: Symbol<unsafe extern "C" fn(*mut c_void, *const c_void, c_ulong) -> c_long> =
                l.get(b"snd_pcm_writei").map_err(|e| e.to_string())?;
            let recover: Symbol<unsafe extern "C" fn(*mut c_void, c_int, c_int) -> c_int> =
                l.get(b"snd_pcm_recover").map_err(|e| e.to_string())?;
            let drain: Symbol<unsafe extern "C" fn(*mut c_void) -> c_int> = l.get(b"snd_pcm_drain").map_err(|e| e.to_string())?;
            let close: Symbol<unsafe extern "C" fn(*mut c_void) -> c_int> = l.get(b"snd_pcm_close").map_err(|e| e.to_string())?;
            let mut pcm: *mut c_void = std::ptr::null_mut();
            let r = open(&mut pcm, c"default".as_ptr(), PLAYBACK, 0);
            if r < 0 {
                return Err(format!("snd_pcm_open: {r}"));
            }
            let r = set(pcm, FORMAT_S16_LE, ACCESS_RW_INTERLEAVED, 1, rate, 1, 100_000);
            if r < 0 {
                close(pcm);
                return Err(format!("snd_pcm_set_params: {r}"));
            }
            let mut at = 0;
            while at < samples.len() {
                let n = write(pcm, samples[at..].as_ptr().cast(), (samples.len() - at) as c_ulong);
                if n < 0 {
                    if recover(pcm, n as c_int, 1) < 0 {
                        break;
                    }
                    continue;
                }
                at += n as usize;
            }
            drain(pcm);
            close(pcm);
        }
        Ok(())
    }
}

#[cfg(windows)]
mod output {
    use libloading::{Library, Symbol};
    use std::ffi::c_void;
    use std::sync::OnceLock;

    #[repr(C)]
    struct WaveFormatEx {
        format_tag: u16,
        channels: u16,
        samples_per_sec: u32,
        avg_bytes_per_sec: u32,
        block_align: u16,
        bits_per_sample: u16,
        size: u16,
    }

    #[repr(C)]
    struct WaveHdr {
        data: *mut u8,
        buffer_length: u32,
        bytes_recorded: u32,
        user: usize,
        flags: u32,
        loops: u32,
        next: *mut WaveHdr,
        reserved: usize,
    }

    fn lib() -> Result<&'static Library, String> {
        static LIB: OnceLock<Result<Library, String>> = OnceLock::new();
        LIB.get_or_init(|| unsafe { Library::new("winmm.dll") }.map_err(|e| e.to_string())).as_ref().map_err(Clone::clone)
    }

    /// One sound to the default output (`waveOut`), played to its end.
    pub fn play(rate: u32, samples: &[i16]) -> Result<(), String> {
        const WAVE_MAPPER: u32 = u32::MAX;
        const WHDR_DONE: u32 = 1;
        let l = lib()?;
        unsafe {
            let open: Symbol<unsafe extern "system" fn(*mut *mut c_void, u32, *const WaveFormatEx, usize, usize, u32) -> u32> =
                l.get(b"waveOutOpen").map_err(|e| e.to_string())?;
            let prepare: Symbol<unsafe extern "system" fn(*mut c_void, *mut WaveHdr, u32) -> u32> =
                l.get(b"waveOutPrepareHeader").map_err(|e| e.to_string())?;
            let write: Symbol<unsafe extern "system" fn(*mut c_void, *mut WaveHdr, u32) -> u32> =
                l.get(b"waveOutWrite").map_err(|e| e.to_string())?;
            let unprepare: Symbol<unsafe extern "system" fn(*mut c_void, *mut WaveHdr, u32) -> u32> =
                l.get(b"waveOutUnprepareHeader").map_err(|e| e.to_string())?;
            let close: Symbol<unsafe extern "system" fn(*mut c_void) -> u32> = l.get(b"waveOutClose").map_err(|e| e.to_string())?;
            let fmt = WaveFormatEx {
                format_tag: 1,
                channels: 1,
                samples_per_sec: rate,
                avg_bytes_per_sec: rate * 2,
                block_align: 2,
                bits_per_sample: 16,
                size: 0,
            };
            let mut out: *mut c_void = std::ptr::null_mut();
            let r = open(&mut out, WAVE_MAPPER, &fmt, 0, 0, 0);
            if r != 0 {
                return Err(format!("waveOutOpen: {r}"));
            }
            let mut buf: Vec<u8> = samples.iter().flat_map(|s| s.to_le_bytes()).collect();
            let mut hdr = WaveHdr {
                data: buf.as_mut_ptr(),
                buffer_length: buf.len() as u32,
                bytes_recorded: 0,
                user: 0,
                flags: 0,
                loops: 0,
                next: std::ptr::null_mut(),
                reserved: 0,
            };
            let hsize = std::mem::size_of::<WaveHdr>() as u32;
            prepare(out, &mut hdr, hsize);
            write(out, &mut hdr, hsize);
            while std::ptr::read_volatile(&hdr.flags) & WHDR_DONE == 0 {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            unprepare(out, &mut hdr, hsize);
            close(out);
        }
        Ok(())
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod output {
    pub fn play(_rate: u32, _samples: &[i16]) -> Result<(), String> {
        Err("no sound on this system yet".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_bundled_sound_decodes() {
        for name in [
            "whitebag.wav",
            "goldbag.wav",
            "amulet_activation.wav",
            "matrix_break_pufferfish.wav",
            "refinery_bubble.wav",
            "goblin-cackle.wav",
        ] {
            let p = sound(name).unwrap_or_else(|| panic!("{name}"));
            assert!(p.rate >= 8000 && p.samples.len() > 1000, "{name}");
        }
    }

    #[test]
    fn stereo_24_bit_to_mono() {
        // one frame: left +0.5, right -0.5 (24 bit) -> 0
        let mut w = b"RIFF\0\0\0\0WAVEfmt ".to_vec();
        w.extend(16u32.to_le_bytes());
        w.extend([1, 0, 2, 0]);
        w.extend(48000u32.to_le_bytes());
        w.extend((48000u32 * 6).to_le_bytes());
        w.extend([6, 0, 24, 0]);
        w.extend(b"data");
        w.extend(6u32.to_le_bytes());
        w.extend([0, 0, 0x40, 0, 0, 0xC0]);
        assert_eq!(decode(&w), Some(Pcm { rate: 48000, samples: vec![0] }));
    }
}

#[cfg(test)]
mod audible {
    /// `cargo test -p lg-gobbler -- --ignored audible`: one bag sound through the real output.
    #[test]
    #[ignore]
    fn audible() {
        let pcm = super::sound("whitebag.wav").expect("sound");
        super::output::play(pcm.rate, &pcm.samples).expect("played");
    }
}

/// The item sounds (Java `ItemSound`): setting key and bundled sounds (file, name), the first the default.
pub const ITEM_SOUNDS: [(&str, &[(&str, &str)]); 6] = [
    ("amuletSoundFile", &[("amulet_activation.wav", "Amulet activation")]),
    (
        "amuletReadySoundFile",
        &[
            ("amulet_ready_chain.wav", "Chain"),
            ("amulet_ready_dangle.wav", "Dangle"),
            ("amulet_ready_pull.wav", "Pull"),
            ("amulet_ready_clasp.wav", "Clasp"),
            ("amulet_ready_gleam.wav", "Gleam"),
        ],
    ),
    (
        "refinerySoundFile",
        &[
            ("refinery_bubble.wav", "Bubble"),
            ("refinery_chime.wav", "Chime"),
            ("refinery_ping.wav", "Ping"),
            ("refinery_droplet.wav", "Droplet"),
            ("refinery_shell.wav", "Shell"),
        ],
    ),
    (
        "alienSoundFile",
        &[
            ("alien_warp.wav", "Warp"),
            ("alien_beacon.wav", "Beacon"),
            ("alien_saucer.wav", "Saucer"),
            ("alien_arpeggio.wav", "Arpeggio"),
            ("alien_signal.wav", "Signal"),
        ],
    ),
    (
        "matrixSoundFile",
        &[
            ("matrix_pulse.wav", "Pulse"),
            ("matrix_zip.wav", "Zip"),
            ("matrix_shield.wav", "Shield"),
            ("matrix_recharge.wav", "Recharge"),
            ("matrix_lock.wav", "Lock"),
        ],
    ),
    (
        "matrixBreakSoundFile",
        &[
            ("matrix_break_pufferfish.wav", "Pufferfish"),
            ("matrix_break_core.wav", "Core down"),
            ("matrix_break_collapse.wav", "Collapse"),
            ("matrix_break_shatter.wav", "Shatter"),
            ("matrix_break_brownout.wav", "Brownout"),
            ("matrix_break_reactor.wav", "Reactor"),
        ],
    ),
];

/// The sounds as the settings say (Java `Sound`'s flags and volume, `ItemSound`, `StartupSound`).
pub struct Sounds<'a>(pub &'a lg_app::properties::Properties);

impl Sounds<'_> {
    fn on(&self, key: &str, by_default: bool) -> bool {
        self.0.get(key).map_or(by_default, |v| v == "true")
    }

    /// Java `Sound.volume`: the one volume of all sounds (0..100).
    pub fn volume(&self) -> i32 {
        self.0.get("soundVolume").and_then(|v| v.trim().parse().ok()).filter(|v| (0..=100).contains(v)).unwrap_or(77)
    }

    pub fn play(&self, file: &str) {
        play(file, self.volume());
    }

    /// A bag that dropped (LootGUI): its colour's sound, if switched on.
    pub fn bag(&self, object_type: i32) {
        let (file, key, by_default) = match object_type {
            1292 | 1296 => ("whitebag.wav", "whiteBagSound", true),
            1295 | 1727 => ("orangebag.wav", "orangeBagSound", true),
            1708 | 1728 => ("redbag.wav", "redBagSound", false),
            1294 | 1724 => ("goldbag.wav", "goldBagSound", false),
            1288 | 1723 => ("eggbag.wav", "eggBagSound", false),
            1291 | 1726 => ("bluebag.wav", "blueBagSound", false),
            _ => return,
        };
        if self.on(key, by_default) {
            self.play(file);
        }
    }

    /// A chat line (Java `updateChat`): guild, party and private messages of others by their switches, else the
    /// ping list ("exact word" in quotes, else anywhere in the text).
    pub fn chat(&self, channel: lg_app::chat::Channel, from_me: bool, text: &str) {
        use lg_app::chat::Channel;
        let switch = match channel {
            Channel::Guild => Some(("guild.wav", "chatPingGuild")),
            Channel::Party => Some(("party.wav", "chatPingParty")),
            Channel::Pm => Some(("pm.wav", "chatPing")),
            _ => None,
        };
        if let Some((file, key)) = switch
            && !from_me
            && self.on(key, false)
        {
            self.play(file);
            return;
        }
        let lower = text.to_lowercase();
        for s in self.0.get("chatPingMessages").unwrap_or("").split('\u{00A7}').filter(|s| !s.is_empty()) {
            let hit = if s.len() >= 2 && s.starts_with('"') && s.ends_with('"') {
                let exact = s[1..s.len() - 1].to_lowercase();
                lower.split(' ').any(|w| w == exact)
            } else {
                lower.contains(&s.to_lowercase())
            };
            if hit {
                self.play("pm.wav");
                return;
            }
        }
    }

    pub fn trade(&self) {
        if self.on("tradePing", false) {
            self.play("trade.wav");
        }
    }

    /// Java `ItemSound.play`: the sound chosen for it (off, a bundled one, or your WAV file; a missing file: the
    /// default) at its own share of the volume.
    pub fn item(&self, key: &str) {
        let Some((_, builtins)) = ITEM_SOUNDS.iter().find(|(k, _)| *k == key) else { return };
        let setting = self.0.get(key).map_or("", str::trim);
        if setting == "off" {
            return;
        }
        let file = if setting.is_empty() {
            builtins[0].0.to_string()
        } else if let Some(name) = setting.strip_prefix("builtin:") {
            builtins.iter().find(|b| b.1.eq_ignore_ascii_case(name)).unwrap_or(&builtins[0]).0.to_string()
        } else if std::path::Path::new(setting).is_file() {
            setting.to_string()
        } else {
            builtins[0].0.to_string()
        };
        let own = self.0.get(&format!("{key}Volume")).and_then(|v| v.trim().parse::<i32>().ok()).map_or(100, |v| v.clamp(0, 100));
        // Java adds the dB of both: the amplitudes multiply
        play(&file, self.volume() * own / 100);
    }

    /// Java `StartupSound.play`.
    pub fn startup(&self, id: &str) {
        let file = match id {
            "goblinCackle" => "goblin-cackle.wav",
            "goblinGobble" => "goblin-gobble.wav",
            "pm" => "pm.wav",
            "whitebag" => "whitebag.wav",
            "goldbag" => "goldbag.wav",
            _ => return,
        };
        self.play(file);
    }

    /// The startup sound saved (default: the gold bag).
    pub fn saved_startup(&self) -> &str {
        match self.0.get("startupSound") {
            Some(id @ ("off" | "goblinCackle" | "goblinGobble" | "pm" | "whitebag" | "goldbag")) => id,
            _ => "goldbag",
        }
    }
}
