package dev.phasenull.mimic.privacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What this client tells servers about itself, and control over it: the client brand it reports, and
 * which outgoing packets or mod channels are never sent (see ConnectionDebug.shouldBlock). Records every
 * outgoing packet type and channel seen, with what each one reveals.
 */
public final class PrivacyGuard {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("privacy.json");

	/** Brand choices: the real one (Fabric's), plain vanilla, or a custom name. */
	public static final String BRAND_UNCHANGED = "";
	public static final String BRAND_VANILLA = "vanilla";

	public static final class Settings {
		public String brand = BRAND_UNCHANGED;
	}

	/** Packets the join can't work without; never offered for blocking. */
	public static final Set<String> REQUIRED = Set.of("minecraft:hello", "minecraft:key", "minecraft:login_acknowledged",
		"minecraft:custom_query_answer", "minecraft:finish_configuration", "minecraft:select_known_packs", "minecraft:keep_alive",
		"minecraft:pong", "minecraft:accept_teleportation", "minecraft:intention", "minecraft:status_request",
		"minecraft:ping_request", "minecraft:configuration_acknowledged", "minecraft:cookie_response",
		"minecraft:resource_pack", "minecraft:player_loaded", "minecraft:client_tick_end", "minecraft:chunk_batch_received");

	/** What known packets and channels tell the server. */
	private static final Map<String, String> REVEALS = Map.ofEntries(
		Map.entry("minecraft:brand", "Your client's name (Fabric reports \"fabric\"); servers use it to spot modded clients"),
		Map.entry("minecraft:client_information", "Language, view distance, chat settings, skin layers, main hand, whether you appear in server lists"),
		Map.entry("minecraft:chat_session_update", "Your chat signing key (needed on servers that enforce secure chat)"),
		Map.entry("minecraft:register", "The mod channels your client supports, which hints at installed mods"),
		Map.entry("minecraft:unregister", "Mod channels your client dropped"),
		Map.entry("c:version", "Fabric's common networking version (Fabric API is installed)"),
		Map.entry("c:register", "Fabric's common channel list (Fabric API's mods)"),
		Map.entry("fabric:accepted_attachments_v1", "Fabric API's data attachment support"),
		Map.entry("fabric:custom_ingredient_sync", "Fabric API's custom ingredient support"),
		Map.entry("minecraft:client_information_settings", "Client settings"),
		Map.entry("minecraft:chat_command", "Commands you type"),
		Map.entry("minecraft:chat", "Chat messages you send"),
		Map.entry("minecraft:debug_subscription_request", "Debug overlays you open"));

	private static final Map<String, AtomicInteger> SEEN = new ConcurrentHashMap<>();
	private static Settings settings = load();

	private PrivacyGuard() {}

	/** Called for every packet about to be sent. */
	public static void seen(Packet<?> packet) {
		String id = packet instanceof ServerboundCustomPayloadPacket p ? p.payload().type().id().toString() : packet.type().id().toString();
		SEEN.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
	}

	/** Outgoing ids seen since the game started, with counts. */
	public static Map<String, Integer> seenIds() {
		Map<String, Integer> out = new TreeMap<>();
		SEEN.forEach((id, count) -> out.put(id, count.get()));
		return out;
	}

	public static String reveals(String id) {
		String known = REVEALS.get(id);
		if (known != null) {
			return known;
		}
		return id.startsWith("minecraft:") ? "Game packet" : "Mod channel (" + id.substring(0, Math.max(0, id.indexOf(':'))) + ")";
	}

	/** The brand to report: Fabric's own unless the user picked another. */
	public static String brand(String real) {
		String brand = settings.brand;
		return brand == null || brand.isEmpty() ? real : brand;
	}

	public static String brandSetting() {
		return settings.brand == null ? BRAND_UNCHANGED : settings.brand;
	}

	public static void setBrand(String brand) {
		settings.brand = brand;
		save();
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(settings));
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not save privacy settings", e);
		}
	}

	private static Settings load() {
		try {
			if (Files.exists(FILE)) {
				Settings loaded = new Gson().fromJson(Files.readString(FILE), Settings.class);
				if (loaded != null) {
					return loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			MimicClient.LOGGER.warn("Could not read privacy settings", e);
		}
		return new Settings();
	}
}
