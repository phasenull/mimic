package dev.phasenull.mimic.assets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Client resource packs for server-only content, always enabled on top of everything else:
 * <ul>
 *   <li>one folder per imported mod jar (config/mimic/packs/jar_*): the jar's assets folder, copied out so
 *   nothing from the jar but images, models, sounds and text is ever read, and its code never runs;</li>
 *   <li>config/mimic/packs/user: textures picked for single entries, above the jars.</li>
 * </ul>
 */
public final class AssetPacks {
	public static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("packs");
	public static final Path USER = ROOT.resolve("user");
	/** File types copied out of mod jars. */
	private static final Set<String> SAFE_EXTENSIONS = Set.of("png", "json", "mcmeta", "ogg", "txt", "lang");
	private static final String JAR_PREFIX = "jar_";

	private AssetPacks() {}

	/** Adds one pack per folder under {@link #ROOT}; the user pack is added last, so it wins. */
	public static RepositorySource source() {
		return consumer -> {
			for (Path dir : packFolders()) {
				String name = dir.getFileName().toString();
				boolean user = dir.equals(USER);
				Component title = Component.literal(user ? "Mimic: your textures" : "Mimic: " + name.substring(JAR_PREFIX.length()));
				PackLocationInfo info = new PackLocationInfo("mimic/" + name, title, PackSource.BUILT_IN, Optional.empty());
				Pack.Metadata metadata = new Pack.Metadata(Component.literal("Assets for server-only mod content"),
					PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
				consumer.accept(new Pack(info, new PathPackResources.PathResourcesSupplier(dir), metadata,
					new PackSelectionConfig(true, Pack.Position.TOP, false)));
			}
		};
	}

	private static List<Path> packFolders() {
		List<Path> folders = new ArrayList<>();
		try {
			Files.createDirectories(USER.resolve("assets"));
			try (Stream<Path> list = Files.list(ROOT)) {
				list.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith(JAR_PREFIX)).sorted().forEach(folders::add);
			}
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not list Mimic asset packs", e);
		}
		folders.add(USER);
		return folders;
	}

	/**
	 * Copies the assets out of a mod jar into its own pack folder (replacing an earlier import of the same
	 * jar name). Returns the asset namespaces it contained.
	 */
	public static Set<String> importJar(Path jar) throws IOException {
		String base = jar.getFileName().toString().replaceAll("(?i)\\.jar$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
		Path target = ROOT.resolve(JAR_PREFIX + base);
		deleteTree(target);
		Set<String> namespaces = new TreeSet<>();
		try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of())) {
			Path assets = zip.getPath("/assets");
			if (!Files.isDirectory(assets)) {
				throw new IOException("No assets folder in " + jar.getFileName());
			}
			try (Stream<Path> files = Files.walk(assets)) {
				for (Path file : (Iterable<Path>) files::iterator) {
					if (Files.isDirectory(file) || !SAFE_EXTENSIONS.contains(extension(file))) {
						continue;
					}
					Path relative = zip.getPath("/").relativize(file);
					if (relative.getNameCount() > 1) {
						namespaces.add(relative.getName(1).toString());
					}
					Path out = target.resolve(relative.toString());
					if (!out.normalize().startsWith(target)) {
						continue;
					}
					Files.createDirectories(out.getParent());
					Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		namespaces.remove("minecraft");
		for (String namespace : namespaces) {
			addItemDefinitions(target.resolve("assets").resolve(namespace), namespace);
		}
		MimicClient.LOGGER.info("[Assets] Imported {} (namespaces {}) into {}", jar.getFileName(), namespaces, target);
		return namespaces;
	}

	/**
	 * Uses {@code png} as the texture of a server-only block or item. Writes a plain model for it unless an
	 * imported jar already has one (then only the texture at the conventional path is replaced).
	 */
	public static void setTexture(String registry, Identifier id, Path png) throws IOException {
		boolean block = registry.equals("minecraft:block");
		String kind = block ? "block" : "item";
		Path ns = USER.resolve("assets").resolve(id.getNamespace());
		Path texture = ns.resolve("textures").resolve(kind).resolve(id.getPath() + ".png");
		Files.createDirectories(texture.getParent());
		Files.copy(png, texture, StandardCopyOption.REPLACE_EXISTING);

		String model = id.getNamespace() + ":" + kind + "/" + id.getPath();
		if (block && find(id.getNamespace(), "blockstates/" + id.getPath() + ".json").isEmpty()) {
			write(ns.resolve("blockstates").resolve(id.getPath() + ".json"), "{\"variants\":{\"\":{\"model\":\"" + model + "\"}}}");
			write(ns.resolve("models/block").resolve(id.getPath() + ".json"),
				"{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"" + model + "\"}}");
		}
		if (find(id.getNamespace(), "items/" + id.getPath() + ".json").isEmpty()) {
			String itemModel = block ? model : id.getNamespace() + ":item/" + id.getPath();
			write(ns.resolve("items").resolve(id.getPath() + ".json"),
				"{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + itemModel + "\"}}");
			if (!block) {
				write(ns.resolve("models/item").resolve(id.getPath() + ".json"),
					"{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + itemModel + "\"}}");
			}
		}
	}

	/** Removes a texture (and the plain model) set with {@link #setTexture}. */
	public static void clearTexture(String registry, Identifier id) throws IOException {
		String kind = registry.equals("minecraft:block") ? "block" : "item";
		Path ns = USER.resolve("assets").resolve(id.getNamespace());
		for (String file : List.of("textures/" + kind + "/" + id.getPath() + ".png", "blockstates/" + id.getPath() + ".json",
				"models/" + kind + "/" + id.getPath() + ".json", "items/" + id.getPath() + ".json")) {
			Files.deleteIfExists(ns.resolve(file));
		}
	}

	public static boolean hasUserTexture(String registry, Identifier id) {
		String kind = registry.equals("minecraft:block") ? "block" : "item";
		return Files.exists(USER.resolve("assets").resolve(id.getNamespace()).resolve("textures").resolve(kind).resolve(id.getPath() + ".png"));
	}

	/** An asset file from any Mimic pack, user pack first. */
	public static Optional<Path> find(String namespace, String path) {
		List<Path> folders = packFolders();
		for (int i = folders.size() - 1; i >= 0; i--) {
			Path file = folders.get(i).resolve("assets").resolve(namespace).resolve(path);
			if (Files.isRegularFile(file)) {
				return Optional.of(file);
			}
		}
		return Optional.empty();
	}

	public static Optional<JsonObject> readJson(String namespace, String path) {
		return find(namespace, path).flatMap(file -> {
			try {
				return Optional.of(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
			} catch (IOException | RuntimeException e) {
				MimicClient.LOGGER.debug("[Assets] Unreadable {}", file, e);
				return Optional.empty();
			}
		});
	}

	/**
	 * Jars made before item model definitions existed (Minecraft 1.21.4) only have models/item/*.json; the
	 * game now needs items/*.json to point at them.
	 */
	private static void addItemDefinitions(Path namespaceDir, String namespace) throws IOException {
		Path models = namespaceDir.resolve("models").resolve("item");
		if (!Files.isDirectory(models)) {
			return;
		}
		try (Stream<Path> files = Files.walk(models)) {
			for (Path model : (Iterable<Path>) files::iterator) {
				if (!model.getFileName().toString().endsWith(".json")) {
					continue;
				}
				String path = models.relativize(model).toString().replace('\\', '/').replaceAll("\\.json$", "");
				Path definition = namespaceDir.resolve("items").resolve(path + ".json");
				if (!Files.exists(definition)) {
					write(definition, "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":item/" + path + "\"}}");
				}
			}
		}
	}

	/** Imported asset files of one namespace by kind (textures, models, blockstates...), over all jars. */
	public static Map<String, Integer> importedCounts(String namespace) {
		Map<String, Integer> counts = new java.util.TreeMap<>();
		for (Path dir : packFolders()) {
			if (dir.equals(USER)) {
				continue;
			}
			Path ns = dir.resolve("assets").resolve(namespace);
			if (!Files.isDirectory(ns)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(ns)) {
				files.filter(Files::isRegularFile).forEach(f -> counts.merge(ns.relativize(f).getName(0).toString(), 1, Integer::sum));
			} catch (IOException ignored) {
				// Unreadable folder: counted as nothing.
			}
		}
		return counts;
	}

	/**
	 * Paths of the blocks (blockstates/*.json) or items (items/*.json, or models/item/*.json in older jars)
	 * a namespace's imported assets describe.
	 */
	public static Set<String> describedIds(String namespace, boolean blocks) {
		Set<String> paths = new TreeSet<>();
		for (Path dir : packFolders()) {
			if (dir.equals(USER)) {
				continue;
			}
			Path ns = dir.resolve("assets").resolve(namespace);
			for (Path folder : blocks ? List.of(ns.resolve("blockstates")) : List.of(ns.resolve("items"), ns.resolve("models").resolve("item"))) {
				if (!Files.isDirectory(folder)) {
					continue;
				}
				try (Stream<Path> files = Files.walk(folder)) {
					files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> paths.add(
						folder.relativize(f).toString().replace(java.io.File.separatorChar, '/').replaceAll("\\.json$", "")));
				} catch (IOException ignored) {
					// Unreadable folder: nothing described.
				}
			}
		}
		return paths;
	}

	/** Namespaces with assets in any imported jar. */
	public static Set<String> importedNamespaces() {
		Set<String> namespaces = new TreeSet<>();
		for (Path dir : packFolders()) {
			if (dir.equals(USER)) {
				continue;
			}
			try (Stream<Path> list = Files.list(dir.resolve("assets"))) {
				list.forEach(p -> namespaces.add(p.getFileName().toString()));
			} catch (IOException ignored) {
				// No assets folder.
			}
		}
		return namespaces;
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private static String extension(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.delete(p);
			}
		}
	}
}
