use super::*;
use lg_gamedata::{Class, GameData};
use lg_protocol::packets::data::{MoveRecord, NewObject, SlotObject};
use lg_protocol::packets::{CreateSuccess, Move, NewTick, Update, UseItem};

const ROGUE: i32 = 768;

fn world() -> World {
    let mut d = GameData::default();
    d.classes.push(Class { id: ROGUE, name: "Rogue".into(), max_stats: [0; 8], base_gear: [0; 4], weapon_group: vec![ROGUE] });
    World::new(d, HashMap::new())
}

fn pos(x: f32, y: f32) -> WorldPos {
    WorldPos { x, y }
}

fn stat(t: u8, v: i32) -> Stat {
    Stat { stat_type_num: t, stat_value: v, string_stat_value: None, stat_value_two: -1 }
}

fn status(id: i32, p: WorldPos, stats: Vec<Stat>) -> ObjectStatus {
    ObjectStatus { object_id: id, pos: p, stats }
}

fn full_stats() -> Vec<Stat> {
    use stats::*;
    [MAX_HP, MAX_MP, ATTACK, DEFENSE, SPEED, DEXTERITY, VITALITY, WISDOM]
        .iter()
        .map(|&t| stat(t, 50))
        .chain(
            [MAX_HP_BOOST, MAX_MP_BOOST, ATTACK_BOOST, DEFENSE_BOOST, SPEED_BOOST, DEXTERITY_BOOST, VITALITY_BOOST, WISDOM_BOOST]
                .iter()
                .map(|&t| stat(t, 5)),
        )
        .collect()
}

fn map(name: &str) -> Decoded {
    Decoded::MapInfo(MapInfo {
        width: 64,
        height: 64,
        name: name.into(),
        display_name: name.into(),
        realm_name: String::new(),
        fp: 7,
        background: 0,
        difficulty: 0.0,
        allow_player_teleport: true,
        no_save: false,
        show_displays: true,
        max_player_count: 1,
        game_opened_time: 0,
        version_number: String::new(),
        unknown1: 0,
        view_distance: 15,
        unknown2: false,
        unknown_int: 0,
        dungeon_modifiers: None,
        dungeon_modifiers2: None,
        dungeon_modifiers3: None,
        dungeon_modifiers4: None,
        dungeon_grade: None,
        bg_color: 0,
        max_realm_score: -1,
        current_realm_score: -1,
    })
}

fn update(objects: Vec<NewObject>, drops: Vec<i32>) -> Decoded {
    Decoded::Update(Update { pos: pos(0.0, 0.0), level_type: 0, tiles: Vec::new(), new_objects: objects, drops })
}

fn tick(statuses: Vec<ObjectStatus>) -> Decoded {
    Decoded::NewTick(NewTick { tick_id: 1, tick_time: 200, server_real_time_ms: 1000, server_last_time_rtt_ms: 0, status: statuses })
}

#[test]
fn entering_a_map_finds_you_and_other_players() {
    let mut w = world();
    w.packet(&map("Nexus")).unwrap();
    w.packet(&Decoded::CreateSuccess(CreateSuccess { object_id: 10, char_id: 3, str: "AAAAAAAA".into() })).unwrap();
    let me = NewObject { object_type: ROGUE as u16, status: status(10, pos(1.0, 2.0), full_stats()) };
    let other = NewObject { object_type: ROGUE as u16, status: status(11, pos(3.0, 2.0), full_stats()) };
    w.packet(&update(vec![me, other], vec![])).unwrap();
    assert_eq!((w.player, w.char_id), (Some(10), 3));
    assert!(w.me().unwrap().is_user && w.entities[&11].is_player);
    assert_eq!(w.entities[&11].base_stats, Some([45; 8]));
    w.packet(&update(vec![], vec![11])).unwrap();
    assert!(w.dropped.contains(&11) && !w.player_list_updated.contains(&11) && w.player_list.contains(&11));
    // a new map forgets it all
    w.packet(&map("Vault")).unwrap();
    assert_eq!((w.player, w.world_player_id, w.time, w.entities.len()), (None, -1, -1, 0));
}

#[test]
fn a_player_without_stats_stops_the_update() {
    let mut w = world();
    w.packet(&map("Nexus")).unwrap();
    let bare = NewObject { object_type: ROGUE as u16, status: status(11, pos(0.0, 0.0), vec![stat(0, 1)]) };
    let after = NewObject { object_type: 1287, status: status(12, pos(0.0, 0.0), vec![]) };
    assert!(w.packet(&update(vec![bare, after], vec![])).is_err());
    assert!(w.entities[&11].is_player && !w.entities.contains_key(&12));
}

#[test]
fn started_inside_a_map_you_are_found_by_your_own_stats() {
    let mut w = world();
    w.packet(&tick(vec![status(5, pos(1.0, 1.0), vec![stat(stats::HP, 100)])])).unwrap();
    assert_eq!(w.player, None);
    w.packet(&tick(vec![status(9, pos(1.0, 1.0), vec![stat(stats::CREDITS, 7)])])).unwrap();
    assert_eq!(w.player, Some(9));
    assert!(w.me().unwrap().is_user);
    assert_eq!(w.me().unwrap().base_stats, Some([0; 8]));
}

#[test]
fn started_inside_a_map_you_are_found_where_you_moved() {
    let mut w = world();
    let here = pos(10.5, 20.25);
    w.packet(&Decoded::Move(Move { tick_id: 1, time: 0, records: vec![MoveRecord { time: 0, pos: here }] })).unwrap();
    w.packet(&tick(vec![status(4, here, vec![stat(stats::HP, 1)])])).unwrap();
    assert_eq!(w.player, None, "once is not enough");
    w.packet(&tick(vec![status(4, here, vec![])])).unwrap();
    assert_eq!(w.player, Some(4));
}

#[test]
fn started_inside_a_map_your_ability_tells_you() {
    let mut w = world();
    let used = |id, slot| {
        Decoded::UseItem(UseItem {
            time: 0,
            slot_object: SlotObject { object_id: id, slot_id: slot, object_type: 0 },
            use_item_position: pos(0.0, 0.0),
            use_item_type: None,
            use_item_flag: 0,
        })
    };
    w.packet(&used(77, 4)).unwrap();
    assert_eq!(w.player, None, "an item from a bag names the bag");
    w.packet(&used(78, 1)).unwrap();
    assert_eq!(w.player, Some(78));
}

#[test]
fn stat_types_beyond_java_s_array_stop_the_tick() {
    let mut w = world();
    let r = w.packet(&tick(vec![status(1, pos(0.0, 0.0), vec![stat(3, 9), stat(200, 1), stat(4, 9)]), status(2, pos(0.0, 0.0), vec![])]));
    assert!(r.is_err());
    assert_eq!((w.entities[&1].value(3), w.entities[&1].value(4)), (Some(9), None));
    assert!(!w.entities.contains_key(&2));
}

#[test]
fn the_map_is_guessed_from_its_enemies() {
    let mut d = GameData::default();
    d.objects.insert(
        900,
        lg_gamedata::Object {
            id: 900,
            display: "Boss".into(),
            class: String::new(),
            group: String::new(),
            projectiles: Vec::new(),
            texture: String::new(),
            label: "ENEMY,BOSS".into(),
            id_name: String::new(),
        },
    );
    let enemies: HashMap<i32, String> = [(1, "Pirate Cave"), (2, "Pirate Cave"), (3, "Pirate Cave"), (900, "Snake Pit")]
        .into_iter()
        .map(|(t, n)| (t, n.to_string()))
        .collect();
    let mut w = World::new(d.clone(), enemies.clone());
    let seen = |id, t: u16| NewObject { object_type: t, status: status(id, pos(0.0, 0.0), vec![]) };
    w.packet(&update(vec![seen(1, 1), seen(2, 2)], vec![])).unwrap();
    assert!(w.map.is_none());
    w.packet(&update(vec![seen(3, 3)], vec![])).unwrap();
    assert_eq!((w.map.as_ref().unwrap().name.as_str(), w.map_guessed), ("Pirate Cave", true));
    let mut w = World::new(d, enemies);
    w.packet(&update(vec![seen(1, 900)], vec![])).unwrap();
    assert_eq!(w.map.as_ref().unwrap().name, "Snake Pit", "a boss alone is enough");
}

#[test]
fn six_bit_strings_as_java() {
    assert_eq!(six_bit_bytes("AQID").unwrap(), [1, 2, 3]);
    assert_eq!(six_bit_bytes("AQI=").unwrap(), [1, 2]);
    assert_eq!(six_bit_bytes("").unwrap(), Vec::<u8>::new());
    assert!(six_bit_bytes("AQI").is_err(), "not a multiple of 4");
    assert!(six_bit_bytes("A=======").is_err(), "more padding than bytes");
}

#[test]
fn character_statistics_too_short_stop_create_success() {
    let mut w = world();
    w.packet(&map("Nexus")).unwrap();
    let created = |s: &str| Decoded::CreateSuccess(CreateSuccess { object_id: 10, char_id: 3, str: s.into() });
    assert!(w.packet(&created("AQI=")).is_err());
    assert_eq!(w.world_player_id, 10);
    assert!(w.packet(&created("AQI=")).is_ok(), "the same string is not decoded again");
    assert!(w.packet(&created("AAAAAAAA")).is_ok());
}
