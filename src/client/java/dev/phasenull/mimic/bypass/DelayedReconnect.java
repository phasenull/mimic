package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Automatic reconnects wait a few seconds first: proxies rate-limit logins (Velocity rejects a second
 * login within 3 s by default), which showed up as an immediate "Connection reset".
 */
public final class DelayedReconnect {
	private static final int DELAY_SECONDS = 4;

	private DelayedReconnect() {}

	public static void schedule(Minecraft client, ServerData server) {
		Thread thread = new Thread(() -> {
			try {
				for (int left = DELAY_SECONDS; left > 0; left--) {
					JoinStatus.post("Reconnecting in " + left + "s");
					Thread.sleep(1000);
				}
			} catch (InterruptedException e) {
				return;
			}
			client.execute(() -> {
				// The user may have left the disconnect screen meanwhile; then don't pull them back.
				if (client.gui.screen() instanceof DisconnectedScreen) {
					ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
						ServerAddress.parseString(server.ip), server, false, null);
				}
			});
		}, "Mimic delayed reconnect");
		thread.setDaemon(true);
		thread.start();
	}
}
