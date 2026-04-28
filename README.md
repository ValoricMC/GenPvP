# GenPvP — PlaceholderAPI Placeholder Reference

All placeholders use the `%genpvp_<identifier>%` format and require PlaceholderAPI.

---

## Leveling & Prestige

| Placeholder | Description |
|---|---|
| `%genpvp_level%` | Player's current level |
| `%genpvp_level_next%` | Next level (current + 1) |
| `%genpvp_prestige%` | Player's current prestige tier |
| `%genpvp_level_bar_lines%` | 20-character XP progress bar toward next level |
| `%genpvp_level_bar_percentage%` | XP progress as a percentage (e.g. `65%`) |

---

## KOTH

| Placeholder | Description |
|---|---|
| `%genpvp_time_till_next_koth%` | Time until the next auto-KOTH starts, or `Active` |
| `%genpvp_time_till_koth_capture%` | Capture time remaining for the current capper, or `N/A` |
| `%genpvp_capturer_koth%` | IGN of the player currently capturing, or `None` |
| `%genpvp_koth_claim_bar%` | 20 `\|` bars — red initially, turns green left to right as capture progresses (1 bar per 6 s) |
| `%genpvp_koth_claim_time_formatted%` | Elapsed capture time formatted as e.g. `1m34s` or `45s` |
| `%genpvp_koth_top_winner_name_<1-10>%` | IGN of the Nth top KOTH winner |
| `%genpvp_koth_top_winner_number_<1-10>%` | Win count of the Nth top KOTH winner |
| `%genpvp_koth_top_winner_playerhead_<1-10>%` | 2D player head of the Nth top KOTH winner |

---

## Bank — Money

| Placeholder | Description |
|---|---|
| `%genpvp_bank_money_total%` | Player's current money balance |
| `%genpvp_bank_money_ign_<1-10>%` | IGN of the Nth richest player |
| `%genpvp_bank_money_amount_<1-10>%` | Money balance of the Nth richest player |
| `%genpvp_bank_money_playerhead_<1-10>%` | 2D player head of the Nth richest player |

## Bank — Stars

| Placeholder | Description |
|---|---|
| `%genpvp_bank_stars_total%` | Player's current star balance |
| `%genpvp_bank_stars_ign_<1-10>%` | IGN of the Nth top stars player |
| `%genpvp_bank_stars_amount_<1-10>%` | Star balance of the Nth top stars player |

## Bank — Gold

| Placeholder | Description |
|---|---|
| `%genpvp_bank_gold_total%` | Player's current gold balance |
| `%genpvp_bank_gold_ign_<1-10>%` | IGN of the Nth top gold player |
| `%genpvp_bank_gold_amount_<1-10>%` | Gold balance of the Nth top gold player |

---

## Kills

| Placeholder | Description |
|---|---|
| `%genpvp_kills%` | Player's total kill count |
| `%genpvp_kills_top_ign_<1-10>%` | IGN of the Nth top killer |
| `%genpvp_kills_top_amount_<1-10>%` | Kill count of the Nth top killer |
| `%genpvp_kills_top_playerhead_<1-10>%` | 2D player head of the Nth top killer |

---

## Deaths

| Placeholder | Description |
|---|---|
| `%genpvp_deaths%` | Player's total death count |
| `%genpvp_deaths_top_ign_<1-10>%` | IGN of the Nth most-died player |
| `%genpvp_deaths_top_amount_<1-10>%` | Death count of the Nth most-died player |
| `%genpvp_deaths_top_playerhead_<1-10>%` | 2D player head of the Nth most-died player |

---

## Teams — Leaderboard

| Placeholder | Description |
|---|---|
| `%genpvp_team_name%` | Name of the player's team, or `N/A` |
| `%genpvp_team_inteam%` | `true` / `false` — whether the player is in a team |
| `%genpvp_team_points%` | Points of the player's team |
| `%genpvp_team_top_name_<1-10>%` | Name of the Nth team ranked by points (e.g. `8` → 8th-highest team name) |
| `%genpvp_team_top_points_<1-10>%` | Points total of the Nth team ranked by points (e.g. `8` → points of the 8th-highest team) |

---

## PvP Rooms

| Placeholder | Description |
|---|---|
| `%genpvp_pvproom1_max%` | Max players in PvP Room 1 (always `2`) |
| `%genpvp_pvproom1_in%` | Players currently inside PvP Room 1 |
| `%genpvp_pvproom2_max%` | Max players in PvP Room 2 (always `2`) |
| `%genpvp_pvproom2_in%` | Players currently inside PvP Room 2 |

---

## Player Head Placeholders

All `_playerhead_<1-10>` placeholders return a `<head:UUID>` tag that the ScoreboardPanel image renderer draws as a 2D face (8x8 skin pixels, hat layer composited on top, scaled to the configured line height).

In the Minecraft sidebar the tag is stripped automatically — place the head placeholder alongside an IGN placeholder so the sidebar still shows useful text:

```yaml
scoreboard:
  lines:
    - text: "%genpvp_kills_top_playerhead_1% %genpvp_kills_top_ign_1%"
    - text: "%genpvp_kills_top_playerhead_2% %genpvp_kills_top_ign_2%"
```

- **ScoreboardPanel image**: renders the face followed by the player's name
- **Minecraft sidebar**: renders only the player's name

Faces are cached when players connect. If a leaderboard entry is for an offline player whose skin was never cached this session, a grey square placeholder is shown until they log in.

---

# Commands & Permissions Reference

> **Soft-depends:** FastAsyncWorldEdit, PlaceholderAPI, LuckPerms  
> **API version:** 1.21

---

## Permissions Overview

| Permission | Description | Default |
|---|---|---|
| `genpvp.admin` | Full access to all administration commands | OP |
| `genpvp.autosmelt` | Toggle auto-smelting of mined ores | OP |
| `genpvp.commandspy` | See all player commands in real time | OP |
| `genpvp.starter` | Access to the **Starter** kit | OP |
| `genpvp.sailor` | Access to the **Sailor** kit | OP |
| `genpvp.pirate` | Access to the **Pirate** kit | OP |
| `genpvp.gunner` | Access to the **Gunner** kit | OP |
| `genpvp.admiral` | Access to the **Admiral** kit | OP |
| `genpvp.captain` | Access to the **Captain** kit | OP |
| `genpvp.<kit_id>` | Access to any custom-registered kit | OP |

> Custom kits added via `/kit register` automatically get the node `genpvp.<kit_id>`. Grant it via LuckPerms or any permissions plugin.

---

## Player Commands

No special permission is required for these unless explicitly stated.

---

### `/spawn`
Teleport to spawn with a countdown timer.

---

### `/bank`
Check your current bank balance.

---

### `/withdraw <amount>`
Withdraw money from your bank as physical Money Shards.

---

### `/deposit <amount>`
Deposit Money Shards from your inventory into your bank.

---

### `/pay <player> <amount>`
Send money to another online player.

---

### `/shop [menu]`
Open the main shop GUI. Optionally pass a sub-menu name.

---

### `/itemshop [shop]`
Open a named item shop GUI.

---

### `/sell`
Open the sell menu.

---

### `/kit`
Kit management.

| Subcommand | Description | Permission |
|---|---|---|
| *(no args)* | Open the kit selection GUI | None |
| `preview <name>` | Preview a kit's contents without claiming it | None |
| `starter_on_death` | Toggle receiving the starter kit automatically on respawn | None |
| `register <name> from_inventory` | Save your current inventory as a new kit | OP |
| `reset_cooldown <player> <kit>` | Reset a player's cooldown for a specific kit | OP |

---

### `/team` (alias: `/teams`)
Team management.

| Subcommand | Description | Notes |
|---|---|---|
| *(no args)* | Open your team info GUI | Requires being in a team |
| `create <name>` | Create a new team | Name ≥ 3 chars, `[a-zA-Z0-9_]` only |
| `join <team>` | Join a team you have been invited to | |
| `leave` | Leave your current team | Owners must disband instead |
| `invite <player>` | Invite a player to your team | Owner only |
| `disband` | Open disband confirmation GUI | Owner only |
| `disband confirm` | Confirm team disbandment | Owner only |
| `chat [message]` | Toggle team chat mode, or send a message to teammates | |
| `info [team]` | Open the team info GUI for your team or another | |
| `kick <player>` | Remove a member from your team | Owner / Moderator |
| `top` | Display the top 10 teams by points | |

---

### `/prestige`
Prestige system — self-prestige and admin management.

| Subcommand | Description | Permission |
|---|---|---|
| *(no args)* | Prestige up (requires max level) | None |
| `add_prestige <player> <amount>` | Add prestige level(s) to a player | OP |
| `remove_prestige <player> <amount>` | Remove prestige level(s) from a player | OP |
| `reset_prestige <player>` | Reset a player's prestige to 0 | OP |
| `blacklist_prestige <player>` | Toggle the prestige blacklist for a player | OP |
| `add_level <player> <amount>` | Add level(s) to a player | OP |
| `remove_level <player> <amount>` | Remove level(s) from a player | OP |
| `reset_level <player>` | Reset a player's level to 0 | OP |
| `blacklist_level <player>` | Toggle the leveling blacklist for a player | OP |
| `blacklist_xp <player>` | Toggle the XP gain blacklist for a player | OP |

> **Self-prestige rules:** level must be ≥ `prestige.levels-per-prestige` (default 10). On success, level resets to 0 and XP resets to 0.

---

### `/xp <subcommand> <player> [amount]`
Raw XP administration. **Requires OP.**

| Subcommand | Description |
|---|---|
| `add_xp <player> <amount>` | Add XP to a player |
| `remove_xp <player> <amount>` | Remove XP from a player |
| `reset_xp <player>` | Reset a player's XP to 0 |

---

### `/level <subcommand> <player> [amount]`
Level administration shorthand. **Requires OP.**

| Subcommand | Description |
|---|---|
| `add_level <player> <amount>` | Add levels to a player |
| `remove_level <player> <amount>` | Remove levels from a player |
| `reset_level <player>` | Reset a player's level |
| `blacklist_level <player>` | Toggle the leveling blacklist for a player |

---

### `/fruit` (aliases: `/df`, `/devilfruit`)
Devil Fruit management.

| Subcommand | Description | Permission |
|---|---|---|
| *(no args)* | Open the general Devil Fruit GUI | None |
| `equip <fruit>` | Equip a Devil Fruit you already own | None |
| `logia` | Open the Logia type GUI | None |
| `paramecia` | Open the Paramecia type GUI | None |
| `zoan` | Open the Zoan type GUI | None |
| `roll <logia\|paramecia\|zoan>` | Roll for a random fruit (costs roll tokens) | None |
| `give <player> <fruit>` | Give a fruit to a player (online or offline) | `genpvp.admin` |
| `remove <player> <fruit>` | Remove a fruit from a player | `genpvp.admin` |
| `roll_give <player> <type> <amount>` | Give roll tokens to a player | `genpvp.admin` |

---

### `/logia`
Open the Logia Devil Fruit type GUI directly.

---

### `/paramecia`
Open the Paramecia Devil Fruit type GUI directly.

---

### `/zoan`
Open the Zoan Devil Fruit type GUI directly.

---

### `/options`
Open the player settings/options menu.

---

### `/settings <receive_payment>`
Toggle individual player settings.

---

### `/feedback <reason>`
Submit feedback or a bug report to server admins.

---

### `/autosmelt`
Toggle auto-smelting of mined ores.

| Permission | Default |
|---|---|
| `genpvp.autosmelt` | OP |

---

### `/commandspy`
Toggle seeing all player commands in real time in your chat.

| Permission | Default |
|---|---|
| `genpvp.commandspy` | OP |

---

## Admin Commands

All commands below require **OP** or `genpvp.admin` unless noted.

---

### `/genpvp <subcommand>`
Core administration. **Requires `genpvp.admin`.**

| Subcommand | Description |
|---|---|
| `reload` | Reload config, shops, scoreboard, and spawn settings |
| `item_give <item> [player]` | Give a custom item to a player (or yourself) |
| `onepiece set from_hand <name>` | Register the held item as a named One Piece custom item |
| `head set from_hand <name>` | Register the held item as a named decorative head |

**`item_give` items:** `autocompressor`, `luffy_head`, `zoro_head`, `nami_head`, `sanji_head`, `luffy_helmet`, `luffy_chestplate`, `luffy_leggings`, `luffy_boots`, `luffy_sword`, `pirate_axe`, `pirate_sword`, plus any custom-registered items.

---

### `/antidupe [subcommand]`
Manage anti-dupe detection. **Requires `genpvp.admin`.**

| Subcommand | Description |
|---|---|
| *(no args)* | Show current flag status |
| `reload` | Reload anti-dupe config from disk |
| `status` | Show current flag status |
| `toggle <flag>` | Toggle a boolean flag |
| `set punish-command <cmd>` | Update the punishment command |

**Toggleable flags:** `enabled`, `check-unstackables`, `delete-item`, `notify-ops`, `log-console`

---

### `/koth <start|stop>`
Manage the King of the Hill event. **Requires OP.**

| Subcommand | Description |
|---|---|
| `start` | Force-start the KOTH event |
| `stop` | Force-stop the KOTH event |

---

### `/combat tag <player> <seconds>`
Force-tag a player into combat for a given number of seconds. **Requires OP.**

---

### `/region <subcommand>`
World protection region management. **Requires OP.**

| Subcommand | Description |
|---|---|
| `pos1` | Set Position 1 to your current block location |
| `pos2` | Set Position 2 to your current block location |
| `define <name> <type>` | Create a region between pos1 and pos2 |
| `remove <name>` | Delete a region |
| `priority <name> <value>` | Set region priority (higher wins overlaps) |
| `protect <name>` | Snapshot all non-air blocks inside a region as unbreakable |
| `bypass` | Toggle region bypass mode for yourself |

**Region types:** `SPAWN`, `GENS`, `OPMINESGENS`, `GLOBAL` *(and others in RegionType)*

---

### `/mysql convert mongo`
Migrate legacy MySQL data to MongoDB. **Requires `genpvp.admin`.**

```
/mysql convert mongo    — start migration
/mysql convert status   — check live progress
```

---

### `/database optimize mongo`
Compact existing MongoDB collections to the optimised per-player document format. Idempotent — safe to re-run. **Requires `genpvp.admin`.**

---

## Kit Permissions

Kit access is controlled by permission nodes in the format `genpvp.<kit_id>`.

| Kit | Permission Node |
|---|---|
| Starter | `genpvp.starter` |
| Sailor | `genpvp.sailor` |
| Pirate | `genpvp.pirate` |
| Gunner | `genpvp.gunner` |
| Admiral | `genpvp.admiral` |
| Captain | `genpvp.captain` |
| *(any custom kit)* | `genpvp.<kit_id>` |

- Players **without** the permission see the kit as **locked** (red barrier icon).
- Players **with** the permission but **on cooldown** see the cooldown timer on the kit icon.
- Players **with** the permission and **off cooldown** can claim the kit.

### Adding a Custom Kit

1. Equip the desired items in your inventory (hotbar, armour, offhand).
2. `/kit register <name> from_inventory`
3. Grant `genpvp.<name>` via LuckPerms.
4. *(Optional)* Edit cooldown, GUI slot, and display in `plugins/GenPvP/kits.yml`, then `/genpvp reload`.

