//! A made-up fight, for comparing the damage logic with Java without a real recording: a map with its seed, you (a
//! Rogue with its starting gear), other players, their pets, enemies (Oryx, Chancellor Dammah, the Forgotten King
//! among them), and tick by tick your shots and hits, the server's shots, damage, enemy shots hitting you, area and
//! ground damage, conditions coming and going, a crucible, a player leaving mid-fight, a second map.
//!
//! Packets go out in timeline order, one TCP segment each, so a shot comes before its hit.

use crate::synth::{Plain, Rng, Session, endpoints, frame};
use lg_capture::{link, pcap, tcp};
use lg_protocol::Direction;
use lg_protocol::rc4::{INCOMING_KEY, OUTGOING_KEY, Rc4};

/// A packet body, field by field (big-endian, as the game).
#[derive(Default)]
pub(crate) struct B(pub(crate) Vec<u8>);

impl B {
    pub(crate) fn i8(mut self, v: i8) -> Self {
        self.0.push(v as u8);
        self
    }
    pub(crate) fn u8(mut self, v: u8) -> Self {
        self.0.push(v);
        self
    }
    pub(crate) fn bool(self, v: bool) -> Self {
        self.u8(u8::from(v))
    }
    pub(crate) fn i16(mut self, v: i16) -> Self {
        self.0.extend(v.to_be_bytes());
        self
    }
    pub(crate) fn u16(mut self, v: u16) -> Self {
        self.0.extend(v.to_be_bytes());
        self
    }
    pub(crate) fn i32(mut self, v: i32) -> Self {
        self.0.extend(v.to_be_bytes());
        self
    }
    pub(crate) fn f32(mut self, v: f32) -> Self {
        self.0.extend(v.to_be_bytes());
        self
    }
    pub(crate) fn pos(self, (x, y): (f32, f32)) -> Self {
        self.f32(x).f32(y)
    }
    pub(crate) fn str(mut self, s: &str) -> Self {
        self.0.extend((s.len() as i16).to_be_bytes());
        self.0.extend(s.as_bytes());
        self
    }
    pub(crate) fn ci(mut self, v: i32) -> Self {
        self.0.extend(lg_protocol::packets::data::compressed(v));
        self
    }
    pub(crate) fn raw(mut self, b: &[u8]) -> Self {
        self.0.extend(b);
        self
    }
}

/// A stat: a number, or a string for the string stats.
#[derive(Clone)]
pub(crate) enum S {
    N(u8, i32),
    T(u8, String),
}

pub(crate) fn stats(list: &[S]) -> Vec<u8> {
    let mut b = B::default().ci(list.len() as i32);
    for s in list {
        b = match s {
            S::N(t, v) => b.u8(*t).ci(*v).ci(-1),
            S::T(t, v) => b.u8(*t).str(v).ci(-1),
        };
    }
    b.0
}

pub(crate) fn status(id: i32, pos: (f32, f32), list: &[S]) -> Vec<u8> {
    B::default().ci(id).pos(pos).raw(&stats(list)).0
}

const ME: i32 = 1000;
const ROGUE: u16 = 0x0300;
const ARCHER: u16 = 0x0307;
const DAGGER: u16 = 0x0a14;
const CLOAK: u16 = 0x0a56;
const ARMOR: u16 = 0x0a78;
const ENEMY_TYPES: [u16; 6] = [45363, 9635, 29039, 33656, 0x0600, 0x0650];
const ORYX_ANIMATIONS: [i32; 3] = [-935_464_302, -918_686_683, 0];
/// The game's base64 variant, for enchant codes.
pub(crate) fn six_bit(b: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut s = String::new();
    for chunk in b.chunks(3) {
        let n = (u32::from(chunk[0]) << 16) | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8) | u32::from(*chunk.get(2).unwrap_or(&0));
        for k in 0..4 {
            s.push(if k <= chunk.len() { A[(n >> (18 - 6 * k) & 63) as usize] as char } else { '=' });
        }
    }
    s
}

struct Fight {
    rng: Rng,
    out: Vec<(Direction, Plain)>,
    tick: i32,
    bullet: i16,
    my_pos: (f32, f32),
    players: Vec<i32>,
    enemies: Vec<i32>,
    minions: Vec<i32>,
    enemy_bullets: Vec<(i32, i16)>,
    /// Next object id for new enemies, bags.
    next_id: i32,
    /// Bags to come next round: where.
    bags_due: Vec<(f32, f32)>,
}

/// Skins: none, two of the game's, one an ability turns you into (Evolutionary Boar...).
const SKINS: [i32; 4] = [0, 312, 7467, 884];
/// Dye values: none, a clothing and an accessory dye (their textures by the game data), a texture itself.
const TEXTURES: [i32; 4] = [0, 0x1000, 0x1100, 0x0100_0005];
/// What your quick slots hold.
const QUICK: [i32; 2] = [2594, 2595];
/// The names the death notifications tell (one never in view).
const DEAD: [&str; 5] = ["Friend0", "Friend1", "Friend2", "Tester", "Nobody"];

const BAGS: [u16; 8] = [1287, 1289, 1291, 1292, 1294, 1295, 1708, 1724];
/// Last words that open a boss's loot window.
const LAST_WORDS: [(&str, &str); 4] = [
    ("#Void Entity", "You fools... You can never truly defeat me! I am in all of you! I AM all of you!"),
    ("#Valen the Unbreakable", "I see now... my strength could not have held against this growing power."),
    ("#Kitsune Umi", "This fully concludes the Moonlight Festival!"),
    ("#Dancer Miko", "Thank you all for coming tonight."),
];

impl Fight {
    fn send(&mut self, d: Direction, id: u8, b: B) {
        self.out.push((d, Plain { id, body: b.0 }));
    }
    fn pick<T: Copy>(&mut self, v: &[T]) -> T {
        v[self.rng.below(v.len())]
    }
    fn chance(&mut self, percent: usize) -> bool {
        self.rng.below(100) < percent
    }
    fn small(&mut self, n: usize) -> i32 {
        self.rng.below(n) as i32
    }

    /// NEWTICK and MOVE: the ticks that keep the cipher in step, with some stat changes.
    fn tick(&mut self, changes: Vec<Vec<u8>>) {
        self.tick += 1;
        let mut b = B::default().i32(self.tick).i32(self.tick * 200).i32(70_000_000 + self.tick * 200).u16(30).i16(changes.len() as i16);
        for c in &changes {
            b = b.raw(c);
        }
        self.send(Direction::Incoming, 10, b);
        let records = B::default().i16(1).i32(self.tick * 200).pos(self.my_pos);
        self.send(Direction::Outgoing, 62, B::default().i32(self.tick).i32(self.tick * 200).raw(&records.0));
    }

    fn map(&mut self, name: &str, display_name: &str, seed: i32) {
        let b = B::default()
            .i32(64)
            .i32(64)
            .str(name)
            .str(display_name)
            .str("")
            .i32(seed)
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
        self.send(Direction::Incoming, 101, B::default().i32(ME).i32(42).str("AAAAAAAA"));
    }

    fn player_stats(&mut self, name: &str, gear: [i32; 4]) -> Vec<S> {
        let mut v = vec![
            S::N(0, 700),
            S::N(1, 650),
            S::N(3, 300),
            S::N(4, 200),
            S::N(7, 20),
            S::T(6, "123456".into()),
            S::T(31, name.into()),
            S::N(20, 50 + self.small(25)),
            S::N(21, self.small(30)),
            S::N(22, 50),
            S::N(26, 40),
            S::N(27, 30 + self.small(50)),
            S::N(28, 50),
            S::N(24, 0),
            S::N(113, if self.chance(50) { 1000 } else { 1050 }),
            S::N(25, self.pick(&SKINS)),
            S::N(32, self.pick(&TEXTURES)),
            S::N(33, self.pick(&TEXTURES)),
        ];
        if let Some((_, guild)) = name.split_once(',') {
            v.push(S::T(62, guild.into()));
        }
        for (k, t) in [46u8, 47, 48, 49, 50, 51, 52, 53].iter().enumerate() {
            v.push(S::N(*t, k as i32));
        }
        for (k, g) in gear.iter().enumerate() {
            v.push(S::N(8 + k as u8, *g));
        }
        // enchant codes: none, or a weapon/ability code (header, 1026, an enchant id, end)
        let code = six_bit(&[0, 0x02, 0x04, self.small(255) as u8, 0x01, 0xfd, 0xff]);
        let codes = match self.small(3) {
            0 => String::new(),
            1 => format!("{code},{code},,"),
            _ => format!(",{code}"),
        };
        v.push(S::T(80, codes));
        v
    }

    fn setup(&mut self) {
        let mut objects = Vec::new();
        let mut me = self.player_stats("Tester", [i32::from(DAGGER), i32::from(CLOAK), i32::from(ARMOR), -1]);
        me.push(S::N(35, 100)); // gold: yours only
        me.extend([S::N(116, QUICK[0]), S::N(117, QUICK[1]), S::N(118, -1)]); // quick slots
        if self.chance(50) {
            me.push(S::T(128, "A".into()));
            me.push(S::T(155, "B".into()));
        }
        objects.push(B::default().u16(ROGUE).raw(&status(ME, self.my_pos, &me)).0);
        for k in 0..3 {
            let id = 2000 + k;
            self.players.push(id);
            let gear = [i32::from(DAGGER), i32::from(CLOAK) + k, i32::from(ARMOR), 2639];
            let st = self.player_stats(&format!("Friend{k},guild"), gear);
            objects.push(B::default().u16(if k == 0 { ARCHER } else { ROGUE }).raw(&status(id, (10.0 + k as f32, 10.0), &st)).0);
        }
        for k in 0..6 {
            let id = 3000 + k;
            self.enemies.push(id);
            let t = ENEMY_TYPES[k as usize];
            let anim = self.pick(&ORYX_ANIMATIONS);
            let anim = if t == 29039 { -123_818_367 } else { anim };
            let st = vec![S::N(0, 5000), S::N(1, 5000), S::N(21, self.small(40)), S::N(29, 0), S::N(69, 0), S::N(125, anim)];
            objects.push(B::default().u16(t).raw(&status(id, (12.0, 14.0 + k as f32), &st)).0);
        }
        for k in 0..2 {
            let id = 4000 + k;
            self.minions.push(id);
            objects.push(B::default().u16(0x0700 + k as u16).raw(&status(id, (11.0, 11.0), &[])).0);
        }
        let mut tiles = B::default().ci(25);
        for x in 0..5 {
            for y in 0..5 {
                tiles = tiles.i16(8 + x).i16(8 + y).u16(self.small(300) as u16);
            }
        }
        let mut b = B::default().pos(self.my_pos).i8(0).raw(&tiles.0).ci(objects.len() as i32);
        for o in &objects {
            b = b.raw(o);
        }
        self.send(Direction::Incoming, 42, b.ci(0));
    }

    fn crucible(&mut self) {
        let json = r#"{"array":[{"id":"A","bonuses":[{"type":5,"amount":1.1},{"type":1,"amount":0.3}]},{"id":"B","x":{"bonuses":[{"type":5,"amount":"1.25"}]}}]}"#;
        self.send(Direction::Incoming, 183, B::default().i16(1).i32(1).i16(1).str(json));
    }

    fn round(&mut self) {
        // conditions and stats change
        let mut changes = Vec::new();
        if self.chance(40) {
            let e = self.pick(&self.enemies.clone());
            let cond = self.pick(&[0, 0x200_0000, 0x400_0000, 0x100_0000]);
            let newc = self.pick(&[0, 0x2_0000, 0x8, 0x40, 0x48]);
            changes.push(status(e, (12.0, 14.0), &[S::N(29, cond), S::N(69, newc), S::N(21, self.small(60))]));
        }
        if self.chance(30) {
            let cond = self.pick(&[0, 0x4, 0x4_0000, 0x100_0000, 0x200_0000, 0x2_0000, 0x40]);
            let attack = 40 + self.small(40);
            let ability = self.pick(&[i32::from(CLOAK), i32::from(CLOAK) + 1, 0x0a5a]);
            changes.push(status(ME, self.my_pos, &[S::N(29, cond), S::N(20, attack), S::N(9, ability), S::N(27, 20 + self.small(70))]));
        }
        self.tick(changes);
        // your shots, and their hits
        for _ in 0..self.small(4) {
            self.bullet = self.bullet.wrapping_add(1);
            let weapon = self.pick(&[DAGGER, DAGGER, DAGGER, CLOAK, 0x0a5a, 0x7fff]);
            let pid = self.pick(&[0i8, 0, 0, 1, -1, 7]);
            let b =
                B::default().i32(0).i16(self.bullet).u16(weapon).i8(pid).pos(self.my_pos).f32(0.5).bool(false).i8(0).i8(0).pos(self.my_pos);
            self.send(Direction::Outgoing, 30, b);
            if self.chance(80) {
                let target = self.pick(&self.enemies.clone());
                self.send(Direction::Outgoing, 25, B::default().i32(0).i16(self.bullet).i32(ME).i32(target).bool(false).i32(target));
            }
        }
        // the server's shots for other players and pets, and hits on them
        for _ in 0..self.small(3) {
            let owner = if self.chance(70) { self.pick(&self.players.clone()) } else { self.pick(&self.minions.clone()) };
            let summoner = if self.minions.contains(&owner) || self.chance(30) { self.pick(&self.players.clone()) } else { 0 };
            let options = [self.small(200), 256 + self.small(255), 600];
            let bullet = self.pick(&options) as i16;
            let count = self.pick(&[1i8, 1, 3]);
            let container = self.pick(&[i32::from(CLOAK), 0x0a5a, i32::from(DAGGER), -1]);
            let b = B::default()
                .i16(bullet)
                .i32(owner)
                .i32(container)
                .pos((10.0, 10.0))
                .f32(0.0)
                .i16(50 + self.small(200) as i16)
                .i32(summoner)
                .i8(0)
                .i8(count)
                .f32(0.1);
            self.send(Direction::Incoming, 12, b);
            if self.chance(70) {
                let target = self.pick(&self.enemies.clone());
                self.send(Direction::Outgoing, 25, B::default().i32(0).i16(bullet).i32(owner).i32(target).bool(false).i32(target));
            }
        }
        // damage the server tells
        for _ in 0..self.small(3) {
            let target = self.pick(&self.enemies.clone());
            let by = self.pick(&[self.players[0], self.players[1], self.minions[0], self.minions[1], 99_999]);
            let b = B::default().i32(target).u8(0).u16(self.small(400) as u16).bool(false).u16(1).i32(by);
            self.send(Direction::Incoming, 75, b);
        }
        // enemies shoot, sometimes hitting you
        if self.chance(60) {
            let owner = self.pick(&self.enemies.clone());
            let bullet = self.small(30000) as i16;
            let b = B::default().i16(bullet).i32(owner).u8(self.small(3) as u8).pos((12.0, 14.0)).f32(0.0).i16(30 + self.small(200) as i16);
            let b = if self.chance(50) { b.u8(1 + self.small(4) as u8).f32(0.2) } else { b };
            self.send(Direction::Incoming, 35, b);
            self.enemy_bullets.push((owner, bullet));
        }
        if self.chance(40) && !self.enemy_bullets.is_empty() {
            let (owner, bullet) = self.pick(&self.enemy_bullets.clone());
            let bullet = bullet.wrapping_add(self.small(2) as i16);
            self.send(Direction::Outgoing, 90, B::default().i16(bullet).i32(owner));
        }
        if self.chance(25) {
            let near = if self.chance(60) { (self.my_pos.0 + 0.5, self.my_pos.1) } else { (40.0, 40.0) };
            let b =
                B::default().pos(near).f32(2.0).u16(100 + self.small(100) as u16).u8(0).f32(1.0).u16(0x0600).i32(0).bool(self.chance(30));
            self.send(Direction::Incoming, 64, b);
        }
        if self.chance(15) {
            self.send(Direction::Outgoing, 103, B::default().i32(0).pos(self.my_pos));
        }
        if self.chance(5) {
            let dammah = self.enemies[1];
            let text = lg_protocol::packets::Text {
                name: "Chancellor Dammah".into(),
                object_id: dammah,
                num_stars: -1,
                bubble_time: 5,
                recipient: String::new(),
                text: "I SAID DO NOT INTERRUPT ME! For this I shall hasten your end!".into(),
                clean_text: String::new(),
                is_supporter: false,
                star_background: 0,
            };
            self.out.push((Direction::Incoming, Plain { id: 44, body: text.encode() }));
        }
        // a player turns into something (their skin changes), and back
        if self.chance(8) {
            let who = self.pick(&[ME, 2000, 2001, 2002]);
            let skin = self.pick(&SKINS);
            let tex = self.pick(&TEXTURES);
            self.tick(vec![status(who, (10.0, 10.0), &[S::N(25, skin), S::N(32, tex)])]);
        }
        // a quick slot used, by its quick slot id or from a slot; or an item from the inventory
        if self.chance(6) {
            let (slot, item) = match self.small(5) {
                0 => (1_000_000 + self.small(3), self.pick(&QUICK)),
                1 => (2, QUICK[0]),
                2 => (0, i32::from(DAGGER)),
                3 => (1_000_005, QUICK[1]),
                _ => (14, QUICK[1]),
            };
            let obj = if self.chance(80) { ME } else { 2000 };
            let b = B::default().i32(0).i32(obj).i32(slot).i32(item).pos(self.my_pos).i8(0).i32(0);
            self.send(Direction::Outgoing, 13, b);
        }
        // someone dies; the dungeon is done
        if self.chance(3) {
            let name = self.pick(&DEAD);
            let message = format!(r#"{{"k":"s.death","t":{{"player":"{name}","enemy":"Something"}}}}"#);
            let picture = self.small(5);
            self.send(Direction::Incoming, 67, B::default().i8(7).i8(0).str(&message).i32(picture));
        }
        if self.chance(2) {
            self.send(Direction::Incoming, 67, B::default().i8(14).i8(0));
        }
        self.my_pos.0 += 0.25;
        if self.chance(4) {
            self.my_pos.1 += 6.0; // a teleport: not walked
        }
    }

    /// You die (DEATH).
    fn die(&mut self) {
        let b = B::default().str("account").ci(42).str("Pirate King").i32(0).ci(100).ci(0).ci(0).ci(0).str("");
        self.send(Direction::Incoming, 46, b);
    }

    fn object(&mut self, kind: u16, id: i32, pos: (f32, f32), st: &[S]) -> Vec<u8> {
        let _ = self;
        B::default().u16(kind).raw(&status(id, pos, st)).0
    }

    /// Enemies die, bags drop, bosses speak their last words.
    fn loot(&mut self) {
        let mut new_objects = Vec::new();
        for pos in std::mem::take(&mut self.bags_due) {
            self.next_id += 1;
            let kind = self.pick(&BAGS);
            let mut st = Vec::new();
            for slot in 0..8 {
                let item = if self.chance(40) { 0x0a00 + self.small(0x300) } else { -1 };
                st.push(S::N(8 + slot, item));
            }
            new_objects.push(self.object(kind, self.next_id, pos, &st));
        }
        let mut drops = Vec::new();
        if self.chance(15) && self.enemies.len() > 2 {
            let k = 2 + self.rng.below(self.enemies.len() - 2);
            let gone = self.enemies.remove(k);
            drops.push(gone);
            let pos = (12.0, 14.0 + (gone % 10) as f32);
            self.bags_due.push(pos);
            if self.chance(30) {
                self.bags_due.push((pos.0 + 0.5, pos.1));
            }
            // a new one comes into view
            self.next_id += 1;
            let id = self.next_id;
            self.enemies.push(id);
            let st = vec![S::N(0, 900), S::N(1, 900), S::N(21, self.small(20))];
            let kind = 0x0600 + self.small(0x80) as u16;
            new_objects.push(self.object(kind, id, (12.0, 14.0 + (id % 10) as f32), &st));
        }
        if self.chance(5) {
            // an enemy hit before it came into view, then gone: it has no position
            self.next_id += 1;
            let unseen = self.next_id;
            self.send(Direction::Outgoing, 25, B::default().i32(0).i16(self.bullet).i32(ME).i32(unseen).bool(false).i32(unseen));
            drops.push(unseen);
            self.bags_due.push((9.0, 9.0));
        }
        if self.chance(5) {
            let (name, text) = self.pick(&LAST_WORDS);
            let t = lg_protocol::packets::Text {
                name: name.into(),
                object_id: 0,
                num_stars: -1,
                bubble_time: 0,
                recipient: String::new(),
                text: text.into(),
                clean_text: String::new(),
                is_supporter: false,
                star_background: 0,
            };
            self.out.push((Direction::Incoming, Plain { id: 44, body: t.encode() }));
            self.bags_due.push((30.0, 30.0));
            if self.chance(50) {
                self.bags_due.push((30.5, 30.0));
            }
        }
        if !new_objects.is_empty() || !drops.is_empty() {
            let mut b = B::default().pos(self.my_pos).i8(0).ci(0).ci(new_objects.len() as i32);
            for o in &new_objects {
                b = b.raw(o);
            }
            b = b.ci(drops.len() as i32);
            for d in drops {
                b = b.ci(d);
            }
            self.send(Direction::Incoming, 42, b);
        }
    }

    fn new_room(&mut self) {
        self.players.clear();
        self.enemies.clear();
        self.minions.clear();
        self.enemy_bullets.clear();
        self.setup();
    }

    fn leave(&mut self) {
        let gone = self.players.remove(0);
        self.send(Direction::Incoming, 42, B::default().pos(self.my_pos).i8(0).ci(0).ci(0).ci(1).ci(gone));
    }
}

/// The fight as a pcap session (seed: which made-up fight).
pub fn fight(seed: u64) -> Session {
    let mut f = Fight {
        rng: Rng(seed.max(1).wrapping_mul(0x9E37_79B9_7F4A_7C15)),
        out: Vec::new(),
        tick: 0,
        bullet: 0,
        my_pos: (10.5, 10.5),
        players: Vec::new(),
        enemies: Vec::new(),
        minions: Vec::new(),
        enemy_bullets: Vec::new(),
        next_id: 5000,
        bags_due: Vec::new(),
    };
    for _ in 0..3 {
        f.tick(Vec::new());
    }
    let seed1 = f.rng.next() as i32;
    let first = f.pick(&["Made-up Dungeon", "Snake Pit", "Undead Lair"]);
    f.map(first, first, seed1);
    f.setup();
    f.crucible();
    for r in 0..120 {
        f.round();
        f.loot();
        if r == 60 {
            f.leave();
        }
        if r == 80 {
            let seed2 = f.rng.next() as i32;
            let (name, display) =
                f.pick(&[("Second Room", "Second Room"), ("Guild Hall", "{s.guildhall}"), ("Pirate Cave", "Pirate Cave")]);
            f.map(name, display, seed2);
            f.new_room();
        }
        if r == 100 && f.chance(50) {
            f.die();
        }
        if r == 110 {
            let seed3 = f.rng.next() as i32;
            f.map("Nexus", "{s.nexus}", seed3);
            f.new_room();
        }
    }
    write(&mut f.rng, &f.out, 3000)
}

/// The packets encrypted, each in a TCP segment of its own, in order.
pub(crate) fn write(rng: &mut Rng, packets: &[(Direction, Plain)], step_micros: u64) -> Session {
    let mut w = pcap::Writer::new(link::DLT_EN10MB);
    let mut micros = 1_763_000_000_000_000u64;
    let mut ip_id = 1u16;
    let isn = [rng.next() as u32, rng.next() as u32];
    let mut seq = [0u32; 2];
    let mut rc4 = [Rc4::new(&INCOMING_KEY), Rc4::new(&OUTGOING_KEY)];
    for (k, d) in [Direction::Incoming, Direction::Outgoing].iter().enumerate() {
        let (src, dst, sp, dp) = endpoints(*d);
        let syn = tcp::Segment::build(sp, dp, isn[k].wrapping_sub(1), tcp::FLAG_SYN, &[]);
        w.frame(micros, &frame(src, dst, ip_id, &syn));
        micros += 1000;
        ip_id = ip_id.wrapping_add(1);
    }
    let (mut incoming, mut outgoing) = (Vec::new(), Vec::new());
    for (d, p) in packets {
        let k = usize::from(*d == Direction::Outgoing);
        let mut bytes = ((p.body.len() + 5) as i32).to_be_bytes().to_vec();
        bytes.push(p.id);
        let mut body = p.body.clone();
        rc4[k].apply(&mut body);
        bytes.extend(body);
        let (src, dst, sp, dp) = endpoints(*d);
        let seg = tcp::Segment::build(sp, dp, isn[k].wrapping_add(seq[k]), tcp::FLAG_ACK | tcp::FLAG_PSH, &bytes);
        seq[k] = seq[k].wrapping_add(bytes.len() as u32);
        w.frame(micros, &frame(src, dst, ip_id, &seg));
        micros += step_micros;
        ip_id = ip_id.wrapping_add(1);
        if k == 0 { incoming.push(p.clone()) } else { outgoing.push(p.clone()) }
    }
    Session { pcap: w.bytes, incoming, outgoing }
}
