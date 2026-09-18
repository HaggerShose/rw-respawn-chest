package de.mahagst.risingworld.respawnchest;

/** One registered refill chest row. {@code nextRefill} null = idle (no pending timer); else world-time ms. */
record RefillChest(
		long storageId,
		long objectId,
		int chunkX,
		int chunkY,
		int chunkZ,
		float worldX,
		float worldY,
		float worldZ,
		String objectType,
		long creationDate,
		int intervalSeconds,
		Long nextRefill,
		long createdAt,
		boolean active) {

	RefillChest withNextRefill(Long next) {
		return new RefillChest(
				storageId, objectId, chunkX, chunkY, chunkZ,
				worldX, worldY, worldZ, objectType, creationDate,
				intervalSeconds, next, createdAt, active);
	}

	RefillChest withIntervalSeconds(int interval) {
		return new RefillChest(
				storageId, objectId, chunkX, chunkY, chunkZ,
				worldX, worldY, worldZ, objectType, creationDate,
				interval, nextRefill, createdAt, active);
	}
}
