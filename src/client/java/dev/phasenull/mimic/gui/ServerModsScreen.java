package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.assets.AssetPacks;
import dev.phasenull.mimic.assets.FilePicker;
import dev.phasenull.mimic.debug.JoinStatus;
import dev.phasenull.mimic.placeholder.Placeholders;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.nio.file.Path;

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

		mods.forEach((name, mod) -> rows.add(TextListScreen.Row.link(name, summary(name, mod), () -> modScreen(parent, name, mod))));
		if (mods.isEmpty()) {
			rows.add(TextListScreen.Row.text("Nothing recorded yet. Join a server first."));
		}
		return new TextListScreen(parent, Component.literal("Server mods: " + JoinSession.server()), rows);
	}

	private static List<String> parts(JoinSession.Mod mod) {
		List<String> parts = new ArrayList<>();
		if (!mod.channels.isEmpty()) parts.add(mod.channels.size() + " channels");
		if (mod.knownCount() > 0) parts.add(mod.knownCount() + " shared entries");
		if (mod.unknownCount() > 0) parts.add(mod.unknownCount() + " server-only entries");
		if (!mod.standIns.isEmpty()) parts.add(mod.standIns.size() + " stand-ins");
		return parts;
	}

	private static String summary(String name, JoinSession.Mod mod) {
		List<String> parts = parts(mod);
		int assets = AssetPacks.importedCounts(name).values().stream().mapToInt(Integer::intValue).sum();
		if (assets > 0) parts.add(assets + " imported assets");
		return parts.isEmpty() ? "seen" : String.join(" | ", parts);
	}

	private static final Set<String> PLACEHOLDER_REGISTRIES = Set.of("minecraft:block", "minecraft:item", "minecraft:entity_type");

	private static Screen modScreen(Screen grandParent, String name, JoinSession.Mod mod) {
		Screen back = create(grandParent);
		Screen[] self = new Screen[1];
		List<TextListScreen.Row> rows = new ArrayList<>();
		Map<String, Integer> counts = AssetPacks.importedCounts(name);
		rows.add(TextListScreen.Row.action("Import this mod's jar (assets only)...",
			!counts.isEmpty() ? "Imported, import again to replace it (or drag the jar onto this window)"
				: "Textures, models and block states from the jar. Its code is never loaded. Or drag the jar here.",
			() -> FilePicker.pick("Mod jar", "jar", jar -> importJar(jar, name, () -> modScreen(grandParent, name, mod)))));
		for (dev.phasenull.mimic.assets.JarScanner.Report report : AssetPacks.scanReports(name)) {
			rows.add(TextListScreen.Row.link("Safety scan of " + report.jar() + ": " + report.summary(),
				report.classes() + " classes read as data, nothing run", () -> scanScreen(self[0], report)));
		}
		rows.add(TextListScreen.Row.action("Scan a jar for malware signs (without importing)...",
			"Useful before installing a mod for real. Read as data only.",
			() -> FilePicker.pick("Mod jar", "jar", jar -> scanOnly(jar, self[0]))));
		if (!counts.isEmpty()) {
			int total = counts.values().stream().mapToInt(Integer::intValue).sum();
			rows.add(TextListScreen.Row.text("Imported assets: " + total + " files", counts.entrySet().stream()
				.map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "))));
		}
		if (!mod.standIns.isEmpty()) {
			rows.add(TextListScreen.Row.header("Stand-ins (" + mod.standIns.size() + "): replaced by a vanilla value"));
			mod.standIns.forEach(s -> rows.add(TextListScreen.Row.text(s)));
		}
		mod.unknownEntries.forEach((registry, ids) -> {
			boolean editable = PLACEHOLDER_REGISTRIES.contains(registry);
			rows.add(TextListScreen.Row.header("Server-only " + registry + " (" + ids.size() + ")" + (editable ? ": click one to set its texture" : "")));
			ids.forEach(id -> {
				Identifier identifier = Identifier.tryParse(id);
				if (editable && identifier != null) {
					String detail = AssetPacks.hasUserTexture(registry, identifier) ? "your texture" : null;
					rows.add(TextListScreen.Row.link(id, detail, () -> new PlaceholderEntryScreen(self[0], registry, identifier)));
				} else {
					rows.add(TextListScreen.Row.text(id));
				}
			});
		});
		if (!mod.knownEntries.isEmpty()) {
			rows.add(TextListScreen.Row.header("Shared registries (this client has them too)"));
			mod.knownEntries.forEach((registry, count) -> rows.add(TextListScreen.Row.text(registry + ": " + count.get())));
		}
		if (!mod.channels.isEmpty()) {
			rows.add(TextListScreen.Row.header("Network channels (" + mod.channels.size() + ")"));
			addAll(rows, mod.channels);
		}
		TextListScreen screen = new TextListScreen(back, Component.literal(name), rows);
		self[0] = screen.onFilesDropped(files -> files.stream()
			.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).findFirst()
			.ifPresent(jar -> importJar(jar, name, () -> modScreen(grandParent, name, mod))));
		return screen;
	}

	private static void importJar(Path jar, String forMod, java.util.function.Supplier<Screen> reopen) {
		Minecraft client = Minecraft.getInstance();
		try {
			AssetPacks.ImportResult result = AssetPacks.importJar(jar, forMod);
			Set<String> namespaces = result.namespaces();
			int blocks = 0;
			int items = 0;
			for (String namespace : namespaces) {
				int[] added = Placeholders.registerFromAssets(namespace);
				blocks += added[0];
				items += added[1];
			}
			JoinStatus.info("[Assets] Imported {}: {} files ({}), {} vanilla files left alone, block properties of {} blocks read, {} recipes, {} new block and {} new item placeholders",
				jar.getFileName(), result.files(), namespaces, result.vanillaSkipped(), result.scannedBlocks(), result.recipes(), blocks, items);
			if (result.scan() != null && result.scan().count(dev.phasenull.mimic.assets.JarScanner.Severity.HIGH) > 0) {
				// Importing is still safe (no code runs), but this jar shouldn't be installed as a mod.
				JoinStatus.info("[Scan] Warning: {} looks suspicious ({}). Don't install it as a mod; see its page for details.",
					jar.getFileName(), result.scan().summary());
			}
			client.reloadResourcePacks();
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not import {}", jar, e);
			JoinStatus.info("[Assets] Could not import {}: {}", jar.getFileName(), e.getMessage());
		}
		client.gui.setScreen(reopen.get());
	}

	private static void scanOnly(Path jar, Screen back) {
		Minecraft client = Minecraft.getInstance();
		try {
			client.gui.setScreen(scanScreen(back, dev.phasenull.mimic.assets.JarScanner.scan(jar)));
		} catch (IOException e) {
			JoinStatus.info("[Scan] Could not read {}: {}", jar.getFileName(), e.getMessage());
		}
	}

	private static Screen scanScreen(Screen back, dev.phasenull.mimic.assets.JarScanner.Report report) {
		List<TextListScreen.Row> rows = new ArrayList<>();
		rows.add(TextListScreen.Row.header(report.jar() + ": " + report.summary()));
		rows.add(TextListScreen.Row.text(report.classes() + " classes and " + report.nestedJars() + " nested jars read as data; nothing was run.",
			"A heuristic: a clean scan doesn't prove a jar is safe, and some findings are normal for mods."));
		for (dev.phasenull.mimic.assets.JarScanner.Finding finding : report.findings()) {
			rows.add(TextListScreen.Row.text(finding.severity() + ": " + finding.what(), String.join(", ", finding.where())));
		}
		return new TextListScreen(back, Component.literal("Safety scan"), rows);
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
