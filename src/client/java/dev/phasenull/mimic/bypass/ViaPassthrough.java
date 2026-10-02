package dev.phasenull.mimic.bypass;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registries whose ids the server already gave the client (Fabric registry sync). A Fabric server numbers
 * its blocks, items, sounds... with its mods mixed in, and the client adopts that numbering (with
 * placeholders for server-only entries). ViaVersion, translating from an older server version, would
 * renumber those ids as if they were vanilla ones, turning server-only blocks into random vanilla blocks,
 * so for these registries it passes ids through unchanged (see mixin.via).
 */
public final class ViaPassthrough {
	private static final Set<String> SYNCED = ConcurrentHashMap.newKeySet();

	private ViaPassthrough() {}

	public static void reset() {
		SYNCED.clear();
	}

	public static void synced(String registry) {
		SYNCED.add(registry);
	}

	public static boolean active(String registry) {
		return SYNCED.contains(registry);
	}
}
