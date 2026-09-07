# AGENTS.md -- rw-respawn-chest

Rising-World-Serverplugin (Unity-API **0.9.3**): Admin peilt eine platzierte Kiste an, speichert ihren Inhalt als Template. Nach Loot-Entnahme startet ein einmaliger Timer; danach wird die Kiste per **RESET** wiederhergestellt.

Chat auf Deutsch. Code, Identifier, Commits auf Englisch. ASCII-Satzzeichen in Dateien (`--`, `...`, `->`); deutsche Umlaute in Prosa sind ok.

Javadoc: lokal unter `RisingWorld/Data/SDK`, online unter <https://javadoc.rising-world.net/latest/>

## Wunschablauf (v1)

```text
1. Admin platziert normale Kiste, legt Items hinein
2. Admin schaut die Kiste an
3. /make-refill 60
4. Plugin speichert Storage/Object-ID + Chunk + Position + Typ + creation_date + Snapshot + Intervall
5. Spieler nimmt Loot aus der Kiste (ins Inventar oder Drop auf den Boden)
6. Plugin startet einmaligen Timer (sofern keiner pending ist)
7. Timer abgelaufen -> Identity-Check -> RESET (clear + Template slotgenau)
```

Kein dauerhaft tickender Poll. Idle-Kisten kosten praktisch nichts. Spieler brauchen nichts zu installieren.

## Commands (v1)

Nur Admins: `player.isAdmin()` (`Server_Admins` in `server.properties`). Sonst still ignorieren (keine Antwort, Event nicht canceln).

Admin-Commands antworten nur dem ausfuehrenden Admin. Auto-RESET ist still (keine Chat-Nachrichten).

| Command                  | Wirkung                                                              |
| ------------------------ | -------------------------------------------------------------------- |
| `/make-refill <minutes>` | Fokussierte Kiste registrieren; Inhalt = Template. Nur RESET.        |
| `/refill-update`         | Aktuellen Inhalt als neues Template speichern (pending Timer bleibt) |
| `/refill-now`            | Sofort RESET auf Template, pending clearen                           |
| `/refill-remove`         | Aus DB entfernen, pending Timer killen                               |
| `/refill-info`           | Intervall, pending ja/nein (+ Restzeit), Template-Kurzinfo           |

Fokus: `Player.getObjectElementInLineOfSight(5f, callback)`.

Ablehnen:

- leere Kiste bei `/make-refill` und `/refill-update`
- bereits registrierte Kiste bei `/make-refill` (Hinweis auf `/refill-remove`)
- transienter Storage / kein Storage
- `/make-refill` ohne Minuten-Argument

Intervall in Minuten: `0` (und kleiner) -> effektive **5 Sekunden**. Sonst `minutes * 60`, Cap **86400** (ein Tag). Gespeichert wird `interval_seconds` (effektive Delay).

**Nur RESET.** Kein REFILL-Modus. Fremde Items verschwinden beim Respawn mit `clear()`.

**Nicht in v1:** Loot-Chancen, YAML-Tabellen, Admin-UI, periodischer Dauer-Respawn, Reinlegen als Trigger.

## API-Pfad

```text
getObjectElementInLineOfSight()
  -> object.getGlobalID()
  -> World.getStorage(globalID)   // bei Kisten: Storage-ID == Object-ID
  -> reject transient / null
  -> storage.getItems()
```

- Item-Subtypen: `Item`, `Item.ObjectItem`, `Item.ConstructionItem`, `Item.ClothingItem` -- passende `Storage.add*ToSlot`.
- `Item.BlueprintItem`: kein Add in der Storage-API -- Slot ueberspringen, Server-Log.
- Nach `add*ToSlot`: `durability`, `status`, `value` auf dem zurueckgegebenen Item setzen.
- RESET: `storage.clear()` + Template slotgenau.
- `ObjectElement.setAttribute` nicht fuer Persistenz.
- Commands: `PlayerCommandEvent`; bei Admin-Handling `setCancelled(true)`.

Trigger: `PlayerStorageToInventoryEvent` (Kiste -> Inventar) und `PlayerDropItemFromStorageEvent` (Kiste -> Boden). Reinlegen startet keinen Timer.

```text
Take-Event auf registrierte Storage
  -> wenn next_refill == null: next_refill = now + interval, one-shot Timer
  -> erneutes Looten: Timer nicht neu starten
  -> Timer: Identity -> RESET -> next_refill = null
```

Pro Kiste hoechstens ein pending `net.risingworld.api.Timer` (`repetitions = 0`). `/refill-remove` und `onDisable` killen Timer.

Nach Start: **alle** DB-Eintraege Identity-checken und Leichen loeschen; pending `next_refill` danach schedulen bzw. sofort RESET wenn faellig.

## Persistenz: SQLite

`getSQLiteConnection(getPath() + "/refill.db")`. `PRAGMA foreign_keys = ON`.

```text
refill_chests:
  storage_id PK, object_id,
  chunk_x/y/z,              -- fuer World.getObject
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

### Identity-Check

Beim Plugin-Start ueber **alle** DB-Eintraege, sowie vor RESET und vor Command-Zielen auf bereits registrierte Kisten:

1. `World.getStorage(storage_id)` fehlt oder transient -> Datensatz + Items loeschen, Timer killen.
2. `storage.getCreationDate()` != gespeichert -> loeschen.
3. `World.getObject(object_id, chunk_x, chunk_y, chunk_z)` wenn vorhanden: Typ muss passen, Distanz zu gespeicherter Position **<= 5** Bloecke; sonst loeschen.
4. ObjectElement null (z.B. Chunk nicht geladen), aber Storage + creation_date ok: Storage gilt als identisch, Eintrag behalten / RESET erlaubt.

Kein Blind-RESET auf eine andere Kiste. Idle-Leichen werden beim Start mit entfernt.

## Scope

v1: eine Plugin-Klasse (Commands + Loot-Events + Timer), Snapshot, SQLite (`RefillRepository`), Identity-Cleanup.

Keine Framework-Schichten, keine Client-Mods.

## Build / Setup

- Java 21+ (`pom.xml`). JAR-Name `RespawnChest`. Deploy: `plugins/RespawnChest/RespawnChest.jar`.
- `plugin.yml` muss in der JAR unter `resources/plugin.yml` liegen (`src/main/resources/resources/plugin.yml`), sonst laedt RW das Plugin nicht.
- Dependency: `net.rising-world:plugin-api:0.9.3` (`provided`).
- Nach RW-Update PluginAPI neu einspielen und Version in `pom.xml` + `notes.txt` anpassen:

```powershell
mvn install:install-file `
  "-Dfile=C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Data\SDK\PluginAPI.jar" `
  "-DgroupId=net.rising-world" `
  "-DartifactId=plugin-api" `
  "-Dversion=0.9.3" `
  "-Dpackaging=jar"
```

PowerShell: `-D...`-Args **immer quoten**.

## Code-Konventionen

- Package: `de.mahagst.risingworld.respawnchest`.
- Kleinster sinnvoller Change. LF-Zeilenenden.
- `notes.txt` = Operator-Notiz fuer API-Updates; Spec = diese Datei.

## Agent-Hinweise

- Vor API-Calls Javadoc 0.9.3 lesen.
- Storage-ID == Object-ID bei Kisten trotzdem null- und Identity-checken.
- Kein globaler Dauer-Poll. Kein REFILL. DB-Leichen aktiv loeschen.
- Auto-RESET bleibt still. Commands nur fuer Server-Admins.
