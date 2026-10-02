package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
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
 * announced with a sound, a toast and a banner at the top of the screen. Mixin classes can't be
 * hot-swapped, so changing one asks for a restart instead.
 */
public final class HotReloadNotifier {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);
	private static final long SETTLE_MILLIS = 400;
	// VS Code may still be finishing its own build right after launch; that isn't a hot reload.
	private static final long STARTUP_GRACE_MILLIS = 15_000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static final long BANNER_MILLIS = 3000;
	private static volatile String bannerText;
	private static volatile boolean bannerWarn;
	private static volatile long bannerUntil;

	private HotReloadNotifier() {}

	public static void start() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MimicClient.MOD_ID, "hot_reload_banner"),
			(g, delta) -> drawBanner(g, Minecraft.getInstance().getWindow().getGuiScaledWidth()));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> drawBanner(g, s.width)));
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
		mc.execute(() -> {
			bannerText = title.getString() + ": " + names;
			bannerWarn = mixin;
			bannerUntil = System.currentTimeMillis() + BANNER_MILLIS;
			SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, title, body);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(mixin ? SoundEvents.VILLAGER_NO : SoundEvents.PLAYER_LEVELUP, 1.0F));
		});
	}

	/** A banner at the top of the screen, drawn in game (HUD) and over any open screen. */
	private static void drawBanner(GuiGraphicsExtractor g, int screenWidth) {
		long left = bannerUntil - System.currentTimeMillis();
		String text = bannerText;
		if (left <= 0 || text == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int alpha = (int) (255 * Math.min(1.0, left / 500.0));
		String shown = mc.font.plainSubstrByWidth(text, screenWidth - 24);
		int w = mc.font.width(shown) + 12;
		int x = (screenWidth - w) / 2;
		g.fill(x, 4, x + w, 4 + mc.font.lineHeight + 8, (alpha * 3 / 4) << 24);
		int color = bannerWarn ? 0xFFAA00 : 0x55FF55;
		g.centeredText(mc.font, shown, screenWidth / 2, 8, (alpha << 24) | color);
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
