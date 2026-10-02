package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.bypass.neoforge.NeoForgeBypass;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.util.CommonColors;

import java.util.List;

/** On the connecting screen: a fading feed of recent Mimic messages mid-screen and the packet status at the bottom. */
public final class ConnectionOverlay {
	private static final int[] FEED_ALPHA = {0xFF, 0xB0, 0x70, 0x40};
	private static final int FEED_COLOR = 0xFFFFFF;
	// Fallback when no Cancel button is found: just below vanilla's status text (height / 2 - 50).
	private static final int FEED_OFFSET_Y = -50 + 16;

	private ConnectionOverlay() {}

	/** Below the screen's Cancel button (the lowest visible vanilla button), so the feed never covers it. */
	private static int feedTop(Screen screen) {
		int bottom = -1;
		for (AbstractWidget w : Screens.getWidgets(screen)) {
			if (w.visible && w instanceof Button && w.getY() < screen.height - 60) {
				bottom = Math.max(bottom, w.getY() + w.getHeight());
			}
		}
		return bottom >= 0 ? bottom + 6 : screen.height / 2 + FEED_OFFSET_Y;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen || screen instanceof DisconnectedScreen) {
				ConnectionDebug.endIfActive("back on " + screen.getClass().getSimpleName());
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
				// Only on the vanilla connecting screen; servers may show their own screens (e.g. dialogs) meanwhile.
				if (!ConnectionDebug.active() || client.level != null || !(s instanceof ConnectScreen)) {
					return;
				}
				Font font = client.font;
				List<String> feed = JoinStatus.recent();
				int y = feedTop(s);
				for (int i = 0; i < feed.size() && i < FEED_ALPHA.length; i++) {
					String line = font.plainSubstrByWidth(feed.get(i), s.width - 16);
					g.centeredText(font, line, s.width / 2, y, (FEED_ALPHA[i] << 24) | FEED_COLOR);
					y += font.lineHeight + 2;
				}

				int learned = NeoForgeBypass.learnedChannels();
				String stats = JoinSession.kind() + " | " + JoinSession.summary() + (learned > 0 ? " | " + learned + " channels learned" : "");
				g.text(font, font.plainSubstrByWidth(stats, s.width - 8), 4, s.height - 2 * font.lineHeight - 6, CommonColors.LIGHT_GRAY);

				String status = font.plainSubstrByWidth(ConnectionDebug.status(), s.width - 8);
				g.text(font, status, 4, s.height - font.lineHeight - 4, CommonColors.GRAY);
			}));
	}
}
