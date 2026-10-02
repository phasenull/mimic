package dev.phasenull.mimic.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/** Splits the Realms button on the title screen in half and puts a Mods button next to it. */
public final class TitleScreenModsButton {
	private static final int SPACING = 4;

	private TitleScreenModsButton() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				addButton(client, screen);
			}
		});
	}

	private static void addButton(Minecraft client, Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		Button.Builder mods = Button.builder(Component.translatable("mimic.mods.button"),
			b -> client.gui.setScreen(new ModListScreen(screen)));

		AbstractWidget realms = widgets.stream()
			.filter(w -> w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.online"))
			.findFirst().orElse(null);

		if (realms != null) {
			int full = realms.getWidth();
			int half = (full - SPACING) / 2;
			realms.setWidth(half);
			widgets.add(mods.bounds(realms.getX() + half + SPACING, realms.getY(), full - half - SPACING, realms.getHeight()).build());
		} else {
			widgets.add(mods.bounds(SPACING, SPACING, 60, 20).build());
		}
	}
}
