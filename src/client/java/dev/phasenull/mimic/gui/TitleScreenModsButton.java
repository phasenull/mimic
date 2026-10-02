package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.devauth.DevAuth;
import net.minecraft.util.CommonColors;
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
				ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) ->
					g.text(client.font, MimicBuild.label(), 2, s.height - 20, CommonColors.GRAY));
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

		if (DevAuth.isDev()) {
			int w = 120;
			widgets.add(Button.builder(Component.translatable("mimic.auth.button", client.getUser().getName()),
				b -> client.gui.setScreen(new AuthScreen(screen))).bounds(screen.width - w - SPACING, SPACING, w, 20).build());
		}
	}
}
