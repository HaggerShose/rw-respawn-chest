# RespawnChest

Server-side Rising World plugin that turns a placed chest into a quiet loot respawn point.

Fill a chest, look at it, register it once. After players loot it, a timer starts and the contents are reset to your saved template. Players do not need to install anything.

## How it works

1. Place a normal chest and put the desired loot inside.
2. Look at the chest.
3. Run `/make-refill <minutes>`.
4. When someone takes items out (into inventory or dropped on the ground), a one-shot timer starts.
5. When the timer ends, the chest is cleared and the original template is restored (RESET).

Notes:

- Putting items **into** the chest does not start a timer.
- While a timer is already running, further looting does not restart it.
- Respawn itself is silent (no broadcast).
- Only the admin who runs a command gets chat feedback.

## Commands

Admin only (`Server_Admins` in `server.properties`).

Look at the chest first, then use chat or the `^` console **with** a leading `/`.

| Command                  | Effect                                                               |
| ------------------------ | -------------------------------------------------------------------- |
| `/make-refill <minutes>` | Register the focused chest. Current contents become the template.    |
| `/refill-update`         | Save the current contents as the new template (pending timer stays). |
| `/refill-now`            | Reset immediately and clear any pending timer.                       |
| `/refill-remove`         | Unregister the chest.                                                |
| `/refill-info`           | Show interval, pending state, remaining time, and template size.     |

### Interval

- `<minutes>` is the delay after loot until reset.
- `0` (or less) -> **5 seconds** (for testing).
- Maximum: **24 hours** (`1440` minutes).

### Rules

- Empty chests cannot be registered or updated.
- An already registered chest must be removed with `/refill-remove` before `/make-refill` again.
- On respawn the chest is emptied completely, then the saved template is put back exactly (same slots and stacks). Anything players added that was not in the template is gone.

## Install

Put the jar here and restart the server:

```text
Plugins/RespawnChest/RespawnChest.jar
```

State is stored automatically in:

```text
Plugins/RespawnChest/refill.db
```

On startup, missing or replaced chests are cleaned out of the database so dead entries do not stick around.

## License

MIT -- see [LICENSE](LICENSE).
