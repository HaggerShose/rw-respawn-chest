package de.mahagst.risingworld.respawnchest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;
import net.risingworld.api.utils.Vector3f;

/**
 * Refill domain: RAM chest map, RAM templates, pending dues, startup orphan sweep, global tick, RESET.
 * Idle ({@code nextRefill == null}) vs pending only. Hot path never hits SQLite for reads.
 * Pending due times use {@link Server#getIngameTimestamp()} (world time); pause does not advance them.
 * Mutating entry points run on the plugin enqueue thread.
 */
final class RefillService {
	/** Cap for interval after converting minutes (24h). */
	static final int MAX_INTERVAL_SECONDS = 24 * 60 * 60;
	/** Effective delay when /make-refill gets 0 or negative minutes (quick test). */
	static final int MIN_TEST_SECONDS = 5;
	/** Max RESET work per 1s tick when many chests are due at once. */
	private static final long TICK_BUDGET_NS = 250_000_000L;
	static final float TICK_SECONDS = 1f;
	/** Extra wait after {@link World#isInitialized()} before orphan sweep. */
	static final float READY_DELAY_SECONDS = 30f;
	/** Failed restores before dropping the registration. */
	private static final int MAX_RESTORE_ATTEMPTS = 3;
	/**
	 * Values at or above this are treated as legacy unix {@code next_refill}
	 * (pre-ingame-timestamp). ~2001-09-09 in wall-clock ms; playtime ms stay far below.
	 */
	private static final long LEGACY_UNIX_NEXT_REFILL_MIN = 1_000_000_000_000L;

	private final Plugin plugin;
	private final RefillRepository repository;

	/** Full refill_chests rows. Mutate on make / interval / pending / drop. */
	private final Map<Long, RefillChest> chests = new HashMap<>();
	/** storageId -> slot-exact template. Loaded on enable / replaced on update. */
	private final Map<Long, List<TemplateItem>> templates = new HashMap<>();
	/** storageId -> due world-ms. Idle chests are absent. */
	private final Map<Long, Long> pendingById = new HashMap<>();
	/** Consecutive failed restores; cleared on success or drop. */
	private final Map<Long, Integer> restoreFailures = new HashMap<>();

	/** False after {@link #disable()} so leftover enqueue callbacks no-op. */
	private boolean running;
	/** True after startup sweep; pending tick is seeded only then. */
	private boolean swept;

	private Timer readyTimer;
	private Timer sweepTimer;
	private Timer tickTimer;

	RefillService(Plugin plugin, RefillRepository repository) {
		this.plugin = plugin;
		this.repository = repository;
	}

	/**
	 * Load {@link #chests} and {@link #templates} from DB. Does not start timers or drop orphans.
	 * Empty templates are deleted; a failed item query skips that chest for this session.
	 */
	void loadMaps() {
		chests.clear();
		templates.clear();
		pendingById.clear();
		restoreFailures.clear();
		for (RefillChest chest : repository.findAll()) {
			List<TemplateItem> items = repository.findItems(chest.storageId());
			if (items == null) {
				System.out.println("[RespawnChest] Failed to load template for #" + chest.storageId());
				continue;
			}
			if (items.isEmpty()) {
				System.out.println("[RespawnChest] Empty template for #" + chest.storageId() + ", dropping");
				repository.delete(chest.storageId());
				continue;
			}
			chests.put(chest.storageId(), chest);
			templates.put(chest.storageId(), items);
		}
	}

	/**
	 * Register loot/commands already running. Wait until the world is initialized,
	 * wait {@link #READY_DELAY_SECONDS} more, then orphan-sweep and resume pending.
	 */
	void start() {
		running = true;
		if (World.isInitialized()) {
			scheduleSweepDelay();
		} else {
			startReadyPoll();
		}
	}

	/** Kill timers and drop RAM. Safe if {@link #start()} never ran. */
	void disable() {
		running = false;
		swept = false;
		killTimer(readyTimer);
		killTimer(sweepTimer);
		killTimer(tickTimer);
		readyTimer = null;
		sweepTimer = null;
		tickTimer = null;
		chests.clear();
		templates.clear();
		pendingById.clear();
		restoreFailures.clear();
	}

	/**
	 * First loot on an idle registered chest arms pending. Further loot while pending is ignored.
	 * RAM is updated first; a failed persist is logged and the session countdown still runs.
	 */
	void onLoot(long storageId) {
		if (!running) {
			return;
		}
		RefillChest chest = chests.get(storageId);
		if (chest == null) {
			return;
		}
		if (chest.nextRefill() != null) {
			return;
		}
		long due = worldNow() + chest.intervalSeconds() * 1000L;
		chests.put(storageId, chest.withNextRefill(due));
		if (swept) {
			pendingById.put(storageId, due);
			ensureTick();
		}
		if (!repository.setNextRefill(storageId, due)) {
			System.out.println("[RespawnChest] Failed to persist next_refill for #" + storageId);
		}
	}

	static int intervalSecondsFromMinutes(int minutes) {
		if (minutes <= 0) {
			return MIN_TEST_SECONDS;
		}
		long seconds = minutes * 60L;
		if (seconds > MAX_INTERVAL_SECONDS) {
			return MAX_INTERVAL_SECONDS;
		}
		return (int) seconds;
	}

	void register(Player player, ObjectElement object, Storage storage, int intervalSeconds, boolean active) {
		RefillChest existing = chests.get(storage.getID());
		if (existing != null) {
			updateInterval(player, storage, intervalSeconds);
			return;
		}
		List<TemplateItem> items = Snapshot.capture(storage.getItems());
		if (items.isEmpty()) {
			player.sendTextMessage("Chest is empty.");
			return;
		}
		Vector3f pos = object.getWorldPosition();
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
				System.currentTimeMillis(),
				active);
		if (!repository.insert(chest, items)) {
			player.sendTextMessage("Could not save refill chest.");
			return;
		}
		chests.put(chest.storageId(), chest);
		templates.put(chest.storageId(), items);
		player.sendTextMessage("Refill chest created. Interval: " + intervalSeconds + "s");
	}

	/** Pending due stays; new interval applies on the next loot. */
	void updateInterval(Player player, Storage storage, int intervalSeconds) {
		RefillChest chest = requireValid(player, storage);
		if (chest == null) {
			return;
		}
		if (chest.intervalSeconds() == intervalSeconds) {
			player.sendTextMessage("Interval not changed (already " + intervalSeconds + "s).");
			return;
		}
		if (!repository.setIntervalSeconds(chest.storageId(), intervalSeconds)) {
			player.sendTextMessage("Could not save interval.");
			return;
		}
		chests.put(chest.storageId(), chest.withIntervalSeconds(intervalSeconds));
		player.sendTextMessage("Interval updated to " + intervalSeconds + "s.");
	}

	void update(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, storage);
		if (chest == null) {
			return;
		}
		List<TemplateItem> items = Snapshot.capture(storage.getItems());
		if (items.isEmpty()) {
			player.sendTextMessage("Chest is empty.");
			return;
		}
		if (!repository.replaceItems(storage.getID(), items)) {
			player.sendTextMessage("Could not save template.");
			return;
		}
		templates.put(storage.getID(), items);
		player.sendTextMessage("Template updated.");
	}

	void now(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, storage);
		if (chest == null) {
			return;
		}
		if (!restore(chest, storage)) {
			player.sendTextMessage("Could not reset chest.");
			return;
		}
		player.sendTextMessage("Chest reset.");
	}

	void remove(Player player, Storage storage) {
		if (!chests.containsKey(storage.getID())) {
			player.sendTextMessage("Chest is not registered.");
			return;
		}
		if (!repository.delete(storage.getID())) {
			player.sendTextMessage("Could not remove refill chest.");
			return;
		}
		evict(storage.getID());
		player.sendTextMessage("Chest removed.");
	}

	void info(Player player, ObjectElement object, Storage storage) {
		RefillChest chest = requireValid(player, storage);
		if (chest == null) {
			return;
		}
		List<TemplateItem> items = templates.get(chest.storageId());
		int amount = 0;
		int stacks = 0;
		if (items != null) {
			stacks = items.size();
			for (TemplateItem item : items) {
				amount += item.stack();
			}
		}
		boolean pending = chest.nextRefill() != null;
		String rest = "-";
		if (pending) {
			rest = Math.max(0, (chest.nextRefill() - worldNow()) / 1000) + "s";
		}
		player.sendTextMessage(
				"Refill Chest: interval " + chest.intervalSeconds() + "s, pending "
						+ (pending ? "yes" : "no") + ", remaining: " + rest
						+ ", template: " + stacks + " stacks / " + amount + " items");
	}

	void list(Player player) {
		Vector3f pos = player.getPosition();
		List<RefillChest> rows = new ArrayList<>();
		for (RefillChest chest : chests.values()) {
			if (chest.active()) {
				rows.add(chest);
			}
		}
		if (rows.isEmpty()) {
			player.sendTextMessage("No refill chests.");
			return;
		}
		if (pos != null) {
			rows.sort(Comparator.comparingDouble(
					c -> pos.distanceSquared(c.worldX(), c.worldY(), c.worldZ())));
		}
		long now = worldNow();
		StringBuilder out = new StringBuilder("<color=#aaaaaa>Refill chests (")
				.append(rows.size()).append(")</color>");
		for (RefillChest chest : rows) {
			out.append("\n<color=#ffffff>#").append(chest.storageId()).append("</color>");
			if (chest.nextRefill() != null) {
				long rest = Math.max(0, (chest.nextRefill() - now) / 1000);
				out.append("  <color=#ffcc66>pending ").append(rest).append("s</color>");
			} else {
				out.append("  <color=#88cc88>idle</color>");
			}
			out.append("  ").append(chest.intervalSeconds()).append("s")
					.append("  (").append((int) chest.worldX())
					.append(", ").append((int) chest.worldY())
					.append(", ").append((int) chest.worldZ()).append(')');
			if (pos != null) {
				int dist = (int) Math.sqrt(pos.distanceSquared(chest.worldX(), chest.worldY(), chest.worldZ()));
				out.append("  <color=#aaaaaa>").append(dist).append("m</color>");
			}
		}
		player.sendTextMessage(out.toString());
	}

	int size() {
		return chests.size();
	}

	private RefillChest requireValid(Player player, Storage storage) {
		RefillChest chest = chests.get(storage.getID());
		if (chest == null) {
			player.sendTextMessage("Chest is not registered.");
			return null;
		}
		if (matches(chest, storage)) {
			return chest;
		}
		drop(chest.storageId());
		player.sendTextMessage("Chest is no longer valid, entry removed.");
		return null;
	}

	private void startReadyPoll() {
		killTimer(readyTimer);
		readyTimer = new Timer(TICK_SECONDS, TICK_SECONDS, -1, () -> plugin.enqueue(this::onReadyPoll));
		readyTimer.start();
	}

	private void onReadyPoll() {
		if (!running) {
			return;
		}
		if (!World.isInitialized()) {
			return;
		}
		killTimer(readyTimer);
		readyTimer = null;
		scheduleSweepDelay();
	}

	private void scheduleSweepDelay() {
		killTimer(sweepTimer);
		sweepTimer = new Timer(1f, READY_DELAY_SECONDS, 0, () -> plugin.enqueue(this::sweepAndResume));
		sweepTimer.start();
	}

	/**
	 * Drop registered ids where {@link World#getStorage(long)} is null, convert legacy
	 * unix {@code next_refill}, seed {@link #pendingById}, start the tick if needed.
	 */
	private void sweepAndResume() {
		if (!running) {
			return;
		}
		sweepTimer = null;
		if (chests.isEmpty()) {
			swept = true;
			System.out.println("[RespawnChest] Startup sweep: no registered chests");
			return;
		}
		int dropped = 0;
		for (Long id : new ArrayList<>(chests.keySet())) {
			if (World.getStorage(id) == null) {
				drop(id);
				dropped++;
			}
		}
		pendingById.clear();
		for (RefillChest chest : new ArrayList<>(chests.values())) {
			Long next = chest.nextRefill();
			if (next == null) {
				continue;
			}
			next = migrateLegacyNextRefill(chest.storageId(), next);
			pendingById.put(chest.storageId(), next);
		}
		swept = true;
		ensureTick();
		System.out.println("[RespawnChest] Startup sweep: dropped " + dropped
				+ " missing, " + pendingById.size() + " pending");
	}

	private void onTick() {
		if (!running) {
			return;
		}
		if (pendingById.isEmpty()) {
			stopTick();
			return;
		}
		long now = worldNow();
		long deadline = System.nanoTime() + TICK_BUDGET_NS;
		List<Long> dueIds = new ArrayList<>();
		for (Map.Entry<Long, Long> entry : pendingById.entrySet()) {
			if (entry.getValue() <= now) {
				dueIds.add(entry.getKey());
			}
		}
		for (Long storageId : dueIds) {
			onDue(storageId);
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		if (pendingById.isEmpty()) {
			stopTick();
		}
	}

	private void onDue(long storageId) {
		RefillChest chest = chests.get(storageId);
		if (chest == null) {
			pendingById.remove(storageId);
			return;
		}
		Storage storage = World.getStorage(storageId);
		if (storage == null || !matches(chest, storage)) {
			drop(storageId);
			return;
		}
		restore(chest, storage);
	}

	/**
	 * RESET from the RAM template. On failure, retry on later ticks;
	 * after {@link #MAX_RESTORE_ATTEMPTS} drops the registration.
	 *
	 * @return {@code true} if the storage was reset
	 */
	private boolean restore(RefillChest chest, Storage storage) {
		List<TemplateItem> items = templates.get(chest.storageId());
		if (!Snapshot.restore(storage, items)) {
			int attempts = restoreFailures.getOrDefault(chest.storageId(), 0) + 1;
			if (attempts >= MAX_RESTORE_ATTEMPTS) {
				System.out.println("[RespawnChest] Restore failed " + attempts
						+ " times for #" + chest.storageId() + ", dropping");
				drop(chest.storageId());
			} else {
				restoreFailures.put(chest.storageId(), attempts);
			}
			return false;
		}
		restoreFailures.remove(chest.storageId());
		clearPending(chest.storageId());
		return true;
	}

	private void clearPending(long storageId) {
		if (!repository.setNextRefill(storageId, null)) {
			System.out.println("[RespawnChest] Failed to clear next_refill for #" + storageId);
			return;
		}
		RefillChest chest = chests.get(storageId);
		if (chest != null) {
			chests.put(storageId, chest.withNextRefill(null));
		}
		pendingById.remove(storageId);
		if (pendingById.isEmpty()) {
			stopTick();
		}
	}

	/** Delete DB row (best effort) and evict RAM. Used for orphans and failed restores. */
	private void drop(long storageId) {
		if (!repository.delete(storageId)) {
			System.out.println("[RespawnChest] Failed to delete #" + storageId);
		}
		evict(storageId);
	}

	/** RAM-only removal after a successful admin delete, or as the last step of {@link #drop}. */
	private void evict(long storageId) {
		chests.remove(storageId);
		templates.remove(storageId);
		restoreFailures.remove(storageId);
		pendingById.remove(storageId);
		if (pendingById.isEmpty()) {
			stopTick();
		}
	}

	/** Same chest: storage exists, not transient, creation_date matches. */
	private static boolean matches(RefillChest saved, Storage storage) {
		if (storage == null || storage.isTransient()) {
			return false;
		}
		return storage.getCreationDate() == saved.creationDate();
	}

	private static String objectType(ObjectElement object) {
		var def = object.getDefinition();
		if (def != null && def.name != null) {
			return def.name;
		}
		return Short.toString(object.getTypeID());
	}

	private void ensureTick() {
		if (!running || pendingById.isEmpty()) {
			return;
		}
		if (tickTimer != null && !tickTimer.isKilled()) {
			return;
		}
		tickTimer = new Timer(TICK_SECONDS, TICK_SECONDS, -1, () -> plugin.enqueue(this::onTick));
		tickTimer.start();
	}

	private void stopTick() {
		killTimer(tickTimer);
		tickTimer = null;
	}

	private static void killTimer(Timer timer) {
		if (timer != null && !timer.isKilled()) {
			timer.kill();
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
		if (!repository.setNextRefill(storageId, converted)) {
			System.out.println("[RespawnChest] Failed to persist migrated next_refill for #" + storageId);
		}
		RefillChest chest = chests.get(storageId);
		if (chest != null) {
			chests.put(storageId, chest.withNextRefill(converted));
		}
		System.out.println("[RespawnChest] Migrated legacy next_refill for #" + storageId
				+ " (remaining " + (remainingMs / 1000) + "s)");
		return converted;
	}
}
