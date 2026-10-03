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

### Other Minecraft versions (with ViaFabricPlus)
- Passes block, item, sound, particle and entity ids through ViaVersion untouched when the server synced them,
  so modded content keeps its ids instead of being mapped to something random.
- Rebuilds the server's block-state table from ViaVersion's data, so blocks after a changed vanilla block
  don't shift.
- Shows items the server has even if its Minecraft version doesn't (e.g. backported blocks).

### Placeholders
- **Blocks:** a missing-texture block with the right number of states, guessed from the mod's jar (its
  classes and block-state files) or from the block's name. Break/step sounds are borrowed from a similarly
  named vanilla block.
- **Items:** a placeholder item that shows its id and data components.
- **Entities:** a box with a name tag.
- **Hover info:** look at a placeholder to see what it stands in for.
- **Anchors:** if blocks still look shifted, `/mimic verify` pins a block you know is right, so a miscount
  earlier in the list stops there.

### Importing a mod's jar
From the pause menu, open **Server mods**, pick a mod, and import its jar (or drop the jar on the screen).
No restart needed.

- Only asset and data files are copied (`png`, `json`, `mcmeta`, `ogg`, `txt`, `lang`). **No code is
  loaded or run.**
- Vanilla files a jar ships are left alone, so a jar can't replace the game's own textures.
- Model files using a mod's own loader or model types are rewritten to vanilla equivalents so they render.
- Recipes are read for display only: on item pages, and in JEI under "Mimic: recipes from mod jars".
- You can also pick your own PNG for any placeholder.

### Debugging joins
- **Join reports:** every failed join writes a report to `config/mimic/reports/` with the connection log
  and the relevant game log.
- **Server list badge:** servers whose last join failed in a way Mimic can't get past yet are marked.
- **Retry until connected,** and a guard that stops servers that keep reconfiguring the client in a loop.
- **Packets:** a toggle in the pause menu shows a live feed of incoming packets (noisy ones hidden).
- **Data components:** shown in item tooltips with advanced tooltips (F3+H) and Shift.

## Commands

| Command | What it does |
| --- | --- |
| `/mimic mods` | Mods seen on this server and how many of their items, blocks and entities |
| `/mimic verify [block]` | Pin the looked-at block's position in the server's block-state numbering |
| `/mimic unverify <block>` | Remove one pin |
| `/mimic nudge <block> <n>` | Move a pin by `n` states |
| `/mimic anchors [clear]` | List (or clear) this server's pins |

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

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client with ViaFabricPlus
and JEI.

## Limitations

- Mods' behaviour doesn't exist on your client: no custom screens, machines, entity models or client-side
  effects.
- Data a mod adds to vanilla things (custom data components, entity data) can't be read and is skipped.
- Block states of server-only blocks are guessed; without the mod's jar, some blocks may show the wrong
  variant until you import it or pin them.
- Forge (pre-NeoForge) servers aren't supported yet.

## License

[CC0 1.0](LICENSE)
