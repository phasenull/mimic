package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.bypass.RetryLoop;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.ConnectionOverlay;
import dev.phasenull.mimic.debug.CopyLogsButton;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import com.mojang.blaze3d.Blaze3D;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pause menu: "Server mods". Disconnect screen: "Server mods", "Retry until connected" and the last
 * attempt's messages (incl. reconnect countdowns). Connecting screen: a stall warning with "Copy logs"
 * when the server only sends keep-alives for a while (usually a mod's config sync waiting for a reply).
 */
public final class JoinScreenExtras {
	private static final int[] FEED_ALPHA = {0xFF, 0xB0, 0x70, 0x40};
	private static final int STALL_COLOR = 0xFFFFAA00;
	private static WeakReference<Screen> lastDisconnectScreen = new WeakReference<>(null);

	private JoinScreenExtras() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen && JoinSession.hasData()) {
				Screens.getWidgets(screen).add(serverModsButton(client, screen, 4, 4));
			} else if (screen instanceof DisconnectedScreen) {
				onDisconnectScreen(client, screen, width, height);
			} else if (screen instanceof ConnectScreen) {
				onConnectScreen(client, screen, height);
			}
		});
	}

	private static Button serverModsButton(Minecraft client, Screen screen, int x, int y) {
		return Button.builder(Component.translatable("mimic.server_mods.button"),
			b -> client.gui.setScreen(ServerModsScreen.create(screen))).bounds(x, y, 100, 20).build();
	}

	private static void onDisconnectScreen(Minecraft client, Screen screen, int width, int height) {
		List<net.minecraft.client.gui.components.AbstractWidget> widgets = Screens.getWidgets(screen);
		if (JoinSession.hasData()) {
			widgets.add(serverModsButton(client, screen, 4, height - 48));
		}
		Button retry = Button.builder(retryLabel(), b -> {
			if (RetryLoop.active()) {
				RetryLoop.stop();
			} else {
				RetryLoop.start(client, JoinSession.serverData());
			}
			b.setMessage(retryLabel());
		}).bounds(4, height - 72, 160, 20).build();
		retry.active = JoinSession.serverData() != null;
		widgets.add(retry);
		widgets.add(Button.builder(Component.translatable("mimic.report.open_folder"), b -> openReportsFolder())
			.bounds(width - 164, height - 24, 160, 20).build());

		// AFTER_INIT also fires on resize; only a newly opened disconnect screen counts as a failed try.
		if (lastDisconnectScreen.get() != screen) {
			lastDisconnectScreen = new WeakReference<>(screen);
			RetryLoop.onDisconnected(client);
			retry.setMessage(retryLabel());
		}

		ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
			drawFeed(client, g, s.width);
			drawStats(client, g, s);
		});
	}

	/** Below the screen's buttons: what Mimic knows about this server, and the retry state. */
	private static void drawStats(Minecraft client, GuiGraphicsExtractor g, Screen screen) {
		int y = ConnectionOverlay.feedTop(screen);
		String stats = client.font.plainSubstrByWidth(ConnectionOverlay.statsLine(), screen.width - 16);
		g.centeredText(client.font, stats, screen.width / 2, y, 0xFFAAAAAA);
		if (RetryLoop.active()) {
			String retry = "Retrying until connected: try " + RetryLoop.tries() + "/" + RetryLoop.MAX_TRIES;
			g.centeredText(client.font, retry, screen.width / 2, y + client.font.lineHeight + 2, 0xFFFFFF55);
		}
	}

	private static void openReportsFolder() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("reports");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			// Opening fails visibly enough if the folder can't exist.
		}
		Blaze3D.openPath(dir);
	}

	private static Component retryLabel() {
		return RetryLoop.active()
			? Component.translatable("mimic.retry.stop", RetryLoop.tries(), RetryLoop.MAX_TRIES)
			: Component.translatable("mimic.retry.start", RetryLoop.MAX_TRIES);
	}

	/** The last attempt's Mimic messages, newest first, fading out. */
	private static void drawFeed(Minecraft client, GuiGraphicsExtractor g, int width) {
		List<String> feed = JoinStatus.recent();
		int y = 6;
		for (int i = 0; i < feed.size() && i < FEED_ALPHA.length; i++) {
			String line = client.font.plainSubstrByWidth(feed.get(i), width - 16);
			g.centeredText(client.font, line, width / 2, y, (FEED_ALPHA[i] << 24) | 0xFFFFFF);
			y += client.font.lineHeight + 2;
		}
	}

	private static void onConnectScreen(Minecraft client, Screen screen, int height) {
		Button copy = CopyLogsButton.button(client, 4, height - 48);
		copy.visible = false;
		Screens.getWidgets(screen).add(copy);
		ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
			long stalled = ConnectionDebug.stalledSeconds();
			copy.visible = stalled > 0;
			if (stalled > 0) {
				String text = "Waiting " + stalled + "s: the server sent only keep-alives since " + ConnectionDebug.lastMeaningful()
					+ " (likely a mod sync waiting for a reply)";
				g.centeredText(client.font, client.font.plainSubstrByWidth(text, s.width - 16), s.width / 2, s.height - 34, STALL_COLOR);
			}
		});
	}
}
