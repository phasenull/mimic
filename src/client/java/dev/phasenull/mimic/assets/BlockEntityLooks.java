package dev.phasenull.mimic.assets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mod blocks drawn by their own block-entity renderer (lootr's chests, shulker boxes, pots) have block
 * models with nothing in them, so without the mod they'd be invisible or missing. For blocks whose names
 * match a vanilla block-entity block, this points their block-state file at a static look-alike model
 * (Mimic's chest, shulker box or pot model), using the jar's texture when it has one, else vanilla's, and
 * adds those textures to the block atlas. Runs on the copied files of an imported jar; idempotent.
 */
public final class BlockEntityLooks {
	private enum Kind { CHEST, SHULKER, POT }

	private BlockEntityLooks() {}

	public static void apply(Path packDir) {
		Path assets = packDir.resolve("assets");
		if (!Files.isDirectory(assets)) {
			return;
		}
		Set<String> sprites = new LinkedHashSet<>();
		int changed = 0;
		try (Stream<Path> namespaces = Files.list(assets)) {
			for (Path ns : (Iterable<Path>) namespaces::iterator) {
				Path states = ns.resolve("blockstates");
				if (!Files.isDirectory(states)) {
					continue;
				}
				try (Stream<Path> files = Files.list(states)) {
					for (Path file : (Iterable<Path>) files::iterator) {
						if (file.toString().endsWith(".json") && apply(packDir, ns, file, sprites)) {
							changed++;
						}
					}
				}
			}
		} catch (IOException e) {
			MimicClient.LOGGER.warn("[Assets] Could not check block-entity blocks in {}", packDir, e);
		}
		if (!sprites.isEmpty()) {
			addToAtlas(packDir, sprites);
		}
		if (changed > 0) {
			MimicClient.LOGGER.info("[Assets] {}: {} block-entity blocks given a look-alike model", packDir.getFileName(), changed);
		}
	}

	private static Kind kind(String name) {
		if (name.contains("shulker")) {
			return Kind.SHULKER;
		}
		if (name.contains("chest") && !name.contains("chestplate")) {
			return Kind.CHEST;
		}
		if (name.endsWith("pot") && !name.contains("flower")) {
			return Kind.POT;
		}
		return null;
	}

	private static boolean apply(Path packDir, Path ns, Path file, Set<String> sprites) throws IOException {
		String name = file.getFileName().toString().replaceAll("\\.json$", "");
		Kind kind = kind(name);
		if (kind == null) {
			return false;
		}
		JsonObject definition;
		try {
			definition = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		} catch (RuntimeException e) {
			return false;
		}
		if (!definition.has("variants") || !definition.get("variants").isJsonObject()) {
			return false;
		}
		for (JsonElement variant : definition.getAsJsonObject("variants").asMap().values()) {
			JsonObject v = variant.isJsonArray() ? variant.getAsJsonArray().get(0).getAsJsonObject() : variant.getAsJsonObject();
			if (!v.has("model") || !emptyModel(packDir, v.get("model").getAsString())) {
				return false;
			}
		}
		String namespace = ns.getFileName().toString();
		JsonObject model = new JsonObject();
		JsonObject textures = new JsonObject();
		switch (kind) {
			case CHEST -> {
				String texture = texture(ns, namespace, "entity/chest/" + chestVariant(name), "minecraft:entity/chest/" + chestVariant(name));
				model.addProperty("parent", "mimic:block/chest_like");
				textures.addProperty("chest", texture);
				sprites.add(texture);
			}
			case SHULKER -> {
				String texture = texture(ns, namespace, "entity/shulker_box/normal", "minecraft:entity/shulker/shulker");
				model.addProperty("parent", "mimic:block/shulker_like");
				textures.addProperty("shulker", texture);
				sprites.add(texture);
			}
			case POT -> {
				model.addProperty("parent", "mimic:block/pot_like");
				textures.addProperty("side", "minecraft:entity/decorated_pot/decorated_pot_side");
				textures.addProperty("base", "minecraft:entity/decorated_pot/decorated_pot_base");
				sprites.add("minecraft:entity/decorated_pot/decorated_pot_side");
				sprites.add("minecraft:entity/decorated_pot/decorated_pot_base");
			}
		}
		model.add("textures", textures);
		String modelId = namespace + ":block/mimic_look_" + name;
		Path modelFile = ns.resolve("models/block/mimic_look_" + name + ".json");
		Files.createDirectories(modelFile.getParent());
		Files.writeString(modelFile, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(model));

		// One variant per facing (the model faces north); other properties don't change the look.
		JsonObject variants = new JsonObject();
		String[][] facings = kind == Kind.SHULKER
			? new String[][] {{"up", "0", "0"}, {"down", "180", "0"}, {"north", "90", "0"}, {"south", "90", "180"}, {"west", "90", "270"}, {"east", "90", "90"}}
			: new String[][] {{"north", "0", "0"}, {"east", "0", "90"}, {"south", "0", "180"}, {"west", "0", "270"}};
		if (hasFacing(packDir, namespace + ":" + name)) {
			for (String[] f : facings) {
				JsonObject v = new JsonObject();
				v.addProperty("model", modelId);
				if (!f[1].equals("0")) {
					v.addProperty("x", Integer.parseInt(f[1]));
				}
				if (!f[2].equals("0")) {
					v.addProperty("y", Integer.parseInt(f[2]));
				}
				variants.add("facing=" + f[0], v);
			}
		} else {
			JsonObject v = new JsonObject();
			v.addProperty("model", modelId);
			variants.add("", v);
		}
		JsonObject out = new JsonObject();
		out.add("variants", variants);
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out));
		return true;
	}

	/** chest -> normal, trapped_chest -> trapped, oxidized_copper_chest -> copper_oxidized... */
	private static String chestVariant(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		for (String stage : new String[] {"oxidized", "weathered", "exposed"}) {
			if (n.contains(stage)) {
				return "copper_" + stage;
			}
		}
		if (n.contains("copper")) {
			return "copper";
		}
		if (n.contains("trapped")) {
			return "trapped";
		}
		if (n.contains("ender")) {
			return "ender";
		}
		return "normal";
	}

	/** The jar's own texture at this path if it has one, else the vanilla fallback. */
	private static String texture(Path ns, String namespace, String path, String fallback) {
		return Files.exists(ns.resolve("textures/" + path + ".png")) ? namespace + ":" + path : fallback;
	}

	/** True for a model with no elements of its own or from its parents in this pack (block-entity drawn). */
	private static boolean emptyModel(Path packDir, String model) {
		String current = model;
		for (int depth = 0; depth < 8 && current != null; depth++) {
			if (current.contains("builtin/")) {
				return true;
			}
			String ns = current.contains(":") ? current.substring(0, current.indexOf(':')) : "minecraft";
			String path = current.substring(current.indexOf(':') + 1);
			Path file = packDir.resolve("assets/" + ns + "/models/" + path + ".json");
			if (!Files.exists(file)) {
				// A vanilla or other model: only the bare "block" parent counts as empty.
				return path.equals("block/block");
			}
			try {
				JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (json.has("elements")) {
					return false;
				}
				current = json.has("parent") ? json.get("parent").getAsString() : null;
			} catch (IOException | RuntimeException e) {
				return false;
			}
		}
		return true;
	}

	/** Whether the jar's class scan found a "facing" property on this block (assumed if not scanned). */
	private static boolean hasFacing(Path packDir, String id) {
		try {
			Path states = packDir.resolve("mimic_states.json");
			if (!Files.exists(states)) {
				return true;
			}
			JsonObject all = JsonParser.parseString(Files.readString(states)).getAsJsonObject();
			if (!all.has(id)) {
				return true;
			}
			for (JsonElement prop : all.getAsJsonArray(id)) {
				if ("facing".equals(prop.getAsJsonObject().get("name").getAsString())) {
					return true;
				}
			}
			return false;
		} catch (IOException | RuntimeException e) {
			return true;
		}
	}

	/** Adds sprites to the block atlas through this pack's atlases/blocks.json (atlas files merge). */
	private static void addToAtlas(Path packDir, Set<String> sprites) {
		Path file = packDir.resolve("assets/minecraft/atlases/blocks.json");
		try {
			JsonObject atlas = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
			JsonArray sources = atlas.has("sources") ? atlas.getAsJsonArray("sources") : new JsonArray();
			Set<String> present = new LinkedHashSet<>();
			for (JsonElement source : sources) {
				JsonObject s = source.getAsJsonObject();
				if (s.has("resource")) {
					present.add(s.get("resource").getAsString());
				}
			}
			boolean changed = false;
			for (String sprite : sprites) {
				if (present.add(sprite)) {
					JsonObject source = new JsonObject();
					source.addProperty("type", "minecraft:single");
					source.addProperty("resource", sprite);
					sources.add(source);
					changed = true;
				}
			}
			if (changed) {
				atlas.add("sources", sources);
				Files.createDirectories(file.getParent());
				Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(atlas));
			}
		} catch (IOException | RuntimeException e) {
			MimicClient.LOGGER.warn("[Assets] Could not extend the block atlas in {}", packDir, e);
		}
	}
}
