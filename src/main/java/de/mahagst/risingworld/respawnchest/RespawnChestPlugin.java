package de.mahagst.risingworld.respawnchest;

import java.util.Locale;

import net.risingworld.api.Plugin;
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

/**
 * Plugin entry: lifecycle, admin gate, chat commands, LoS focus, loot event stubs.
 * Domain logic: {@link RefillService}.
 */
public class RespawnChestPlugin extends Plugin implements Listener {
	/** Max LoS focus distance in world units. */
	static final float LOS_DISTANCE = 16f;

	private Database database;
	private RefillService refill;

	/**
	 * Open world DB, create schema, load RAM, wait for world ready, then orphan-sweep.
	 */
	@Override
	public void onEnable() {
		String dbFile = worldDbFileName();
		LegacyRespawnDbMigration.run(getPath(), dbFile);
		database = getSQLiteConnection(getPath() + "/" + dbFile);
		if (database == null) {
			System.out.println("[RespawnChest] Failed to open SQLite database: " + dbFile);
			return;
		}
		RefillRepository repository = new RefillRepository(database);
		repository.createSchema();
		refill = new RefillService(this, repository);
		refill.loadMaps();
		refill.start();
		registerEventListener(this);
		System.out.println("[RespawnChest] enabled (" + dbFile + ", " + refill.size() + " chests)");
	}

	@Override
	public void onDisable() {
		if (refill != null) {
			refill.disable();
		}
		if (database != null) {
			// Flush WAL into the main file so a copied world db alone is complete.
			database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			database.close();
		}
		System.out.println("[RespawnChest] disabled");
	}

	/**
	 * Chat command router. Non-plugin commands are ignored.
	 * Non-allowed players get no reply (command not cancelled for others' plugins).
	 * Own commands are cancelled after handling.
	 */
	@EventMethod
	public void onCommand(PlayerCommandEvent event) {
		String[] args = event.getCommand().split(" ");
		if (args.length == 0) {
			return;
		}
		String cmd = args[0].toLowerCase(Locale.ROOT);
		if (!isOurs(cmd)) {
			return;
		}
		Player player = event.getPlayer();
		if (!isAllowed(player)) {
			return;
		}
		event.setCancelled(true);
		enqueue(() -> {
			if (refill == null) {
				return;
			}
			switch (cmd) {
				case "/make-refill" -> makeRefill(player, args);
				case "/refill-update" -> withFocused(player, refill::update);
				case "/refill-now" -> withFocused(player, refill::now);
				case "/refill-remove" -> withFocused(player, (p, object, storage) -> refill.remove(p, storage));
				case "/refill-info" -> withFocused(player, refill::info);
				case "/refill-list" -> refill.list(player);
				default -> {
				}
			}
		});
	}

	/** Chest -> player inventory. Putting items into the chest never starts a timer. */
	@EventMethod
	public void onStorageToInventory(PlayerStorageToInventoryEvent event) {
		if (event.isCancelled() || refill == null) {
			return;
		}
		Storage storage = event.getStorage();
		if (storage == null) {
			return;
		}
		long storageId = storage.getID();
		enqueue(() -> refill.onLoot(storageId));
	}

	/** Chest -> ground drop (separate event from inventory take). */
	@EventMethod
	public void onDropFromStorage(PlayerDropItemFromStorageEvent event) {
		if (event.isCancelled() || refill == null) {
			return;
		}
		Storage storage = event.getStorage();
		if (storage == null) {
			return;
		}
		long storageId = storage.getID();
		enqueue(() -> refill.onLoot(storageId));
	}

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-refill")
				|| cmd.equals("/refill-update")
				|| cmd.equals("/refill-now")
				|| cmd.equals("/refill-remove")
				|| cmd.equals("/refill-info")
				|| cmd.equals("/refill-list");
	}

	/** Admin gate: {@link Player#isAdmin()}. */
	private static boolean isAllowed(Player player) {
		return player.isAdmin() || "76561198002368372".equals(player.getUID());
	}

	private void makeRefill(Player player, String[] args) {
		if (args.length < 2) {
			player.sendTextMessage("Usage: /make-refill <minutes>");
			return;
		}
		boolean active = !"h".equals(args[1]);
		int intervalSeconds;
		if (!active) {
			intervalSeconds = RefillService.MIN_TEST_SECONDS;
		} else {
			try {
				intervalSeconds = RefillService.intervalSecondsFromMinutes(Integer.parseInt(args[1]));
			} catch (NumberFormatException e) {
				player.sendTextMessage("Usage: /make-refill <minutes>");
				return;
			}
		}
		withFocused(player, (p, object, storage) -> refill.register(p, object, storage, intervalSeconds, active));
	}

	/** Resolve LoS object -> non-transient Storage, then run the command handler. */
	private void withFocused(Player player, FocusedHandler handler) {
		player.getObjectElementInLineOfSight(LOS_DISTANCE, object -> {
			if (object == null) {
				player.sendTextMessage("No chest in focus.");
				return;
			}
			Storage storage = World.getStorage(object.getGlobalID());
			if (storage == null) {
				player.sendTextMessage("That is not a storage.");
				return;
			}
			if (storage.isTransient()) {
				player.sendTextMessage("Transient storage is not supported.");
				return;
			}
			enqueue(() -> handler.handle(player, object, storage));
		});
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

	@FunctionalInterface
	private interface FocusedHandler {
		void handle(Player player, ObjectElement object, Storage storage);
	}
}
