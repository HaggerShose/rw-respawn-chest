package de.mahagst.risingworld.respawnchest;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.risingworld.api.database.Database;

/** SQLite persistence for registered chests and their slot-exact item templates. */
final class RefillRepository {
	private final Database database;

	RefillRepository(Database database) {
		this.database = database;
	}

	void createSchema() {
		database.execute("PRAGMA foreign_keys = ON");
		// DELETE so a copied refill.db alone is complete; write load is tiny.
		database.execute("PRAGMA journal_mode=DELETE");
		database.execute("""
				CREATE TABLE IF NOT EXISTS refill_chests (
				  storage_id INTEGER PRIMARY KEY NOT NULL,
				  object_id INTEGER NOT NULL,
				  chunk_x INTEGER NOT NULL,
				  chunk_y INTEGER NOT NULL,
				  chunk_z INTEGER NOT NULL,
				  world_x REAL NOT NULL,
				  world_y REAL NOT NULL,
				  world_z REAL NOT NULL,
				  object_type TEXT NOT NULL,
				  creation_date INTEGER NOT NULL,
				  interval_seconds INTEGER NOT NULL,
				  next_refill INTEGER,
				  created_at INTEGER NOT NULL
				)
				""");
		database.execute("""
				CREATE TABLE IF NOT EXISTS refill_items (
				  storage_id INTEGER NOT NULL,
				  slot INTEGER NOT NULL,
				  item_kind TEXT NOT NULL,
				  type_id INTEGER NOT NULL,
				  variant INTEGER NOT NULL,
				  stack INTEGER NOT NULL,
				  durability INTEGER NOT NULL,
				  status INTEGER NOT NULL,
				  value REAL NOT NULL,
				  color INTEGER NOT NULL,
				  info_id INTEGER NOT NULL,
				  PRIMARY KEY(storage_id, slot),
				  FOREIGN KEY(storage_id) REFERENCES refill_chests(storage_id) ON DELETE CASCADE
				)
				""");
	}

	Optional<RefillChest> findChest(long storageId) {
		var sql = "SELECT * FROM refill_chests WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, storageId);
			try (var result = prep.executeQuery()) {
				if (result.next()) {
					return Optional.of(readChest(result));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return Optional.empty();
	}

	List<RefillChest> findAll() {
		var chests = new ArrayList<RefillChest>();
		var sql = "SELECT * FROM refill_chests";
		try (var prep = database.getConnection().prepareStatement(sql);
				var result = prep.executeQuery()) {
			while (result.next()) {
				chests.add(readChest(result));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return chests;
	}

	List<TemplateItem> findItems(long storageId) {
		var items = new ArrayList<TemplateItem>();
		var sql = "SELECT * FROM refill_items WHERE storage_id = ? ORDER BY slot";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, storageId);
			try (var result = prep.executeQuery()) {
				while (result.next()) {
					items.add(new TemplateItem(
							result.getInt("slot"),
							result.getString("item_kind"),
							(short) result.getInt("type_id"),
							result.getInt("variant"),
							result.getInt("stack"),
							result.getInt("durability"),
							(short) result.getInt("status"),
							result.getFloat("value"),
							result.getInt("color"),
							result.getLong("info_id")));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return items;
	}

	boolean insert(RefillChest chest, List<TemplateItem> items) {
		var sql = """
				INSERT INTO refill_chests (
				  storage_id, object_id, chunk_x, chunk_y, chunk_z,
				  world_x, world_y, world_z, object_type, creation_date,
				  interval_seconds, next_refill, created_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			bindChest(prep, chest);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
		replaceItems(chest.storageId(), items);
		return true;
	}

	/** Replace the whole template; used by /make-refill insert path and /refill-update. */
	void replaceItems(long storageId, List<TemplateItem> items) {
		var deleteSql = "DELETE FROM refill_items WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(deleteSql)) {
			prep.setLong(1, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			return;
		}
		var insertSql = """
				INSERT INTO refill_items (
				  storage_id, slot, item_kind, type_id, variant, stack,
				  durability, status, value, color, info_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (var prep = database.getConnection().prepareStatement(insertSql)) {
			for (TemplateItem item : items) {
				prep.setLong(1, storageId);
				prep.setInt(2, item.slot());
				prep.setString(3, item.itemKind());
				prep.setInt(4, item.typeId());
				prep.setInt(5, item.variant());
				prep.setInt(6, item.stack());
				prep.setInt(7, item.durability());
				prep.setInt(8, item.status());
				prep.setFloat(9, item.value());
				prep.setInt(10, item.color());
				prep.setLong(11, item.infoId());
				prep.addBatch();
			}
			prep.executeBatch();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/** null next_refill means idle (no timer pending). */
	void setNextRefill(long storageId, Long nextRefill) {
		var sql = "UPDATE refill_chests SET next_refill = ? WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			if (nextRefill == null) {
				prep.setNull(1, java.sql.Types.INTEGER);
			} else {
				prep.setLong(1, nextRefill);
			}
			prep.setLong(2, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	void setIntervalSeconds(long storageId, int intervalSeconds) {
		var sql = "UPDATE refill_chests SET interval_seconds = ? WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setInt(1, intervalSeconds);
			prep.setLong(2, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/** CASCADE deletes refill_items via FK. */
	void delete(long storageId) {
		var sql = "DELETE FROM refill_chests WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void bindChest(java.sql.PreparedStatement prep, RefillChest chest) throws SQLException {
		prep.setLong(1, chest.storageId());
		prep.setLong(2, chest.objectId());
		prep.setInt(3, chest.chunkX());
		prep.setInt(4, chest.chunkY());
		prep.setInt(5, chest.chunkZ());
		prep.setFloat(6, chest.worldX());
		prep.setFloat(7, chest.worldY());
		prep.setFloat(8, chest.worldZ());
		prep.setString(9, chest.objectType());
		prep.setLong(10, chest.creationDate());
		prep.setInt(11, chest.intervalSeconds());
		if (chest.nextRefill() == null) {
			prep.setNull(12, java.sql.Types.INTEGER);
		} else {
			prep.setLong(12, chest.nextRefill());
		}
		prep.setLong(13, chest.createdAt());
	}

	private static RefillChest readChest(java.sql.ResultSet result) throws SQLException {
		long nextRefill = result.getLong("next_refill");
		Long next = result.wasNull() ? null : nextRefill;
		return new RefillChest(
				result.getLong("storage_id"),
				result.getLong("object_id"),
				result.getInt("chunk_x"),
				result.getInt("chunk_y"),
				result.getInt("chunk_z"),
				result.getFloat("world_x"),
				result.getFloat("world_y"),
				result.getFloat("world_z"),
				result.getString("object_type"),
				result.getLong("creation_date"),
				result.getInt("interval_seconds"),
				next,
				result.getLong("created_at"));
	}
}
