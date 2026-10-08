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
- `NPC`: with Citizens installed, creates a temporary player NPC that wears the logged-out player's inventory and keeps the player's health.
- The inventory/XP are written to `plugins/RaidPvP/pending.yml` before the player data is cleared:
  - NPC killed + `drop-inventory` / `drop-experience`: that part is dropped.
  - NPC killed with drop disabled, NPC expired, server restart or crash: the owner gets everything back on the next login.
- NPC mode blocks the same player from logging back in until the NPC is killed or expires, when enabled.
  Expiry runs on the global scheduler, so an unloaded chunk can no longer lock a player out.
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
