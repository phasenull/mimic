package dev.phasenull.mimic.sniffer;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.cache.ModCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketSniffer {
	private static final Path LOG_FILE = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("packets.log");
	private static final Map<String, AtomicInteger> UNKNOWN_CHANNELS = new ConcurrentHashMap<>();

	private PacketSniffer() {}

	public static void onPayload(CustomPacketPayload payload) {
		Identifier id = payload.type().id();
		boolean unknown = payload instanceof DiscardedPayload;
		if (id.getNamespace().equals("minecraft") && !unknown) {
			return;
		}

		int count = UNKNOWN_CHANNELS.computeIfAbsent(id.toString(), k -> new AtomicInteger()).incrementAndGet();
		if (count == 1) {
			MimicClient.LOGGER.info("New {} channel: {}", unknown ? "unhandled" : "mod", id);
		}
		if (count == 1 && unknown) {
			ModCache cache = ModCache.get();
			if (!cache.seen(id.getNamespace(), ModCache.UNKNOWN_VERSION)) {
				cache.mod(id.getNamespace(), ModCache.UNKNOWN_VERSION);
				cache.save();
			}
		}
		write(Instant.now() + " " + (unknown ? "UNHANDLED " : "HANDLED   ") + id + " (#" + count + ")\n");
	}

	private static void write(String line) {
		try {
			Files.createDirectories(LOG_FILE.getParent());
			Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not write packet log", e);
		}
	}
}
