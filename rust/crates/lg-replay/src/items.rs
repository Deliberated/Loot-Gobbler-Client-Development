//! A made-up session about items, for comparing the app's records with Java's (collection, holdings, the UPE rules):
//! a fresh character in the vault, then items taken from and put into the vault's chests, bags picked up, potions
//! drunk, forging, trades, quests, purchases, the raw use packets, dropping and feeding, a map change and a second
//! vault visit. Paced like real play, so the app's "every 5 s" work runs.
//!
//! The items are real ones (from a real vault, so the game data knows them); without game data they are just
//! numbers, which exercises the other paths.

use crate::fight::{B, S, six_bit, status, write};
use crate::synth::{Plain, Rng, Session};
use lg_protocol::Direction;

const ME: i32 = 1000;
const CHAR: i32 = 42;
const VAULT: i32 = 9001;
const GIFTS: i32 = 9002;
const POTIONS: i32 = 9003;
const SPOILS: i32 = 9004;
const MATERIALS: i32 = 9005;
const POOL: [i32; 15] = [14257, 3274, 5298, 8386, 65518, 1221, 25734, 5272, 14866, 8254, 8791, 2580, 2723, 2680, 2815];
const POTION_ITEMS: [i32; 4] = [2793, 2591, 2636, 9070];

fn slot(b: B, (obj, slot, kind): (i32, i32, i32)) -> B {
    b.i32(obj).i32(slot).i32(kind)
}

struct Items {
    rng: Rng,
    out: Vec<(Direction, Plain)>,
    tick: i32,
    /// My 12 slots (equipment, inventory).
    mine: [i32; 12],
    vault: Vec<i32>,
    gifts: Vec<i32>,
    potions: Vec<i32>,
    next_bag: i32,
    in_vault: bool,
}

impl Items {
    fn send(&mut self, d: Direction, id: u8, b: B) {
        self.out.push((d, Plain { id, body: b.0 }));
    }
    fn below(&mut self, n: usize) -> usize {
        self.rng.below(n)
    }
    fn pick<T: Copy>(&mut self, v: &[T]) -> T {
        v[self.rng.below(v.len())]
    }
    fn code(&mut self) -> String {
        if self.below(2) == 0 {
            return String::new();
        }
        six_bit(&[0, 0x02, 0x04, self.below(250) as u8 + 1, 0x01, 0xff, 0xff, 0xfd, 0xff])
    }

    fn me_stats(&mut self, full: bool) -> Vec<S> {
        let mut v = vec![S::N(7, 1), S::N(24, 1), S::N(25, 0)];
        for (i, it) in self.mine.iter().enumerate() {
            v.push(S::N(8 + i as u8, *it));
        }
        let codes: Vec<String> = (0..12).map(|_| self.code()).collect();
        v.push(S::T(80, codes.join(",")));
        if full {
            v.extend([S::N(0, 150), S::N(1, 150), S::N(3, 100), S::N(4, 100), S::T(6, "0".into()), S::T(31, "Tester".into())]);
            for t in [20u8, 21, 22, 26, 27, 28] {
                v.push(S::N(t, 10));
            }
            for t in [46u8, 47, 48, 49, 50, 51, 52, 53] {
                v.push(S::N(t, 0));
            }
            v.extend([S::N(35, 0), S::N(113, 1000), S::N(116, -1), S::N(117, -1), S::N(118, -1)]);
        }
        v
    }

    /// A tick: me (my slots as they are now) and any other statuses.
    fn tick(&mut self, mut others: Vec<Vec<u8>>) {
        self.tick += 1;
        let me = self.me_stats(false);
        others.insert(0, status(ME, (10.0, 10.0), &me));
        let mut b = B::default().i32(self.tick).i32(self.tick * 200).i32(70_000_000 + self.tick * 200).u16(30).i16(others.len() as i16);
        for o in &others {
            b = b.raw(o);
        }
        self.send(Direction::Incoming, 10, b);
        let rec = B::default().i16(1).i32(self.tick * 200).pos((10.0, 10.0));
        self.send(Direction::Outgoing, 62, B::default().i32(self.tick).i32(self.tick * 200).raw(&rec.0));
    }

    fn chest_status(&self, obj: i32, contents: &[i32]) -> Vec<u8> {
        let v: Vec<S> = (0..8).map(|k| S::N(8 + k as u8, contents.get(k).copied().unwrap_or(-1))).collect();
        status(obj, (12.0, 12.0), &v)
    }

    fn vault_packet(&mut self) {
        let arr = |b: B, v: &[i32]| {
            let mut b = b.ci(v.len() as i32);
            for x in v {
                b = b.ci(*x);
            }
            b
        };
        let codes: Vec<String> = (0..self.vault.len()).map(|_| self.code()).collect();
        let gift_codes: Vec<String> = (0..self.gifts.len()).map(|_| self.code()).collect();
        let b = B::default().bool(true).ci(VAULT).ci(MATERIALS).ci(GIFTS).ci(POTIONS).ci(SPOILS);
        let b = arr(b, &self.vault.clone());
        let b = arr(b, &[5272, -1, 1221]);
        let b = arr(b, &self.gifts.clone());
        let b = arr(b, &self.potions.clone());
        let b = arr(b, &[]);
        let b = b.i16(0).i16(0).i16(0).i16(10).i16(20).i16(0).str(&codes.join(",")).str(&gift_codes.join(",")).str("");
        self.send(Direction::Incoming, 117, b);
    }

    fn enter(&mut self, vault: bool) {
        self.in_vault = vault;
        let (name, display) = if vault { ("Vault", "{s.vault}") } else { ("Nexus", "{s.nexus}") };
        let b = B::default()
            .i32(64)
            .i32(64)
            .str(name)
            .str(display)
            .str("")
            .i32(self.tick)
            .i32(0)
            .f32(1.0)
            .bool(true)
            .bool(false)
            .bool(true)
            .i16(1)
            .i32(0)
            .str("7.2")
            .i16(0)
            .i16(15)
            .bool(false)
            .i32(0)
            .str("")
            .i16(0);
        self.send(Direction::Incoming, 92, b);
        self.send(Direction::Incoming, 101, B::default().i32(ME).i32(CHAR).str("AAAAAAAA"));
        let me = self.me_stats(true);
        let mut objects = vec![B::default().u16(0x0300).raw(&status(ME, (10.0, 10.0), &me)).0];
        if vault {
            for (obj, kind, contents) in
                [(VAULT, 0x0504u16, self.vault.clone()), (GIFTS, 0x0744, self.gifts.clone()), (POTIONS, 0x0745, self.potions.clone())]
            {
                objects.push(B::default().u16(kind).raw(&self.chest_status(obj, &contents)).0);
            }
        }
        let mut b = B::default().pos((10.0, 10.0)).i8(0).ci(0).ci(objects.len() as i32);
        for o in &objects {
            b = b.raw(o);
        }
        self.send(Direction::Incoming, 42, b.ci(0));
        if vault {
            self.vault_packet();
        }
    }

    fn free(&mut self) -> Option<usize> {
        let free: Vec<usize> = (4..12).filter(|&i| self.mine[i] < 1).collect();
        (!free.is_empty()).then(|| self.pick(&free))
    }

    fn held(&mut self) -> Option<usize> {
        let held: Vec<usize> = (0..12).filter(|&i| self.mine[i] > 0).collect();
        (!held.is_empty()).then(|| self.pick(&held))
    }

    fn swap(&mut self, from: (i32, i32, i32), to: (i32, i32, i32), now_from: i32, now_to: i32, ok: bool) {
        self.send(Direction::Outgoing, 55, slot(slot(B::default().i32(0).pos((10.0, 10.0)), from), to));
        let b = slot(slot(B::default().bool(ok).i8(0), (from.0, from.1, now_from)), (to.0, to.1, now_to)).i32(0).i32(0);
        self.send(Direction::Incoming, 95, b);
    }

    fn round(&mut self) {
        let mut others = Vec::new();
        match self.below(14) {
            // take from the vault chest
            0 | 1 if self.in_vault => {
                let k = self.below(self.vault.len());
                if let (Some(s), true) = (self.free(), self.vault[k] > 0) {
                    let item = self.vault[k];
                    self.swap((VAULT, k as i32, item), (ME, s as i32, -1), -1, item, true);
                    self.vault[k] = -1;
                    self.mine[s] = item;
                    others.push(self.chest_status(VAULT, &self.vault.clone()));
                }
            }
            // put into the vault chest
            2 if self.in_vault => {
                if let Some(s) = self.held() {
                    let k = self.below(self.vault.len());
                    let (mine, theirs) = (self.mine[s], self.vault[k]);
                    let ok = self.below(5) > 0;
                    self.swap((ME, s as i32, mine), (VAULT, k as i32, theirs), theirs, mine, ok);
                    self.mine[s] = theirs;
                    self.vault[k] = mine;
                }
            }
            // a gift taken
            3 if self.in_vault && !self.gifts.is_empty() => {
                if let Some(s) = self.free() {
                    let k = self.below(self.gifts.len());
                    let item = self.gifts.remove(k);
                    self.swap((GIFTS, k as i32, item), (ME, s as i32, -1), -1, item, true);
                    self.mine[s] = item;
                }
            }
            // a bag dropped for me, an item picked up from it
            4 | 5 => {
                self.next_bag += 1;
                let bag = self.next_bag;
                let item = self.pick(&POOL);
                let st = [S::N(8, item), S::N(9, -1), S::T(80, self.code())];
                let o = B::default().u16(1294).raw(&status(bag, (10.5, 10.0), &st)).0;
                self.send(Direction::Incoming, 42, B::default().pos((10.0, 10.0)).i8(0).ci(0).ci(1).raw(&o).ci(0));
                if let Some(s) = self.free() {
                    self.swap((bag, 0, item), (ME, s as i32, -1), -1, item, true);
                    self.mine[s] = item;
                }
            }
            // a potion: drunk from my slot, or from the potion storage
            6 => {
                let potion = self.pick(&POTION_ITEMS);
                if self.in_vault && self.below(2) == 0 {
                    let k = self.below(self.potions.len().max(1)) as i32;
                    let b = slot(B::default().i32(0), (POTIONS, k, potion)).pos((10.0, 10.0)).i8(0).i32(0);
                    self.send(Direction::Outgoing, 13, b);
                } else if let Some(s) = self.free() {
                    self.mine[s] = potion;
                    self.tick(Vec::new());
                    let b = slot(B::default().i32(0), (ME, s as i32, potion)).pos((10.0, 10.0)).i8(0).i32(0);
                    self.send(Direction::Outgoing, 13, b);
                    self.mine[s] = -1;
                }
            }
            // forging two items into one
            7 => {
                let held: Vec<usize> = (0..12).filter(|&i| self.mine[i] > 0).collect();
                if held.len() >= 2 {
                    let (a, b) = (held[0], held[1]);
                    let made = self.pick(&POOL);
                    let req = B::default().i32(made).i32(2);
                    let req = slot(slot(req, (ME, a as i32, self.mine[a])), (ME, b as i32, self.mine[b]));
                    self.send(Direction::Outgoing, 118, req);
                    self.send(Direction::Incoming, 119, slot(B::default().bool(true).i8(1), (ME, a as i32, made)));
                    self.mine[a] = made;
                    self.mine[b] = -1;
                }
            }
            // a trade
            8 => {
                let item = |b: B, i: i32| b.i32(i).i32(0).bool(true).bool(false).str("");
                let mut st = B::default().i16(12);
                for m in self.mine {
                    st = item(st, m);
                }
                let got = self.pick(&POOL);
                st = item(st.str("Bob").i16(1), got).i32(5000).i8(0);
                self.send(Direction::Incoming, 86, st);
                let mut a = B::default().i16(12);
                for _ in 0..12 {
                    a = a.bool(false);
                }
                self.send(Direction::Outgoing, 36, a.i16(1).bool(true));
                self.send(Direction::Incoming, 34, B::default().i32(0).str(""));
                if let Some(s) = self.free() {
                    self.mine[s] = got;
                }
            }
            // a Tinkerer quest handed in, its reward
            9 => {
                if let Some(s) = self.held() {
                    let given = self.mine[s];
                    let reward = self.pick(&POOL);
                    self.send(Direction::Outgoing, 58, slot(B::default().str("q1").i32(7).i16(1), (ME, s as i32, given)));
                    self.send(Direction::Incoming, 96, B::default().bool(true).str(""));
                    self.mine[s] = reward;
                }
            }
            // something bought
            10 => {
                self.send(Direction::Incoming, 22, B::default().i32(0).str(""));
                if let Some(s) = self.free() {
                    self.mine[s] = self.pick(&POOL);
                }
            }
            // used up through the raw use packets (forging or feeding from the inventory)
            11 => {
                if let Some(s) = self.held() {
                    let item = self.mine[s];
                    let made = if self.below(2) == 0 { -1 } else { self.pick(&POOL) };
                    self.send(Direction::Outgoing, 195, slot(B::default().u8(1), (ME, s as i32, item)));
                    self.send(Direction::Incoming, 196, slot(B::default().u8(1).u8(1), (ME, s as i32, made)));
                    self.mine[s] = made;
                }
            }
            // dropped on the ground, or fed to a pet
            12 => {
                if let Some(s) = self.held() {
                    let item = self.mine[s];
                    if self.below(2) == 0 {
                        self.send(Direction::Outgoing, 19, slot(B::default(), (ME, s as i32, item)).i8(0));
                    } else {
                        let b = B::default().i8(2).i32(1).i32(0).i32(0).i8(0).i16(1);
                        self.send(Direction::Outgoing, 16, slot(b, (ME, s as i32, item)));
                    }
                    self.mine[s] = -1;
                }
            }
            // equip something from the inventory
            _ => {
                let inv: Vec<usize> = (4..12).filter(|&i| self.mine[i] > 0).collect();
                if !inv.is_empty() {
                    let s = self.pick(&inv);
                    let e = self.below(4);
                    let (a, b) = (self.mine[s], self.mine[e]);
                    self.swap((ME, s as i32, a), (ME, e as i32, b), b, a, true);
                    self.mine.swap(s, e);
                }
            }
        }
        self.tick(others);
    }
}

/// The session (seed: which made-up one).
pub fn items(seed: u64) -> Session {
    let mut rng = Rng(seed.max(1).wrapping_mul(0x9E37_79B9_7F4A_7C15));
    let pool = |n: usize, rng: &mut Rng| -> Vec<i32> {
        (0..n).map(|_| if rng.below(3) == 0 { -1 } else { POOL[rng.below(POOL.len())] }).collect()
    };
    let vault = pool(16, &mut rng);
    let gifts = pool(5, &mut rng).into_iter().filter(|i| *i > 0).collect();
    let potions = vec![2793, 2591, -1, 2636];
    let mut s = Items {
        rng,
        out: Vec::new(),
        tick: 0,
        mine: [2580, 2723, 2680, -1, -1, -1, -1, -1, -1, -1, -1, -1],
        vault,
        gifts,
        potions,
        next_bag: 20_000,
        in_vault: true,
    };
    for _ in 0..3 {
        s.tick(Vec::new());
    }
    s.enter(true);
    // the Tinkerer's quests, once
    let q = B::default()
        .i16(1)
        .str("q1")
        .str("Quest")
        .str("")
        .str("")
        .i32(0)
        .i32(0)
        .i16(1)
        .i32(2580)
        .i16(1)
        .i32(14257)
        .bool(false)
        .bool(false)
        .bool(true)
        .i16(0);
    s.send(Direction::Incoming, 6, q);
    for r in 0..110 {
        s.round();
        if r == 50 {
            s.enter(false);
        }
        if r == 70 {
            s.enter(true);
        }
    }
    write(&mut s.rng, &s.out, 60_000)
}
