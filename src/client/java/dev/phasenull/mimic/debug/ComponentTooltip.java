package dev.phasenull.mimic.debug;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * With advanced tooltips (F3+H) on and Shift held, lists an item's data components and their values.
 * Components the server set or changed (not the item's defaults) are marked with "*".
 */
public final class ComponentTooltip {
	private static final int MAX_VALUE_CHARS = 70;

	private ComponentTooltip() {}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!flag.isAdvanced() || stack.isEmpty()) {
				return;
			}
			if (!Minecraft.getInstance().hasShiftDown()) {
				lines.add(Component.literal("Hold Shift for components").withStyle(ChatFormatting.DARK_GRAY));
				return;
			}
			DataComponentMap defaults = stack.getItem().components();
			for (TypedDataComponent<?> component : stack.getComponents()) {
				boolean changed = !Objects.equals(defaults.get(component.type()), component.value());
				String value = String.valueOf(component.value());
				if (value.length() > MAX_VALUE_CHARS) {
					value = value.substring(0, MAX_VALUE_CHARS) + "...";
				}
				lines.add(Component.literal((changed ? "* " : "  ") + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()) + " = " + value)
					.withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
			}
		});
	}
}
