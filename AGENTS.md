# AGENTS.md -- rw-respawn-chest

Rising World server plugin (Unity API **0.9.3**): an admin looks at a placed chest and saves its contents as a template. After loot is taken, a one-shot timer starts; when it fires the chest is restored via **RESET**.

Chat with the user in German. Code, identifiers, and commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local under `RisingWorld/Data/SDK`, online at <https://javadoc.rising-world.net/latest/>

## Desired flow (v1)

```text
1. Admin places a normal chest and fills it
2. Admin looks at the chest
3. /make-refill 60
4. Plugin stores storage/object id + chunk + position + type + creation_date + snapshot + interval
5. Player takes loot from the chest (into inventory or drop to ground)
6. Plugin starts a one-shot timer (if none is pending)
7. Timer fires -> identity check -> RESET (clear + template slot-exact)
```

No continuous poll. Idle chests cost almost nothing. Players install nothing.

## Commands (v1)

Admins only: `player.isAdmin()` (`Server_Admins` in `server.properties`). Otherwise ignore silently (no reply, do not cancel the event).

Admin commands reply only to the executing admin. Auto-RESET is silent (no chat).

| Command                  | Effect                                                                                                          |
| ------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `/make-refill <minutes>` | Register focused chest; contents = template. If already registered: update interval only (pending timer stays). |
| `/refill-update`         | Save current contents as new template (pending timer stays)                                                     |
| `/refill-now`            | Immediate RESET to template, clear pending                                                                      |
| `/refill-remove`         | Remove from DB, kill pending timer                                                                              |
| `/refill-info`           | Interval, pending yes/no (+ remaining), short template summary                                                  |

Focus: `Player.getObjectElementInLineOfSight(5f, callback)`.

Reject:

- empty chest on `/make-refill` (new register) and `/refill-update`
- transient storage / no storage
- `/make-refill` without a minutes argument

Interval in minutes: `0` (or less) -> effective **5 seconds**. Else `minutes * 60`, cap **86400** (one day). Stored as `interval_seconds` (effective delay).

**RESET only.** No REFILL mode. Foreign items disappear on respawn via `clear()`.

**Not in v1:** loot chances, YAML tables, admin UI, periodic always-on respawn, putting items in as a trigger.

## API path

```text
getObjectElementInLineOfSight()
  -> object.getGlobalID()
  -> World.getStorage(globalID)   // for chests: storage id == object id
  -> reject transient / null
  -> storage.getItems()
```

- Item subtypes: `Item`, `Item.ObjectItem`, `Item.ConstructionItem`, `Item.ClothingItem` -- matching `Storage.add*ToSlot`.
- `Item.BlueprintItem`: no add API on Storage -- skip slot, server log.
- After `add*ToSlot`: set `durability`, `status`, `value` on the returned item.
- RESET: `storage.clear()` + template slot-exact.
- Do not use `ObjectElement.setAttribute` for persistence.
- Commands: `PlayerCommandEvent`; on admin handling `setCancelled(true)`.

Triggers: `PlayerStorageToInventoryEvent` (chest -> inventory) and `PlayerDropItemFromStorageEvent` (chest -> ground). Putting items in does not start a timer.

```text
Take event on storage
  -> RAM Set miss: return (no SQLite)
  -> findChest; ghost ID: remove from Set
  -> if next_refill == null: next_refill = now + interval, one-shot timer
  -> further looting: do not restart timer
  -> timer: identity -> RESET -> next_refill = null
```

At most one pending `net.risingworld.api.Timer` per chest (`repetitions = 0`). `/refill-remove` and `onDisable` kill timers.

On startup: load registered `storage_id`s into a RAM `Set`, identity-check **all** DB rows and delete orphans (`drop` also removes the id from the Set); then schedule pending `next_refill` or RESET immediately if due. Register the event listener last.

## Persistence: SQLite

`getSQLiteConnection(getPath() + "/refill.db")`. `PRAGMA foreign_keys = ON`. Prefer `PRAGMA journal_mode=DELETE` so a copied `refill.db` alone is usable (WAL left data in `-wal`). Checkpoint on disable (`PRAGMA wal_checkpoint(TRUNCATE)`).

A RAM `Set` of `storage_id`s filters loot events (`registeredIds.contains` <=> row in `refill_chests`). SQLite remains source of truth. Sync: add after successful insert, remove in `drop` only.

```text
refill_chests:
  storage_id PK, object_id,
  chunk_x/y/z,              -- for World.getObject
  world_x/y/z,
  object_type,              -- Objects.ObjectDefinition.name
  creation_date,            -- Storage.getCreationDate()
  interval_seconds,
  next_refill,              -- unix ms, NULL = idle
  created_at

refill_items:
  storage_id + slot PK,
  item_kind, type_id, variant, stack,
  durability, status, value, color, info_id
  FK storage_id ON DELETE CASCADE
```

### Identity check

On plugin start over **all** DB rows, and before RESET / before command targets on already registered chests:

1. `World.getStorage(storage_id)` missing or transient -> delete row + items, kill timer.
2. `storage.getCreationDate()` != saved -> delete.
3. `World.getObject(object_id, chunk_x, chunk_y, chunk_z)` when present: type must match, distance to saved position **<= 5** blocks; else delete.
4. ObjectElement null (e.g. chunk unloaded) but storage + creation_date OK: treat as same chest, keep entry / allow RESET.

No blind RESET onto a different chest. Idle orphans are removed on startup too.

## Scope

v1: one plugin class (commands + loot events + timers), Snapshot, SQLite (`RefillRepository`), identity cleanup.

No framework layers, no client mods.

## Build / Setup

- Java **20** (`pom.xml` source/target) -- RW Unity API runs on JDK 20. JAR name `RespawnChest`. Deploy: `plugins/RespawnChest/RespawnChest.jar`.
- `plugin.yml` must live in the JAR as `resources/plugin.yml` (`src/main/resources/resources/plugin.yml`), or RW will not load the plugin.
- Dependency: `net.rising-world:plugin-api:0.9.3` (`provided`).
- After an RW update, reinstall PluginAPI and bump version in `pom.xml` + `notes.txt`:

```powershell
mvn install:install-file `
  "-Dfile=C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Data\SDK\PluginAPI.jar" `
  "-DgroupId=net.rising-world" `
  "-DartifactId=plugin-api" `
  "-Dversion=0.9.3" `
  "-Dpackaging=jar"
```

PowerShell: always quote `-D...` args.

## Code conventions

- Package: `de.mahagst.risingworld.respawnchest`.
- Smallest sensible change. LF line endings.
- `notes.txt` = operator notes for API updates; this file is the spec.

## Agent notes

- Read Javadoc 0.9.3 before API calls.
- Storage id == object id for chests still requires null and identity checks.
- No global continuous poll. No REFILL mode. Actively delete DB orphans.
- Auto-RESET stays silent. Commands only for server admins.
- Loot hot path: RAM `registeredIds` first; SQLite only on hit. Keep Set in sync via insert + `drop` only.
