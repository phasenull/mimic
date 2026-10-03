package dev.phasenull.mimic.assets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Imported item definitions (assets/<ns>/items/*.json) often use model types, properties and tints the mod
 * registers itself (computercraft:pocket_computer_state, lootr:chest...). Without the mod the game rejects
 * the whole file and the item shows as missing. This rewrites those parts to vanilla ones: a switch on an
 * unknown property takes its fallback (or first case), unknown tints become constants, an unknown special
 * model uses its base model, and other unknown models are left empty.
 * <p>
 * Block-state files get the same treatment: a variant with a mod's own loader ("fabric:type": "lootr:custom")
 * becomes a plain model variant using the first block model it names.
 */
public final class ItemModelFallbacks {
	private ItemModelFallbacks() {}

	/** Rewrites every item definition in an imported pack folder that needs it. Returns how many changed. */
	public static int apply(Path packDir) {
		Path assets = packDir.resolve("assets");
		if (!Files.isDirectory(assets)) {
			return 0;
		}
		int changed = 0;
		try (Stream<Path> files = Files.walk(assets)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				Path relative = assets.relativize(file);
				if (relative.getNameCount() < 3 || !file.toString().endsWith(".json")) {
					continue;
				}
				if (relative.getName(1).toString().equals("blockstates")) {
					changed += blockState(file) ? 1 : 0;
					continue;
				}
				if (!relative.getName(1).toString().equals("items")) {
					continue;
				}
				try {
					JsonObject definition = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
					if (!definition.has("model") || !definition.get("model").isJsonObject()) {
						continue;
					}
					JsonObject model = definition.getAsJsonObject("model");
					JsonObject fixed = model(model);
					if (!fixed.equals(model)) {
						definition.add("model", fixed);
						Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(definition));
						changed++;
					}
				} catch (IOException | RuntimeException e) {
					MimicClient.LOGGER.debug("[Assets] Could not check item definition {}", file, e);
				}
			}
		} catch (IOException e) {
			MimicClient.LOGGER.warn("[Assets] Could not check item definitions in {}", packDir, e);
		}
		if (changed > 0) {
			MimicClient.LOGGER.info("[Assets] {}: {} item definitions rewritten to vanilla model types", packDir.getFileName(), changed);
		}
		return changed;
	}

	private static boolean blockState(Path file) {
		try {
			JsonObject definition = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (!definition.has("variants") || !definition.get("variants").isJsonObject()) {
				return false;
			}
			boolean changed = false;
			JsonObject variants = definition.getAsJsonObject("variants");
			for (String key : variants.keySet()) {
				JsonElement variant = variants.get(key);
				if (variant.isJsonObject()) {
					JsonObject fixed = variant(variant.getAsJsonObject());
					if (fixed != null) {
						variants.add(key, fixed);
						changed = true;
					}
				} else if (variant.isJsonArray()) {
					JsonArray list = variant.getAsJsonArray();
					for (int i = 0; i < list.size(); i++) {
						JsonObject fixed = list.get(i).isJsonObject() ? variant(list.get(i).getAsJsonObject()) : null;
						if (fixed != null) {
							list.set(i, fixed);
							changed = true;
						}
					}
				}
			}
			if (changed) {
				Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(definition));
			}
			return changed;
		} catch (IOException | RuntimeException e) {
			MimicClient.LOGGER.debug("[Assets] Could not check block state {}", file, e);
			return false;
		}
	}

	/** A plain variant for one using a mod's own loader, or null if it doesn't need one. */
	private static JsonObject variant(JsonObject in) {
		String type = string(in, "fabric:type");
		if (type == null || known(type)) {
			return null;
		}
		String model = string(in, "model");
		for (String key : new String[] {"unopened", "vanilla", "default", "base", "stage_0"}) {
			if (model == null) {
				model = string(in, key);
			}
		}
		if (model == null) {
			for (String key : in.keySet()) {
				String value = string(in, key);
				if (value != null && value.contains("block/")) {
					model = value;
					break;
				}
			}
		}
		if (model == null) {
			model = "minecraft:block/stone";
		}
		JsonObject out = new JsonObject();
		out.addProperty("model", model);
		JsonObject rotation = in.has("state") && in.get("state").isJsonObject() ? in.getAsJsonObject("state") : in;
		for (String key : new String[] {"x", "y", "uvlock"}) {
			if (rotation.has(key)) {
				out.add(key, rotation.get(key));
			}
		}
		return out;
	}

	/** Vanilla types, and those of mods this client actually has. */
	private static boolean known(String id) {
		String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
		return namespace.equals("minecraft") || FabricLoader.getInstance().isModLoaded(namespace);
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static JsonObject empty() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "minecraft:empty");
		return o;
	}

	private static JsonObject model(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return empty();
		}
		JsonObject in = element.getAsJsonObject();
		String type = string(in, "type");
		if (type == null) {
			return in.deepCopy();
		}
		String property = string(in, "property");
		boolean unknownProperty = property != null && !known(property);
		String path = type.substring(type.indexOf(':') + 1);
		if (!known(type)) {
			return empty();
		}
		switch (path) {
			case "condition" -> {
				if (unknownProperty) {
					return model(in.get("on_false"));
				}
				JsonObject out = in.deepCopy();
				out.add("on_true", model(in.get("on_true")));
				out.add("on_false", model(in.get("on_false")));
				return out;
			}
			case "select", "range_dispatch" -> {
				String listKey = path.equals("select") ? "cases" : "entries";
				JsonArray list = in.has(listKey) && in.get(listKey).isJsonArray() ? in.getAsJsonArray(listKey) : new JsonArray();
				if (unknownProperty) {
					if (in.has("fallback")) {
						return model(in.get("fallback"));
					}
					return list.isEmpty() || !list.get(0).isJsonObject() ? empty() : model(list.get(0).getAsJsonObject().get("model"));
				}
				JsonObject out = in.deepCopy();
				JsonArray cases = new JsonArray();
				for (JsonElement entry : list) {
					if (entry.isJsonObject()) {
						JsonObject c = entry.getAsJsonObject().deepCopy();
						c.add("model", model(entry.getAsJsonObject().get("model")));
						cases.add(c);
					}
				}
				out.add(listKey, cases);
				if (in.has("fallback")) {
					out.add("fallback", model(in.get("fallback")));
				}
				return out;
			}
			case "composite" -> {
				JsonObject out = in.deepCopy();
				JsonArray models = new JsonArray();
				if (in.has("models") && in.get("models").isJsonArray()) {
					for (JsonElement m : in.getAsJsonArray("models")) {
						JsonObject fixed = model(m);
						if (!"minecraft:empty".equals(string(fixed, "type"))) {
							models.add(fixed);
						}
					}
				}
				out.add("models", models);
				return out;
			}
			case "special" -> {
				JsonObject special = in.has("model") && in.get("model").isJsonObject() ? in.getAsJsonObject("model") : null;
				String specialType = special == null ? null : string(special, "type");
				if (specialType != null && !known(specialType)) {
					String base = string(in, "base");
					if (base == null) {
						return empty();
					}
					JsonObject out = new JsonObject();
					out.addProperty("type", "minecraft:model");
					out.addProperty("model", base);
					return out;
				}
				return in.deepCopy();
			}
			case "model" -> {
				JsonObject out = in.deepCopy();
				if (in.has("tints") && in.get("tints").isJsonArray()) {
					JsonArray tints = new JsonArray();
					for (JsonElement tint : in.getAsJsonArray("tints")) {
						String tintType = tint.isJsonObject() ? string(tint.getAsJsonObject(), "type") : null;
						if (tintType == null || known(tintType)) {
							tints.add(tint);
						} else {
							JsonObject constant = new JsonObject();
							constant.addProperty("type", "minecraft:constant");
							JsonElement value = tint.getAsJsonObject().get("default");
							constant.addProperty("value", value != null && value.isJsonPrimitive() ? value.getAsInt() : -1);
							tints.add(constant);
						}
					}
					out.add("tints", tints);
				}
				return out;
			}
			default -> {
				return in.deepCopy();
			}
		}
	}
}
