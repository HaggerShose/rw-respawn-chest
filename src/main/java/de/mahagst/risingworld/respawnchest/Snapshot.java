package de.mahagst.risingworld.respawnchest;

import java.util.ArrayList;
import java.util.List;

import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Storage;

final class Snapshot {
	private Snapshot() {
	}

	static List<TemplateItem> capture(Item[] items) {
		var template = new ArrayList<TemplateItem>();
		if (items == null) {
			return template;
		}
		for (int slot = 0; slot < items.length; slot++) {
			Item item = items[slot];
			if (item == null) {
				continue;
			}
			TemplateItem saved = captureItem(slot, item);
			if (saved != null) {
				template.add(saved);
			}
		}
		return template;
	}

	static void restore(Storage storage, List<TemplateItem> template) {
		storage.clear();
		for (TemplateItem saved : template) {
			Item created = addToSlot(storage, saved);
			if (created == null) {
				continue;
			}
			created.setDurability(saved.durability());
			created.setStatus(saved.status());
			created.setValue(saved.value());
		}
	}

	private static TemplateItem captureItem(int slot, Item item) {
		if (item instanceof Item.BlueprintItem) {
			System.out.println("[RespawnChest] Skipping blueprint item in slot " + slot
					+ " (no Storage add API)");
			return null;
		}
		if (item instanceof Item.ObjectItem objectItem) {
			return new TemplateItem(
					slot,
					TemplateItem.KIND_OBJECT,
					objectItem.getObjectID(),
					objectItem.getVariant(),
					objectItem.getStack(),
					objectItem.getDurability(),
					objectItem.getStatus(),
					objectItem.getValue(),
					0,
					0);
		}
		if (item instanceof Item.ConstructionItem constructionItem) {
			return new TemplateItem(
					slot,
					TemplateItem.KIND_CONSTRUCTION,
					constructionItem.getConstructionID(),
					constructionItem.getVariant(),
					constructionItem.getStack(),
					constructionItem.getDurability(),
					constructionItem.getStatus(),
					constructionItem.getValue(),
					constructionItem.getColor(),
					constructionItem.getInfoID());
		}
		if (item instanceof Item.ClothingItem clothingItem) {
			return new TemplateItem(
					slot,
					TemplateItem.KIND_CLOTHING,
					clothingItem.getClothingID(),
					clothingItem.getVariant(),
					clothingItem.getStack(),
					clothingItem.getDurability(),
					clothingItem.getStatus(),
					clothingItem.getValue(),
					0,
					clothingItem.getInfoID());
		}
		return new TemplateItem(
				slot,
				TemplateItem.KIND_ITEM,
				item.getTypeID(),
				item.getVariant(),
				item.getStack(),
				item.getDurability(),
				item.getStatus(),
				item.getValue(),
				0,
				0);
	}

	private static Item addToSlot(Storage storage, TemplateItem saved) {
		return switch (saved.itemKind()) {
			case TemplateItem.KIND_OBJECT -> storage.addObjectItemToSlot(
					saved.typeId(), saved.variant(), saved.stack(), saved.slot());
			case TemplateItem.KIND_CONSTRUCTION -> storage.addConstructionItemToSlot(
					(byte) saved.typeId(), saved.variant(), saved.stack(), saved.color(), saved.slot());
			case TemplateItem.KIND_CLOTHING -> storage.addClothingItemToSlot(
					saved.typeId(), saved.variant(), saved.stack(), saved.color(), saved.infoId(), saved.slot());
			default -> storage.addItemToSlot(
					saved.typeId(), saved.variant(), saved.stack(), saved.slot());
		};
	}
}
