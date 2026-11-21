//! The Loot Gobbler server's calls, each on a thread of its own (Java's asynchronous HTTP client); the answers come
//! back to the window, which hands them to the app.

use lg_app::server::{Done, Job, TestResult};
use lg_net::server::Server;
use std::sync::mpsc::{Receiver, Sender, channel};

pub struct Net {
    tx: Sender<Done>,
    pub rx: Receiver<Done>,
}

impl Default for Net {
    fn default() -> Self {
        let (tx, rx) = channel();
        Net { tx, rx }
    }
}

impl Net {
    /// Makes the call; the window is woken when its answer is there.
    pub fn run(&self, job: Job, ctx: &egui::Context) {
        let (tx, ctx) = (self.tx.clone(), ctx.clone());
        std::thread::spawn(move || {
            if let Some(d) = perform(job) {
                let _ = tx.send(d);
                ctx.request_repaint();
            }
        });
    }
}

fn perform(job: Job) -> Option<Done> {
    Some(match job {
        Job::Connect { url, token } => {
            let s = Server::new(&url, &token);
            let status = s.status();
            let me = (status.is_ok() && !token.is_empty()).then(|| s.me());
            Done::Connect { url, status, me }
        }
        Job::Test { url, token } => {
            let s = Server::new(&url, &token);
            let result = s.status().and_then(|status| {
                s.ping().map(|p| TestResult {
                    status,
                    millis: p.millis,
                    private_ip: is_private(&p.your_ip),
                    your_ip: p.your_ip,
                    user: p.user,
                    session_sent: !token.is_empty(),
                })
            });
            Done::Test { url, result }
        }
        Job::Login { url, ign, password, client } => {
            let result = Server::new(&url, "").login(&ign, &password, &client);
            Done::Login { url, result }
        }
        Job::Register { url, ign, password, role_token, client } => {
            let s = Server::new(&url, "");
            let result = s.register(&ign, &password, &role_token).and_then(|u| {
                if u.status == "active" { s.login(&ign, &password, &client).map(|(t, u)| (Some(t), u)) } else { Ok((None, u)) }
            });
            Done::Register { url, result }
        }
        Job::Logout { url, token } => {
            let _ = Server::new(&url, &token).logout();
            return None;
        }
        Job::Redeem { url, token, role_token } => {
            let result = Server::new(&url, &token).redeem(&role_token);
            Done::Redeem { url, result }
        }
        Job::Data { url, token } => {
            let result = Server::new(&url, &token).data();
            Done::Data { url, result }
        }
        Job::Put { url, token, kind, base, text } => {
            let result = Server::new(&url, &token).put_data(&kind, base, &text);
            Done::Put { url, kind, result }
        }
    })
}

/// Java `isPrivate`: a local address (only literals, no name lookups).
fn is_private(ip: &str) -> bool {
    match ip.parse::<std::net::IpAddr>() {
        Ok(std::net::IpAddr::V4(a)) => a.is_private() || a.is_loopback() || a.is_link_local(),
        Ok(std::net::IpAddr::V6(a)) => {
            let s = a.segments()[0];
            a.is_loopback() || (s & 0xffc0) == 0xfe80 || (s & 0xffc0) == 0xfec0 || (s & 0xfe00) == 0xfc00
        }
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn private_addresses() {
        assert!(super::is_private("192.168.1.5") && super::is_private("127.0.0.1") && super::is_private("fd00::1"));
        assert!(!super::is_private("8.8.8.8") && !super::is_private("example.com") && !super::is_private(""));
    }
}
