package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Bundles everything useful for debugging a failed join into one file under config/mimic/reports. */
public final class JoinReport {
	private static final int LATEST_LOG_LINES = 600;
	private static final long RECENT_MILLIS = 10 * 60 * 1000L;

	private JoinReport() {}

	public static Path write(String disconnectReason) throws IOException {
		Path game = FabricLoader.getInstance().getGameDir();
		Path mimic = FabricLoader.getInstance().getConfigDir().resolve("mimic");
		Path out = mimic.resolve("reports").resolve("join-report-"
			+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
		Files.createDirectories(out.getParent());

		StringBuilder sb = new StringBuilder();
		sb.append("Mimic join report ").append(Instant.now()).append('\n');
		sb.append("Mimic build: ").append(MimicBuild.commit()).append('\n');
		sb.append("Disconnect reason: ").append(disconnectReason == null ? "(unknown)" : disconnectReason).append('\n');
		sb.append("Last status: ").append(ConnectionDebug.status()).append('\n');
		sb.append("Mods: ");
		FabricLoader.getInstance().getAllMods().stream()
			.filter(m -> m.getContainingMod().isEmpty())
			.map(ModContainer::getMetadata)
			.forEach(m -> sb.append(m.getId()).append(' ').append(m.getVersion().getFriendlyString()).append(", "));
		sb.append('\n');

		section(sb, "config/mimic/block_outgoing.txt", mimic.resolve("block_outgoing.txt"), 0);
		section(sb, "config/mimic/connection.log", mimic.resolve("connection.log"), 0);
		section(sb, "config/mimic/connection.prev.log", mimic.resolve("connection.prev.log"), 0);
		section(sb, "logs/latest.log (last " + LATEST_LOG_LINES + " lines)", game.resolve("logs").resolve("latest.log"), LATEST_LOG_LINES);
		for (Path recent : recentFiles(game.resolve("debug"))) {
			section(sb, "debug/" + recent.getFileName(), recent, 0);
		}
		for (Path recent : recentFiles(game.resolve("crash-reports"))) {
			section(sb, "crash-reports/" + recent.getFileName(), recent, 0);
		}

		Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
		MimicClient.LOGGER.info("Wrote join report to {}", out);
		return out;
	}

	private static void section(StringBuilder sb, String title, Path file, int lastLines) {
		sb.append("\n===== ").append(title).append(" =====\n");
		if (!Files.exists(file)) {
			sb.append("(missing)\n");
			return;
		}
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (lastLines > 0 && lines.size() > lastLines) {
				lines = lines.subList(lines.size() - lastLines, lines.size());
			}
			lines.forEach(l -> sb.append(l).append('\n'));
		} catch (IOException e) {
			sb.append("(unreadable: ").append(e.getMessage()).append(")\n");
		}
	}

	private static List<Path> recentFiles(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		long cutoff = System.currentTimeMillis() - RECENT_MILLIS;
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
				.filter(f -> f.toFile().lastModified() >= cutoff)
				.sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
				.limit(3)
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}
}
