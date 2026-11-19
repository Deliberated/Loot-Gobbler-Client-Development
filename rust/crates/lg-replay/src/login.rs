//! A made-up login (`lg-replay synth-login`): HELLO with a made-up token, the Nexus, the Pet Yard, the Daily Quest
//! Room, the vault, with your character in view; and made-up answers of the game's web API for it (character list
//! with vault, pets, enchantments and statistics; exaltations; friends), which both the Rust replay and the Java
//! oracle read from files. Nothing reaches the game. The seed picks variants, among them the answers Java cannot
//! read and the ones the game refuses.

use crate::fight::{B, S, six_bit, status, write};
use crate::synth::{Plain, Rng, Session};
use lg_protocol::Direction;

const ME: i32 = 1000;
const ROGUE: u16 = 0x0300;
const POOL: [i32; 15] = [14257, 3274, 5298, 8386, 65518, 1221, 25734, 5272, 14866, 8254, 8791, 2580, 2723, 2680, 2815];

struct Login {
    rng: Rng,
    out: Vec<(Direction, Plain)>,
    tick: i32,
}

impl Login {
    fn send(&mut self, d: Direction, id: u8, b: B) {
        self.out.push((d, Plain { id, body: b.0 }));
    }
    fn below(&mut self, n: usize) -> usize {
        self.rng.below(n)
    }
    fn chance(&mut self, percent: usize) -> bool {
        self.rng.below(100) < percent
    }

    fn tick(&mut self, changes: Vec<Vec<u8>>) {
        self.tick += 1;
        let mut b = B::default().i32(self.tick).i32(self.tick * 200).i32(70_000_000 + self.tick * 200).u16(30).i16(changes.len() as i16);
        for c in &changes {
            b = b.raw(c);
        }
        self.send(Direction::Incoming, 10, b);
        let records = B::default().i16(1).i32(self.tick * 200).pos((10.5, 10.5));
        self.send(Direction::Outgoing, 62, B::default().i32(self.tick).i32(self.tick * 200).raw(&records.0));
    }

    fn hello(&mut self, token: &str) {
        let b = B::default().i32(-2).str("4.2.1").str(token).i32(0).i16(0).str("rotmg").str("rotmg").str("").str("").str("");
        self.send(Direction::Outgoing, 74, b);
    }

    fn map(&mut self, name: &str, display: &str) {
        let b = B::default()
            .i32(64)
            .i32(64)
            .str(name)
            .str(display)
            .str("")
            .i32(7)
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
    }

    fn create(&mut self, char_id: i32) {
        self.send(Direction::Incoming, 101, B::default().i32(ME).i32(char_id).str("AAAAAAAA"));
    }

    /// You come into view: inventory and backpack from the pool, enchant codes for only the first slots (the rest is
    /// looked up in the character list).
    fn me(&mut self, slots: &[i32]) {
        let mut st = vec![S::N(0, 700), S::N(1, 650), S::N(7, 20), S::T(31, "Tester".into()), S::N(35, 100), S::N(24, 0)];
        for (i, s) in slots.iter().enumerate().take(28) {
            let t = if i >= 12 { 131 + (i - 12) as u8 } else { 8 + i as u8 };
            st.push(S::N(t, *s));
        }
        let code = six_bit(&[0, 0x02, 0x04, 0x05, 0x00, 0xfd, 0xff]);
        st.push(S::T(80, if self.chance(50) { format!("{code},{code}") } else { String::new() }));
        st.push(S::N(130, 8));
        let o = B::default().u16(ROGUE).raw(&status(ME, (10.5, 10.5), &st)).0;
        let b = B::default().pos((10.5, 10.5)).i8(0).ci(0).ci(1).raw(&o).ci(0);
        self.send(Direction::Incoming, 42, b);
    }

    fn exalt(&mut self, class: i16, v: [i32; 8]) {
        let mut b = B::default().i16(class);
        for x in v {
            b = b.ci(x);
        }
        self.send(Direction::Incoming, 114, b);
    }
}

/// The game's base64 variant of `PCStats`: flag 1, the bits of the statistics sent, their values.
fn pc_stats(rng: &mut Rng) -> String {
    let mut b = vec![1u8, 0, 0, 0];
    let ids = [24usize, 25, 30, 1, 2, 8, 9, 21, 22, 23, 75, 100];
    let mut bits = [0u8; 16];
    for id in ids {
        if rng.below(3) > 0 {
            bits[id / 8] |= 1 << (id % 8);
        }
    }
    b.extend_from_slice(&bits);
    // values in the parse order for those set: small numbers, one byte each, some two
    for _ in 0..ids.len() {
        let v = rng.below(300) as u32;
        if v < 64 {
            b.push(v as u8);
        } else {
            b.push(((v & 63) | 128) as u8);
            b.push((v >> 6) as u8);
        }
    }
    six_bit(&b)
}

fn code(id: u8) -> String {
    six_bit(&[0, 0x02, 0x04, id, 0x00, 0xfd, 0xff, 0xff, 0xff])
}

/// The character list answer (`full`: with the account's vault, as with muleDump).
fn char_list(rng: &mut Rng, slots: &[i32], full: bool, account: &str, variant: usize) -> String {
    let mut x = format!("<Chars maxNumChars=\"20\" nextCharId=\"{}\">", if variant == 5 { 44 } else { 50 });
    let mut instance = 9_000_000_000_000_000u64;
    let mut next_instance = || {
        instance += 17;
        instance
    };
    for (k, id) in [42, 43, 44, 47].into_iter().enumerate() {
        let dead = id == 44 && rng.below(2) == 0;
        let mut equipment = Vec::new();
        let mut uii = String::new();
        let source =
            if id == 42 { slots.to_vec() } else { (0..20).map(|i| if i % 3 == 0 { -1 } else { POOL[(i * 7 + k) % POOL.len()] }).collect() };
        for &s in &source {
            if s > 0 && rng.below(3) == 0 {
                equipment.push(format!("{s}#{}", next_instance()));
                uii.push_str(&format!("<ItemData type=\"{s}\">{}</ItemData>", code(rng.below(200) as u8)));
            } else {
                equipment.push(s.to_string());
            }
        }
        let equipment = if variant == 3 && id == 43 { "12,x,3".to_string() } else { equipment.join(",") };
        x.push_str(&format!(
            "<Char id=\"{id}\"><Account><Name>{account}</Name></Account><ObjectType>{}</ObjectType>",
            if id == 47 { 775 } else { 768 }
        ));
        x.push_str(&format!(
            "<Level>{}</Level><Exp>{}</Exp><CurrentFame>{}</CurrentFame>",
            1 + rng.below(20),
            rng.below(100_000),
            rng.below(5000)
        ));
        x.push_str(&format!("<Equipment>{equipment}</Equipment><EquipQS>2594|1,-1|0</EquipQS><Texture>{}</Texture>", 800 + rng.below(3)));
        if rng.below(2) == 0 {
            x.push_str(&format!("<TextureOverride>{}</TextureOverride>", 900 + k));
        }
        if variant == 4 && id == 47 {
            x.push_str("<Tex1><b/></Tex1>"); // a tag without text: Java cannot read it
        } else if rng.below(2) == 0 {
            x.push_str(&format!("<Tex1>{}</Tex1>", [0, 4096, 4352, 16_777_221][rng.below(4)]));
        }
        x.push_str(&format!(
            "<Seasonal>{}</Seasonal><Dead>{dead}</Dead><BackpackSlots>{}</BackpackSlots>",
            if k == 2 { "True" } else { "False" },
            [0, 8, 16][rng.below(3)]
        ));
        for (tag, max) in [
            ("MaxHitPoints", 720),
            ("MaxMagicPoints", 252),
            ("Attack", 75),
            ("Defense", 25),
            ("Speed", 75),
            ("Dexterity", 75),
            ("HpRegen", 40),
            ("MpRegen", 60),
        ] {
            x.push_str(&format!("<{tag}>{}</{tag}>", max - rng.below(3) as i32));
        }
        if rng.below(3) == 0 {
            x.push_str("<Favorite>1</Favorite>");
        }
        x.push_str(&format!("<PCStats>{}</PCStats>", pc_stats(rng)));
        let abilities = if variant == 2 && id == 42 {
            String::new()
        } else {
            "<Abilities><Ability type=\"402\" power=\"50\" points=\"1234\"/><Ability type=\"406\" power=\"20\" points=\"77\"/></Abilities>"
                .to_string()
        };
        let pet_items = [2594, -1, 14257];
        let inv: Vec<String> =
            pet_items.iter().map(|i| if *i == 14257 { format!("{i}#{}", 1_000_000 + k) } else { i.to_string() }).collect();
        x.push_str(&format!(
            "<Pet name=\"Rex{k}\" type=\"{}\" instanceId=\"{}\" skin=\"{}\" rarity=\"{}\" maxAbilityPower=\"90\" createdOn=\"Nov 1, 2026\" shader=\"0\" incInv=\"0\" inv=\"0,6;X;{}\"><UniqueItemInfo><ItemData id=\"{}\" type=\"14257\">{}</ItemData></UniqueItemInfo>{abilities}</Pet>",
            32000 + k,
            500 + k,
            if rng.below(3) == 0 { 0 } else { 2 },
            rng.below(5),
            inv.join(","),
            1_000_000 + k,
            code(9)
        ));
        x.push_str(&format!("<UniqueItemInfo>{uii}</UniqueItemInfo></Char>"));
    }
    if full {
        let mut vault = String::new();
        let mut account_uii = String::new();
        for _ in 0..4 {
            let items: Vec<String> = (0..8)
                .map(|_| {
                    let i = POOL[rng.below(POOL.len())];
                    if rng.below(4) == 0 {
                        "-1".to_string()
                    } else if rng.below(3) == 0 {
                        let n = next_instance();
                        account_uii.push_str(&format!("<ItemData id=\"{n}\" type=\"{i}\">{}</ItemData>", code(rng.below(200) as u8)));
                        format!("{i}#{n}")
                    } else {
                        i.to_string()
                    }
                })
                .collect();
            vault.push_str(&format!("<Chest>{}</Chest>", items.join(",")));
        }
        x.push_str(&format!(
            "<Account><Name>{account}</Name><Credits>10</Credits><Vault>{vault}</Vault><Gifts>2594,-1,2595</Gifts><Potions>2793,2591,-1</Potions><MaterialStorage><Chest>1221,-1</Chest></MaterialStorage><UniqueItemInfo>{account_uii}</UniqueItemInfo></Account>"
        ));
    } else {
        x.push_str(&format!("<Account><Name>{account}</Name></Account>"));
    }
    x.push_str("<PowerUpStats><ClassStats class=\"768\">1,2,3,4,5,6,7,8</ClassStats></PowerUpStats></Chars>");
    x
}

/// A made-up login and the game's answers to it: (session, files of the answers folder).
pub fn login(seed: u64) -> (Session, Vec<(String, String)>) {
    let mut l = Login { rng: Rng(seed.max(1).wrapping_mul(0x9E37_79B9_7F4A_7C15)), out: Vec::new(), tick: 0 };
    let slots: Vec<i32> = (0..20).map(|i| if i % 4 == 3 { -1 } else { POOL[i % POOL.len()] }).collect();
    let variant = l.below(7); // 0, 1, 6: plain; 2: your pet without abilities; 3: Java cannot read it; 4: a tag Java cannot read; 5: created later
    let account = if l.chance(80) { "Tester" } else { "Somebody" };
    let mut files = Vec::new();
    let full = char_list(&mut l.rng, &slots, true, account, variant);
    let plain = char_list(&mut l.rng, &slots, false, account, variant);
    match l.below(4) {
        0 => files.push(("char_list-mule.xml".into(), "<Error>muleDump not allowed</Error>".into())),
        1 => {}
        _ => files.push(("char_list-mule.xml".into(), full)),
    }
    if l.chance(75) {
        files.push(("char_list.xml".into(), plain));
    }
    match l.below(5) {
        0 => {} // refused: Java parses null and throws
        1 => files.push(("account_listPowerUpStats.xml".into(), "not xml".into())),
        _ => files.push((
            "account_listPowerUpStats.xml".into(),
            "<AccountPowerups><ClassPowerup><Class>768</Class><Dex>6</Dex><Spd>15</Spd><Vit>30</Vit><Wis>50</Wis><Def>75</Def><Att>1</Att><Mana>4</Mana><Life>11</Life></ClassPowerup><ClassPowerup><Class>799</Class><Dex>1</Dex></ClassPowerup></AccountPowerups>".into(),
        )),
    }
    if l.chance(70) {
        files.push((
            "friends_getList.xml".into(),
            "<Friends>\n<Friend><Name>Bob</Name></Friend>\n<Friend name=\"ALICE\"/>\n</Friends>\n".into(),
        ));
    }
    for _ in 0..3 {
        l.tick(Vec::new());
    }
    let token = format!("made-up-token-{seed}");
    l.hello(&token);
    l.map("Nexus", "{s.nexus}");
    l.create(42);
    l.me(&slots);
    for _ in 0..8 {
        l.tick(Vec::new());
    }
    if l.chance(50) {
        l.hello(&token); // the same login again: nothing new to ask
    }
    l.map("Pet Yard", "Pet Yard");
    l.create(42);
    for _ in 0..4 {
        l.tick(Vec::new());
    }
    let relogin = l.chance(50);
    if relogin {
        l.hello(&format!("{token}-2"));
    }
    l.map("Daily Quest Room", "Daily Quest Room");
    l.create(43);
    for _ in 0..30 {
        l.tick(Vec::new());
    }
    l.map("Nexus", "{s.nexus}");
    l.create(42);
    l.me(&slots);
    for _ in 0..8 {
        l.tick(Vec::new());
    }
    l.exalt(768, [7, 15, 30, 50, 75, 1, 4, 12]);
    l.exalt(775, [1, 0, 0, 0, 0, 0, 0, 0]);
    for _ in 0..3 {
        l.tick(Vec::new());
    }
    (write(&mut l.rng, &l.out, 400_000), files)
}
