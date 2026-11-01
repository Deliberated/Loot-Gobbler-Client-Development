# Loot Gobbler

A read-only RotMG companion. It is a fork of **Tomato** built on the **RealmShark** sniffing engine (both MIT, see `THIRD_PARTY_LICENSE-RealmShark.md`), plus Discord loot alerts.

From Tomato you get:
- Chat with Guild, Party and PM pings
- A loot log with per-color bag sounds (white, orange, red, gold, egg, blue)
- Item, enchant, entity and dungeon pings
- DPS logger, stats, characters, vault and more

Added in this fork:
- **Inventory tab**: lifetime Items grid of all 1,696 UT/ST collection items (All / Shiny / Normal). Items are added when they drop for you and when you enter your vault (vault and gift chest contents; already-collected items are left unchanged). Also includes a Live view, dated Log, progress, recent drops, copy as text, and copy as image (laid out with the grid's current column count). Saved in `collection.json`.
- **Profile tab** (leftmost): your player name and your recently most played character (most fame in the last 24 h, else the one you play), plus the My Info and Dungeon Stats pages.
- **Social → Daily Quests**: Tomato's daily quest list.
- **Social → Discord** (under construction: greyed out, nothing is posted for now): any number of webhooks with a connection check. "Tops" (UT/ST items, or anything from a cyan bag) are posted as rendered loot-card images. Includes a Send Test Loot button.
- **DPS Logger tab**: live or logged run with one damage table per boss (DPS, damage, damage taken, % share of boss damage, deaths), Copy Log / Copy All, and a Run History list (the current run on top, flagged as current) kept across restarts in `dps_history.json`. "Detail" opens Tomato's original DPS view.
- **Profile → Dungeon Stats**: favourite dungeons and your peak boss DPS per dungeon (last run vs. best).
- **Social → Chat**: one striped log (time, channel, name: text) with a Channels sidebar (Clear Chat, per-channel toggles).
- **Settings tab**: Sound & Pings, Loot Filter and Theme in one place (these replace the old Edit → Sound / Theme / Filter Loot menus).
- **Guild tab** (needs a Loot Gobbler server): your in-game guild's leaderboard (items, finds, fame, exaltations, characters, maxed; sortable), the guild's recent UT/ST drops, and member profiles (characters with gear, exaltations and collection). Click a player to open their profile.
- **Server tab**: connect to a [Loot Gobbler server](https://github.com/Deliberated/Loot-Gobbler-Server): log in or register, and test the connection. Once you're logged in, your collection, characters, exaltations, pets and DPS history are synced with the server, so several PCs share one set of data. Changes are uploaded within a minute, or later if the server can't be reached. Moderators and admins approve new accounts there, and admins create moderator tokens.
- **Status bar**: sniffer state and a Start/Stop Sniffer button.
- **Window** reopens at the size, position and maximized state it had when closed. The tab row scrolls sideways instead of wrapping, and tabs can be reordered by dragging (the order is remembered).
- **Characters tab**: all your characters (favourites first, then most recently played) with their skin drawn with its applied dyes and cloths (like in-game), the equipped items with enchantment markers, fame, stats maxed (x/8) and seasonal / crucible / blood ritual tags; click one for its fame gain over 1h / 1d / 1w / 1m. Data comes from the character list at login plus live tracking while you play (`characters.json`). A **Classes** view lists every class with its exaltations per stat and in total (most exalted first) and your characters of that class; exaltations are saved in `exalts.json`.
- **ST Sets** (Collection → ST Sets): every 4-piece ST set with its skin, which pieces you own, filters by status and class, and progress per class.
- **Vault diagnostics**: entering the vault writes `vault-debug.txt` (which packets arrived, and any that could not be decoded) and, if the vault packet decodes, `vault-contents.txt`.
- **Item history**: click an item in the Collection grid or log to see every time you found it (drop, vault or inventory), where, and with how many enchantments.
- **Themes**: Gobbler Gold, Goblin Green, Treasure Hoard (default), Burlap Sack, Gem Vault and Amethyst Moss (colors taken from the Loot Gobbler artwork; previews in `theme-pocs/`), all with pixel fonts (Jersey 10 for headings, Jersey 15 for text), built on FlatLaf. Each palette is `src/main/resources/lootgobbler/themes/<Name>Laf.properties`, and the shared styling is `common.properties`.

It never sends, blocks or modifies game packets.

## Requirements

- Windows, JDK 21+
- [Npcap](https://npcap.com) installed with **"WinPcap API-compatible mode"** ticked
- RotMG Exalt installed (assets are extracted from your local copy)

## Build and run

```powershell
.\gradlew.bat run          # build and start
.\gradlew.bat shadowJar    # single jar -> build\libs\LootGobbler-<version>.jar
java -jar build\libs\LootGobbler-0.4.0.jar   # run it from the project root
.\gradlew.bat packageExe        # build\dist\LootGobbler\LootGobbler.exe (Java bundled) + LootGobbler-portable.zip
.\gradlew.bat packageInstaller  # build\dist\LootGobbler.exe setup (needs WiX Toolset 3.x on PATH)
```

The exe keeps all its data (settings, collection, characters, extracted assets) in its own folder, so the whole
`LootGobbler` folder can be moved or zipped. If it is started from another working directory, it restarts itself
in its own folder. An app icon is picked up from `src/main/resources/icon/lootgobbler.ico` once it exists.

The first Gradle run downloads Gradle and the dependencies, then caches them.
Start the sniffer via **File → Start Sniffer**, then start RotMG or change server.

## Settings

All settings are saved in `realmShark.properties` in the project root (Tomato's own settings file).

- **Bag sounds, chat pings, volume, ping lists, loot filter, theme:** Settings tab
- **Discord webhooks:** Social → Discord. Anyone with a webhook URL can post to that channel.
- **Fonts, chat saving, DPS options:** Edit menu

## Layout

```
src/main/java/tomato/        Tomato fork (ours to edit)
src/main/java/lootgobbler/   our additions (Discord alerts, theme; ui/ = Chat log, Settings, status bar, sections)
src/main/resources/sound/    Tomato's bag/chat sounds
src/main/resources/fonts/    Jersey 10 + Jersey 15 (SIL Open Font License, see OFL-*.txt)
vendor/RealmShark-realmshark/  upstream engine, compiled in by build.gradle
                               (small patches marked "[Loot Gobbler patch]": dialog texts,
                               standing sprite frame, sprites by sheet, dye masks)
vendor/RealmShark-tomato/      pristine upstream Tomato, kept for diffing upstream updates
assets/                      generated from your game install, not shipped
```

Changes to Tomato sources are kept small and marked by calls into `lootgobbler.*`:
- `LootGUI.updateGui` calls `DiscordAlerts.bagDropped`
- `TomatoMenuBar` adds a "Discord..." menu item, a Loot Gobbler theme and a Jersey 15 font option
- `TomatoGUI.loadThemePreset` delegates to `Themes.install` (Darklaf was replaced by FlatLaf)
- User-facing names say Loot Gobbler, and the upstream update check is disabled. Java packages and classes keep their `tomato` names so upstream diffs stay readable.

## Network calls (inherited from Tomato)

- `api.realmshark.cc`: Crucible data and a chat spam-filter list
- `realmofthemadgod.com`: the Characters tab sends your game access token, captured from your own traffic, to Deca's official servers to load your character list
- Your Discord webhook, if set

## Known limits

- Only one game client at a time is supported.
- Game patches can change packet IDs. Update `vendor/RealmShark-realmshark` from upstream when that happens.
- Passive sniffers are not officially sanctioned by Deca. Use at your own risk.
