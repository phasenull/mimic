package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinStatus;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers server login queries the client doesn't understand, per channel, as configured in
 * config/mimic/login_answers.txt. Re-read on every connect. Known "install mod X (vN)" kicks are turned
 * into answer lines automatically, followed by one reconnect.
 */
public final class LoginQueryAnswers {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("login_answers.txt");
	private static final int HEX_LIMIT = 256;
	private static final List<String> DEFAULT = List.of(
		"# Answers for server login queries this client doesn't understand. Re-read on every connect.",
		"# One per line: <channel> = echo | empty | semver <x.y.z>",
		"#   echo   = send the server's query bytes back (passes many \"same version?\" checks)",
		"#   empty  = answer \"understood\" with no data",
		"#   semver = a version as int count + ints (Origins-style handshakes), e.g. semver 1.12.18",
		"# Unlisted channels stay unanswered (\"not understood\"), like vanilla.",
		"thematic:version_handshake = echo");

	/** Kick messages that name the version a login handshake wants. */
	private record KnownKick(Pattern message, String channel) {}

	private static final List<KnownKick> KNOWN_KICKS = List.of(
		new KnownKick(Pattern.compile("install the Origins mod \\(v ?([0-9][0-9.]*)\\)"), "origins:handshake"));

	private static final Set<Identifier> registered = new HashSet<>();
	private static volatile ServerData lastServer;
	private static volatile boolean retried;

	private LoginQueryAnswers() {}

	public static void registerReconnect() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				learnFromKick(client);
			}
		});
	}

	/** Called on every connect. One automatic retry per server; switching servers allows another. */
	public static synchronized void reload(ServerData server) {
		ServerData previous = lastServer;
		if (server == null || previous == null || !server.ip.equals(previous.ip)) {
			retried = false;
		}
		lastServer = server;
		registered.forEach(ClientLoginNetworking::unregisterGlobalReceiver);
		registered.clear();
		for (String line : readLines()) {
			int eq = line.indexOf('=');
			if (line.isBlank() || line.startsWith("#") || eq < 0) {
				continue;
			}
			Identifier id = Identifier.tryParse(line.substring(0, eq).trim());
			byte[] fixed = null;
			String mode = line.substring(eq + 1).trim();
			String lower = mode.toLowerCase(Locale.ROOT);
			if (lower.startsWith("semver ")) {
				fixed = semver(mode.substring(7).trim());
				mode = "semver";
			} else {
				mode = lower;
			}
			if (id == null || !(mode.equals("echo") || mode.equals("empty") || (mode.equals("semver") && fixed != null))) {
				MimicClient.LOGGER.warn("Ignoring login_answers.txt line: {}", line);
				continue;
			}
			String kind = mode;
			byte[] answerBytes = fixed;
			if (ClientLoginNetworking.registerGlobalReceiver(id, (client, handler, request, listeners) -> answer(id, kind, answerBytes, request))) {
				registered.add(id);
			}
		}
	}

	private static CompletableFuture<FriendlyByteBuf> answer(Identifier channel, String kind, byte[] fixed, FriendlyByteBuf request) {
		byte[] bytes = new byte[request.readableBytes()];
		request.readBytes(bytes);
		String hex = HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, HEX_LIMIT)) + (bytes.length > HEX_LIMIT ? "..." : "");
		ConnectionDebug.note("QUERY", channel + " (" + bytes.length + " bytes) " + hex);
		JoinStatus.info("[Login] Answering {} with {}", channel, kind);
		byte[] reply = switch (kind) {
			case "echo" -> bytes;
			case "semver" -> fixed;
			default -> new byte[0];
		};
		return CompletableFuture.completedFuture(new FriendlyByteBuf(Unpooled.wrappedBuffer(reply)));
	}

	/** "1.12.18" -> int 3, int 1, int 12, int 18 (big-endian, as FriendlyByteBuf.writeInt). */
	private static byte[] semver(String version) {
		String[] parts = version.split("\\.");
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(parts.length);
		try {
			for (String part : parts) {
				buf.writeInt(Integer.parseInt(part));
			}
		} catch (NumberFormatException e) {
			return null;
		}
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		return out;
	}

	private static void learnFromKick(Minecraft client) {
		String reason = ConnectionDebug.lastDisconnectReason();
		ServerData server = lastServer;
		if (reason == null || server == null || retried) {
			return;
		}
		for (KnownKick kick : KNOWN_KICKS) {
			Matcher m = kick.message().matcher(reason);
			if (m.find()) {
				String line = kick.channel() + " = semver " + m.group(1);
				if (readLines().stream().anyMatch(l -> l.trim().equals(line))) {
					return;
				}
				append(line);
				retried = true;
				JoinStatus.info("[Login] Learned '{}', reconnecting", line);
				client.execute(() -> ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
					ServerAddress.parseString(server.ip), server, false, null));
				return;
			}
		}
	}

	private static void append(String line) {
		try {
			readLines();
			Files.writeString(FILE, System.lineSeparator() + line + System.lineSeparator(), StandardOpenOption.APPEND);
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not update login_answers.txt", e);
		}
	}

	private static List<String> readLines() {
		try {
			if (!Files.exists(FILE)) {
				Files.createDirectories(FILE.getParent());
				Files.write(FILE, DEFAULT);
			}
			return Files.readAllLines(FILE);
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not read login_answers.txt", e);
			return List.of();
		}
	}
}
