package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinStatus;

import java.util.ArrayDeque;

/**
 * Proxies move a player between backends by reconfiguring the client. When a backend keeps rejecting this
 * client during configuration, the proxy sends it back to the fallback, which forwards it again: the client
 * flickers between "Reconfiguring..." and the world forever. Past {@link #MAX_RECONFIGS} reconfigurations
 * within {@link #WINDOW_MS}, Mimic disconnects instead, so the attempt ends with a reason and a report.
 */
public final class ReconfigLoopGuard {
	public static final int MAX_RECONFIGS = 5;
	public static final long WINDOW_MS = 20_000;

	private static final ArrayDeque<Long> RECENT = new ArrayDeque<>();

	private ReconfigLoopGuard() {}

	public static synchronized void reset() {
		RECENT.clear();
	}

	/** Called when the server starts a reconfiguration; true when it's looping and the client should leave. */
	public static synchronized boolean onReconfigure() {
		long now = System.currentTimeMillis();
		RECENT.addLast(now);
		while (!RECENT.isEmpty() && now - RECENT.peekFirst() > WINDOW_MS) {
			RECENT.removeFirst();
		}
		ConnectionDebug.note("RECONFIG", RECENT.size() + " in the last " + WINDOW_MS / 1000 + "s");
		if (RECENT.size() < MAX_RECONFIGS) {
			JoinStatus.info("[Proxy] Server moved this client ({}/{} within {}s)", RECENT.size(), MAX_RECONFIGS, WINDOW_MS / 1000);
			return false;
		}
		RECENT.clear();
		return true;
	}

	public static String reason() {
		return "Mimic: the server reconfigured this client " + MAX_RECONFIGS + " times within " + WINDOW_MS / 1000
			+ "s. A proxy is probably forwarding you to a backend that rejects this client, then sending you back."
			+ " Check chat or the report for the backend's message.";
	}
}
