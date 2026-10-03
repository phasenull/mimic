package dev.phasenull.mimic.bypass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.JoinSession;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verified block positions in a server's block-state numbering, per server (config/mimic/state_anchors.json):
 * block id -> the server's id of its first state. While building the table ({@link ServerStateTable}), an
 * anchored block starts exactly there, so a miscount in earlier (unverified) blocks stops at it instead of
 * shifting every later mod's blocks.
 */
public final class StateAnchors {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("state_anchors.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Map<String, Integer>> anchors = load();

	private StateAnchors() {}

	/** Anchors of the server being joined (block id -> first state id). */
	public static synchronized Map<String, Integer> current() {
		return Map.copyOf(anchors.getOrDefault(JoinSession.server(), Map.of()));
	}

	public static synchronized void set(String block, int firstState) {
		anchors.computeIfAbsent(JoinSession.server(), k -> new TreeMap<>()).put(block, firstState);
		save();
	}

	public static synchronized boolean nudge(String block, int by) {
		Map<String, Integer> server = anchors.get(JoinSession.server());
		if (server == null || !server.containsKey(block)) {
			return false;
		}
		server.put(block, server.get(block) + by);
		save();
		return true;
	}

	public static synchronized boolean remove(String block) {
		Map<String, Integer> server = anchors.get(JoinSession.server());
		if (server == null || server.remove(block) == null) {
			return false;
		}
		save();
		return true;
	}

	public static synchronized void clear() {
		anchors.remove(JoinSession.server());
		save();
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(anchors));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save state anchors", e);
		}
	}

	private static Map<String, Map<String, Integer>> load() {
		try {
			if (Files.exists(FILE)) {
				Map<String, Map<String, Integer>> loaded = GSON.fromJson(Files.readString(FILE),
					new TypeToken<TreeMap<String, TreeMap<String, Integer>>>() {}.getType());
				if (loaded != null) {
					return loaded;
				}
			}
		} catch (Exception e) {
			MimicClient.LOGGER.warn("Could not read state anchors", e);
		}
		return new TreeMap<>();
	}
}
