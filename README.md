# NoTotem

Removes **Totems of Undying** from the server so nobody can cheat death. Built for hardcore and
survival servers that want deaths to count.

Event driven: no repeating tasks, no scanning of players every tick.

## What it does

- **Drops:** evokers and every other mob, chest or loot drop can't produce totems.
- **Pickup:** a totem picked up from the ground (or grabbed in creative) is removed.
- **Join, respawn, world change:** totems are cleared from the inventory, armor slots and offhand.
- **Inventory:** clicks, shift-clicks, drags, number-key swaps and offhand swaps can't hold a totem.
- **Storage:** totems can't be moved into containers or hoppers. Shulker boxes and bundles are checked too,
  and totems that were stored before are removed when a container or ender chest is opened.
- **Use:** a totem can never save a player, even if someone gets hold of one (creative, `/give`).
- **Purge:** `/nototem purge` clears online players, ender chests, loaded containers and items on the ground.

## Commands

| Command | What it does |
|---|---|
| `/nototem` | Status and help |
| `/nototem on` / `off` | Switch the whole plugin on or off |
| `/nototem status` | Show every setting |
| `/nototem reload` | Reload `config.yml` |
| `/nototem purge` | Remove existing totems |

Alias: `/nt`. Works from the console too.

## Permission

`nototem.admin` (op by default) for all commands.

## Configuration

`config.yml` has a switch for every feature (`prevent-drops`, `remove-on-join`, `prevent-pickup`,
`prevent-storage`, `remove-existing`, `block-use`, `notify-player`, `notify-admins`) and the messages.
Each option works on its own, and `enabled: false` turns everything off.

## Requirements

Paper 26.x (built against Paper API 26.3), Java 25.

## Known limits

- Other plugins that add their own totem items are not covered.
- Items in chunks nobody has loaded are only cleaned once the chunk loads and a container is opened,
  or on the next `/nototem purge`.
- Offline players are cleaned when they next join.

## Build

```
gradle build
```

The jar is in `build/libs/NoTotem-1.0.0.jar`.
