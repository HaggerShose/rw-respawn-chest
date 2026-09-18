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
6. Plugin persists next_refill = getIngameTimestamp() + interval; one-shot Timer (if none pending)
7. Timer -> re-check world time -> identity check -> RESET (clear + template slot-exact)
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
| `/refill-list`           | All registered chests, nearest first (pos, interval, pending remaining, distance)                               |

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
- After `add*ToSlot`: set `durability`, `status`, `value`, `modifier` on the returned item.
- RESET: `storage.clear()` + template slot-exact.
- Do not use `ObjectElement.setAttribute` for persistence.
- Commands: `PlayerCommandEvent`; on admin handling `setCancelled(true)`.

Triggers: `PlayerStorageToInventoryEvent` (chest -> inventory) and `PlayerDropItemFromStorageEvent` (chest -> ground). Putting items in does not start a timer.

```text
Take event on storage
  -> RAM Set miss: return (no SQLite)
  -> findChest; ghost ID: remove from Set
  -> if next_refill == null: next_refill = getIngameTimestamp() + interval, one-shot timer
  -> further looting: do not restart timer
  -> timer: re-check world time (reschedule if still early / pause) -> identity -> RESET -> next_refill = null
```

At most one pending `net.risingworld.api.Timer` per chest (`repetitions = 0`). `/refill-remove` and `onDisable` kill timers.

`next_refill` is world time (`Server.getIngameTimestamp` ms), not wall clock: pause / empty idle does not advance it. Session `Timer` is only a wake-up; due is always re-checked against world time. `created_at` stays unix wall clock.

On startup: load registered `storage_id`s into a RAM `Set`, identity-check **all** DB rows and delete orphans (`drop` also removes the id from the Set); migrate legacy unix `next_refill` to world time (preserve remaining); then schedule pending `next_refill` or RESET immediately if due. Register the event listener last.

## Persistence: SQLite

One file per world: `getPath() + "/" + World.getName() + ".db"` (path-unsafe chars in the name become `_`). `PRAGMA foreign_keys = ON`. Prefer `PRAGMA journal_mode=DELETE` so a copied world db alone is usable (WAL left data in `-wal`). Checkpoint on disable (`PRAGMA wal_checkpoint(TRUNCATE)`).

One-shot file migrate on enable: if `refill.db` exists and the world db does not, `LegacyRespawnDbMigration` moves it (plus `-wal`/`-shm`). Delete that class once old installs are gone.

A RAM `Set` of `storage_id`s filters loot events (`registeredIds.contains` <=> row in `refill_chests`). SQLite remains source of truth. Sync: add after successful insert, remove in `drop` only.

```text
refill_chests:
  storage_id PK, object_id,
  chunk_x/y/z,              -- for World.getObject
  world_x/y/z,
  object_type,              -- Objects.ObjectDefinition.name
  creation_date,            -- Storage.getCreationDate()
  interval_seconds,
  next_refill,              -- world ms (Server.getIngameTimestamp), NULL = idle
  created_at                -- unix wall-clock ms

refill_items:
  storage_id + slot PK,
  item_kind, type_id, variant, stack,
  durability, status, value, color, info_id,
  modifier                 -- Items.Modifier name; NULL/blank reads as Normal
  FK storage_id ON DELETE CASCADE
```

Existing templates without `modifier` stay valid (`Normal`). Legendary (and other) items need `/refill-update` once after this change so the real modifier is stored.

### Schema evolution

No schema migration runner. `CREATE TABLE IF NOT EXISTS` is the target schema. After CREATE, call `SqliteSchema.ensureColumn` for each column added later so old world db files pick it up.

Copy [`_tools/templates/SqliteSchema.java`](../_tools/templates/SqliteSchema.java) into the plugin package and change the package line. `ensureColumn` is idempotent (`PRAGMA table_info`, then `ALTER TABLE ... ADD COLUMN` only if missing). Keep the call permanently -- it also covers an old db copied onto a new server.

### Identity check

On plugin start over **all** DB rows, and before RESET / before command targets on already registered chests:

1. `World.getStorage(storage_id)` missing -> **keep** the row (UNCERTAIN). Never treat null as proof the chest is gone. Pending refill: silent retry every 30s.
2. Storage transient, or `storage.getCreationDate()` != saved -> delete.
3. `World.getObject(object_id, chunk_x, chunk_y, chunk_z)` when present: type must match, distance to saved position **<= 5** blocks; else delete. Position null while object loaded -> keep (UNCERTAIN), retry if pending.
4. ObjectElement null (e.g. chunk unloaded) but storage + creation_date OK: treat as same chest, keep entry / allow RESET.

No blind RESET onto a different chest. Truly deleted chests may leave idle DB orphans; that is preferred over false deletes.

## Scope

v1: one plugin class (commands + loot events + timers), Snapshot, SQLite (`RefillRepository`), identity cleanup.

No framework layers, no client mods.

## Build / Setup

- Java **20** (`pom.xml` source/target) -- RW Unity API runs on JDK 20. JAR name `RespawnChest`. Deploy: `plugins/RespawnChest/RespawnChest.jar`.
- `plugin.yml` must live in the JAR as `resources/plugin.yml` (`src/main/resources/resources/plugin.yml`), or RW will not load the plugin.
- Dependency: `net.rising-world:plugin-api:0.9.3` (`provided`). Install once into local `.m2` via workspace bootstrap (not on every Maven run).
- After an RW update, bump `<rw.plugin.api.version>` / `api:` and refresh `.m2` from the workspace root:

```powershell
.\_tools\bootstrap-libs.ps1 -P rw-respawn-chest
cd rw-respawn-chest; mvn -B package
```

PowerShell: always quote `-D...` args.

## Code conventions

- Package: `de.mahagst.risingworld.respawnchest`.
- Smallest sensible change. LF line endings.
- `notes.txt` = operator notes for API/bootstrap updates (paths assume workspace root); this file is the spec.

## Agent notes

- Read Javadoc 0.9.3 before API calls.
- Storage id == object id for chests still requires null and identity checks.
- No global continuous poll. No REFILL mode. Drop only on clear identity mismatch (not on null storage).
- Auto-RESET stays silent. Commands only for server admins.
- Loot hot path: RAM `registeredIds` first; SQLite only on hit. Keep Set in sync via insert + `drop` only.
