package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Dev only: VS Code writes recompiled classes to bin/ right before it hot-swaps them, so a change there is
 * shown as a toast. Mixin classes can't be hot-swapped, so changing one asks for a restart instead.
 */
public final class HotReloadNotifier {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);
	private static final long SETTLE_MILLIS = 400;
	// VS Code may still be finishing its own build right after launch; that isn't a hot reload.
	private static final long STARTUP_GRACE_MILLIS = 15_000;
	private static final long STARTED_AT = System.currentTimeMillis();

	private HotReloadNotifier() {}

	public static void start() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		Path project = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent();
		List<Path> roots = List.of(project.resolve("bin").resolve("client"), project.resolve("bin").resolve("main"));
		Thread thread = new Thread(() -> watch(roots), "Mimic hot reload watcher");
		thread.setDaemon(true);
		thread.start();
	}

	private static void watch(List<Path> roots) {
		try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
			for (Path root : roots) {
				if (Files.isDirectory(root)) {
					registerTree(watcher, root);
				}
			}
			while (true) {
				WatchKey key = watcher.take();
				Set<String> changed = new HashSet<>();
				collect(watcher, key, changed);
				// One save rewrites several files; gather them into one notification.
				WatchKey more;
				while ((more = watcher.poll(SETTLE_MILLIS, TimeUnit.MILLISECONDS)) != null) {
					collect(watcher, more, changed);
				}
				if (!changed.isEmpty() && System.currentTimeMillis() - STARTED_AT > STARTUP_GRACE_MILLIS) {
					notify(changed);
				}
			}
		} catch (InterruptedException | ClosedWatchServiceException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Hot reload watcher stopped", e);
		}
	}

	private static void collect(WatchService watcher, WatchKey key, Set<String> changed) {
		Path dir = (Path) key.watchable();
		for (WatchEvent<?> event : key.pollEvents()) {
			if (!(event.context() instanceof Path name)) {
				continue;
			}
			Path path = dir.resolve(name);
			if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
				try {
					registerTree(watcher, path);
				} catch (IOException ignored) {
					// A folder that vanished right away has nothing to watch.
				}
			} else if (name.toString().endsWith(".class") && !name.toString().contains("$")) {
				changed.add(path.toString().replace('\\', '/'));
			}
		}
		key.reset();
	}

	private static void notify(Set<String> changed) {
		boolean mixin = changed.stream().anyMatch(p -> p.contains("/mixin/"));
		String names = String.join(", ", changed.stream()
			.map(p -> p.substring(p.lastIndexOf('/') + 1).replace(".class", ""))
			.sorted().limit(3).toList()) + (changed.size() > 3 ? ", ..." : "");
		Component title = Component.translatable(mixin ? "mimic.hotreload.restart" : "mimic.hotreload.done", changed.size());
		Component body = Component.literal(names);
		MimicClient.LOGGER.info("{} ({})", title.getString(), names);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, title, body));
	}

	private static void registerTree(WatchService watcher, Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
