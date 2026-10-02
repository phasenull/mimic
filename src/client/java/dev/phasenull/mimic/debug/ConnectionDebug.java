package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Logs every packet of the client connection until shortly after the play phase starts, to
 * config/mimic/connection.log (previous attempt kept as connection.prev.log), and keeps a live
 * status for the connecting-screen overlay.
 */
public final class ConnectionDebug {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("mimic");
	private static final Path LOG = DIR.resolve("connection.log");
	private static final Path PREV = DIR.resolve("connection.prev.log");
	private static final int PLAY_PACKETS_TO_LOG = 200;
	private static final Path BLOCK_FILE = DIR.resolve("block_outgoing.txt");
	private static volatile java.util.Set<String> blockedOutgoing = java.util.Set.of();

	private static BufferedWriter writer;
	private static long start;
	private static int playLogged;

	private static volatile boolean active;
	private static volatile String phase = "-";
	private static volatile int in;
	private static volatile int out;
	private static volatile String last = "";
	private static volatile long lastAt;
	private static volatile String lastDisconnectReason;
	private static volatile long worldBytes;
	private static volatile long modBytes;

	private ConnectionDebug() {}

	public static synchronized void begin(String address) {
		close();
		try {
			Files.createDirectories(DIR);
			if (Files.exists(LOG)) {
				Files.move(LOG, PREV, StandardCopyOption.REPLACE_EXISTING);
			}
			writer = Files.newBufferedWriter(LOG);
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not open connection log", e);
			writer = null;
		}
		start = System.currentTimeMillis();
		playLogged = 0;
		blockedOutgoing = readBlockList();
		in = 0;
		out = 0;
		worldBytes = 0;
		modBytes = 0;
		phase = "handshake";
		active = true;
		JoinStatus.clear();
		event("CONNECT", address);
	}

	public static void protocol(String direction, String protocol) {
		if (direction.equals("in")) {
			phase = protocol;
			JoinStatus.post("Phase: " + protocol);
		}
		event("PROTOCOL", direction + " -> " + protocol);
	}

	public static void received(Packet<?> packet) {
		in++;
		packet("IN ", packet);
	}

	public static void sent(Packet<?> packet) {
		out++;
		packet("OUT", packet);
	}

	/**
	 * Debug switch: packets listed in config/mimic/block_outgoing.txt (one packet or payload id per
	 * line, e.g. minecraft:chat_session_update or minecraft:register) are not sent. Read on each connect.
	 */
	public static boolean shouldBlock(Packet<?> packet) {
		java.util.Set<String> blocked = blockedOutgoing;
		if (blocked.isEmpty()) {
			return false;
		}
		String id = packet.type().id().toString();
		String payload = packet instanceof ServerboundCustomPayloadPacket p ? p.payload().type().id().toString() : null;
		if (blocked.contains(id) || (payload != null && blocked.contains(payload))) {
			write("BLK play " + name(packet));
			return true;
		}
		return false;
	}

	private static java.util.Set<String> readBlockList() {
		try {
			if (!Files.exists(BLOCK_FILE)) {
				return java.util.Set.of();
			}
			java.util.Set<String> ids = new java.util.HashSet<>();
			for (String line : Files.readAllLines(BLOCK_FILE)) {
				String id = line.trim();
				if (!id.isEmpty() && !id.startsWith("#")) {
					ids.add(id);
				}
			}
			if (!ids.isEmpty()) {
				JoinStatus.info("[Debug] Blocking outgoing: {}", String.join(", ", ids));
			}
			return ids;
		} catch (IOException e) {
			return java.util.Set.of();
		}
	}

	public static void disconnected(String reason) {
		lastDisconnectReason = reason;
		event("DISCONNECT", reason);
		active = false;
		synchronized (ConnectionDebug.class) {
			close();
		}
	}

	public static void error(Throwable t) {
		event("ERROR", t.toString());
	}

	private static void packet(String dir, Packet<?> packet) {
		String name = name(packet);
		last = dir.trim() + " " + name;
		lastAt = System.currentTimeMillis();
		if (phase.equals("play") && ++playLogged > PLAY_PACKETS_TO_LOG) {
			return;
		}
		write(dir + " " + phase + " " + name);
	}

	private static void event(String kind, String detail) {
		last = kind + " " + detail;
		lastAt = System.currentTimeMillis();
		write("--- " + kind + " " + detail);
	}

	private static String name(Packet<?> packet) {
		String id = packet.type().id().toString();
		if (packet instanceof ClientboundCustomPayloadPacket p) {
			return id + " [" + p.payload().type().id() + "]";
		}
		if (packet instanceof ServerboundCustomPayloadPacket p) {
			return id + " [" + p.payload().type().id() + "]";
		}
		return id;
	}

	private static synchronized void write(String line) {
		if (writer == null) {
			return;
		}
		try {
			writer.write(String.format("%8d ms  %s%n", System.currentTimeMillis() - start, line));
			writer.flush();
		} catch (IOException e) {
			writer = null;
		}
	}

	private static void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// Nothing useful to do if the log can't be closed.
			}
			writer = null;
		}
	}

	/** Counts a received packet's decompressed size; non-vanilla custom payloads count as mod data. Netty thread only. */
	public static void countBytes(Packet<?> packet, int bytes) {
		if (packet instanceof ClientboundCustomPayloadPacket p && !p.payload().type().id().getNamespace().equals("minecraft")) {
			modBytes += bytes;
		} else {
			worldBytes += bytes;
		}
	}

	/** e.g. "1.42 MB (world 1.10 MB / mods 324.5 KB)", or null when nothing was received yet. */
	public static String receivedSummary() {
		long world = worldBytes;
		long mods = modBytes;
		if (!active || world + mods == 0) {
			return null;
		}
		return size(world + mods) + " (world " + size(world) + " / mods " + size(mods) + ")";
	}

	private static String size(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
	}

	public static String lastDisconnectReason() {
		return lastDisconnectReason;
	}

	public static boolean active() {
		return active;
	}

	/** One-line status for the overlay. */
	public static String status() {
		long ago = (System.currentTimeMillis() - lastAt) / 100;
		return "Mimic: " + phase + " | in " + in + " / out " + out + " | last: " + last + " (" + ago / 10 + "." + ago % 10 + "s ago)";
	}
}
