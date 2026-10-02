package dev.phasenull.mimic.placeholder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Stands in for an item only the server has; named after its id since the client has no translation. */
public class PlaceholderItem extends Item {
	private final String id;

	PlaceholderItem(Properties properties, String id) {
		super(properties);
		this.id = id;
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.literal(id);
	}
}
