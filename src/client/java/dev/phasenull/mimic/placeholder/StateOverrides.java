package dev.phasenull.mimic.placeholder;

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

/** Block-state counts set by hand on a block's page, per server (config/mimic/state_overrides.json). */
public final class StateOverrides {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("state_overrides.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Map<String, Integer>> overrides = load();

	private StateOverrides() {}

	/** The count set for {@code id} on the server being joined, or null. */
	public static synchronized Integer get(String id) {
		Map<String, Integer> server = overrides.get(JoinSession.server());
		return server == null ? null : server.get(id);
	}

	/** Sets (or with null, clears) the count for {@code id} on the current server. */
	public static synchronized void set(String id, Integer count) {
		if (count == null) {
			Map<String, Integer> server = overrides.get(JoinSession.server());
			if (server != null) {
				server.remove(id);
			}
		} else {
			overrides.computeIfAbsent(JoinSession.server(), k -> new TreeMap<>()).put(id, count);
		}
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(overrides));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save state overrides", e);
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
			MimicClient.LOGGER.warn("Could not read state overrides", e);
		}
		return new TreeMap<>();
	}
}
