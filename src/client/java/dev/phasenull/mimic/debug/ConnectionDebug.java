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

	private static BufferedWriter writer;
	private static long start;
	private static int playLogged;

	private static volatile boolean active;
	private static volatile String phase = "-";
	private static volatile int in;
	private static volatile int out;
	private static volatile String last = "";
	private static volatile long lastAt;

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
		in = 0;
		out = 0;
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

	public static void disconnected(String reason) {
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

	public static boolean active() {
		return active;
	}

	/** One-line status for the overlay. */
	public static String status() {
		long ago = (System.currentTimeMillis() - lastAt) / 100;
		return "Mimic: " + phase + " | in " + in + " / out " + out + " | last: " + last + " (" + ago / 10 + "." + ago % 10 + "s ago)";
	}
}
