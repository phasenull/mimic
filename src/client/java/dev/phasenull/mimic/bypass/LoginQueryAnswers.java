package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinStatus;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Answers server login queries the client doesn't understand, per channel, as configured in
 * config/mimic/login_answers.txt ("channel = echo" sends the server's own bytes back, "channel = empty"
 * answers with no data). Re-read on every connect.
 */
public final class LoginQueryAnswers {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("login_answers.txt");
	private static final int HEX_LIMIT = 256;
	private static final List<String> DEFAULT = List.of(
		"# Answers for server login queries this client doesn't understand. Re-read on every connect.",
		"# One per line: <channel> = echo | empty",
		"#   echo  = send the server's query bytes back (passes many \"same version?\" checks)",
		"#   empty = answer \"understood\" with no data",
		"# Unlisted channels stay unanswered (\"not understood\"), like vanilla.",
		"thematic:version_handshake = echo");

	private static final Set<Identifier> registered = new HashSet<>();

	private LoginQueryAnswers() {}

	public static synchronized void reload() {
		registered.forEach(ClientLoginNetworking::unregisterGlobalReceiver);
		registered.clear();
		for (String line : readLines()) {
			int eq = line.indexOf('=');
			if (line.isBlank() || line.startsWith("#") || eq < 0) {
				continue;
			}
			String channel = line.substring(0, eq).trim();
			String mode = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
			Identifier id = Identifier.tryParse(channel);
			if (id == null || !(mode.equals("echo") || mode.equals("empty"))) {
				MimicClient.LOGGER.warn("Ignoring login_answers.txt line: {}", line);
				continue;
			}
			boolean echo = mode.equals("echo");
			if (ClientLoginNetworking.registerGlobalReceiver(id, (client, handler, request, listeners) -> answer(id, echo, request))) {
				registered.add(id);
			}
		}
	}

	private static CompletableFuture<FriendlyByteBuf> answer(Identifier channel, boolean echo, FriendlyByteBuf request) {
		byte[] bytes = new byte[request.readableBytes()];
		request.readBytes(bytes);
		String hex = HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, HEX_LIMIT)) + (bytes.length > HEX_LIMIT ? "..." : "");
		ConnectionDebug.note("QUERY", channel + " (" + bytes.length + " bytes) " + hex);
		JoinStatus.info("[Login] Answering {} with {}", channel, echo ? "echo" : "empty");
		FriendlyByteBuf response = new FriendlyByteBuf(echo ? Unpooled.wrappedBuffer(bytes) : Unpooled.buffer(0));
		return CompletableFuture.completedFuture(response);
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
