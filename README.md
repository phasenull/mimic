# Mimic

Join modded servers without installing their mods.

## Why

Small modded SMPs pop up all the time, and most don't last more than a couple of weeks. Joining one usually
means downloading a pile of mods from wherever the server links them, and every one of those jars is code
running on your computer with full access to it. Malicious mods that steal accounts, browser sessions and
files are a real and recurring problem.

For a server that runs a few item, block or decoration mods, that risk isn't worth it. Mimic lets you join
anyway: you see the world, play with the vanilla parts, and see the modded content as placeholders. If you
want the modded blocks and items to look right, Mimic can read their textures, models and sounds from the
mod jars without ever loading the code inside them.

Mimic is a client-side Fabric mod for Minecraft 26.3. When a server runs mods you don't have, the vanilla
client (and Fabric API) refuse to join: unknown registry entries, unknown network channels, mod handshakes.
Mimic answers those checks, fills the gaps with stand-ins, and gets you into the world.

You won't get the mods' features (Mimic never runs a mod's code), but you can walk around, talk, build with
vanilla blocks, and see where the modded content is. Import a mod's jar and its blocks and items get their
real textures, models, sounds and recipes too.

> Some servers don't allow joining without their modpack. Check the server's rules before using Mimic there.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.19.5 or newer
- Fabric API
- Java 25
- Optional: [ViaFabricPlus](https://modrinth.com/mod/viafabricplus), to join servers on other Minecraft
  versions
- Optional: [JEI](https://modrinth.com/mod/jei), to browse imported mods' items and recipes

## What it handles

### Fabric servers
- Drops registries and entries the client doesn't have from Fabric's registry sync, so the join doesn't
  abort, and gives every server-only block, item, entity type and sound a placeholder at the server's id.
- Keeps the client's own numbering where adopting the server's would scramble data (data component types).

### NeoForge servers
- Takes part in NeoForge's channel negotiation. When the server reports missing channels, wrong versions or
  wrong directions, Mimic saves what it learned and reconnects on its own until negotiation passes.
- Remembers channels per server and reuses them on other servers running the same mods.
- Claims NeoForge's own built-in channels, acknowledges its configuration checks (registry sync, data maps,
  extensible enums, feature flags) and joins split packets.

### Forge servers (1.20.1 and older, with ViaFabricPlus)
- Learns that a server runs Forge from its "requires Forge" kick and reconnects as a Forge client: answers
  the FML login handshake with the server's own mod list.
- Reads the registry snapshots Forge sends, gives server-only blocks, items and entities placeholders, and
  maps Forge's mod block and item ids (which ViaVersion would drop) to them.

### Other Minecraft versions (with ViaFabricPlus)
- Passes block, item, sound, particle and entity ids through ViaVersion untouched when the server synced them,
  so modded content keeps its ids instead of being mapped to something random.
- Rebuilds the server's block-state table from ViaVersion's data, so blocks after a changed vanilla block
  don't shift.
- Shows items the server has even if its Minecraft version doesn't (e.g. backported blocks).

### Placeholders
- **Blocks:** a missing-texture block with the right number of states, guessed from the mod's jar (its
  classes and block-state files) or from the block's name. Break/step sounds are borrowed from a similarly
  named vanilla block. With the jar imported, its outline and collision shape follow the mod's model, so
  you don't bump into cables and slabs as if they were full blocks.
- **Block-entity blocks:** chests, shulker boxes and pots that a mod draws with code get a static vanilla
  look-alike model with the mod's texture.
- **Items:** a placeholder item that shows its id and data components.
- **Entities:** drawn with the mod's model when the jar has a Bedrock/GeckoLib `.geo.json` model (resting
  pose, no animations); otherwise as the mod's item of the same name (an easel shows the easel item), or a
  name tag. You can hit and click them like real entities; the server decides what happens.
- **Containers:** a mod's inventories (backpacks, machines) open as a plain slot grid above your inventory.
  Clicks go to the server as usual, so moving items in and out works.
- **Sounds:** server-only sounds play when the mod's jar is imported.
- **Hover info:** look at a placeholder to see what it stands in for.
- **Anchors:** if blocks still look shifted, `/mimic verify` pins a block you know is right, so a miscount
  earlier in the list stops there.
- **Self-test:** a few seconds after joining, Mimic checks the blocks around you. If blocks that never
  generate naturally (command blocks, barriers...) or placeholders of blocks the server doesn't have show
  up in the terrain, it says where the ids start to shift and which guessed block before that most likely
  has the wrong state count.

### Importing a mod's jar
From the pause menu, open **Server mods**, pick a mod, and import its jar (or drop the jar on the screen),
or let Mimic find it on Modrinth. No restart needed.

- Only asset and data files are copied (`png`, `json`, `mcmeta`, `ogg`, `txt`, `lang`). **No code is
  loaded or run.**
- Vanilla files a jar ships are left alone, so a jar can't replace the game's own textures.
- Model files using a mod's own loader or model types are rewritten to vanilla equivalents so they render.
- Recipes are read for display only: on item pages, and in JEI under "Mimic: recipes from mod jars".
  Ingredient tags the server didn't send are guessed from their names (`c:dusts/redstone` shows redstone).
- You can also pick your own PNG for any placeholder.
- **Modrinth:** "Find this mod's jar on Modrinth" looks the mod up for the server's loader and version and
  shows what it found. Nothing is downloaded until you confirm; the file is checked against Modrinth's
  SHA-512, imported as data only, and deleted afterwards.

### Safety scan
Every imported jar is read (as data, with nothing run) for signs of malware: starting programs, loading
code from bytes or the internet, reading browser, Discord or launcher credentials, Discord webhooks,
autostart entries, native code, known Fractureiser markers, heavy obfuscation. The report is on the mod's
page, and you can scan any jar from there without importing it, e.g. before installing a mod for real. It's
a heuristic: a clean scan doesn't prove a jar is safe.

### Privacy
**Server mods > Privacy** lists everything your client has sent to servers (packet types and mod channels)
and what each one tells the server. You can stop sending any of them (packets needed to stay connected
excepted) and report your client as vanilla instead of Fabric.

### Debugging joins
- **Join reports:** every failed join writes a report to `config/mimic/reports/` with the connection log
  and the relevant game log.
- **Server list badge:** servers whose last join failed in a way Mimic can't get past yet are marked.
- **Retry until connected,** and a guard that stops servers that keep reconfiguring the client in a loop.
- **Packets:** a toggle in the pause menu shows a live feed of incoming packets (noisy ones hidden), the
  sounds your client plays, and debug info under the crosshair.
- **Data components:** shown in item tooltips with advanced tooltips (F3+H) and Shift.

## Commands

| Command | What it does |
| --- | --- |
| `/mimic mods` | Mods seen on this server and how many of their items, blocks and entities |
| `/mimic verify [block]` | Pin the looked-at block's position in the server's block-state numbering |
| `/mimic unverify <block>` | Remove one pin |
| `/mimic nudge <block> <n>` | Move a pin by `n` states |
| `/mimic anchors [clear]` | List (or clear) this server's pins |
| `/mimic selftest` | Check the blocks around you for shifted ids now |

Pins take effect when you rejoin.

## Files

Everything Mimic saves is under `config/mimic/`:

| Path | Contents |
| --- | --- |
| `packs/` | Imported jar assets (`jar_*`) and your own textures (`user`) |
| `reports/` | Join reports |
| `neoforge_channels.json` | NeoForge channels learned per server |
| `state_anchors.json` | Block-state pins per server |
| `failed_servers.json` | Servers marked in the server list |
| `block_outgoing.txt` | Packets and channels never sent (edited from Privacy) |
| `privacy.json` | The client brand to report |
| `forge_servers.txt` | Servers known to run legacy Forge |

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client with ViaFabricPlus.
JEI is only compiled against; to use it in the development client, put the JEI jar in `run/mods`.

## Limitations

- Mods' behaviour doesn't exist on your client: no custom screens (only a plain slot grid), machine
  animations, entity animations or client-side effects. Entity models written in Java code can't be read.
- Data a mod adds to vanilla things (custom data components, entity data) can't be read and is skipped.
- Block states of server-only blocks are guessed; without the mod's jar, some blocks may show the wrong
  variant until you import it or pin them.
- Forge support covers the login handshake and ids; Forge's play-time mod networking isn't emulated.
- NeoForge servers don't get placeholders for their mods' blocks and items yet.

## License

[CC0 1.0](LICENSE)
