package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.debug.JoinSession;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Server -> mods -> per-mod details: what Mimic saw, mocked or skipped on the current/last server. */
public final class ServerModsScreen {
	private ServerModsScreen() {}

	public static Screen create(Screen parent) {
		List<TextListScreen.Row> rows = new ArrayList<>();
		Map<String, JoinSession.Mod> mods = JoinSession.mods();
		rows.add(TextListScreen.Row.header(JoinSession.kind() + " server, " + mods.size() + " mods seen"));

		Map<String, AtomicInteger> skipped = JoinSession.skipped();
		if (!skipped.isEmpty()) {
			int total = skipped.values().stream().mapToInt(AtomicInteger::get).sum();
			rows.add(TextListScreen.Row.link("Skipped packets (" + total + ")", skipped.size() + " packet types this client couldn't read",
				() -> skippedScreen(parent)));
		}

		mods.forEach((name, mod) -> rows.add(TextListScreen.Row.link(name, summary(mod), () -> modScreen(parent, name, mod))));
		if (mods.isEmpty()) {
			rows.add(TextListScreen.Row.text("Nothing recorded yet. Join a server first."));
		}
		return new TextListScreen(parent, Component.literal("Server mods: " + JoinSession.server()), rows);
	}

	private static String summary(JoinSession.Mod mod) {
		List<String> parts = new ArrayList<>();
		if (!mod.channels.isEmpty()) parts.add(mod.channels.size() + " channels");
		if (mod.knownCount() > 0) parts.add(mod.knownCount() + " shared entries");
		if (mod.unknownCount() > 0) parts.add(mod.unknownCount() + " server-only entries");
		if (!mod.standIns.isEmpty()) parts.add(mod.standIns.size() + " stand-ins");
		return parts.isEmpty() ? "seen" : String.join(" | ", parts);
	}

	private static Screen modScreen(Screen grandParent, String name, JoinSession.Mod mod) {
		Screen back = create(grandParent);
		List<TextListScreen.Row> rows = new ArrayList<>();
		if (!mod.standIns.isEmpty()) {
			rows.add(TextListScreen.Row.header("Stand-ins (" + mod.standIns.size() + "): replaced by a vanilla value"));
			mod.standIns.forEach(s -> rows.add(TextListScreen.Row.text(s)));
		}
		mod.unknownEntries.forEach((registry, ids) -> {
			rows.add(TextListScreen.Row.header("Server-only " + registry + " (" + ids.size() + ")"));
			ids.forEach(id -> rows.add(TextListScreen.Row.text(id)));
		});
		if (!mod.knownEntries.isEmpty()) {
			rows.add(TextListScreen.Row.header("Shared registries (this client has them too)"));
			mod.knownEntries.forEach((registry, count) -> rows.add(TextListScreen.Row.text(registry + ": " + count.get())));
		}
		if (!mod.channels.isEmpty()) {
			rows.add(TextListScreen.Row.header("Network channels (" + mod.channels.size() + ")"));
			addAll(rows, mod.channels);
		}
		return new TextListScreen(back, Component.literal(name), rows);
	}

	private static Screen skippedScreen(Screen grandParent) {
		List<TextListScreen.Row> rows = new ArrayList<>();
		rows.add(TextListScreen.Row.header("Packets skipped because this client couldn't read them"));
		JoinSession.skipped().forEach((what, count) -> rows.add(TextListScreen.Row.text(count.get() + "x  " + what)));
		return new TextListScreen(create(grandParent), Component.literal("Skipped packets"), rows);
	}

	private static void addAll(List<TextListScreen.Row> rows, Set<String> values) {
		values.forEach(v -> rows.add(TextListScreen.Row.text(v)));
	}
}
