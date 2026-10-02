package dev.phasenull.mimic.debug;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.Font;
import net.minecraft.util.CommonColors;

import java.util.List;

/** While joining a server: a fading feed of recent Mimic messages mid-screen and the packet status at the bottom. */
public final class ConnectionOverlay {
	private static final int[] FEED_ALPHA = {0xFF, 0xB0, 0x70, 0x40};
	private static final int FEED_COLOR = 0xFFFFFF;
	// Below the vanilla "Joining world..." text and its Cancel button.
	private static final int FEED_OFFSET_Y = 76;

	private ConnectionOverlay() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
				if (!ConnectionDebug.active() || client.level != null) {
					return;
				}
				Font font = client.font;
				List<String> feed = JoinStatus.recent();
				int y = s.height / 2 + FEED_OFFSET_Y;
				for (int i = 0; i < feed.size() && i < FEED_ALPHA.length; i++) {
					String line = font.plainSubstrByWidth(feed.get(i), s.width - 16);
					g.centeredText(font, line, s.width / 2, y, (FEED_ALPHA[i] << 24) | FEED_COLOR);
					y += font.lineHeight + 2;
				}

				String status = font.plainSubstrByWidth(ConnectionDebug.status(), s.width - 8);
				g.text(font, status, 4, s.height - font.lineHeight - 4, CommonColors.GRAY);
			}));
	}
}
