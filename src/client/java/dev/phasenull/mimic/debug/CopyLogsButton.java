package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.io.IOException;
import java.nio.file.Path;

/**
 * "Copy logs" on the disconnect screen (bottom-left, with the build label) and the multiplayer screen
 * (top-left): writes a {@link JoinReport} and copies its path.
 */
public final class CopyLogsButton {
	private static final int W = 100;

	private CopyLogsButton() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof DisconnectedScreen) {
				Screens.getWidgets(screen).add(button(client, 4, height - 24));
				ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) ->
					g.text(client.font, MimicBuild.label(), W + 10, s.height - 18, CommonColors.GRAY));
			} else if (screen instanceof JoinMultiplayerScreen) {
				Screens.getWidgets(screen).add(button(client, 4, 4));
			}
		});
	}

	public static Button button(Minecraft client, int x, int y) {
		return Button.builder(Component.translatable("mimic.report.copy"), b -> {
			try {
				Path report = JoinReport.write(ConnectionDebug.lastDisconnectReason());
				client.keyboardHandler.setClipboard(report.toAbsolutePath().toString());
				b.setMessage(Component.translatable("mimic.report.copied"));
			} catch (IOException e) {
				MimicClient.LOGGER.warn("Could not write join report", e);
				b.setMessage(Component.translatable("mimic.report.failed"));
			}
		}).bounds(x, y, W, 20).build();
	}
}
