package dev.phasenull.mimic.debug;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.util.CommonColors;

/** Shows the live connection status at the bottom of any screen while joining a server. */
public final class ConnectionOverlay {
	private ConnectionOverlay() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
				if (!ConnectionDebug.active() || client.level != null) {
					return;
				}
				var font = client.font;
				String text = font.plainSubstrByWidth(ConnectionDebug.status(), s.width - 8);
				g.text(font, text, 4, s.height - font.lineHeight - 4, CommonColors.GRAY);
			}));
	}
}
