package dev.phasenull.mimic.bypass.neoforge;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.bypass.DelayedReconnect;
import dev.phasenull.mimic.bypass.RawPayload;
import dev.phasenull.mimic.cache.ModCache;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a NeoForge (1.20.5+) server treat this client as a NeoForge client: answers the channel
 * negotiation, learns the server's required mod channels from its failure report (reconnecting
 * automatically), and acknowledges NeoForge's configuration checks.
 */
public final class NeoForgeBypass {
	private static final String NS = "neoforge";
	private static final int PROTOCOL_PLAY = 1;
	private static final int PROTOCOL_CONFIGURATION = 4;
	private static final int FLOW_SERVERBOUND = 0;
	private static final int FLOW_CLIENTBOUND = 1;
	private static final int MAX_AUTO_RECONNECTS = 10;

	// Clientbound
	private static final CustomPacketPayload.Type<RawPayload> QUERY = RawPayload.type(NS, "register");
	private static final CustomPacketPayload.Type<RawPayload> SETUP = RawPayload.type(NS, "network");
	private static final CustomPacketPayload.Type<RawPayload> SETUP_FAILED = RawPayload.type(NS, "modded_network_setup_failed");
	private static final CustomPacketPayload.Type<RawPayload> REGISTRY_SYNC_START = RawPayload.type(NS, "frozen_registry_sync_start");
	private static final CustomPacketPayload.Type<RawPayload> REGISTRY = RawPayload.type(NS, "frozen_registry");
	private static final CustomPacketPayload.Type<RawPayload> REGISTRY_SYNC_DONE = RawPayload.type(NS, "frozen_registry_sync_completed");
	private static final CustomPacketPayload.Type<RawPayload> DATA_MAPS = RawPayload.type(NS, "known_registry_data_maps");
	private static final CustomPacketPayload.Type<RawPayload> ENUMS = RawPayload.type(NS, "extensible_enum_data");
	private static final CustomPacketPayload.Type<RawPayload> FEATURE_FLAGS = RawPayload.type(NS, "feature_flags");
	// Serverbound
	private static final CustomPacketPayload.Type<RawPayload> DATA_MAPS_REPLY = RawPayload.type(NS, "known_registry_data_maps_reply");
	private static final CustomPacketPayload.Type<RawPayload> ENUMS_ACK = RawPayload.type(NS, "extensible_enum_ack");
	private static final CustomPacketPayload.Type<RawPayload> FEATURE_FLAGS_ACK = RawPayload.type(NS, "feature_flags_ack");

	/** NeoForge's own configuration channels (all version "1", optional on the server). */
	private static final List<Claim> BUILTIN = List.of(
		new Claim(REGISTRY_SYNC_START, FLOW_CLIENTBOUND),
		new Claim(REGISTRY, FLOW_CLIENTBOUND),
		new Claim(REGISTRY_SYNC_DONE, -1),
		new Claim(DATA_MAPS, FLOW_CLIENTBOUND),
		new Claim(DATA_MAPS_REPLY, FLOW_SERVERBOUND),
		new Claim(ENUMS, FLOW_CLIENTBOUND),
		new Claim(ENUMS_ACK, FLOW_SERVERBOUND),
		new Claim(FEATURE_FLAGS, FLOW_CLIENTBOUND),
		new Claim(FEATURE_FLAGS_ACK, FLOW_SERVERBOUND));

	private record Claim(CustomPacketPayload.Type<RawPayload> type, int flow) {}

	private static volatile String server = "unknown";
	private static volatile ServerData serverData;
	private static volatile boolean learnedThisAttempt;
	private static final Map<String, Integer> reconnects = new ConcurrentHashMap<>();

	private NeoForgeBypass() {}

	public static void register() {
		for (var type : List.of(QUERY, SETUP, SETUP_FAILED, REGISTRY_SYNC_START, REGISTRY, REGISTRY_SYNC_DONE, DATA_MAPS, ENUMS, FEATURE_FLAGS)) {
			PayloadTypeRegistry.clientboundConfiguration().register(type, RawPayload.codec(type));
		}
		for (var type : List.of(QUERY, REGISTRY_SYNC_DONE, DATA_MAPS_REPLY, ENUMS_ACK, FEATURE_FLAGS_ACK)) {
			PayloadTypeRegistry.serverboundConfiguration().register(type, RawPayload.codec(type));
		}

		ClientConfigurationNetworking.registerGlobalReceiver(QUERY, (p, ctx) -> onQuery(ctx));
		ClientConfigurationNetworking.registerGlobalReceiver(SETUP, (p, ctx) -> onSetupAccepted());
		ClientConfigurationNetworking.registerGlobalReceiver(SETUP_FAILED, (p, ctx) -> onSetupFailed(p.data()));
		ClientConfigurationNetworking.registerGlobalReceiver(REGISTRY_SYNC_START, (p, ctx) -> JoinStatus.info("[NeoForge] Registry sync started"));
		ClientConfigurationNetworking.registerGlobalReceiver(REGISTRY, (p, ctx) -> onRegistry(p.data()));
		ClientConfigurationNetworking.registerGlobalReceiver(REGISTRY_SYNC_DONE, (p, ctx) -> reply(ctx, REGISTRY_SYNC_DONE, new byte[0]));
		ClientConfigurationNetworking.registerGlobalReceiver(DATA_MAPS, (p, ctx) -> reply(ctx, DATA_MAPS_REPLY, dataMapsReply(p.data())));
		ClientConfigurationNetworking.registerGlobalReceiver(ENUMS, (p, ctx) -> reply(ctx, ENUMS_ACK, new byte[0]));
		ClientConfigurationNetworking.registerGlobalReceiver(FEATURE_FLAGS, (p, ctx) -> reply(ctx, FEATURE_FLAGS_ACK, new byte[0]));

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				maybeReconnect(client);
			}
		});
	}

	/** Called when a connection starts, before the server can be asked anything. */
	public static void onConnecting(ServerAddress address, ServerData data) {
		server = (address.getHost() + ":" + address.getPort()).toLowerCase(Locale.ROOT);
		serverData = data;
		learnedThisAttempt = false;
		NeoForgeChannelStore.adopt("unknown", server);
	}

	private static void onQuery(ClientConfigurationNetworking.Context ctx) {
		learnedThisAttempt = false;
		JoinSession.kind("NeoForge");

		List<NeoForgeChannelStore.Channel> learned = NeoForgeChannelStore.channels(server);
		JoinStatus.info("[NeoForge] Server {} is running NeoForge; claiming {} built-in + {} learned channels", server, BUILTIN.size(), learned.size());

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(2);
		buf.writeVarInt(PROTOCOL_CONFIGURATION);
		buf.writeVarInt(BUILTIN.size() + learned.size());
		for (Claim claim : BUILTIN) {
			writeComponent(buf, claim.type().id().toString(), "1", claim.flow());
		}
		learned.forEach(c -> writeComponent(buf, c.id, c.version, flowOrdinal(c.flow)));
		// Learned channels go in both protocols: failure reports don't say which one they belong to,
		// and an optional claim the server doesn't have is simply dropped.
		buf.writeVarInt(PROTOCOL_PLAY);
		buf.writeVarInt(learned.size());
		learned.forEach(c -> writeComponent(buf, c.id, c.version, flowOrdinal(c.flow)));
		reply(ctx, QUERY, bytes(buf));
	}

	private static void writeComponent(FriendlyByteBuf buf, String id, String version, int flow) {
		buf.writeUtf(id);
		buf.writeUtf(version);
		buf.writeBoolean(flow >= 0);
		if (flow >= 0) {
			buf.writeVarInt(flow);
		}
		buf.writeBoolean(true);
	}

	private static void onSetupAccepted() {
		reconnects.remove(server);
		learnedThisAttempt = false;
		JoinStatus.info("[NeoForge] Channel negotiation with {} passed", server);
	}

	private static void onSetupFailed(byte[] data) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		int count = buf.readVarInt();
		boolean changed = false;
		for (int i = 0; i < count; i++) {
			String channel = buf.readUtf();
			Text reason;
			try {
				reason = Text.of(NbtIo.readAnyTag(new DataInputStream(new ByteBufInputStream(buf)), NbtAccounter.unlimitedHeap()));
			} catch (IOException e) {
				MimicClient.LOGGER.warn("[NeoForge] Unreadable failure reason for {}", channel, e);
				continue;
			}
			while (reason.key().endsWith(".failure.mod") && reason.args().size() == 2 && reason.args().get(1) instanceof Text inner) {
				reason = inner;
			}
			JoinStatus.info("[NeoForge] {} -> {}", channel, reason);
			changed |= learn(channel, reason);
			ModCache.get().mod(namespace(channel), ModCache.UNKNOWN_VERSION);
		}
		ModCache.get().save();
		if (changed) {
			NeoForgeChannelStore.save();
			learnedThisAttempt = true;
		}
	}

	private static boolean learn(String channel, Text reason) {
		String key = reason.key();
		List<Object> args = reason.args();
		if (key.endsWith(".missing.server.client")) {
			return NeoForgeChannelStore.update(server, channel, c -> {});
		}
		if (key.endsWith(".missing.client.server")) {
			return NeoForgeChannelStore.remove(server, channel);
		}
		if (key.endsWith(".version.mismatch") && !args.isEmpty()) {
			String serverVersion = String.valueOf(args.getFirst());
			boolean changed = NeoForgeChannelStore.update(server, channel, c -> {
				c.version = serverVersion;
				c.versionConfirmed = true;
			});
			// Mods almost always use one version for all their channels; guess it for the rest.
			return NeoForgeChannelStore.guessNamespaceVersion(server, namespace(channel), serverVersion) | changed;
		}
		if (key.endsWith(".flow.client.missing") && !args.isEmpty()) {
			return NeoForgeChannelStore.update(server, channel, c -> c.flow = flowName(args.get(0)));
		}
		if (key.endsWith(".flow.client.mismatch") && !args.isEmpty()) {
			return NeoForgeChannelStore.update(server, channel, c -> c.flow = flowName(args.get(0)));
		}
		if (key.endsWith(".flow.server.missing")) {
			return NeoForgeChannelStore.update(server, channel, c -> c.flow = null);
		}
		if (key.endsWith(".flow.server.mismatch") && args.size() >= 2) {
			return NeoForgeChannelStore.update(server, channel, c -> c.flow = flowName(args.get(1)));
		}
		MimicClient.LOGGER.warn("[NeoForge] Don't know how to fix {} ({})", channel, key);
		return false;
	}

	private static void maybeReconnect(Minecraft client) {
		ServerData data = serverData;
		if (!learnedThisAttempt || data == null) {
			return;
		}
		int attempt = reconnects.merge(server, 1, Integer::sum);
		learnedThisAttempt = false;
		if (attempt > MAX_AUTO_RECONNECTS) {
			MimicClient.LOGGER.warn("[NeoForge] Gave up auto-reconnecting to {} after {} tries", server, MAX_AUTO_RECONNECTS);
			reconnects.remove(server);
			return;
		}
		DelayedReconnect.schedule(client, data, "NeoForge: learned new channels (try " + attempt + "/" + MAX_AUTO_RECONNECTS + ")");
	}

	private static void onRegistry(byte[] data) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		String registry = buf.readUtf();
		recordSnapshot(registry, buf);
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("neoforge_registries")
			.resolve(server.replaceAll("[^a-z0-9._-]", "_")).resolve(registry.replaceAll("[^a-z0-9._-]", "_") + ".bin");
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, data);
		} catch (IOException e) {
			MimicClient.LOGGER.warn("[NeoForge] Could not save registry snapshot {}", registry, e);
		}
		JoinStatus.info("[NeoForge] Received registry snapshot {} ({} bytes)", registry, data.length);
	}

	/** Snapshot body: map of network id -> entry id. Recorded per mod for the server mods screen. */
	private static void recordSnapshot(String registry, FriendlyByteBuf buf) {
		try {
			var local = BuiltInRegistries.REGISTRY.getValue(Identifier.parse(registry));
			int count = buf.readVarInt();
			for (int i = 0; i < count; i++) {
				buf.readVarInt();
				Identifier id = Identifier.parse(buf.readUtf());
				JoinSession.entry(registry, id.toString(), local != null && local.containsKey(id));
			}
		} catch (RuntimeException e) {
			MimicClient.LOGGER.debug("[NeoForge] Could not read snapshot entries of {}", registry, e);
		}
	}

	/** Claims to know every data map the server offers. */
	private static byte[] dataMapsReply(byte[] data) {
		FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		int registries = in.readVarInt();
		out.writeVarInt(registries);
		for (int i = 0; i < registries; i++) {
			out.writeUtf(in.readUtf());
			int maps = in.readVarInt();
			out.writeVarInt(maps);
			for (int j = 0; j < maps; j++) {
				out.writeUtf(in.readUtf());
				in.readBoolean();
			}
		}
		return bytes(out);
	}

	private static void reply(ClientConfigurationNetworking.Context ctx, CustomPacketPayload.Type<RawPayload> type, byte[] data) {
		// Raw packet: Fabric's send() would refuse channels the server never announced via minecraft:register.
		ctx.responseSender().sendPacket(new ServerboundCustomPayloadPacket(new RawPayload(type, data)));
	}

	private static byte[] bytes(FriendlyByteBuf buf) {
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		return out;
	}

	private static String namespace(String channel) {
		return channel.substring(0, Math.max(0, channel.indexOf(':')));
	}

	private static int flowOrdinal(String flow) {
		if (flow == null) {
			return -1;
		}
		return flow.equalsIgnoreCase("SERVERBOUND") ? FLOW_SERVERBOUND : FLOW_CLIENTBOUND;
	}

	private static String flowName(Object arg) {
		String s = String.valueOf(arg).toUpperCase(Locale.ROOT);
		return s.contains("SERVER") ? "SERVERBOUND" : "CLIENTBOUND";
	}

	/** Minimal view of a network-NBT text component: a translation key (or literal text) and its arguments. */
	record Text(String key, List<Object> args) {
		static Text of(Tag tag) {
			if (tag instanceof CompoundTag compound) {
				if (compound.size() == 1 && compound.contains("")) {
					return of(compound.get(""));
				}
				String key = compound.getString("translate").orElse(compound.getString("text").orElse(""));
				List<Object> args = new ArrayList<>();
				compound.getList("with").ifPresent(list -> list.forEach(t -> args.add(arg(t))));
				return new Text(key, args);
			}
			if (tag instanceof ListTag list && !list.isEmpty()) {
				return of(list.get(0));
			}
			return new Text(tag.asString().orElse(tag.toString()), List.of());
		}

		private static Object arg(Tag tag) {
			if (tag instanceof CompoundTag compound && compound.size() == 1 && compound.contains("")) {
				return arg(compound.get(""));
			}
			if (tag instanceof CompoundTag || tag instanceof ListTag) {
				return of(tag);
			}
			return tag.asString().orElse(tag.toString());
		}

		@Override
		public String toString() {
			return args.isEmpty() ? key : key + args;
		}
	}
}
