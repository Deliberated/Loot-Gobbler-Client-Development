**Linux:** 
- Loot Gobbler now runs on Linux too, with the game played through Steam (Proton), Wine, Lutris or Bottles
- **LootGobbler-x86_64.rpm** (Fedora): open it, click Install and enter your password once. Loot Gobbler is then in your app menu and the sniffer works right away
- **LootGobbler-linux-x86_64.tar.gz** (any distribution): unpack it and start `bin/LootGobbler`. The first Start Sniffer asks for a one-time permission: click Grant permission and enter your password
- The game files are found by themselves inside the Steam/Wine folders
- Updates on Linux: the update button opens the release page, download the new file from there

**Graveyard:** 
- Deaths are recognized again: the game changed its death message, so dying didn't move characters to the graveyard anymore (Windows too)
- Deleted characters go to the graveyard when the full character list next arrives (only characters known to be on that account)

**Sync between PCs:** 
- Alive or dead: whoever saw the character last decides, even if that PC syncs days later. Characters buried on one PC no longer come back from another
- Inventory and vaults now sync too, place by place: the newer look wins, not the newer sync

**Sniffer:** 
- Also works when the game runs through a VPN
- If no network adapter can be opened, a message says why and the sniffer stops, instead of waiting without a word
- Fixed a possible crash when stopping the sniffer

**Other:** 
- Faster startup: game data loads in parallel while the window is built
- Best Friends: bigger skins are drawn bigger, at the same pixel size as the other skins
- Starting Loot Gobbler a second time no longer opens a second copy
- The chat spam filter keeps its last keyword list when the RealmShark server is down
