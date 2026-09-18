# AGENTS.md -- rw-respawn-chest

Rising World server plugin (Unity API **0.9.3**): an admin looks at a placed chest and saves its contents as a template. After loot is taken, `next_refill` is set; a global 1s tick (only while something is pending) RESETs due chests.

Chat with the user in German. Code, identifiers, and commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local under `RisingWorld/Data/SDK`, online at <https://javadoc.rising-world.net/latest/>

## Flow

```text
1. Admin places a normal chest and fills it
2. Admin looks at the chest
3. /make-refill 60
4. Plugin stores storage/object id + chunk + position + type + creation_date + snapshot + interval
5. Player takes loot (inventory or drop to ground)
6. RAM hit: if idle, set next_refill = getIngameTimestamp() + interval (do not restart if already pending)
7. Global 1s tick (only while pending > 0): due chests, stop after 250 ms of RESET work
     getStorage(id) null or identity mismatch -> drop
     else RESET from RAM template (clear + slot-exact) -> next_refill = null
     restore failure: retry, drop after 3 failed attempts
```

Two runtime states only: **idle** (`next_refill == null`) and **pending**. Players install nothing.

## Layout

| File                                       | Role                                                                                       |
| ------------------------------------------ | ------------------------------------------------------------------------------------------ |
| `RespawnChestPlugin`                       | Lifecycle, admin gate, commands, LoS, loot event stubs                                     |
| `RefillService`                            | RAM chests+templates, ready+sweep, pending tick, register/update/now/remove/info/list, identity, RESET |
| `RefillRepository`                         | SQLite only                                                                                |
| `RefillChest`, `TemplateItem`, `Snapshot`  | Row + capture/restore                                                                      |
| `LegacyRespawnDbMigration`, `SqliteSchema` | Untouched file migrate / `ensureColumn`                                                    |

No extra packages. No client mods.

## Commands

Admins only: `player.isAdmin()`. Otherwise ignore silently (no reply, do not cancel the event).

Admin commands reply only to the executing admin. Auto-RESET is silent (no chat).

| Command                  | Effect                                                                                                    |
| ------------------------ | --------------------------------------------------------------------------------------------------------- |
| `/make-refill <minutes>` | Register focused chest; contents = template. If already registered: update interval only (pending stays). |
| `/refill-update`         | Save current contents as new template (pending stays)                                                     |
| `/refill-now`            | Immediate RESET to template, clear pending                                                                |
| `/refill-remove`         | Remove from DB + RAM                                                                                      |
| `/refill-info`           | Interval, pending yes/no (+ remaining), short template summary                                            |
| `/refill-list`           | Active registered chests, nearest first (pos, interval, pending remaining, distance)                      |

Focus: `Player.getObjectElementInLineOfSight(5f, callback)`. Commands are LoS-only; a missing chest cannot be targeted.

Reject:

- empty chest on `/make-refill` (new register) and `/refill-update`
- transient storage / no storage
- `/make-refill` without a minutes argument

Interval in minutes: `0` (or less) -> effective **5 seconds**. Else `minutes * 60L`, cap **86400** (one day). Stored as `interval_seconds`.

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
- RESET: `storage.clear()` + RAM template slot-exact. Missing/empty template does not clear.
- Do not use `ObjectElement.setAttribute` for persistence.
- Commands: `PlayerCommandEvent`; on admin handling `setCancelled(true)`.

Triggers: `PlayerStorageToInventoryEvent` (chest -> inventory) and `PlayerDropItemFromStorageEvent` (chest -> ground). Putting items in does not start a timer.

## Runtime

```text
onEnable
  -> schema + loadMaps (findAll + findItems -> RAM chests + templates)
  -> skip / drop rows with empty templates; skip a chest for this session if item query fails
  -> register listeners
  -> wait World.isInitialized() + 5s
  -> World.getAllStorages() -> HashSet of ids
  -> drop any registered id not in the set
  -> migrate legacy unix next_refill to world time (preserve remaining)
  -> seed pendingById from remaining next_refill; start tick if pending > 0

Loot / command / tick
  -> read event/LoS data, then plugin.enqueue (one thread mutates maps)
  -> Loot: RAM chests miss: return (no SQLite)
  -> nextRefill != null: return
  -> RAM next_refill = getIngameTimestamp() + interval; persist DB (log on fail, countdown still runs)
  -> after sweep: pendingById + ensure tick

Tick (1s, only while pendingById not empty; 250 ms RESET budget)
  -> due = next_refill <= getIngameTimestamp()
  -> getStorage(id) null or identity mismatch -> drop (DB + RAM)
  -> else RESET from RAM template
  -> restore fail: retry (pending stays); after 3 failures drop + log
  -> restore ok: clear pending (if that DB write fails, pending stays -> RESET again next tick)
```

RAM:

- `Map<Long, RefillChest> chests` -- membership + interval + due.
- `Map<Long, List<TemplateItem>> templates` -- slot-exact snapshot. RESET and `/refill-info` never read items from SQLite.
- `Map<Long, Long> pendingById` -- storageId -> due world-ms. Idle chests absent.

One repeating `net.risingworld.api.Timer` (interval 1s, repetitions -1) while pending > 0. No per-chest timers. Due is always world time; the session timer is only a wake-up. `/refill-remove` and `onDisable` kill timers. Pause / empty idle does not advance `next_refill`. `created_at` stays unix wall clock.

While pending, contents are ignored until RESET (further loot does not restart the due time).

## Persistence: SQLite

One file per world: `getPath() + "/" + World.getName() + ".db"` (path-unsafe chars in the name become `_`). `PRAGMA foreign_keys = ON`. Prefer `PRAGMA journal_mode=DELETE` so a copied world db alone is usable (WAL left data in `-wal`). Checkpoint on disable (`PRAGMA wal_checkpoint(TRUNCATE)`).

One-shot file migrate on enable: if `refill.db` exists and the world db does not, `LegacyRespawnDbMigration` moves it (plus `-wal`/`-shm`). Delete that class once old installs are gone.

Do not change table/column names. `SqliteSchema` and `LegacyRespawnDbMigration` stay as they are.

```text
refill_chests:
  storage_id PK, object_id,
  chunk_x/y/z,              -- register metadata / list
  world_x/y/z,              -- list distance sort
  object_type,              -- register metadata (not used for identity)
  creation_date,            -- Storage.getCreationDate()
  interval_seconds,
  next_refill,              -- world ms (Server.getIngameTimestamp), NULL = idle
  created_at,               -- unix wall-clock ms
  active                    -- list filter; refill still runs when 0

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

### Identity

Used on due RESET and focused commands that already have a live storage:

1. `World.getStorage(storage_id)` missing -> **drop** (startup sweep via `getAllStorages`, or mid-session when a pending due fires).
2. Storage transient, or `storage.getCreationDate()` != saved -> drop.

Chunk/object fields (`object_id`, chunk, world pos, `object_type`) stay in the DB for list/display and register metadata; they are not part of runtime identity. No `UNCERTAIN` retry loop.

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
- Storage id == object id for chests still requires null and identity checks before RESET.
- No per-chest timers. No REFILL mode. Drop on missing storage (after ready) and on identity mismatch.
- Auto-RESET stays silent. Commands only for server admins.
- Loot hot path: RAM `chests` then idle check; SQLite only when arming pending (best-effort persist).
- Templates live in RAM after enable / `/refill-update`. RESET does not query `refill_items`.
- Loot, commands, and the tick mutate service maps only via `plugin.enqueue`.
