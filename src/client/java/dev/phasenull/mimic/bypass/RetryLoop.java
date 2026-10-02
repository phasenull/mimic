package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.debug.JoinStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** "Retry until connected": reconnects after each failed join, up to {@link #MAX_TRIES} times. */
public final class RetryLoop {
	public static final int MAX_TRIES = 10;

	private static volatile ServerData server;
	private static volatile int tries;

	private RetryLoop() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (server != null) {
				JoinStatus.info("[Retry] Connected after {} tries", tries);
				stop();
			}
		});
	}

	public static void start(Minecraft client, ServerData target) {
		server = target;
		tries = 0;
		onDisconnected(client);
	}

	public static void stop() {
		server = null;
		tries = 0;
	}

	public static boolean active() {
		return server != null;
	}

	public static int tries() {
		return tries;
	}

	/** Called when a disconnect screen opens. */
	public static void onDisconnected(Minecraft client) {
		ServerData target = server;
		if (target == null) {
			return;
		}
		if (tries >= MAX_TRIES) {
			JoinStatus.info("[Retry] Gave up after {} tries", MAX_TRIES);
			stop();
			return;
		}
		tries++;
		DelayedReconnect.schedule(client, target, "retry until connected (try " + tries + "/" + MAX_TRIES + ")");
	}
}
