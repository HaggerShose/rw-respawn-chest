# Ideas for the future

## Kleine QoL (wenig Aufwand)
- **`/refill-list`** -- alle registrierten Kisten (Chunk/Pos/Intervall/pending), ohne LoS. Hilft auf dem Server, wenn man „welche Kisten hab ich überhaupt?“ fragt.
- **Warnung bei Blueprint-Slots** nicht nur Server-Log, sondern kurz an den Admin beim `/make-refill` („Slot X übersprungen“).
- **Release-Skript-Commit** für den `gh`-Fallback-Cleanup, falls der noch lokal rumliegt.

## Robustheit
- **Insert-Transaktion** -- Chest-Row + Items atomar (heute: Chest ok, Items fail -> halber Zustand). Selten, aber unschön.
- **`/refill-now` während pending** -- tut ihr schon (Timer clearen). Evtl. dokumentieren, dass Offline-Zeit den Reset vorzieht (hast du im Code, gut fürs Forum).

## Später / bewusst nicht v1
- Loot-Chancen / YAML-Tabellen  
- Mehrere Templates / Gewichtungen  
- Periodischer Dauer-Respawn ohne Loot-Trigger  
- Admin-UI statt Chat  
- Volle `Map<RefillChest>` statt nur ID-Set (hast du bewusst schlank gelassen)

## Was ich **nicht** anfassen würde
- Mehr Framework-Schichten  
- Client-Mod  
- Globaler Poll  
