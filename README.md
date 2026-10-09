# RaidPvP

RaidPvP is a standalone combat-management plugin designed for raiding / faction-style PvP servers.

## First version

### Combat tag
- PvP damage places both players into combat.
- Configurable combat duration.
- Hit refreshes the timer by default.
- BossBar + ActionBar countdown.
- Stores the player's pre-combat flight, invisibility and game mode state and restores it when combat ends.

### Newbie protection
- First-time players receive configurable protection.
- The remaining time is saved when a player leaves (offline time does not count) and survives restarts.
- A BossBar counts the protection down (`newbie.bossbar.*` in `config.yml`); the bar drains smoothly.
- PvP protection can be enabled independently from full-damage protection.
- `/newbie disable` lets a new player opt out.
- Admins can add/remove/check protection.

### Combat restrictions
- Command blacklist or whitelist (`/minecraft:tp`-style namespaced commands are matched too).
- Teleport blocking (pearl, chorus fruit, commands, plugin teleports; portals are not affected).
- Ender Pearl / Chorus Fruit blocking.
- Spectator / Creative switching control.
- Flight disabling.
- Invisibility disabling.
- Optional block place / break blocking.
- Optional container opening blocking.
- Optional interaction blocking by material.
- Optional Totem blocking.
- Per-item vanilla cooldowns while in combat (right click only).
- `blocked-items` also blocks using the block itself (ender chest, respawn anchor).
- Splash / lingering potions, TNT and end crystals fired by players follow the same PvP rules
  (`/pvp off`, newbie protection) and tag combat.

### Combat logging
- `KILL`: combat logout directly kills the player.
- `NPC`: with Citizens installed, creates a temporary player NPC that wears the logged-out player's inventory and keeps the player's health. The NPC stands still (no knockback).
- The inventory/XP are written to `plugins/RaidPvP/pending.yml` before the player data is cleared:
  - NPC killed + `drop-inventory` / `drop-experience`: that part is dropped.
  - NPC killed with drop disabled, NPC expired, server restart or crash: the owner gets everything back on the next login.
- The owner may log in at any time and takes the NPC's place (NPC's current health and position, items returned).
  `combat-log.block-login-while-npc-exists: true` blocks that instead.
- If the NPC was killed, the owner **dies** on the next login (keeping whatever the NPC did not drop).
- Expiry runs on the global scheduler, so an unloaded chunk can no longer lock a player out.
- If Citizens is unavailable or NPC creation fails, the plugin falls back to `KILL`.
- Combat-logout deaths never count for the anti kill-abuse limit.
- Kicks by admins / restarts / bans / plugins are not punished (`combat-log.kick-exempt-causes`); a server shutdown never is.

### Other
- Per-player `/pvp on|off` toggle with cooldown. With `pvp-toggle.enabled: false` everybody follows `default-enabled`.
- `disabled-worlds`: RaidPvP does nothing in those worlds.
- Flight / game mode / invisibility are also restored after death or logout while tagged.
- Respawn protection.
- Anti kill-abuse limit.
- Disabled-world list.
- Folia-safe entity/global scheduling.

## Commands

Player:
- `/pvp [on|off]`
- `/pvptag`
- `/newbie [disable]`

Admin:
- `/raidpvp reload`
- `/raidpvp status [player]`
- `/raidpvp tag <player> [seconds]`
- `/raidpvp untag <player>`
- `/raidpvp newbie <add|remove|check> <player> [minutes]`

## Dependencies

Paper/Purpur/Folia 1.21.4+.
Java 21+ bytecode.
Citizens (2.0.37-SNAPSHOT API tested against) is optional and is only required for `combat-log.mode: NPC`.

## Build

The included GitHub Actions workflow builds a direct JAR and uploads `build/libs/*.jar` as the workflow artifact.

For local development:

```bash
gradle clean build
```

## Messages
- `plugins/RaidPvP/messages.yml` holds every chat message of the plugin (commands, restrictions, NPC, newbie...).
  Missing keys fall back to the bundled defaults; messages found in an old `config.yml` are moved over once.
- The combat ActionBar / BossBar texts stay in `config.yml` (`combat.actionbar.message`, `combat.bossbar.message`,
  `newbie.bossbar.message`).
- `%time%` is always a plain number. Write the unit yourself: `%time%s`, `%time% 秒`, ...
  The newbie bar also knows `%minutes%` and `%mmss%` (e.g. `29:59`).

## PlaceholderAPI (optional)
All countdown placeholders return a plain number without unit.

| Placeholder | Value |
|---|---|
| `%raidpvp_in_combat%` | `true` / `false` |
| `%raidpvp_combat_time%` | seconds left in combat (0 if none) |
| `%raidpvp_combat_enemy%` | opponent name |
| `%raidpvp_newbie%` | `true` / `false` |
| `%raidpvp_newbie_time%` | newbie protection seconds left |
| `%raidpvp_newbie_minutes%` | minutes left, rounded up |
| `%raidpvp_newbie_mmss%` | e.g. `29:59` |
| `%raidpvp_pvp%` | `/pvp` state |

## API for other plugins
Add `softdepend: [RaidPvP]` to your plugin.yml, then:

```java
RaidPvPAPI api = RaidPvPAPI.get(); // null while RaidPvP is not enabled
// or: Bukkit.getServicesManager().load(RaidPvPAPI.class)

if (api != null && api.isNewbieProtected(player)) {
    // e.g. refuse the duel invite
}
long seconds = api.getNewbieRemainingSeconds(player.getUniqueId());
boolean fighting = api.isInCombat(player.getUniqueId());
```

Compile against the RaidPvP jar (`compileOnly files("libs/RaidPvP.jar")`). Methods taking a `UUID` are safe from any thread.
