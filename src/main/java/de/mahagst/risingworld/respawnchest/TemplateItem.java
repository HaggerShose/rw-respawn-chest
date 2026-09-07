package de.mahagst.risingworld.respawnchest;

record TemplateItem(
		int slot,
		String itemKind,
		short typeId,
		int variant,
		int stack,
		int durability,
		short status,
		float value,
		int color,
		long infoId) {
	static final String KIND_ITEM = "item";
	static final String KIND_OBJECT = "object";
	static final String KIND_CONSTRUCTION = "construction";
	static final String KIND_CLOTHING = "clothing";
}
