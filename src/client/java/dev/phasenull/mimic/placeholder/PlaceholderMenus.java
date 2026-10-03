package dev.phasenull.mimic.placeholder;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menu types for server-only mod containers. Registered as Fabric "extended" menu types whose extra
 * opening data is kept as raw bytes (whatever the mod sends, nothing is read from it), so both ways a
 * server opens a menu work: vanilla's open_screen packet (see MenuScreensMixin) and Fabric's.
 */
public final class PlaceholderMenus {
	private static final Set<MenuType<?>> TYPES = ConcurrentHashMap.newKeySet();

	/** The opening data, unread. */
	private static final StreamCodec<RegistryFriendlyByteBuf, byte[]> RAW = StreamCodec.of(
		(buf, data) -> buf.writeBytes(data),
		buf -> {
			byte[] data = new byte[buf.readableBytes()];
			buf.readBytes(data);
			return data;
		});

	private PlaceholderMenus() {}

	static void register(Identifier id) {
		@SuppressWarnings("unchecked")
		ExtendedMenuType<PlaceholderMenu, byte[]>[] self = new ExtendedMenuType[1];
		self[0] = Placeholders.register(BuiltInRegistries.MENU, ResourceKey.create(Registries.MENU, id),
			() -> new ExtendedMenuType<>((containerId, inventory, data) -> new PlaceholderMenu(self[0], containerId, inventory), RAW));
		TYPES.add(self[0]);
		MenuScreens.register(self[0], PlaceholderMenuScreen::new);
	}

	public static boolean isPlaceholder(MenuType<?> type) {
		return TYPES.contains(type);
	}

	/** Opens a placeholder menu from vanilla's open_screen packet (Fabric's extended type can't be created there). */
	public static void open(MenuType<?> type, Minecraft client, int containerId, Component title) {
		if (client.player == null) {
			return;
		}
		PlaceholderMenu menu = new PlaceholderMenu(type, containerId, client.player.getInventory());
		client.player.containerMenu = menu;
		client.gui.setScreen(new PlaceholderMenuScreen(menu, client.player.getInventory(), title));
	}
}
