---
title: Area settings by type
kind: reference
summary: The fields of each type of area, the setting each one writes, and what the server uses without it.
tool: areas
next: areas/check-areas
related: areas/what-is-an-area, areas/make-areas, terms/areadata
---
Under **SETTINGS** the panel shows the settings the area's type reads, in plain words. The tooltip of each field gives
the word the file uses. A label in a faint colour, with "· default", shows what the server uses while the file does not
set it. Typing writes the setting, and the small cross after a label takes it out again. Typing into one field is one
step for Undo.

## Every area

| Field | In the file | What it holds |
|---|---|---|
| **Name** | `name` | What scripts and events find the area by. Letters A to Z, digits and underscores |
| **Type** | `type` | What the area does. The types of the file come first, the others are grey under **Not in this file**, and **Custom type…** at the end takes a word of your own. A new type keeps the other settings, and those it does not read move to **Other settings** |
| **Note** | the `//` line above the area | One line for people. Empty takes the line out |

## By type

| Type | Field | In the file | Without it |
|---|---|---|---|
| Every type that works on characters in turn | **Every N ticks** | `unit_tick` | The type's own: 9 for damage, 45 for poison and instant skill, 5 for teleport |
| The same types | **Works on** | `target` | Everyone. The others are players and their summons, NPCs, or players only |
| Poison area, Instant skill area | **Skill**, **Chance, %** | `skill_name`, `skill_prob` | No skill, so it casts nothing. A chance of 100 |
| Poison area, Instant skill area | **Skills**, **Order** | `skill_list`, `skill_action_type` | One skill of the list at random |
| Damage area | **HP per tick**, **MP per tick** | `damage_on_hp`, `damage_on_mp` | The file's `default_setting` for damage |
| Damage area | **Message** | `message_no` | No message |
| Swamp | **Speed change, %** | `move_bonus` | The file's `default_setting` for swamps. -50 halves the speed |
| Mother Tree | **Race**, **HP regen bonus**, **MP regen bonus** | `affect_race`, `hp_regen_bonus`, `mp_regen_bonus` | Elves only. The file's `default_setting` |
| Mother Tree | **Entering message**, **Leaving message** | `entering_message_no`, `leaving_message_no` | No message |
| No-restart zone | **Restart after** | `restart_time` | 10 min |
| No-restart zone | **Restart allowed** | `restart_allowed_time` | Not set |
| No-restart zone | **Restart points** | `restart_points` | A town of the server's choice |
| Teleport area | **Teleport points**, **Teleport range** | `teleport_points`, `teleport_range` | Nowhere, so it sends nobody |
| Seven Signs zone | **XP penalty, %**, **Item drop** | `exp_penalty_per`, `item_drop` | Not set |
| Peace zone | **Private stores**, **Private workshops** | `priv_store` and `priv_rstore` in `blocked_actions` | Both allowed |

A time, such as **Restart after**, is a number of seconds, or a number with sec, min, hour or day, such as `10min` or
`1hour 30min`. A line under the field says it in words.

## State and timer

**State and timer**, folded at first, holds what any type can have:

- **On when the server starts**, `default_status`. An area that starts off does nothing until an event or an AI turns it
  on.
- **Event** and **Instance zones**, `event_id` and `instantzone_id`. An area of instance zones works only inside them.
- **On for, sec**, **Off for, sec**, **First delay, sec** and **Random, sec**, the timer. On and off last 30 seconds each
  when they are not set. A line under them says what the timer does.

## Other settings

**Other settings** lists each setting that no field shows, such as one the type does not read, as its word and its
text, with a button that takes it out. **Add setting…** adds one the server knows. **Custom key…** at the end of its
list adds a key of your own, for a custom server: type the key, with letters A to Z, digits and `_` and a letter first,
and its value as the file writes it, then press **Add**. A value that the file could not read back, such as one with a
space outside braces, is refused with a note, and the old one stays.
