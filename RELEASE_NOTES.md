**Game Modes (new tab):** 
- Play a character as a **PPE**, **UPE**, **Pure UPE** (UT only), **STPE** (ST only), **Shiny UPE** (shiny UT only) or **Divine PE** (divine items of any tier)
- Every mode needs a fresh character created with upgrades turned off: its T0 weapon, ability and armor are starting gear and never count against the rules
- **Guild version:** trade with Loot Gobbler guild mates, but only items their own runs gathered. It can be turned on later too
- **No pet:** an extra setting for any mode, the run is lost if a pet shows up
- The rules at a glance: what the loadout may hold (UT, ST, tiered, shiny, divine) and where things may come from (own drops, vault, trades, shop)
- Loot Gobbler checks the run as you play: drops, vault, trades, shop purchases, rewards, forging and potions. A broken rule ends the run and says why
- **Setup wizard:** the green plus at the top of Game Modes picks a mode, shows you to turn off upgrades (and the pet), then waits for your new character and starts the run on its own
- "Potential runs": characters seen from their creation that could still become a run

**Copy as image:** 
- The character card shows the game mode as a badge under the fame, each mode with its own look, with the guild shield and the no-pet paw beside it

**Characters:** 
- A banner shows a character's mode, or that its run was lost and why
- The loot bonus sits beside the pet and stats, its sources on hover

**Sniffer:** 
- Fixed "Packets missing" piling up after a while: the packet buffer handed out old packets again
- Lost or repeated packets no longer stall the stream, it picks up at the next packet there
- The same error is logged once instead of over and over

**Other:** 
- Settings are no longer lost if the settings file was being written when Loot Gobbler started
- A freshly created character no longer causes errors in the character list
