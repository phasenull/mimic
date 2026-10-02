package dev.phasenull.mimic.texture;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User-supplied PNGs, stored under config/mimic/textures and referenced by file name from the cache. */
public final class TextureStore {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("textures");

	private TextureStore() {}

	public static Path directory() {
		return DIR;
	}

	/** Copies a PNG into the store and returns the stored file name. */
	public static String importPng(Path source, String modId, String path) throws IOException {
		if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".png")) {
			throw new IOException("Not a PNG file: " + source);
		}
		Files.createDirectories(DIR);
		String name = (modId + "__" + path).replaceAll("[^a-zA-Z0-9._-]", "_") + ".png";
		Files.copy(source, DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		return name;
	}

	public static Path resolve(String storedName) {
		return DIR.resolve(storedName);
	}
}
