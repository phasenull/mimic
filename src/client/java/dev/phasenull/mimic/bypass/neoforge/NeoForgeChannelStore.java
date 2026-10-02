package dev.phasenull.mimic.bypass.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Mod network channels each NeoForge server requires, learned from its negotiation failures. */
public final class NeoForgeChannelStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("neoforge_channels.json");
	private static Map<String, List<Channel>> servers = load();

	public static final class Channel {
		public String id;
		public String version;
		/** "CLIENTBOUND", "SERVERBOUND" or null for bidirectional. */
		public String flow;
		/** True once the server itself reported this version. */
		public boolean versionConfirmed;

		Channel(String id, String version, String flow) {
			this.id = id;
			this.version = version;
			this.flow = flow;
		}
	}

	private NeoForgeChannelStore() {}

	public static synchronized List<Channel> channels(String server) {
		return List.copyOf(servers.getOrDefault(server, List.of()));
	}

	/** Applies a change to one channel, creating it with defaults first. Returns true if anything changed. */
	public static synchronized boolean update(String server, String id, java.util.function.Consumer<Channel> change) {
		List<Channel> list = servers.computeIfAbsent(server, k -> new ArrayList<>());
		Channel channel = list.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
		boolean created = channel == null;
		if (created) {
			String ns = id.substring(0, Math.max(0, id.indexOf(':'))) + ":";
			String version = list.stream().filter(c -> c.versionConfirmed && c.id.startsWith(ns)).map(c -> c.version).findFirst().orElse("1");
			channel = new Channel(id, version, null);
			list.add(channel);
		}
		String oldVersion = channel.version;
		String oldFlow = channel.flow;
		change.accept(channel);
		return created || !Objects.equals(oldVersion, channel.version) || !Objects.equals(oldFlow, channel.flow);
	}

	/** Sets {@code version} on every channel of {@code namespace} whose version is still a guess. */
	public static synchronized boolean guessNamespaceVersion(String server, String namespace, String version) {
		boolean changed = false;
		for (Channel c : servers.getOrDefault(server, List.of())) {
			if (!c.versionConfirmed && c.id.startsWith(namespace + ":") && !version.equals(c.version)) {
				c.version = version;
				changed = true;
			}
		}
		return changed;
	}

	/** Moves channels saved under {@code from} (e.g. "unknown" from older builds) to {@code to} if it has none. */
	public static synchronized void adopt(String from, String to) {
		if (!servers.containsKey(to) && servers.containsKey(from)) {
			servers.put(to, servers.remove(from));
			save();
		}
	}

	public static synchronized boolean remove(String server, String id) {
		List<Channel> list = servers.get(server);
		return list != null && list.removeIf(c -> c.id.equals(id));
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(servers));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save NeoForge channel cache", e);
		}
	}

	private static Map<String, List<Channel>> load() {
		try {
			if (Files.exists(FILE)) {
				Map<String, List<Channel>> loaded = GSON.fromJson(Files.readString(FILE), new TypeToken<TreeMap<String, List<Channel>>>() {}.getType());
				if (loaded != null) {
					loaded.replaceAll((k, v) -> new ArrayList<>(v));
					return loaded;
				}
			}
		} catch (Exception e) {
			MimicClient.LOGGER.warn("Could not read NeoForge channel cache, starting fresh", e);
		}
		return new TreeMap<>();
	}
}
