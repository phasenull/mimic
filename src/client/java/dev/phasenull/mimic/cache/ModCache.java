package dev.phasenull.mimic.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Persistent per-mod knowledge, keyed by "modid@version" so it carries across servers. */
public final class ModCache {
	public static final String UNKNOWN_VERSION = "unknown";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("cache.json");
	private static final ModCache INSTANCE = load();

	public static class ObjectEntry {
		public int slot = -1;
		public String name;
		public String texture;
	}

	public static class ModEntry {
		public Map<String, ObjectEntry> items = new TreeMap<>();
		public Map<String, ObjectEntry> blocks = new TreeMap<>();
		public Map<String, ObjectEntry> entities = new TreeMap<>();
	}

	public Map<String, ModEntry> mods = new TreeMap<>();

	public static ModCache get() {
		return INSTANCE;
	}

	public static String key(String modId, String version) {
		return modId + "@" + version;
	}

	public synchronized ModEntry mod(String modId, String version) {
		return mods.computeIfAbsent(key(modId, version), k -> new ModEntry());
	}

	public synchronized boolean seen(String modId, String version) {
		return mods.containsKey(key(modId, version));
	}

	public synchronized ObjectEntry item(String modId, String version, String path) {
		return mod(modId, version).items.computeIfAbsent(path, k -> new ObjectEntry());
	}

	public synchronized ObjectEntry block(String modId, String version, String path) {
		return mod(modId, version).blocks.computeIfAbsent(path, k -> new ObjectEntry());
	}

	public synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save mod cache", e);
		}
	}

	private static ModCache load() {
		try {
			if (Files.exists(FILE)) {
				ModCache loaded = GSON.fromJson(Files.readString(FILE), ModCache.class);
				if (loaded != null && loaded.mods != null) {
					return loaded;
				}
			}
		} catch (Exception e) {
			MimicClient.LOGGER.warn("Could not read mod cache, starting fresh", e);
		}
		return new ModCache();
	}
}
