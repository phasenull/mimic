package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic reconnects wait a few seconds first: proxies rate-limit logins (Velocity rejects a second
 * login within 3 s by default), which showed up as an immediate "Connection reset". Only one reconnect
 * is pending at a time, whichever feature asked first.
 */
public final class DelayedReconnect {
	private static final int DELAY_SECONDS = 4;
	private static final AtomicBoolean PENDING = new AtomicBoolean();

	private DelayedReconnect() {}

	/** @param why shown in the countdown, e.g. "learned 12 channels (try 2/10)" */
	public static void schedule(Minecraft client, ServerData server, String why) {
		if (server == null || !PENDING.compareAndSet(false, true)) {
			return;
		}
		JoinStatus.info("[Reconnect] {}", why);
		Thread thread = new Thread(() -> {
			try {
				for (int left = DELAY_SECONDS; left > 0; left--) {
					JoinStatus.post("Reconnecting to " + server.ip + " in " + left + "s");
					Thread.sleep(1000);
				}
			} catch (InterruptedException e) {
				PENDING.set(false);
				return;
			}
			client.execute(() -> {
				PENDING.set(false);
				// The user may have left the disconnect screen meanwhile; then don't pull them back.
				if (client.gui.screen() instanceof DisconnectedScreen) {
					ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
						ServerAddress.parseString(server.ip), server, false, null);
				} else {
					JoinStatus.post("Reconnect cancelled");
				}
			});
		}, "Mimic delayed reconnect");
		thread.setDaemon(true);
		thread.start();
	}

	public static boolean pending() {
		return PENDING.get();
	}
}
