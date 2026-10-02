package dev.phasenull.mimic.debug;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * In-game packet log at the bottom left (toggled from the pause menu): incoming and outgoing packets with
 * a short description, plus Mimic's skip notes. Movement, keep-alives and similar constant traffic are left
 * out; repeats of the same line are folded into a counter.
 */
public final class PacketFeed {
	private static final int LINES = 18;
	private static final int DESCRIPTION_CHARS = 140;
	private static final int IN_COLOR = 0xFF77FF77;
	private static final int OUT_COLOR = 0xFF77BBFF;
	private static final int NOTE_COLOR = 0xFFFF7777;

	/** Constant background traffic (movement, keep-alives, time, particles, sounds) that would bury the rest. */
	private static final Set<String> IGNORED = Set.of(
		"move_entity_pos", "move_entity_pos_rot", "move_entity_rot", "rotate_head", "set_entity_motion",
		"entity_position_sync", "teleport_entity", "move_minecart_along_track", "set_entity_data",
		"move_player_pos", "move_player_rot", "move_player_pos_rot", "move_player_status_only", "player_input",
		"player_loaded", "client_tick_end", "keep_alive", "ping", "pong", "ping_request", "pong_response",
		"set_time", "level_particles", "sound", "sound_entity", "animate", "swing", "update_attributes",
		"chunk_batch_start", "chunk_batch_finished", "chunk_batch_received", "light_update", "block_destruction",
		"player_info_update", "set_health", "set_experience", "debug_sample", "ticking_state", "ticking_step");

	private record Line(String text, int color, int count, long at) {}

	private static final Deque<Line> LINES_SHOWN = new ArrayDeque<>();
	private static volatile boolean enabled;

	private PacketFeed() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("mimic", "packet_feed"), PacketFeed::extract);
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void toggle() {
		enabled = !enabled;
		synchronized (LINES_SHOWN) {
			LINES_SHOWN.clear();
		}
	}

	static void packet(boolean incoming, Packet<?> packet, String name) {
		if (!enabled || IGNORED.contains(packet.type().id().getPath())) {
			return;
		}
		add((incoming ? "IN  " : "OUT ") + name + describe(packet), incoming ? IN_COLOR : OUT_COLOR);
	}

	static void note(String kind, String detail) {
		if (enabled) {
			add(kind + " " + detail, NOTE_COLOR);
		}
	}

	private static String describe(Packet<?> packet) {
		if (packet instanceof BundlePacket<?> bundle) {
			int count = 0;
			for (Object ignored : bundle.subPackets()) {
				count++;
			}
			return " (" + count + " packets)";
		}
		String text = packet.toString();
		int brace = text.indexOf('[');
		if (brace < 0 || text.contains("@")) {
			return "";
		}
		String fields = text.substring(brace);
		return " " + (fields.length() > DESCRIPTION_CHARS ? fields.substring(0, DESCRIPTION_CHARS) + "..." : fields);
	}

	private static void add(String text, int color) {
		synchronized (LINES_SHOWN) {
			Line previous = LINES_SHOWN.peekLast();
			if (previous != null && previous.text().equals(text)) {
				LINES_SHOWN.pollLast();
				LINES_SHOWN.addLast(new Line(text, color, previous.count() + 1, System.currentTimeMillis()));
				return;
			}
			LINES_SHOWN.addLast(new Line(text, color, 1, System.currentTimeMillis()));
			while (LINES_SHOWN.size() > LINES) {
				LINES_SHOWN.pollFirst();
			}
		}
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!enabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		List<Line> lines;
		synchronized (LINES_SHOWN) {
			lines = new ArrayList<>(LINES_SHOWN);
		}
		int lineHeight = client.font.lineHeight + 1;
		int maxWidth = g.guiWidth() / 2;
		int y = g.guiHeight() - 50 - lines.size() * lineHeight;
		g.fill(2, y - 2, maxWidth + 6, y + lines.size() * lineHeight, 0x90000000);
		for (Line line : lines) {
			String text = (line.count() > 1 ? "x" + line.count() + " " : "") + line.text();
			g.text(client.font, client.font.plainSubstrByWidth(text, maxWidth), 4, y, line.color());
			y += lineHeight;
		}
	}
}
