# Grand Tab List

A server-side Forge **1.16.5** mod that turns the Tab player list into a styled server screen:

- a logo, title and subtitle at the top;
- a live **ONLINE PLAYERS** count;
- kingdom rank prefixes such as **[K1 MONARCH] Ali**;
- live ping;
- animated gradient rank prefixes in Tab, chat and over players' heads;
- Discord and website lines at the bottom.

Made for The Grand Expedition (Crazy Craft Updated). It's separate from Flan Warfare & Conquest.

> **Status:** compiles on GitHub Actions against Forge 1.16.5-36.2.39, and its 53 automated
> logic checks pass. It has **not** been tested on a running server yet; see
> [docs/TESTING.md](docs/TESTING.md).

---

## 1. What is possible without a client mod (feasibility)

The reference screenshot was checked against what a 1.16.5 **server** controls on an unmodified
client.

| Part of the screenshot | Server-only (Option A)? | How |
|---|---|---|
| Logo image at the top | **Yes, with the server resource pack** | The logo is a font character (bitmap glyph `U+E000` in font `grandtab:logo`). 1.16 text supports custom fonts, so the header can display it. |
| Title, subtitle, colours, bold | **Yes** | The header is a text component with full RGB colours. Each line is centred by the client. |
| ONLINE PLAYERS: X | **Yes** | Rebuilt on join, leave and every 2 s. |
| Player heads | **Yes (vanilla)** | Drawn by the client when the server runs `online-mode=true`. |
| Rank prefix + coloured name | **Yes** | Forge's `TabListNameFormat` event sets each player's tab name. **No scoreboard teams are involved**, so mods that remove players from teams can't remove the prefixes. |
| Ping bars | **Yes (vanilla)** | Resent every 2 s instead of every 30 s. Optional "45ms" text after names. |
| Discord / website footer | **Yes** | Footer text component. |
| Horizontal lines | **Yes** | Struck-through spaces. |
| Sorting by rank | **Partly** | Vanilla sorts the list by scoreboard team name, then player name. Rank sorting therefore needs teams (optional, see §5). |
| Custom background panel, its size, transparency or texture | **No** | The client draws the Tab background itself, and the server can't change it. Doing so needs Option B (a client mod). |
| Hiding ping bars | **No** | The client always draws them. |
| Logo for players who decline the resource pack | Text fallback | 1.16.5 can't force a pack. The mod sees each player's pack answer and shows the logo to those who loaded it. Everyone else gets the title as text, so nobody sees broken boxes. |

**Recommendation: Option A, this mod.** It reproduces the screenshot's content and style, and
players install nothing. Only the exact background panel would need Option B. Option B means
every player installs a client mod, and the background is the only visible difference.

**Do clients need to install anything?** No. The mod tells Forge it is server-only, registers no
blocks, items or network channels, and only uses vanilla packets. The resource pack is offered by
the server automatically when players join; it's optional.

---

## 2. Installation on Pterodactyl

1. **Get the jar.** In GitHub, open **Actions**, click the newest green **Build** run, and
   download **grandtab-jar**. It contains `grandtab-1.0.0-bN.jar` (use the one *without*
   `-sources`) and `GrandTab-ResourcePack.zip`.
2. **Stop the server** in the Pterodactyl panel.
3. **File Manager → `mods/`**: upload `grandtab-1.0.0-bN.jar`. Remove any older `grandtab-*.jar`.
4. **Start the server.** The mod creates `config/grandtab/config.json` and
   `config/grandtab/assignments.json`, and the log shows `Grand Tab List ... ready`.
5. **Set the texts.** Edit `config/grandtab/config.json` in the File Manager (Discord, website,
   title …) and run `/tabrank reload`. Or use the commands in §4 without touching the file.
6. **Add the logo (optional).** See §3.

To update: stop the server, replace the jar, start again. Your config and assignments are kept.

### Building the jar yourself

GitHub builds it on every push (`.github/workflows/build.yml`). Locally, with JDK 8:

```
./gradlew build
```

The jar appears in `build/libs/`. The first build downloads Minecraft and Forge, which takes a
few minutes.

---

## 3. The logo (server resource pack)

`GrandTab-ResourcePack.zip` contains the "THE GRAND EXPEDITION" pixel logo (132×56) as the
glyph `U+E000` of the font `grandtab:logo`.

1. **Host the zip at a direct-download URL.** The easiest way is this repository. It must be
   **public** for the link to work:
   `https://raw.githubusercontent.com/mohkhadesign-droid/grand-tablist/main/resourcepack/GrandTab-ResourcePack.zip`.
   A Dropbox link ending in `?dl=1` also works. Discord attachment links expire, so don't use
   them.
2. **In Pterodactyl → File Manager → `server.properties`**, set:
   ```
   resource-pack=https://raw.githubusercontent.com/mohkhadesign-droid/grand-tablist/main/resourcepack/GrandTab-ResourcePack.zip
   resource-pack-sha1=3ea9306fed9910323c17c8515748c5976b142b1c
   ```
   The SHA-1 above matches the zip in this repository. If you change the zip, run
   `python3 tools/build_pack.py`; it prints the new value.
3. **Restart.** Players are asked to accept the pack when they join. With `logoMode: "AUTO"`
   (the default), players who loaded it see the logo; the others see the title as text.

**Your own logo:**

1. Replace `resourcepack/source/assets/grandtab/textures/font/logo.png`.
2. Set `"height"` in `resourcepack/source/assets/grandtab/font/logo.json` to the image height.
3. Set `logoSpacerLines` to about `height / 9`.
4. Rebuild the zip and update the SHA-1.

`tools/make_logo.py` is the script that drew the current logo.

> If Crazy Craft Updated already offers its own server resource pack, merge the `assets/grandtab`
> folder into that pack. A server can offer only one pack.

---

## 4. Commands

All commands need permission level 2 (operators) by default; this is `commandPermissionLevel`
in the config. Changes are saved immediately and appear in Tab within a second. `<player>`
works for offline players the server has seen before.

| Command | What it does |
|---|---|
| `/tabrank set <player> <kingdom> <role>` | Give a rank, e.g. `/tabrank set Ali K1 monarch` → **[K1 MONARCH] Ali** |
| `/tabrank clear <player>` | Remove the rank |
| `/tabrank info <player>` | Their rank and resource-pack status |
| `/tabrank list` | All ranked players by kingdom and role |
| `/tabrank kingdoms` | Kingdom and role ids, names and colours |
| `/tabrank kingdom <K1> color <green\|#55FF55>` | Kingdom colour |
| `/tabrank kingdom <K1> name <Kingdom I>` / `tag <K1>` | Kingdom name / the short tag used in prefixes |
| `/tabrank role <monarch> name <MONARCH>` | Role display name |
| `/tabrank role <monarch> color <gold\|#FFD700\|kingdom>` | Role colour (`kingdom` = use the kingdom colour) |
| `/tabrank role <monarch> bold true\|false` | Bold prefix for that role |
| `/tabrank title <text>` / `subtitle <text>` | Header texts |
| `/tabrank discord <text>` / `website <text>` | Footer texts |
| `/tabrank source tabrank\|serverutilities\|both` | Where prefixes come from (see §6). `both` = `/tabrank` rank if set, otherwise the Server Utilities prefix. |
| `/tabrank enable` / `disable` | Switch the custom tab list on or off (off = vanilla) |
| `/tabrank reload` | Re-read both files after editing them by hand |
| `/tabrank version` | Installed version and build |

---

## 4b. Rank gradients and animations

Gradients recolour rank prefixes such as `[KING]`: gold → yellow → white, optionally
animated. **Players install nothing.** Minecraft 1.16 can colour every character separately,
and the server re-sends the text for each animation frame.

### Which rank a gradient belongs to

A gradient is stored under a **rank key**:

- **A Server Utilities rank name**, e.g. `testking` or `k3warden`. This is the internal name,
  not the displayed prefix. A player uses the gradient of their dominant Server Utilities rank,
  the one whose prefix Server Utilities shows in chat.
- **For `/tabrank` ranks:** `K1.monarch` (one kingdom's role) or `monarch` (that role in every
  kingdom).

Server Utilities ranks and permissions are never modified. Its rank names and prefixes are read
by reflection, because Server Utilities 2.8.0 has no public API for them; see §6.

### Commands (operators)

| Command | What it does |
|---|---|
| `/rankgradient set testking #FFAA00 #FFFF55 #FFFFFF` | Gradient with any number of colour stops (minimum 2). `#` is optional; names like `gold` work too. |
| `/rankgradient animate testking wave` | `static`, `wave` (colours slide along), `pulse` (brightness breathes), `rainbow` (cycling hues, ignores the stops), `shine` (a white highlight sweeps across) |
| `/rankgradient speed testking 3` | 1 (slow) to 10 (fast) |
| `/rankgradient static testking` | Stop the animation and keep the gradient |
| `/rankgradient addcolor testking #FF4500` | Add a colour at the end |
| `/rankgradient setcolor testking 2 #FFD700` | Change colour number 2 |
| `/rankgradient removecolor testking 2` | Remove colour number 2 (at least two remain) |
| `/rankgradient bold testking true\|false\|default` | Force bold on or off, or keep the prefix's own `&l` |
| `/rankgradient remove testking` | Delete the gradient; the normal prefix colours return |
| `/rankgradient preview testking` | Show the prefix with its gradient in chat |
| `/rankgradient list` | All gradients with previews; click one to edit it |
| `/rankgradient reload` | Re-read `config/grandtab/gradients.json` after editing it by hand |
| `/rankgradient gui [rank]` | The clickable editor (below) |

Every change is saved at once to `config/grandtab/gradients.json` and appears within a second.
`gradients.json` looks like this:

```json
{
  "testking": { "colors": ["#FFAA00", "#FFFF55", "#FFFFFF"], "animation": "WAVE", "speed": 3, "bold": true }
}
```

### The editor: `/rankgradient gui`

The editor opens **in chat**, server-only, with clickable parts:

- **Rank list:** the Server Utilities ranks, each with its current look; click one.
- **Preview:** the prefix as it will look, followed by a sample name.
- **Colours:** each colour shown as a coloured block.
  - Click a block to change it: the command is put in your chat box with the hex code, so you
    edit the hex and press Enter.
  - Click **×** to remove a colour.
  - Click **[+ hex]** to type any `#RRGGBB`.
- **Add:** a palette of 24 colours. Click one to append it.
- **Animation:** static, wave, pulse, rainbow, shine, with the current one highlighted.
- **Speed:** `[-]` and `[+]`.
- **Bold:** cycles default, on and off.
- **Remove gradient:** puts the command in your chat box; press Enter to confirm.

Changes save instantly and the editor redraws with the new preview.

**A real graphical window needs a client mod.** A colour wheel, sliders or a live animated
preview can't be drawn by a server; only the client can draw GUI screens. This could be a
separate small client mod that **only admins install**: it would send its changes to the server,
and ordinary players still need nothing. It isn't part of this build. Ask if you want it.

### Where gradients appear

| Place | Server-only? | How | Animated? |
|---|---|---|---|
| **Tab list** | Yes | The tab name (Forge `TabListNameFormat`, no scoreboard teams) | Yes. Only players whose rank is animated are re-sent each frame. |
| **Chat** | Yes | Server Utilities builds the chat line as usual. This mod runs after it (lowest priority) and replaces only the prefix inside that line with the gradient version. The name, message and clickable links stay exactly as Server Utilities made them. | No. A sent chat line can't change, so each message shows the animation frame of the moment it was sent. |
| **Over the head** | Yes | An invisible label entity riding the player shows the prefix on its own line, above the name (`ABOVE`, default) or between the name and the head (`BELOW`). The vanilla nametag can only be decorated through scoreboard teams, which another mod on your server keeps clearing, so they aren't used. | Yes |

**What the head labels do:**

- **They never get in the way.** They have no hitbox, so they never block hits, arrows or clicks.
- **They hide when they should:** while the player sneaks, is invisible or spectates; while
  dead; and under water, because vanilla drops riders there.
- **They clean up after themselves.** They're removed on logout, death, dimension change and
  server stop. Leftovers after a crash are deleted automatically.

**`&g` and `&h` no longer appear literally.** These are Server Utilities' own codes:

- `&g` is its rainbow: every character cycles through `4 c 6 e 2 a b 3 1 9 d 5`.
- `&h` uses this month's colours from its config.

The Tab list and head labels now draw them the same way Server Utilities does in chat. A
gradient, when set, replaces them.

### Settings (`config.json`, section `gradients`)

```json
"gradients": {
  "enabled": true,
  "frameTicks": 4,
  "inTabList": true,
  "inChat": true,
  "nametags": { "enabled": true, "position": "ABOVE", "showAllPrefixes": true }
}
```

| Key | Meaning |
|---|---|
| `enabled` | Master switch for gradients and head labels |
| `frameTicks` | Ticks between animation frames. 4 = 5 frames per second. Raise it (e.g. 10) to send fewer updates. |
| `inTabList` / `inChat` | Where gradients are applied |
| `nametags.enabled` | Rank labels over heads |
| `nametags.position` | `ABOVE` or `BELOW` |
| `nametags.showAllPrefixes` | `true`: every ranked player gets a head label. `false`: only ranks with a gradient. |

**Network cost:**

- **Static gradients** are sent only when something changes.
- **Animated ranks:**
  - Tab list: one small "display name" update per animated player per frame.
  - Head labels: one entity-name update per animated player per frame, sent only to players
    near them.
- With 15 players at the default 5 frames per second, that's a few kilobytes per second in
  total.

## 5. Configuration (`config/grandtab/config.json`)

Plain JSON. Run `/tabrank reload` after editing. If the file has a syntax error, the error is
logged and reported by `/tabrank reload`, the previous settings stay active, and the file is
**not** overwritten. Colours accept the 16 Minecraft names (`gold`, `dark_purple` …) or
`#RRGGBB`.

```json
{
  "enabled": true,
  "updateIntervalTicks": 40,
  "pingUpdateIntervalTicks": 40,
  "commandPermissionLevel": 2,
  "header": {
    "logoMode": "AUTO",
    "logoFont": "grandtab:logo",
    "logoGlyph": "",
    "logoSpacerLines": 6,
    "showTitleWithLogo": false,
    "title": "THE GRAND EXPEDITION",
    "titleColor": "#55FFFF",
    "titleBold": true,
    "showSubtitle": true,
    "subtitle": "CRAZY CRAFT UPDATED",
    "subtitleColor": "#FFAA00",
    "subtitleBold": true,
    "showRule": true,
    "ruleColor": "#555555",
    "ruleWidth": 48,
    "showPlayerCount": true,
    "playerCountLabel": "ONLINE PLAYERS: ",
    "playerCountLabelColor": "#AAAAAA",
    "playerCountValue": "{online}",
    "playerCountValueColor": "#55FF55"
  },
  "footer": {
    "lines": [
      { "label": "DISCORD: ", "labelColor": "#AAAAAA", "value": "discord.gg/yourserver", "valueColor": "#5599FF", "bold": true },
      { "label": "WEBSITE: ", "labelColor": "#AAAAAA", "value": "yourserver.com", "valueColor": "#55FFFF", "bold": true }
    ],
    "showRule": true
  },
  "players": {
    "prefixFormat": "[{kingdom} {role}]",
    "nameColor": "KINGDOM",
    "nameBold": false,
    "unrankedNameColor": "#FFFFFF",
    "showPingMs": false,
    "rankSource": "TABRANK_THEN_SERVER_UTILITIES",
    "serverUtilitiesNameColor": "PREFIX",
    "sortByRank": false
  },
  "kingdoms": [
    { "id": "K1", "name": "Kingdom I",   "tag": "K1", "color": "#55FF55" },
    { "id": "K2", "name": "Kingdom II",  "tag": "K2", "color": "#5599FF" },
    { "id": "K3", "name": "Kingdom III", "tag": "K3", "color": "#BB66FF" }
  ],
  "roles": [
    { "id": "monarch",  "name": "MONARCH",  "order": 1, "bold": true,  "color": "#FFD700" },
    { "id": "champion", "name": "CHAMPION", "order": 2, "bold": true,  "color": "" },
    { "id": "warden",   "name": "WARDEN",   "order": 3, "bold": true,  "color": "" },
    { "id": "sage",     "name": "SAGE",     "order": 4, "bold": false, "color": "" },
    { "id": "knight",   "name": "KNIGHT",   "order": 5, "bold": false, "color": "" }
  ]
}
```

**Top level**

| Key | Meaning |
|---|---|
| `enabled` | `false` = vanilla tab list (names, header and footer cleared) |
| `updateIntervalTicks` | How often everything is checked for changes (20 ticks = 1 s). Join, leave and commands refresh right away. |
| `pingUpdateIntervalTicks` | How often ping bars are resent |

**`header`**

| Key | Meaning |
|---|---|
| `logoMode` | `AUTO` (logo if the player loaded the pack, otherwise the title as text), `PACK` (always the logo), `TEXT` (always text), `NONE` (no logo or title) |
| `logoFont` / `logoGlyph` | Font and character of the logo in the resource pack |
| `logoSpacerLines` | Empty lines reserved under the logo |
| `showTitleWithLogo` | Also show the title text under the logo |
| `title*` / `subtitle*` | Header texts, colours and bold |
| `showRule`, `ruleColor`, `ruleWidth` | The horizontal lines at the top and bottom |
| `playerCount*` | Label and number; `{online}` and `{max}` are filled in |

**`footer`**

| Key | Meaning |
|---|---|
| `lines` | Any number of label + value lines. `/tabrank discord` and `/tabrank website` edit the first two. |

**`players`**

| Key | Meaning |
|---|---|
| `prefixFormat` | `{kingdom}` (tag, K1), `{kingdom_name}` (Kingdom I), `{role}` (MONARCH) |
| `nameColor` | `KINGDOM`, `ROLE` (prefix colour), or a fixed colour |
| `showPingMs` | Adds " 45ms" after names, coloured by quality |
| `rankSource` | `TABRANK` (only `/tabrank`), `SERVER_UTILITIES` (only the Server Utilities rank prefix), or `TABRANK_THEN_SERVER_UTILITIES` (default: the `/tabrank` rank if the player has one, otherwise their Server Utilities prefix; never both) |
| `serverUtilitiesNameColor` | For players shown with a Server Utilities prefix: `PREFIX` (the prefix's last colour) or a fixed colour |
| `sortByRank` | Order the list by kingdom, then role, using scoreboard teams `gt0001`…`gt9999`. Players another mod puts into its own team keep that team and appear in vanilla order; `/tabrank list` names them. Off by default, because you reported that something on your server removes team members. |

**`kingdoms`** and **`roles`**

- Add, remove or rename freely.
- `id` is what commands use. `tag` and `name` are what players see.
- A role's `color` empty means the kingdom colour.
- The prefix uses the role colour; names use `nameColor`.
- Kingdoms are listed in file order and roles by `order` (for `/tabrank list` and sorting).

**`assignments.json`** is written by `/tabrank set` and `clear`. It is keyed by player UUID, so
name changes don't lose ranks:

```json
{
  "0f3e…-uuid": { "name": "Ali", "kingdom": "K1", "role": "monarch" }
}
```

---

## 6. Compatibility notes

- **Server Utilities 2.8.0 (Elrol's)**: integrated, verified against
  `Server_Utils-1.16.5-2.8.0.jar`.
  - **Its ranks show in Tab.** The Tab list can show the same prefix Server Utilities uses in
    chat: the player's dominant rank prefix, e.g. `&5[K3 WARDEN]`. Colour codes are converted,
    including `&l` bold and `&#RRGGBB`.
  - **Read only.** Ranks are read by reflection when Server Utilities is installed; nothing is
    bundled, nothing is written, and its permissions and chat are untouched.
  - **Choosing the source:** with the default `rankSource`, a `/tabrank` rank wins; otherwise
    the Server Utilities prefix is used. Use `/tabrank source serverutilities` to use only
    Server Utilities ranks (managed with its own `/rank` commands), or `/tabrank source tabrank`
    to ignore them.
  - **Sorting:** with `sortByRank`, Server Utilities ranks sort by their weight (higher first),
    after `/tabrank` ranks.
  - **Changes appear within 2 s,** for example after `/rank` in Server Utilities.
  - **No duplicate prefixes.** A player is shown with one prefix only, and the name is built from
    the account name, not from display names other mods decorate.
  - **Not the team remover.** Server Utilities 2.8.0 contains no scoreboard-team code and does
    not set tab names, so it is not what kept removing players from your `/team` teams. That is
    another mod. This mod's prefixes don't depend on teams either way.
  - **Gradient integration (verified in the 2.8.0 jar):**
    - Rank names come from `Ranks.rankMap`.
    - A player's rank comes from `PlayerDatabase.get(uuid).getDomRank()`.
    - Its chat formatting comes from `Main.textUtils.formatString`, used to find the prefix
      inside its chat line.
    - The `&h` colours come from `FeatureConfig.<month>_colors`.

    Server Utilities 2.8.0 has no public API for any of this: its `IElrolAPI` only offers
    commands, text helpers and folders. So these public members of its implementation are used,
    every call is guarded, and if a future version changes them the integration switches itself
    off instead of crashing.
  - **Every `&g` chat message adds one log line.** Server Utilities logs a "Color List" line each
    time its `&g` rainbow is formatted; that's its own logging.
  - **Flan Warfare's rank chat clashes with it.** Server Utilities also rewrites chat lines, so
    disable one of the two chat formats (Flan Warfare: `rank_chat.enabled = false`).
- **Flan, FTB Teams, FTB Chunks:** untouched. The mod reads nothing from them and changes no
  claims, teams or chunks.
- **Flan Warfare & Conquest**
  - **Tab shows this mod's prefix only.** If its kingdom-rank nametags use `LEFT` (a scoreboard
    team prefix), the Tab list still shows only this mod's name, because a tab display name
    replaces the team prefix there.
  - **No rank sorting for those players.** Players in Flan Warfare's teams aren't sorted by
    rank. Set Flan Warfare's `rank_nametags.default_position` to `BELOW` if you use
    `sortByRank`.
- **Other mods that also set Tab names:** this mod's name wins. Set `enabled` to `false` to give
  control back.

---

## 7. Known limitations

- **Head labels can collide with Flan Warfare's.** If Flan Warfare's kingdom-rank labels
  (`rank_nametags` `ABOVE`/`BELOW`) are also on, both would float at the same height. Use one
  of them.
- **Chat gradients need Server Utilities to write the chat line.** They apply to Server
  Utilities ranks. If another chat mod formats chat instead, the line is left unchanged.
- **Not tested in game yet.** Follow `docs/TESTING.md` on a copy of the server first.
- **Background panel:** the Tab background can't be changed without a client mod (Option B).
- **Resource pack is optional for players:** 1.16.5 cannot force it, so decliners see the text
  title.
- **Logo size:** large logos make the header tall. Vanilla also limits how wide the list can be
  on small screens.
- **Sorting** depends on scoreboard teams (vanilla rule). Without `sortByRank`, players are
  listed alphabetically.
- **Player heads** need `online-mode=true`, which is the normal setting.
