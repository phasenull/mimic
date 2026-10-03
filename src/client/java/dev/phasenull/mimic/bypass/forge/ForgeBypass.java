package dev.phasenull.mimic.bypass.forge;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Legacy Forge (1.20.1, FML3) login handshake: marks the handshake host with "\0FML3\0" for servers known
 * to run Forge, answers the server's mod list with its own mods and channels (the server only validates
 * channels), and acknowledges every other handshake message. Forge servers are learned from their
 * "requires Forge" kick and reconnected once.
 */
public final class ForgeBypass {
	public static final String MARKER = "\0FML3\0";
	private static final Identifier WRAPPER = Identifier.fromNamespaceAndPath("fml", "loginwrapper");
	private static final String HANDSHAKE = "fml:handshake";
	private static final int MOD_LIST = 1;
	private static final int MOD_LIST_REPLY = 2;
	private static final int REGISTRY = 3;
	private static final int CONFIG = 4;
	private static final int CHANNEL_MISMATCH = 6;
	private static final int ACK = 99;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("forge_servers.txt");

	private static volatile String server = "";
	private static volatile ServerData serverData;
	private static volatile boolean retried;

	private ForgeBypass() {}

	public static void register() {
		ClientLoginNetworking.registerGlobalReceiver(WRAPPER, (client, handler, request, listeners) -> onWrapped(request));
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				learnFromKick(client);
			}
		});
	}

	public static void onConnecting(ServerAddress address, ServerData data) {
		String key = (address.getHost() + ":" + address.getPort()).toLowerCase(Locale.ROOT);
		if (!key.equals(server)) {
			retried = false;
		}
		server = key;
		serverData = data;
	}

	/** The host to put in the handshake packet: unchanged unless this server is known to run Forge. */
	public static String handshakeHost(String host) {
		if (!host.contains("\0") && knownServers().contains(server)) {
			JoinStatus.info("[Forge] Marking handshake as a Forge client (FML3)");
			return host + MARKER;
		}
		return host;
	}

	private static CompletableFuture<FriendlyByteBuf> onWrapped(FriendlyByteBuf request) {
		String inner = request.readUtf();
		byte[] body = new byte[request.readVarInt()];
		request.readBytes(body);
		FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
		int index = HANDSHAKE.equals(inner) && in.isReadable() ? in.readUnsignedByte() : -1;
		FriendlyByteBuf reply = new FriendlyByteBuf(Unpooled.buffer());
		switch (index) {
			case MOD_LIST -> replyToModList(in, reply);
			case REGISTRY -> {
				JoinStatus.info("[Forge] Registry {}", in.readUtf());
				reply.writeByte(ACK);
			}
			case CONFIG -> {
				JoinStatus.info("[Forge] Config {}", in.readUtf());
				reply.writeByte(ACK);
			}
			case CHANNEL_MISMATCH -> {
				Map<String, String> mismatched = new LinkedHashMap<>();
				int count = in.readVarInt();
				for (int i = 0; i < count; i++) {
					mismatched.put(in.readUtf(), in.readUtf());
				}
				JoinStatus.info("[Forge] Server reports mismatched channels: {}", mismatched);
				reply.writeByte(ACK);
			}
			default -> {
				ConnectionDebug.note("FORGE", "message " + index + " on " + inner + " (" + body.length + " bytes), acknowledging");
				reply.writeByte(ACK);
			}
		}
		return CompletableFuture.completedFuture(wrap(reply));
	}

	/** Claims exactly the server's mods and channels; the registry map is not checked by the server. */
	private static void replyToModList(FriendlyByteBuf in, FriendlyByteBuf reply) {
		List<String> mods = new ArrayList<>();
		int modCount = in.readVarInt();
		for (int i = 0; i < modCount; i++) {
			mods.add(in.readUtf(0x100));
		}
		Map<String, String> channels = new LinkedHashMap<>();
		int channelCount = in.readVarInt();
		for (int i = 0; i < channelCount; i++) {
			channels.put(in.readUtf(), in.readUtf(0x100));
		}
		JoinStatus.info("[Forge] Server mod list: {} mods, {} channels; mirroring it", mods.size(), channels.size());

		reply.writeByte(MOD_LIST_REPLY);
		reply.writeVarInt(mods.size());
		mods.forEach(m -> reply.writeUtf(m, 0x100));
		reply.writeVarInt(channels.size());
		channels.forEach((id, version) -> {
			reply.writeUtf(id);
			reply.writeUtf(version, 0x100);
		});
		reply.writeVarInt(0);
	}

	private static FriendlyByteBuf wrap(FriendlyByteBuf inner) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		out.writeUtf(HANDSHAKE);
		out.writeVarInt(inner.readableBytes());
		out.writeBytes(inner);
		return out;
	}

	private static void learnFromKick(Minecraft client) {
		String reason = ConnectionDebug.lastDisconnectReason();
		ServerData data = serverData;
		if (reason == null || data == null || retried || !reason.contains("require Forge")) {
			return;
		}
		if (!knownServers().contains(server)) {
			try {
				Files.createDirectories(FILE.getParent());
				Files.writeString(FILE, server + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				MimicClient.LOGGER.warn("Could not update forge_servers.txt", e);
				return;
			}
		}
		retried = true;
		JoinStatus.info("[Forge] {} runs Forge; reconnecting as a Forge client", server);
		client.execute(() -> ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
			ServerAddress.parseString(data.ip), data, false, null));
	}

	private static List<String> knownServers() {
		try {
			return Files.exists(FILE) ? Files.readAllLines(FILE).stream().map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of();
		} catch (IOException e) {
			return List.of();
		}
	}
}
