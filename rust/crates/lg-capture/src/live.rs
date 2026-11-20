//! Capturing live (Java: `Sniffer`, through RealmShark's libpcap binding): libpcap (Linux, macOS) or Npcap (Windows)
//! loaded at runtime, every usable interface opened with the game's filter (`tcp port 2050`), and the first interface
//! that shows one of its packets kept: the others are closed.
//!
//! As in Java: on Linux and macOS only interfaces with an IPv4 address that is neither loopback nor link-local are
//! used (the others are pseudo devices, or would see every packet twice); frames are taken as they come (no TCP
//! checksum check: routers and network cards fill them in). Unlike Java, which waits up to 60 s for a buffer to fill
//! on some systems, frames are handed on at once (libpcap's immediate mode).

use libloading::{Library, Symbol};
use std::ffi::{CStr, CString, c_char, c_int, c_long, c_uint, c_void};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};

/// What capturing needs from the system and did not get.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// No libpcap / Npcap to load.
    NoLibrary(String),
    /// Opening interfaces was refused: capturing needs rights (root or the capture capabilities on Linux, an
    /// administrator install of Npcap on Windows).
    Permission(String),
    /// Nothing could be opened.
    NoInterface(String),
    Other(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            Error::NoLibrary(e) => write!(f, "no packet capture library (libpcap / Npcap): {e}"),
            Error::Permission(e) => write!(f, "no permission to capture: {e}"),
            Error::NoInterface(e) => write!(f, "no network interface could be opened for capturing: {e}"),
            Error::Other(e) => write!(f, "{e}"),
        }
    }
}

/// One captured frame: its interface's link type, when (µs since 1970), the bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub interface: usize,
    pub link_type: u32,
    pub micros: u64,
    pub data: Vec<u8>,
}

/// An interface as libpcap lists it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Interface {
    pub name: String,
    pub description: Option<String>,
    pub ipv4: Vec<[u8; 4]>,
}

impl Interface {
    /// Java: the interfaces worth opening on Linux and macOS (an IPv4 address that is not loopback or link-local).
    pub fn usable(&self) -> bool {
        self.ipv4.iter().any(|a| a[0] != 127 && !(a[0] == 169 && a[1] == 254))
    }
}

#[repr(C)]
struct PcapIf {
    next: *mut PcapIf,
    name: *const c_char,
    description: *const c_char,
    addresses: *mut PcapAddr,
    flags: c_uint,
}

#[repr(C)]
struct PcapAddr {
    next: *mut PcapAddr,
    addr: *const u8,
    netmask: *const u8,
    broadaddr: *const u8,
    dstaddr: *const u8,
}

#[cfg(target_os = "macos")]
#[repr(C)]
struct Timeval {
    sec: c_long,
    usec: i32,
}

#[cfg(not(target_os = "macos"))]
#[repr(C)]
struct Timeval {
    sec: c_long,
    usec: c_long,
}

#[repr(C)]
struct PktHdr {
    ts: Timeval,
    caplen: u32,
    len: u32,
}

#[repr(C)]
struct BpfProgram {
    len: c_uint,
    insns: *mut c_void,
}

type Handle = *mut c_void;

/// libpcap's functions.
struct Pcap {
    lib: Library,
}

const ERRBUF: usize = 256;

impl Pcap {
    fn load() -> Result<Pcap, Error> {
        let names: &[&str] = if cfg!(windows) {
            &[r"C:\Windows\System32\Npcap\wpcap.dll", "wpcap.dll"]
        } else if cfg!(target_os = "macos") {
            &["libpcap.A.dylib", "libpcap.dylib"]
        } else {
            &["libpcap.so.1", "libpcap.so", "libpcap.so.0.8"]
        };
        let mut last = String::new();
        for n in names {
            // SAFETY: loading the system's libpcap runs its initialisers, which are made to be loaded this way
            match unsafe { Library::new(n) } {
                Ok(lib) => return Ok(Pcap { lib }),
                Err(e) => last = e.to_string(),
            }
        }
        Err(Error::NoLibrary(last))
    }

    fn sym<T>(&self, name: &[u8]) -> Result<Symbol<'_, T>, Error> {
        // SAFETY: the types given are libpcap's documented signatures
        unsafe { self.lib.get(name) }.map_err(|e| Error::NoLibrary(e.to_string()))
    }

    fn err(buf: &[u8]) -> String {
        CStr::from_bytes_until_nul(buf).map_or_else(|_| "unknown error".into(), |s| s.to_string_lossy().into_owned())
    }

    fn interfaces(&self) -> Result<Vec<Interface>, Error> {
        let find: Symbol<unsafe extern "C" fn(*mut *mut PcapIf, *mut c_char) -> c_int> = self.sym(b"pcap_findalldevs\0")?;
        let free: Symbol<unsafe extern "C" fn(*mut PcapIf)> = self.sym(b"pcap_freealldevs\0")?;
        let mut errbuf = [0u8; ERRBUF];
        let mut list: *mut PcapIf = std::ptr::null_mut();
        // SAFETY: as documented: a list to free with pcap_freealldevs, or -1 and the error
        if unsafe { find(&mut list, errbuf.as_mut_ptr().cast()) } != 0 {
            return Err(Error::Other(Self::err(&errbuf)));
        }
        let mut out = Vec::new();
        let mut cur = list;
        while !cur.is_null() {
            // SAFETY: a node of the list libpcap made, valid until it is freed below
            let i = unsafe { &*cur };
            let text = |p: *const c_char| (!p.is_null()).then(|| unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned());
            let mut ipv4 = Vec::new();
            let mut a = i.addresses;
            while !a.is_null() {
                // SAFETY: the same list's address nodes, and their sockaddrs
                let addr = unsafe { &*a };
                if !addr.addr.is_null() {
                    let family = if cfg!(target_os = "macos") {
                        u16::from(unsafe { *addr.addr.add(1) }) // BSD: sa_len, then the family byte
                    } else {
                        u16::from_ne_bytes(unsafe { [*addr.addr, *addr.addr.add(1)] })
                    };
                    if family == 2 {
                        // AF_INET: sockaddr_in's address at offset 4
                        ipv4.push(unsafe { [*addr.addr.add(4), *addr.addr.add(5), *addr.addr.add(6), *addr.addr.add(7)] });
                    }
                }
                a = addr.next;
            }
            out.push(Interface { name: text(i.name).unwrap_or_default(), description: text(i.description), ipv4 });
            cur = i.next;
        }
        // SAFETY: the list from pcap_findalldevs, not used after this
        unsafe { free(list) };
        Ok(out)
    }

    /// Opens an interface for capturing (or, with `file`, a capture file), with the filter.
    fn open(&self, source: &str, file: bool, filter: &str) -> Result<Handle, Error> {
        let mut errbuf = [0u8; ERRBUF];
        let c = CString::new(source).map_err(|e| Error::Other(e.to_string()))?;
        let h: Handle = if file {
            let open: Symbol<unsafe extern "C" fn(*const c_char, *mut c_char) -> Handle> = self.sym(b"pcap_open_offline\0")?;
            // SAFETY: a file name and an error buffer of PCAP_ERRBUF_SIZE
            unsafe { open(c.as_ptr(), errbuf.as_mut_ptr().cast()) }
        } else {
            let create: Symbol<unsafe extern "C" fn(*const c_char, *mut c_char) -> Handle> = self.sym(b"pcap_create\0")?;
            // SAFETY: as above
            unsafe { create(c.as_ptr(), errbuf.as_mut_ptr().cast()) }
        };
        if h.is_null() {
            return Err(Error::Other(Self::err(&errbuf)));
        }
        let close: Symbol<unsafe extern "C" fn(Handle)> = self.sym(b"pcap_close\0")?;
        let fail = |e: Error| -> Error {
            // SAFETY: the handle opened above, not used after this
            unsafe { close(h) };
            e
        };
        if !file {
            let set_int = |name: &[u8], v: c_int| -> Result<(), Error> {
                let f: Symbol<unsafe extern "C" fn(Handle, c_int) -> c_int> = self.sym(name)?;
                // SAFETY: setting an option of a created, not yet activated handle
                unsafe { f(h, v) };
                Ok(())
            };
            set_int(b"pcap_set_snaplen\0", 65536).map_err(fail)?;
            set_int(b"pcap_set_promisc\0", 1).map_err(fail)?;
            set_int(b"pcap_set_timeout\0", 500).map_err(fail)?;
            set_int(b"pcap_set_immediate_mode\0", 1).map_err(fail)?;
            let activate: Symbol<unsafe extern "C" fn(Handle) -> c_int> = self.sym(b"pcap_activate\0").map_err(fail)?;
            // SAFETY: the created handle; negative: an error (warnings are positive)
            let r = unsafe { activate(h) };
            if r < 0 {
                let e = self.handle_error(h);
                // PCAP_ERROR_PERM_DENIED (-8), PCAP_ERROR_PROMISC_PERM_DENIED (-11)
                return Err(fail(if r == -8 || r == -11 { Error::Permission(e) } else { Error::Other(e) }));
            }
        }
        let compile: Symbol<unsafe extern "C" fn(Handle, *mut BpfProgram, *const c_char, c_int, u32) -> c_int> =
            self.sym(b"pcap_compile\0").map_err(fail)?;
        let setfilter: Symbol<unsafe extern "C" fn(Handle, *mut BpfProgram) -> c_int> = self.sym(b"pcap_setfilter\0").map_err(fail)?;
        let freecode: Symbol<unsafe extern "C" fn(*mut BpfProgram)> = self.sym(b"pcap_freecode\0").map_err(fail)?;
        let f = CString::new(filter).map_err(|e| fail(Error::Other(e.to_string())))?;
        let mut prog = BpfProgram { len: 0, insns: std::ptr::null_mut() };
        // SAFETY: compiling into a program freed with pcap_freecode; PCAP_NETMASK_UNKNOWN
        if unsafe { compile(h, &mut prog, f.as_ptr(), 1, 0xffff_ffff) } != 0 {
            return Err(fail(Error::Other(self.handle_error(h))));
        }
        // SAFETY: the program just compiled
        let r = unsafe { setfilter(h, &mut prog) };
        unsafe { freecode(&mut prog) };
        if r != 0 {
            return Err(fail(Error::Other(self.handle_error(h))));
        }
        Ok(h)
    }

    fn handle_error(&self, h: Handle) -> String {
        let Ok(geterr) = self.sym::<unsafe extern "C" fn(Handle) -> *const c_char>(b"pcap_geterr\0") else { return "unknown error".into() };
        // SAFETY: the handle's own error text
        let p = unsafe { geterr(h) };
        if p.is_null() { "unknown error".into() } else { unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned() }
    }

    fn datalink(&self, h: Handle) -> u32 {
        let Ok(f) = self.sym::<unsafe extern "C" fn(Handle) -> c_int>(b"pcap_datalink\0") else { return 1 };
        // SAFETY: an open handle
        unsafe { f(h) as u32 }
    }

    /// The handle's frames, until `stop` or its end: each handed to `out`.
    fn read(&self, h: Handle, stop: &AtomicBool, mut out: impl FnMut(u64, &[u8]) -> bool) -> Result<(), Error> {
        let next: Symbol<unsafe extern "C" fn(Handle, *mut *const PktHdr, *mut *const u8) -> c_int> = self.sym(b"pcap_next_ex\0")?;
        while !stop.load(Ordering::Relaxed) {
            let (mut hdr, mut data): (*const PktHdr, *const u8) = (std::ptr::null(), std::ptr::null());
            // SAFETY: an open handle; the header and data stay valid until the next call
            match unsafe { next(h, &mut hdr, &mut data) } {
                1 => {
                    let hd = unsafe { &*hdr };
                    let bytes = unsafe { std::slice::from_raw_parts(data, hd.caplen as usize) };
                    // the time's fields are 32 bits on Windows (and its microseconds on macOS), 64 on Linux
                    #[allow(clippy::useless_conversion)]
                    let micros = i64::from(hd.ts.sec).max(0) as u64 * 1_000_000 + i64::from(hd.ts.usec).max(0) as u64;
                    if !out(micros, bytes) {
                        return Ok(());
                    }
                }
                0 => {}              // the timeout: look at stop again
                -2 => return Ok(()), // the end of a file
                _ => return Err(Error::Other(self.handle_error(h))),
            }
        }
        Ok(())
    }

    fn close(&self, h: Handle) {
        if let Ok(close) = self.sym::<unsafe extern "C" fn(Handle)>(b"pcap_close\0") {
            // SAFETY: an open handle, not used after this
            unsafe { close(h) };
        }
    }
}

/// The interfaces libpcap lists.
pub fn interfaces() -> Result<Vec<Interface>, Error> {
    Pcap::load()?.interfaces()
}

/// A capture running: its frames come through `frames`; dropped (or `stop`), every interface is closed.
pub struct Capture {
    pub frames: mpsc::Receiver<Frame>,
    stop: Arc<AtomicBool>,
    threads: Vec<std::thread::JoinHandle<()>>,
    /// The interface the game's packets came on (usize::MAX until one did).
    pub chosen: Arc<AtomicUsize>,
    /// The interfaces opened, by index.
    pub opened: Vec<Interface>,
}

impl Capture {
    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Raw pointers handed to the capture threads (libpcap handles may be used from the thread that reads them).
struct SendHandle(Handle);
// SAFETY: each handle is read and closed by one thread only
unsafe impl Send for SendHandle {}

/// Starts capturing on every usable interface with `filter` (Java: port 2050, `tcp port 2050`). The first interface
/// that yields a frame is kept, the others stop.
pub fn start(filter: &str) -> Result<Capture, Error> {
    let pcap = Arc::new(Pcap::load()?);
    let all = pcap.interfaces()?;
    let only_ipv4 = cfg!(target_os = "linux") || cfg!(target_os = "macos");
    let (tx, rx) = mpsc::channel();
    let stop = Arc::new(AtomicBool::new(false));
    let chosen = Arc::new(AtomicUsize::new(usize::MAX));
    let (mut threads, mut opened) = (Vec::new(), Vec::new());
    let mut refused: Option<String> = None;
    let mut last: Option<String> = None;
    for iface in all {
        if only_ipv4 && !iface.usable() {
            continue;
        }
        let h = match pcap.open(&iface.name, false, filter) {
            Ok(h) => h,
            Err(Error::Permission(e)) => {
                refused = Some(format!("{}: {e}", iface.name));
                continue;
            }
            Err(e) => {
                last = Some(format!("{}: {e}", iface.name));
                continue;
            }
        };
        let index = opened.len();
        opened.push(iface);
        let (pcap, stop, chosen, tx, h) = (pcap.clone(), stop.clone(), chosen.clone(), tx.clone(), SendHandle(h));
        threads.push(std::thread::spawn(move || {
            let h = h;
            let link_type = pcap.datalink(h.0);
            let _ = pcap.read(h.0, &stop, |micros, data| {
                // the first interface with a frame is the game's; the others end
                let c = chosen.compare_exchange(usize::MAX, index, Ordering::SeqCst, Ordering::SeqCst).unwrap_or_else(|v| v);
                if c != index && c != usize::MAX {
                    return false;
                }
                tx.send(Frame { interface: index, link_type, micros, data: data.to_vec() }).is_ok()
            });
            pcap.close(h.0);
        }));
    }
    if opened.is_empty() {
        return Err(match (refused, last) {
            (Some(r), _) => Error::Permission(r),
            (None, l) => Error::NoInterface(l.unwrap_or_else(|| "none usable".into())),
        });
    }
    Ok(Capture { frames: rx, stop, threads, chosen, opened })
}

/// A capture file read through libpcap with `filter` (to check this module without capture rights).
pub fn read_file(path: &str, filter: &str) -> Result<Vec<Frame>, Error> {
    let pcap = Pcap::load()?;
    let h = pcap.open(path, true, filter)?;
    let link_type = pcap.datalink(h);
    let frames = Mutex::new(Vec::new());
    let stop = AtomicBool::new(false);
    let r = pcap.read(h, &stop, |micros, data| {
        frames.lock().expect("frames").push(Frame { interface: 0, link_type, micros, data: data.to_vec() });
        true
    });
    pcap.close(h);
    r?;
    Ok(frames.into_inner().expect("frames"))
}
