# RespawnChest

Server-side Rising World plugin that turns a placed chest into a quiet loot respawn point.

Fill a chest, look at it, register it once. After players loot it, a pending due time starts and the contents are reset to your saved template. Players do not need to install anything.

## How it works

1. Place a normal chest and put the desired loot inside.
2. Look at the chest.
3. Run `/make-refill <minutes>`.
4. When someone takes items out (into inventory or dropped on the ground), a pending due time is stored (delay uses **world time**: pause / empty idle does not count). Further loot does not restart it.
5. When that due time is reached, the chest is cleared and the original template is restored (RESET). A single 1s tick runs only while at least one chest is pending (up to 100 resets per tick).

Notes:

- Putting items **into** the chest does not start a refill.
- While a refill is already pending, further looting does not restart it.
- Respawn itself is silent (no broadcast).
- Only the admin who runs a command gets chat feedback.

## Commands

Admin only.

Look at the chest first, then use chat **with** a leading `/`.

| Command                  | Effect                                                                                                      |
| ------------------------ | ----------------------------------------------------------------------------------------------------------- |
| `/make-refill <minutes>` | Register the focused chest. Current contents become the template. Already registered: update interval only. |
| `/refill-update`         | Save the current contents as the new template (pending due stays).                                          |
| `/refill-now`            | Reset immediately and clear any pending due.                                                                |
| `/refill-remove`         | Unregister the chest.                                                                                       |
| `/refill-info`           | Show interval, pending state, remaining time, and template size.                                            |
| `/refill-list`           | List registered chests nearest-first (position, interval, pending remaining, distance).                     |

### Interval

- `<minutes>` is the delay after loot until reset, measured in **world time** (paused / empty idle does not count).
- `0` (or less) -> **5 seconds** (for testing).
- Maximum: **24 hours** (`1440` minutes).

### Rules

- Empty chests cannot be registered or have their template updated.
- `/make-refill` on an already registered chest only changes the interval (same interval = no-op). A pending due is not restarted.
- On respawn the chest is emptied completely, then the saved template is put back exactly (same slots and stacks). Anything players added that was not in the template is gone.

## Install

Put the jar here and restart the server:

```text
Plugins/RespawnChest/RespawnChest.jar
```

State is stored automatically in:

```text
Plugins/RespawnChest/<WorldName>.db
```

Older installs used `refill.db`. On first start the plugin moves that file to the world db if the world file does not exist yet.

On startup the plugin waits until the world is initialized plus 5 seconds, then drops any registered chest whose storage no longer exists (`World.getAllStorages()`). A pending due that fires later and finds no storage is dropped the same way.
Pending due times use the world's accumulated active time (`Server.getIngameTimestamp`), not wall-clock -- so pause and empty-server idle do not burn the refill delay. Legacy unix `next_refill` values from older plugin builds are converted once on load. Remaining delay survives a server restart.

## License

MIT -- see [LICENSE](LICENSE).
