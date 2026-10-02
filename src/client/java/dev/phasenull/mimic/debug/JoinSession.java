package dev.phasenull.mimic.debug;

import net.minecraft.client.multiplayer.ServerData;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What Mimic saw from the current (or last) server, grouped by mod namespace: network channels,
 * server-only registry entries, stand-ins and skipped packets. Shown in {@code ServerModsScreen}.
 * Reset when a new join starts.
 */
public final class JoinSession {
	public static final class Mod {
		public final Set<String> channels = new ConcurrentSkipListSet<>();
		/** Registry id -> entries of this mod that this client doesn't have. */
		public final Map<String, Set<String>> unknownEntries = new ConcurrentSkipListMap<>();
		/** Registry id -> entries of this mod this client knows. */
		public final Map<String, AtomicInteger> knownEntries = new ConcurrentSkipListMap<>();
		/** "registry: entry" replaced by a stand-in value. */
		public final Set<String> standIns = new ConcurrentSkipListSet<>();

		public int unknownCount() {
			return unknownEntries.values().stream().mapToInt(Set::size).sum();
		}

		public int knownCount() {
			return knownEntries.values().stream().mapToInt(AtomicInteger::get).sum();
		}
	}

	private static final Map<String, Mod> MODS = new ConcurrentSkipListMap<>();
	private static final Map<String, AtomicInteger> SKIPPED = new ConcurrentSkipListMap<>();
	private static volatile String server = "";
	private static volatile String kind = "Vanilla";
	private static volatile ServerData serverData;

	private JoinSession() {}

	public static void begin(String address, ServerData data) {
		MODS.clear();
		SKIPPED.clear();
		server = address;
		serverData = data;
		kind = "Vanilla";
	}

	public static void kind(String serverKind) {
		kind = serverKind;
	}

	public static void channel(String id) {
		if (!id.startsWith("minecraft:")) {
			mod(namespace(id)).channels.add(id);
		}
	}

	public static void entry(String registry, String id, boolean known) {
		if (known && namespace(id).equals("minecraft")) {
			return;
		}
		Mod mod = mod(namespace(id));
		if (known) {
			mod.knownEntries.computeIfAbsent(registry, k -> new AtomicInteger()).incrementAndGet();
		} else {
			mod.unknownEntries.computeIfAbsent(registry, k -> new ConcurrentSkipListSet<>()).add(id);
		}
	}

	public static void standIn(String registry, String id) {
		mod(namespace(id)).standIns.add(registry + ": " + id);
	}

	public static void skipped(String what) {
		SKIPPED.computeIfAbsent(what, k -> new AtomicInteger()).incrementAndGet();
	}

	public static Map<String, Mod> mods() {
		return MODS;
	}

	public static Map<String, AtomicInteger> skipped() {
		return SKIPPED;
	}

	public static String server() {
		return server;
	}

	public static String kind() {
		return kind;
	}

	public static ServerData serverData() {
		return serverData;
	}

	public static boolean hasData() {
		return !MODS.isEmpty() || !SKIPPED.isEmpty();
	}

	private static Mod mod(String namespace) {
		return MODS.computeIfAbsent(namespace, k -> new Mod());
	}

	private static String namespace(String id) {
		int colon = id.indexOf(':');
		return colon > 0 ? id.substring(0, colon) : "minecraft";
	}
}
