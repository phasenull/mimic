package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.MimicClient;
import net.minecraft.util.CommonColors;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/** Adds "Copy logs" to the disconnect screen: writes a {@link JoinReport} and copies its path. */
public final class CopyLogsButton {
	private static final int W = 100;

	private CopyLogsButton() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof DisconnectedScreen)) {
				return;
			}
			Screens.getWidgets(screen).add(Button.builder(Component.translatable("mimic.report.copy"), b -> {
				try {
					Path report = JoinReport.write(ConnectionDebug.lastDisconnectReason());
					client.keyboardHandler.setClipboard(report.toAbsolutePath().toString());
					b.setMessage(Component.translatable("mimic.report.copied"));
				} catch (IOException e) {
					MimicClient.LOGGER.warn("Could not write join report", e);
					b.setMessage(Component.translatable("mimic.report.failed"));
				}
			}).bounds(4, height - 24, W, 20).build());
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) ->
				g.text(client.font, MimicBuild.label(), W + 10, s.height - 18, CommonColors.GRAY));
		});
	}
}
