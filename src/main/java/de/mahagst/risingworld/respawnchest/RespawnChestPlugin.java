package de.mahagst.risingworld.respawnchest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.database.Database;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.events.player.PlayerDropItemFromStorageEvent;
import net.risingworld.api.events.player.inventory.PlayerStorageToInventoryEvent;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;
import net.risingworld.api.utils.Vector3f;

/**
 * Single plugin class: admin commands, loot triggers, one-shot refill timers, identity cleanup.
 * Idle registered chests do nothing until loot starts a timer.
 * Pending due times use {@link Server#getIngameTimestamp()} (world time); pause does not advance them.
 */
public class RespawnChestPlugin extends Plugin implements Listener {
	static final float LOS_DISTANCE = 5f;
	static final float MAX_IDENTITY_DISTANCE = 5f;
	/** Retry when storage is temporarily unavailable; never delete on null alone. */
	static final float STORAGE_RETRY_SECONDS = 30f;
	static final int MAX_INTERVAL_SECONDS = 24 * 60 * 60;
	/** Effective delay when /make-refill gets 0 or negative minutes (quick test). */
	static final int MIN_TEST_SECONDS = 5;
	/**
	 * Values at or above this are treated as legacy unix {@code next_refill}
	 * (pre-ingame-timestamp). ~2001-09-09 in wall-clock ms; playtime ms stay far below.
	 */
	private static final long LEGACY_UNIX_NEXT_REFILL_MIN = 1_000_000_000_000L;
	private static final Set<String> ALLOWED_UIDS = Set.of(
			"76561198002368372");

	private Database database;
	private RefillRepository repository;
	/** Membership mirror of refill_chests PKs; not chest state. */
	private final Set<Long> registeredIds = new HashSet<>();
	/** At most one pending RW timer per storage id. */
	private final Map<Long, Timer> timers = new HashMap<>();

	@Override
	public void onEnable() {
		String dbFile = worldDbFileName();
		LegacyRespawnDbMigration.run(getPath(), dbFile);
		database = getSQLiteConnection(getPath() + "/" + dbFile);
		if (database == null) {
			System.out.println("[RespawnChest] Failed to open SQLite database: " + dbFile);
			return;
		}
		repository = new RefillRepository(database);
		repository.createSchema();
		loadRegisteredIds();
		sweepAndResume();
		registerEventListener(this);
		System.out.println("[RespawnChest] enabled");
	}

	@Override
	public void onDisable() {
		cancelAllTimers();
		if (database != null) {
			// Flush WAL into the main file so a copied world db alone is complete.
			database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			database.close();
		}
		System.out.println("[RespawnChest] disabled");
	}

	@EventMethod
	public void onCommand(PlayerCommandEvent event) {
		String[] args = event.getCommand().split(" ");
		if (args.length == 0) {
			return;
		}
		String cmd = args[0].toLowerCase();
		if (!isOurs(cmd)) {
			return;
		}
		Player player = event.getPlayer();
		// Non-admins: ignore silently (do not cancel).
		if (!isAllowed(player)) {
			return;
		}
		event.setCancelled(true);
		switch (cmd) {
			case "/make-refill" -> makeRefill(player, args);
			case "/refill-update" -> withFocused(player, this::update);
			case "/refill-now" -> withFocused(player, this::now);
			case "/refill-remove" -> withFocused(player, this::remove);
			case "/refill-info" -> withFocused(player, this::info);
			default -> {
			}
		}
	}

	/** Chest -> player inventory. Putting items into the chest never starts a timer. */
	@EventMethod
	public void onStorageToInventory(PlayerStorageToInventoryEvent event) {
		if (!event.isCancelled()) {
			scheduleIfNeeded(event.getStorage());
		}
	}

	/** Chest -> ground drop (separate event from inventory take). */
	@EventMethod
	public void onDropFromStorage(PlayerDropItemFromStorageEvent event) {
		if (!event.isCancelled()) {
			scheduleIfNeeded(event.getStorage());
		}
	}

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-refill")
				|| cmd.equals("/refill-update")
				|| cmd.equals("/refill-now")
				|| cmd.equals("/refill-remove")
				|| cmd.equals("/refill-info");
	}

	private static boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && ALLOWED_UIDS.contains(uid);
	}

	static int intervalSecondsFromMinutes(int minutes) {
		if (minutes <= 0) {
			return MIN_TEST_SECONDS;
		}
		return Math.min(minutes * 60, MAX_INTERVAL_SECONDS);
	}

	private void makeRefill(Player player, String[] args) {
		if (args.length < 2) {
			player.sendTextMessage("Usage: /make-refill <minutes>");
			return;
		}
		int minutes;
		try {
			minutes = Integer.parseInt(args[1]);
		} catch (NumberFormatException e) {
			player.sendTextMessage("Usage: /make-refill <minutes>");
			return;
		}
		int intervalSeconds = intervalSecondsFromMinutes(minutes);
		withFocused(player, (p, object, storage) -> register(p, object, storage, intervalSeconds));
	}

	private void register(Player player, ObjectElement object, Storage storage, int intervalSeconds) {
		if (repository.findChest(storage.getID()).isPresent()) {
			updateInterval(player, object, storage, intervalSeconds);
			return;
		}
		List<TemplateItem> items = Snapshot.capture(storage.getItems());
		if (items.isEmpty()) {
			player.sendTextMessage("Chest is empty.");
			return;
		}
		var pos = object.getWorldPosition();
		RefillChest chest = new RefillChest(
				storage.getID(),
				object.getGlobalID(),
				object.getChunkPositionX(),
				object.getChunkPositionY(),
				object.getChunkPositionZ(),
				pos.x,
				pos.y,
				pos.z,
				objectType(object),
				storage.getCreationDate(),
				intervalSeconds,
				null,
				System.currentTimeMillis());
		if (!repository.insert(chest, items)) {
			player.sendTextMessage("Could not save refill chest.");
			return;
		}
		registeredIds.add(chest.storageId());
		player.sendTextMessage("Refill chest created. Interval: " + intervalSeconds + "s");
	}

	/** Pending timer and next_refill stay as they are; new interval applies on the next loot. */
	private void updateInterval(Player player, ObjectElement object, Storage storage, int intervalSeconds) {
		RefillChest chest = requireValid(player, object, storage);
		if (chest == null) {
			return;
		}
		if (chest.intervalSeconds() == intervalSeconds) {
			player.sendTextMessage("Interval not changed (already " + intervalSeconds + "s).");
			return;
		}
		repository.setIntervalSeconds(chest.storageId(), intervalSeconds);
		player.sendTextMessage("Interval updated to " + intervalSeconds + "s.");
	}

	private void update(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, object, storage);
		if (chest == null) {
			return;
		}
		List<TemplateItem> items = Snapshot.capture(storage.getItems());
		if (items.isEmpty()) {
			player.sendTextMessage("Chest is empty.");
			return;
		}
		// Pending timer is intentionally left alone.
		if (!repository.replaceItems(storage.getID(), items)) {
			player.sendTextMessage("Could not save template.");
			return;
		}
		player.sendTextMessage("Template updated.");
	}

	private void now(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, object, storage);
		if (chest == null) {
			return;
		}
		restoreQuietly(chest);
		player.sendTextMessage("Chest reset.");
	}

	private void remove(Player player, ObjectElement object, Storage storage) {
		if (repository.findChest(storage.getID()).isEmpty()) {
			player.sendTextMessage("Chest is not registered.");
			return;
		}
		drop(storage.getID());
		player.sendTextMessage("Chest removed.");
	}

	private void info(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, object, storage);
		if (chest == null) {
			return;
		}
		List<TemplateItem> items = repository.findItems(chest.storageId());
		int amount = 0;
		for (TemplateItem item : items) {
			amount += item.stack();
		}
		boolean pending = chest.nextRefill() != null;
		String rest = "-";
		if (pending) {
			rest = Math.max(0, (chest.nextRefill() - worldNow()) / 1000) + "s";
		}
		player.sendTextMessage(
				"Refill Chest: interval " + chest.intervalSeconds() + "s, pending "
						+ (pending ? "yes" : "no") + ", remaining: " + rest
						+ ", template: " + items.size() + " stacks / " + amount + " items");
	}

	private RefillChest requireValid(Player player, ObjectElement object, Storage storage) {
		Optional<RefillChest> chestOpt = repository.findChest(storage.getID());
		if (chestOpt.isEmpty()) {
			player.sendTextMessage("Chest is not registered.");
			return null;
		}
		RefillChest chest = chestOpt.get();
		Identity id = identity(chest, storage, object);
		if (id == Identity.MATCH) {
			return chest;
		}
		if (id == Identity.MISMATCH) {
			drop(chest.storageId());
			player.sendTextMessage("Chest is no longer valid, entry removed.");
			return null;
		}
		player.sendTextMessage("Chest is not available right now.");
		return null;
	}

	/** Resolve LoS object -> non-transient Storage, then run the command handler. */
	private void withFocused(Player player, FocusedHandler handler) {
		player.getObjectElementInLineOfSight(LOS_DISTANCE, object -> {
			if (object == null) {
				player.sendTextMessage("No chest in focus.");
				return;
			}
			// For chests, storage id equals object global id -- still null-check.
			Storage storage = World.getStorage(object.getGlobalID());
			if (storage == null) {
				player.sendTextMessage("That is not a storage.");
				return;
			}
			if (storage.isTransient()) {
				player.sendTextMessage("Transient storage is not supported.");
				return;
			}
			handler.handle(player, object, storage);
		});
	}

	/**
	 * First loot on an idle registered chest schedules one timer.
	 * Further loot while pending does not restart it.
	 */
	private void scheduleIfNeeded(Storage storage) {
		if (storage == null) {
			return;
		}
		long storageId = storage.getID();
		if (!registeredIds.contains(storageId)) {
			return;
		}
		Optional<RefillChest> chestOpt = repository.findChest(storageId);
		if (chestOpt.isEmpty()) {
			registeredIds.remove(storageId);
			return;
		}
		RefillChest chest = chestOpt.get();
		if (chest.nextRefill() != null) {
			return;
		}
		Identity id = identity(chest, storage, findObject(chest));
		if (id == Identity.MISMATCH) {
			drop(storageId);
			return;
		}
		if (id != Identity.MATCH) {
			return;
		}
		repository.setNextRefill(storageId, worldNow() + chest.intervalSeconds() * 1000L);
		schedule(storageId, chest.intervalSeconds());
	}

	private void onRefillDue(long storageId) {
		Optional<RefillChest> chestOpt = repository.findChest(storageId);
		if (chestOpt.isEmpty()) {
			cancelTimer(storageId);
			return;
		}
		RefillChest chest = chestOpt.get();
		Long next = chest.nextRefill();
		if (next == null) {
			cancelTimer(storageId);
			return;
		}
		long now = worldNow();
		if (next > now) {
			schedule(storageId, (next - now) / 1000f);
			return;
		}
		Identity id = identity(chest, World.getStorage(storageId), findObject(chest));
		if (id == Identity.MISMATCH) {
			drop(storageId);
			return;
		}
		if (id == Identity.UNCERTAIN) {
			// Keep DB row; storage may not be ready yet (startup / unload race).
			schedule(storageId, STORAGE_RETRY_SECONDS);
			return;
		}
		restoreQuietly(chest);
	}

	/** Silent RESET: clear storage, write template slots, clear pending. No chat. */
	private void restoreQuietly(RefillChest chest) {
		Storage storage = World.getStorage(chest.storageId());
		Identity id = identity(chest, storage, findObject(chest));
		if (id == Identity.MISMATCH) {
			drop(chest.storageId());
			return;
		}
		if (id != Identity.MATCH) {
			schedule(chest.storageId(), STORAGE_RETRY_SECONDS);
			return;
		}
		Snapshot.restore(storage, repository.findItems(chest.storageId()));
		repository.setNextRefill(chest.storageId(), null);
		cancelTimer(chest.storageId());
	}

	private void drop(long storageId) {
		cancelTimer(storageId);
		repository.delete(storageId);
		registeredIds.remove(storageId);
	}

	private static String objectType(ObjectElement object) {
		var def = object.getDefinition();
		if (def != null && def.name != null) {
			return def.name;
		}
		return Short.toString(object.getTypeID());
	}

	private static ObjectElement findObject(RefillChest chest) {
		return World.getObject(chest.objectId(), chest.chunkX(), chest.chunkY(), chest.chunkZ());
	}

	/**
	 * MATCH: same chest, safe to RESET / command.
	 * UNCERTAIN: storage missing -- keep DB row (never treat null as proof of deletion).
	 * MISMATCH: positive proof the chest was replaced or is invalid -- drop.
	 */
	private static Identity identity(RefillChest saved, Storage storage, ObjectElement object) {
		if (storage == null) {
			return Identity.UNCERTAIN;
		}
		if (storage.isTransient()) {
			return Identity.MISMATCH;
		}
		if (storage.getCreationDate() != saved.creationDate()) {
			return Identity.MISMATCH;
		}
		if (object != null) {
			if (!saved.objectType().equals(objectType(object))) {
				return Identity.MISMATCH;
			}
			Vector3f pos = object.getWorldPosition();
			if (pos == null) {
				return Identity.UNCERTAIN;
			}
			float max = MAX_IDENTITY_DISTANCE * MAX_IDENTITY_DISTANCE;
			if (pos.distanceSquared(saved.worldX(), saved.worldY(), saved.worldZ()) > max) {
				return Identity.MISMATCH;
			}
		}
		return Identity.MATCH;
	}

	private void schedule(long storageId, float delaySeconds) {
		cancelTimer(storageId);
		float delay = Math.max(delaySeconds, 0.1f);
		// repetitions = 0 => one-shot; enqueue keeps the callback on the plugin thread.
		Timer timer = new Timer(1f, delay, 0, () -> enqueue(() -> onRefillDue(storageId)));
		timers.put(storageId, timer);
		timer.start();
	}

	private void cancelTimer(long storageId) {
		Timer timer = timers.remove(storageId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelAllTimers() {
		for (Timer timer : timers.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		timers.clear();
	}

	private void loadRegisteredIds() {
		for (RefillChest chest : repository.findAll()) {
			registeredIds.add(chest.storageId());
		}
	}

	/**
	 * Startup: drop only clear mismatches, migrate legacy unix {@code next_refill},
	 * then fire overdue resets or schedule remaining delay (world time).
	 */
	private void sweepAndResume() {
		for (RefillChest chest : repository.findAll()) {
			Identity id = identity(chest, World.getStorage(chest.storageId()), findObject(chest));
			if (id == Identity.MISMATCH) {
				drop(chest.storageId());
				continue;
			}
			Long next = chest.nextRefill();
			if (next == null) {
				continue;
			}
			next = migrateLegacyNextRefill(chest.storageId(), next);
			if (id == Identity.UNCERTAIN) {
				schedule(chest.storageId(), STORAGE_RETRY_SECONDS);
				continue;
			}
			long now = worldNow();
			if (next <= now) {
				restoreQuietly(chest);
			} else {
				schedule(chest.storageId(), (next - now) / 1000f);
			}
		}
	}

	/**
	 * Accumulated active world time in ms ({@link Server#getIngameTimestamp()}).
	 * Pause and empty-world idle do not advance this clock.
	 */
	private static long worldNow() {
		return Server.getIngameTimestamp();
	}

	/**
	 * One-shot: rewrite unix-ms {@code next_refill} to world time, preserving remaining delay.
	 *
	 * @return converted due time, or {@code next} unchanged
	 */
	private Long migrateLegacyNextRefill(long storageId, long next) {
		if (next < LEGACY_UNIX_NEXT_REFILL_MIN) {
			return next;
		}
		long remainingMs = Math.max(0L, next - System.currentTimeMillis());
		long converted = worldNow() + remainingMs;
		repository.setNextRefill(storageId, converted);
		System.out.println("[RespawnChest] Migrated legacy next_refill for #" + storageId
				+ " (remaining " + (remainingMs / 1000) + "s)");
		return converted;
	}

	/**
	 * One SQLite file per world: {@code <World.getName()>.db}.
	 * Path-unsafe characters become {@code _}.
	 */
	private static String worldDbFileName() {
		String name = World.getName();
		if (name == null || name.isBlank()) {
			return "world.db";
		}
		String safe = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
		if (safe.isBlank()) {
			return "world.db";
		}
		return safe + ".db";
	}

	/**
	 * Identity outcome for a registered chest.
	 * Null storage is UNCERTAIN so existing chests are never dropped on a miss.
	 */
	private enum Identity {
		MATCH,
		UNCERTAIN,
		MISMATCH
	}

	@FunctionalInterface
	private interface FocusedHandler {
		void handle(Player player, ObjectElement object, Storage storage);
	}
}
