//! Optional log files (Java `AppLog`): when turned on in Settings, everything the app reports is also written to
//! one file per day, "LootGobbler-2026-11-02.log", in the chosen folder ("logs" next to the app unless changed).
//! Off by default. Files older than the chosen number of days (14 unless changed, 0 = keep all) are deleted.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const ENABLED_KEY: &str = "logging";
pub const DIR_KEY: &str = "logDir";
pub const KEEP_KEY: &str = "logKeepDays";
pub const DEFAULT_KEEP_DAYS: i32 = 14;
const PREFIX: &str = "LootGobbler-";
const SUFFIX: &str = ".log";

struct State {
    enabled: bool,
    dir: PathBuf,
    keep_days: i32,
    file: Option<(String, std::fs::File)>,
    zone: Option<crate::localtime::Zone>,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// Takes the settings (on start, then `banner`; and whenever one of them changes); `default_dir` is "logs" next to
/// the app.
pub fn configure(props: &crate::properties::Properties, default_dir: &Path) {
    let enabled = props.get(ENABLED_KEY) == Some("true");
    let dir = props.get(DIR_KEY).filter(|d| !d.trim().is_empty()).map_or(default_dir.to_path_buf(), PathBuf::from);
    let keep_days = props.get(KEEP_KEY).and_then(|k| k.trim().parse::<i32>().ok()).map_or(DEFAULT_KEEP_DAYS, |k| k.max(0));
    let mut s = STATE.lock().unwrap_or_else(|e| e.into_inner());
    let was_on = s.as_ref().is_none_or(|s| s.enabled); // on start: the banner comes from the caller
    let changed_dir = s.as_ref().is_some_and(|s| s.dir != dir);
    let zone = s.as_mut().and_then(|s| s.zone.take()).unwrap_or_else(crate::localtime::Zone::system);
    let file = if changed_dir || !enabled { None } else { s.as_mut().and_then(|s| s.file.take()) };
    *s = Some(State { enabled, dir: dir.clone(), keep_days, file, zone: Some(zone) });
    drop(s);
    if enabled && !was_on {
        line("app", &format!("Logging turned on ({})", dir.display()));
        banner();
    }
}

/// What a bug report needs first: version, system, where the app runs from.
pub fn banner() {
    line("app", &format!("Loot Gobbler {} starting", env!("CARGO_PKG_VERSION")));
    let cores = std::thread::available_parallelism().map_or(0, |n| n.get());
    line("app", &format!("Rust build, {} {}, {cores} cores", std::env::consts::OS, std::env::consts::ARCH));
    if let Ok(exe) = std::env::current_exe() {
        line("app", &format!("Folder {}", exe.parent().map_or(exe.clone(), Path::to_path_buf).display()));
    }
}

pub fn enabled() -> bool {
    STATE.lock().ok().and_then(|s| s.as_ref().map(|s| s.enabled)).unwrap_or(false)
}

pub fn dir() -> PathBuf {
    STATE.lock().ok().and_then(|s| s.as_ref().map(|s| s.dir.clone())).unwrap_or_else(|| PathBuf::from("logs"))
}

/// One log line, "[area] message", on the console and in the file.
pub fn line(area: &str, message: &str) {
    let text = format!("[{area}] {message}");
    println!("{text}");
    write("", &text);
}

/// An error: on the console's error stream and in the file, marked "ERR " (and written at once).
pub fn err(message: &str) {
    eprintln!("{message}");
    write("ERR ", message);
}

fn write(prefix: &str, text: &str) {
    let mut guard = STATE.lock().unwrap_or_else(|e| e.into_inner());
    let Some(s) = guard.as_mut() else { return };
    if !s.enabled {
        return;
    }
    let now = now_ms();
    let zone = s.zone.get_or_insert_with(crate::localtime::Zone::system);
    let (y, m, d, h, mi, sec) = zone.fields(now);
    let day = format!("{y:04}-{m:02}-{d:02}");
    if s.file.as_ref().is_none_or(|(fd, _)| *fd != day) {
        s.file = None;
        if std::fs::create_dir_all(&s.dir).is_err() {
            s.enabled = false; // folder not writable: stop rather than fail on every line
            return;
        }
        prune(&s.dir, s.keep_days, zone, now); // at start and at midnight
        match std::fs::OpenOptions::new().create(true).append(true).open(s.dir.join(format!("{PREFIX}{day}{SUFFIX}"))) {
            Ok(f) => s.file = Some((day, f)),
            Err(_) => {
                s.enabled = false;
                return;
            }
        }
    }
    let nl = if cfg!(windows) { "\r\n" } else { "\n" };
    if let Some((_, f)) = &mut s.file
        && f.write_all(format!("{h:02}:{mi:02}:{sec:02} {prefix}{text}{nl}").as_bytes()).is_err()
    {
        s.enabled = false;
    }
}

/// Deletes the log files of days older than `keep` days; today's always stays.
fn prune(dir: &Path, keep: i32, zone: &crate::localtime::Zone, now: i64) {
    if keep == 0 {
        return;
    }
    let (y, m, d, ..) = zone.fields(now - i64::from(keep - 1) * 86_400_000);
    let oldest = format!("{y:04}-{m:02}-{d:02}");
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for e in entries.flatten() {
        let n = e.file_name().to_string_lossy().to_string();
        let Some(day) = n.strip_prefix(PREFIX).and_then(|r| r.strip_suffix(SUFFIX)) else { continue };
        let is_day = day.len() == 10 && day.chars().enumerate().all(|(i, c)| if i == 4 || i == 7 { c == '-' } else { c.is_ascii_digit() });
        if is_day && *day < *oldest.as_str() {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

/// The log files in the log folder, newest first.
pub fn files() -> Vec<PathBuf> {
    let dir = dir();
    let Ok(entries) = std::fs::read_dir(&dir) else { return Vec::new() };
    let mut out: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.file_name().map(|n| n.to_string_lossy()).is_some_and(|n| n.starts_with(PREFIX) && n.ends_with(SUFFIX)))
        .collect();
    out.sort();
    out.reverse();
    out
}
