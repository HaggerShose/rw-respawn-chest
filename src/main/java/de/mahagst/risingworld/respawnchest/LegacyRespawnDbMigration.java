package de.mahagst.risingworld.respawnchest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * One-shot migrate from shared {@code refill.db} to per-world {@code <WorldName>.db}.
 * Delete this class once old installs are gone.
 */
public final class LegacyRespawnDbMigration {
	private static final String LEGACY_NAME = "refill.db";

	private LegacyRespawnDbMigration() {
	}

	/**
	 * If {@code refill.db} exists and the world db does not, move (plus WAL sidecars) then
	 * remove the legacy files. If the world db already exists, leave legacy alone and log.
	 */
	public static void run(String pluginDir, String worldDbFileName) {
		if (pluginDir == null || pluginDir.isBlank() || worldDbFileName == null || worldDbFileName.isBlank()) {
			return;
		}
		if (LEGACY_NAME.equals(worldDbFileName)) {
			return;
		}
		Path dir = Path.of(pluginDir);
		Path legacy = dir.resolve(LEGACY_NAME);
		if (!Files.isRegularFile(legacy)) {
			return;
		}
		Path target = dir.resolve(worldDbFileName);
		if (Files.exists(target)) {
			System.out.println("[RespawnChest] Legacy " + LEGACY_NAME + " present, but " + worldDbFileName
					+ " already exists -- skip migrate, leave legacy file.");
			return;
		}
		try {
			Files.move(legacy, target);
			moveSidecar(dir, LEGACY_NAME + "-wal", worldDbFileName + "-wal");
			moveSidecar(dir, LEGACY_NAME + "-shm", worldDbFileName + "-shm");
			deleteQuietly(dir.resolve(LEGACY_NAME));
			deleteQuietly(dir.resolve(LEGACY_NAME + "-wal"));
			deleteQuietly(dir.resolve(LEGACY_NAME + "-shm"));
			System.out.println("[RespawnChest] Migrated " + LEGACY_NAME + " -> " + worldDbFileName);
		} catch (IOException e) {
			System.out.println("[RespawnChest] Failed to migrate " + LEGACY_NAME + " -> " + worldDbFileName
					+ ": " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void moveSidecar(Path dir, String fromName, String toName) throws IOException {
		Path from = dir.resolve(fromName);
		if (!Files.isRegularFile(from)) {
			return;
		}
		Path to = dir.resolve(toName);
		Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup after move
		}
	}
}
