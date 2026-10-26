# Test plan and results

**Honest status:**

- **Executed:**
  - The project compiles and reobfuscates on GitHub Actions against Forge 1.16.5-36.2.39.
  - `TabLogicSelfTest` passes: all 53 checks (colours, prefix format, sorting-team order and
    names, Server Utilities weight sorting, legacy colour-code parsing including Server
    Utilities' &g and &h, gradient maths and every animation, speed, ping colours, player count,
    ids).
  - The Server Utilities members used were read from `Server_Utils-1.16.5-2.8.0.jar` with javap.
  - Every Minecraft/Forge member the mod uses was checked against the mapped Forge jar (javap).
- **Executed for the b4 head-label fix:** compile only. The new label code uses only members
  confirmed in the mapped Forge jar by compilation. The behaviour in game is untested.
- **Not executed:** every in-game scenario below. They need a running server with clients.

## In-game scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| T1 | Join without the resource pack configured | Header: rule, title (cyan, bold), subtitle (gold), ONLINE PLAYERS: 1; footer: Discord, website, rule. |
| T2 | `/tabrank set <you> K1 monarch` | Within 1 s your Tab name is **[K1 MONARCH]** (gold, bold) followed by your name in green. Ping bars and head unchanged. |
| T3 | Second account joins / leaves | Count goes to 2 / back to 1 within a second; the new player has a head and ping. |
| T4 | `/team add test` then `/team join test <you>`; then `/team leave <you>` | Prefix stays in Tab both times. |
| T5 | Restart the server | Assignments and edited texts still there. |
| T6 | Configure the resource pack (README §3), accept it on join | Logo image at the top instead of the title text; no overlap with the subtitle. |
| T7 | Decline the pack on another account | That player sees the text title; the first still sees the logo. `/tabrank info` shows DECLINED / SUCCESSFULLY_LOADED. |
| T8 | `/tabrank kingdom K3 color #AA00FF`, `/tabrank role warden name GUARDIAN` | K3 names update; prefixes read [K3 GUARDIAN]. |
| T9 | `/tabrank discord discord.gg/test`, `/tabrank title MY SERVER` | Footer and header update without a restart. |
| T10 | Break `config.json` (remove a brace), `/tabrank reload` | Error message; tab list keeps working; file untouched. Fix it, reload: OK. |
| T11 | `showPingMs: true`, reload | " 45ms" after names, colour by quality, updating every 2 s. |
| T12 | `sortByRank: true`, reload, with players from several kingdoms | Order K1 monarch … K3 knight, unranked last. A player in a foreign team keeps it and is listed in `/tabrank list`. |
| T13 | `/tabrank disable` | Vanilla tab list (no header/footer, plain names). `enable` restores it. |
| T14 | With Server Utilities ranks active | Server Utilities chat unchanged; Tab shows only this mod's prefix (no duplicates). |
| T15 | 15+ players (or as many accounts as available) | All listed; header and footer still centred. |
| T16 | Player dies and respawns | Prefix still shown. |
| T17 | A client with the modpack but without this mod joins | Can join; the mod is not required on the client. |
| T18 | Server Utilities installed, player with an SU rank prefix (e.g. `&5[K3 WARDEN]`) and no `/tabrank` rank | Tab shows the SU prefix in its colours, name in the prefix's last colour. `/tabrank info` shows "connected" and the rank. |
| T19 | Same player gets `/tabrank set … K3 warden` | Only the `/tabrank` prefix is shown (no duplicate). `/tabrank clear` brings the SU prefix back. |
| T20 | Change the player's SU rank with SU's `/rank` command | Tab updates within 2 s. |
| T21 | `/tabrank source tabrank` / `serverutilities` | SU prefixes ignored / only SU prefixes used. |
| T22 | Remove Server Utilities from the server | Mod still loads; `/tabrank info` says it is not installed. |

## Rank gradient scenarios (all NOT RUN)

| # | Scenario | Expected |
|---|---|---|
| G1 | Player with SU rank `testking` (prefix using `&g`) before any gradient | Tab and head label show [KING] in the SU rainbow; no literal `&g`. |
| G2 | `/rankgradient set testking #FFAA00 #FFFF55 #FFFFFF` | Within 1 s: Tab and head label show [KING] gold → yellow → white; next chat message too. |
| G3 | `/rankgradient animate testking wave`, then `speed testking 8`, then `static testking` | Colours slide in Tab and over the head, faster after speed 8, stop after static. Chat lines each show one frame. |
| G4 | `pulse`, `rainbow`, `shine` | Breathing brightness / cycling hues / white sweep. |
| G5 | `/rankgradient set testking #FFAA00` (one colour) / `set testking #GGGGGG #FFFFFF` | Refused with a clear message. |
| G6 | `/rankgradient set nosuchrank #FF0000 #00FF00` | Refused; lists the SU rank names. |
| G7 | `/rankgradient gui` → click testking → click palette colours, ×, speed [+], wave | Each click saves and redraws the editor with the new preview. |
| G8 | Restart the server | Gradients still there (`gradients.json`). |
| G9 | Edit `gradients.json` by hand, `/rankgradient reload`; then break it and reload | Changes applied; broken file reported and previous gradients kept. |
| G10 | Chat with a link from a gradient rank | Prefix in gradient; name and message as SU formats them; link still clickable. |
| G11 | `/team` a player, or let the other mod clear teams | Tab prefix and head label unaffected. |
| G12 | Player with head label sneaks / swims / dies / changes dimension / logs out | Label hidden while sneaking / stays on the player while swimming / gone while dead, back after respawn / shown in the new dimension / removed. No stray floating labels. |
| G13 | Hit the player in the head area, shoot arrows, right-click them | Never blocked by the label. |
| G14 | `nametags.position` BELOW, `/tabrank reload` | Label between name and head. |
| G15 | `frameTicks` 10 and 15 players online with animated ranks | No noticeable lag (check `/forge tps`). |
| G16 | `/rankgradient set K1.monarch #FFD700 #FFFFFF` for a `/tabrank` monarch | That prefix gets the gradient. |

## Head label fix (1.0.0-b4) scenarios (all NOT RUN)

Two accounts: A watches, B has a rank prefix (e.g. `[K3 MONARCH]`).

| # | Scenario | Expected |
|---|---|---|
| N1 | B walks, sprints, jumps, flies (creative/elytra), swims | A sees the label exactly over B at all times. |
| N2 | B teleports far (`/tp`, Server Utilities `/home` or `/spawn`, ender pearl) | No label left at the old spot; label appears over B at the destination when A is there. |
| N3 | B walks out of A's view distance and back | Label disappears with B and comes back with B. |
| N4 | B dies (PvP near a flagpole) and respawns elsewhere | Label gone during death; back over B after respawn; nothing left at the death spot. |
| N5 | B goes through a Nether portal and back | Label follows; nothing left at either portal. |
| N6 | B logs out and in; A logs out and in | Label gone / back; A sees B's label again after rejoining. |
| N7 | B sneaks / drinks invisibility / spectator mode | Label hidden / gone / gone; back afterwards. |
| N8 | B gets in a boat or on a horse | Label stays over B. |
| N9 | `nametags.position` BELOW/ABOVE, `/tabrank reload` | Label moves under/over B's name; no duplicate. |
| N10 | Animated gradient on B's rank | Colours animate on the label; `/forge tps` unchanged. |
| N11 | Start the server once with b3 to create labels, crash it (kill the process), start with b4 | No floating labels; log says leftover labels removed (if any were loaded). |
| N12 | `/tabrank cleanup` with a normal armour stand and an armour stand of another mod nearby | Only GrandTab leftovers removed; other armour stands untouched. |
