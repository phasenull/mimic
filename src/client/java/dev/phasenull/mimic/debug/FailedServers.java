package dev.phasenull.mimic.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Servers whose last join ended in a failure Mimic can't get past yet, shown with a warning in the server
 * list (see ServerEntryMixin). A server is cleared once a join keeps the player in the world for a while.
 */
public final class FailedServers {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("failed_servers.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Disconnect reasons worth marking. */
	private static final List<String> MARKED_REASONS = List.of("Invalid player data");
	private static final long STABLE_MS = 20_000;

	public static final class Failure {
		public String reason;
		public String build;
		public long at;
	}

	private static Map<String, Failure> failures = load();
	private static long joinedAt;
	private static String joinedIp;

	private FailedServers() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				onDisconnect(ConnectionDebug.lastDisconnectReason());
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ServerData data = JoinSession.serverData();
			joinedIp = data == null ? null : data.ip;
			joinedAt = System.currentTimeMillis();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> joinedIp = null);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (joinedIp != null && client.player != null && System.currentTimeMillis() - joinedAt > STABLE_MS) {
				clear(joinedIp);
				joinedIp = null;
			}
		});
	}

	public static synchronized Failure get(String ip) {
		return failures.get(key(ip));
	}

	/** "Play.Example.net" and "play.example.net:25565" are the same server. */
	private static String key(String ip) {
		ServerAddress address = ServerAddress.parseString(ip);
		return (address.getHost() + ":" + address.getPort()).toLowerCase(Locale.ROOT);
	}

	/**
	 * Called as the connection closes, while the server being joined is still known (by the time the
	 * disconnect screen opens, a play-time disconnect has already cleared it).
	 */
	public static void disconnected(String reason) {
		onDisconnect(reason);
	}

	private static synchronized void onDisconnect(String reason) {
		ServerData data = JoinSession.serverData();
		String ip = data != null ? data.ip : JoinSession.server();
		if (ip == null || reason == null || MARKED_REASONS.stream().noneMatch(reason::contains)) {
			return;
		}
		Failure failure = new Failure();
		failure.reason = reason.lines().findFirst().orElse(reason);
		failure.build = MimicBuild.commit();
		failure.at = System.currentTimeMillis();
		failures.put(key(ip), failure);
		save();
	}

	private static synchronized void clear(String ip) {
		if (failures.remove(key(ip)) != null) {
			save();
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(failures));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save failed servers", e);
		}
	}

	private static Map<String, Failure> load() {
		try {
			if (Files.exists(FILE)) {
				Map<String, Failure> loaded = GSON.fromJson(Files.readString(FILE), new TypeToken<TreeMap<String, Failure>>() {}.getType());
				if (loaded != null) {
					return loaded;
				}
			}
		} catch (Exception e) {
			MimicClient.LOGGER.warn("Could not read failed servers", e);
		}
		return new TreeMap<>();
	}
}
