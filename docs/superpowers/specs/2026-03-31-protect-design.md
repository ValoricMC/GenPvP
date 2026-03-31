# Protect System Design
**Date:** 2026-03-31
**Plugin:** GenPvP (Paper 1.21, Java 21)

---

## Overview

The Protect system handles two responsibilities:
1. **Region protection** — named cuboid regions with hardcoded per-type rule enforcement
2. **Block decay timers** — survival-placed blocks expire to air after a configurable duration

---

## File Layout

```
src/main/java/me/revqz/genPvP/
└── protect/
    ├── flags/
    │   ├── RegionRule.java       # Enum of all rule flags
    │   └── RegionType.java       # Enum of region types, each with Set<RegionRule>
    ├── ProtectRegion.java        # Data class: name, type, world, two corners (BlockVector3)
    ├── RegionManager.java        # In-memory store + MongoDB load/save
    ├── BlockTimerManager.java    # Tracks survival-placed blocks, schedules decay
    ├── ProtectListener.java      # Enforces region rules + block timers via Bukkit events
    └── ProtectCommand.java       # /region pos1|pos2|define|bypass
```

`GenPvP.java` instantiates `RegionManager` and `BlockTimerManager` on enable, registers `ProtectListener` and `ProtectCommand`.

---

## Dependencies

- **FastAsyncWorldEdit (FAWE)** — compileOnly, used for `BlockVector3` region math
- **PlaceholderAPI** — compileOnly, registered for future placeholder expansion
- **MongoDB Driver** — for region persistence

Both added to `build.gradle` and declared in `plugin.yml` as `softdepend` (FAWE) and `depend` (PlaceholderAPI is optional).

---

## config.yml

```yaml
mongodb-uri: "mongodb://localhost:27017"
block-timer-seconds: 600
block-decay-sound: BLOCK_SAND_BREAK
```

---

## Region Flags (`RegionRule.java`)

```java
public enum RegionRule {
    ALLOW_DAMAGE,
    ALLOW_BREAK,
    ALLOW_PLACE,
    ALLOW_MOB_SPAWN,
    ALLOW_KNOCKBACK,
    ALLOW_FLINT_STEEL,
    ALLOW_INTERACT       // anvils, enchant tables, grindstones, crafting tables
}
```

---

## Region Types (`RegionType.java`)

Each type is an enum constant holding an `EnumSet<RegionRule>` of what is **allowed**.

| Type | Allowed Rules |
|---|---|
| `SPAWN` | `ALLOW_INTERACT` only |
| `GENS` | `ALLOW_BREAK`, `ALLOW_INTERACT` |
| `OPMINES` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK` |
| `OPMINESGENS` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK`, `ALLOW_BREAK`, `ALLOW_PLACE` |
| `KOTH` | `ALLOW_DAMAGE`, `ALLOW_KNOCKBACK`, `ALLOW_PLACE` |
| `KOTHCAPTURE` | `ALLOW_DAMAGE`, `ALLOW_KNOCKBACK` |
| `PVPROOM1` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK` |
| `PVPROOM2` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK` |
| `PVPROOMGATE1` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK` |
| `PVPROOMGATE2` | `ALLOW_DAMAGE`, `ALLOW_MOB_SPAWN`, `ALLOW_KNOCKBACK` |
| `PIT` | `ALLOW_DAMAGE`, `ALLOW_KNOCKBACK` |

---

## Region Data (`ProtectRegion.java`)

```java
public class ProtectRegion {
    String name;
    RegionType type;
    String world;
    int minX, minY, minZ;
    int maxX, maxY, maxZ;

    boolean contains(Location loc);
    boolean hasRule(RegionRule rule);
}
```

`hasRule(rule)` delegates to `type.getAllowedRules().contains(rule)`.

---

## RegionManager

- On startup: connects to MongoDB using URI from config, loads all regions into `Map<String, ProtectRegion>`
- `getRegionsAt(Location)` — returns all regions containing that location
- `defineRegion(name, type, pos1, pos2, world)` — creates, stores in memory, and upserts to MongoDB
- MongoDB collection: `regions`, document fields match `ProtectRegion` fields

---

## ProtectListener

Handles the following events, checks `RegionManager.getRegionsAt()` for each relevant player location:

| Event | Rule checked | Action if denied |
|---|---|---|
| `EntityDamageByEntityEvent` | `ALLOW_DAMAGE` | Cancel |
| `BlockBreakEvent` | `ALLOW_BREAK` | Cancel |
| `BlockPlaceEvent` | `ALLOW_PLACE` | Cancel |
| `CreatureSpawnEvent` | `ALLOW_MOB_SPAWN` | Cancel (non-player spawns) |
| `EntityKnockbackEvent` | `ALLOW_KNOCKBACK` | Cancel |
| `PlayerInteractEvent` (flint & steel) | `ALLOW_FLINT_STEEL` | Cancel |
| `PlayerInteractEvent` (anvil/enchant/grindstone/crafting) | `ALLOW_INTERACT` | Allow even if not in region |

**Global creative block rule:** On `BlockBreakEvent`, if the broken block has PDC tag `protect:creative_placed=true` and the region does NOT have `ALLOW_BREAK`, cancel. Blocks placed in creative are tagged on `BlockPlaceEvent` when `player.getGameMode() == GameMode.CREATIVE`.

**Bypass:** Players in the bypass set skip all region rule checks. The global creative-placed block rule still applies to non-bypassed players; bypassed OPs can break creative blocks anywhere.

---

## BlockTimerManager

- `Map<Location, BukkitTask> activeTimers`
- On `BlockPlaceEvent` (survival mode only): schedule a repeating task for `block-timer-seconds` seconds that:
  1. Sets block to `AIR`
  2. Plays `block-decay-sound` at the location
  3. Removes entry from map
- On `BlockBreakEvent` (player mines early): cancel the scheduled task, remove from map
- On plugin disable: cancel all active tasks

---

## ProtectCommand (`/region`)

All subcommands require OP.

| Subcommand | Description |
|---|---|
| `/region pos1` | Sets pos1 to player's current block location |
| `/region pos2` | Sets pos2 to player's current block location |
| `/region define <name> <type>` | Creates region from pos1+pos2, saves to MongoDB |
| `/region bypass` | Toggles bypass mode for the executing player |

Per-player pos1/pos2 stored in `Map<UUID, Location>` inside `ProtectCommand`.

---

## Error Handling

- If MongoDB connection fails on startup: log a severe error and disable the Protect system (regions will not be enforced)
- If a player runs `/region define` without setting pos1/pos2: send an error message
- If an invalid `RegionType` name is passed to `/region define`: send list of valid types
