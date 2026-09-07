package de.mahagst.risingworld.respawnchest;

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
		long createdAt) {
}
