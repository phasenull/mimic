package dev.phasenull.mimic.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recipes from imported mod jars, for display only (item pages, JEI): what the jar says, which the server
 * may have changed. The game's own crafting and recipe book are never touched.
 */
public final class MockRecipes {
	/** How a recipe is laid out. */
	public enum Kind { SHAPED, SHAPELESS, COOKING, STONECUTTING, OTHER }

	/**
	 * One recipe. {@code inputs} are slots (row by row, {@code width} wide for shaped recipes); each slot lists
	 * alternatives: item ids, or "#tag" for item tags; an empty slot is an empty list.
	 */
	public record Recipe(String id, String type, Kind kind, int width, List<List<String>> inputs, String output, int count, String jar) {}

	private static volatile List<Recipe> all;

	private MockRecipes() {}

	public static void invalidate() {
		all = null;
	}

	public static List<Recipe> all() {
		List<Recipe> recipes = all;
		if (recipes != null) {
			return recipes;
		}
		recipes = new ArrayList<>();
		for (Path dir : AssetPacks.jarFolders()) {
			Path data = dir.resolve("data");
			if (!Files.isDirectory(data)) {
				continue;
			}
			String jar = dir.getFileName().toString().replaceFirst("^jar_", "");
			try (Stream<Path> files = Files.walk(data)) {
				for (Path file : (Iterable<Path>) files::iterator) {
					if (!file.toString().endsWith(".json")) {
						continue;
					}
					Path relative = data.relativize(file);
					String id = relative.getName(0) + ":" + relative.subpath(2, relative.getNameCount()).toString().replace('\\', '/').replaceAll("\\.json$", "");
					try {
						Recipe recipe = parse(id, JsonParser.parseString(Files.readString(file)).getAsJsonObject(), jar);
						if (recipe != null) {
							recipes.add(recipe);
						}
					} catch (IOException | RuntimeException e) {
						MimicClient.LOGGER.debug("[Recipes] Unreadable {}", file, e);
					}
				}
			} catch (IOException e) {
				MimicClient.LOGGER.warn("[Recipes] Could not read {}", data, e);
			}
		}
		all = List.copyOf(recipes);
		return all;
	}

	/** Recipes that make {@code itemId}. */
	public static List<Recipe> makes(String itemId) {
		return all().stream().filter(r -> itemId.equals(r.output())).toList();
	}

	/** Recipes that take {@code itemId} as an input (tags aren't resolved here). */
	public static List<Recipe> uses(String itemId) {
		return all().stream().filter(r -> r.inputs().stream().anyMatch(slot -> slot.contains(itemId))).toList();
	}

	static Recipe parse(String id, JsonObject json, String jar) {
		String type = json.has("type") ? json.get("type").getAsString() : "?";
		String path = type.substring(type.indexOf(':') + 1);
		JsonElement result = json.has("result") ? json.get("result") : json.get("output");
		String output = resultId(result);
		int count = resultCount(result, json);
		// Mods' own grid recipes (e.g. computercraft:transform_shaped) use the vanilla shapes, so go by structure.
		if (json.has("pattern") && json.has("key")) {
			path = "crafting_shaped";
		} else if (json.has("ingredients") && json.get("ingredients").isJsonArray() && !path.equals("crafting_shapeless")) {
			path = "crafting_shapeless";
		}
		switch (path) {
			case "crafting_shaped" -> {
				List<List<String>> slots = new ArrayList<>();
				JsonArray pattern = json.getAsJsonArray("pattern");
				JsonObject key = json.getAsJsonObject("key");
				int width = 0;
				for (JsonElement row : pattern) {
					width = Math.max(width, row.getAsString().length());
				}
				for (JsonElement row : pattern) {
					String line = row.getAsString();
					for (int i = 0; i < width; i++) {
						char c = i < line.length() ? line.charAt(i) : ' ';
						slots.add(c == ' ' || !key.has(String.valueOf(c)) ? List.of() : ingredient(key.get(String.valueOf(c))));
					}
				}
				return new Recipe(id, type, Kind.SHAPED, width, slots, output, count, jar);
			}
			case "crafting_shapeless" -> {
				List<List<String>> slots = new ArrayList<>();
				for (JsonElement ingredient : json.getAsJsonArray("ingredients")) {
					slots.add(ingredient(ingredient));
				}
				return new Recipe(id, type, Kind.SHAPELESS, 3, slots, output, count, jar);
			}
			case "smelting", "blasting", "smoking", "campfire_cooking" -> {
				return new Recipe(id, type, Kind.COOKING, 1, List.of(ingredient(json.get("ingredient"))), output, count, jar);
			}
			case "stonecutting" -> {
				return new Recipe(id, type, Kind.STONECUTTING, 1, List.of(ingredient(json.get("ingredient"))), output, count, jar);
			}
			default -> {
				// Unknown type: every id-like value under an input-ish key is an input.
				List<List<String>> slots = new ArrayList<>();
				for (Map.Entry<String, JsonElement> e : json.entrySet()) {
					String k = e.getKey().toLowerCase();
					if (k.contains("ingredient") || k.contains("input") || k.equals("key") || k.equals("base") || k.equals("addition") || k.equals("template")) {
						collectInputs(e.getValue(), slots);
					}
				}
				return output == null && slots.isEmpty() ? null : new Recipe(id, type, Kind.OTHER, 3, slots, output, count, jar);
			}
		}
	}

	/** An ingredient's alternatives: "id", "#tag", {"item"|"id"|"tag"|"items": ...} or a list of those. */
	static List<String> ingredient(JsonElement element) {
		Set<String> ids = new LinkedHashSet<>();
		addIngredient(element, ids);
		return List.copyOf(ids);
	}

	private static void addIngredient(JsonElement element, Set<String> ids) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonPrimitive()) {
			ids.add(element.getAsString());
		} else if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(e -> addIngredient(e, ids));
		} else if (element.isJsonObject()) {
			JsonObject o = element.getAsJsonObject();
			if (o.has("tag")) {
				ids.add("#" + o.get("tag").getAsString());
			} else if (o.has("item")) {
				ids.add(o.get("item").getAsString());
			} else if (o.has("id")) {
				ids.add(o.get("id").getAsString());
			} else if (o.has("items")) {
				addIngredient(o.get("items"), ids);
			}
		}
	}

	private static void collectInputs(JsonElement element, List<List<String>> slots) {
		if (element.isJsonObject() && !element.getAsJsonObject().has("item") && !element.getAsJsonObject().has("tag")
				&& !element.getAsJsonObject().has("id") && !element.getAsJsonObject().has("items")) {
			element.getAsJsonObject().entrySet().forEach(e -> collectInputs(e.getValue(), slots));
		} else if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(e -> collectInputs(e, slots));
		} else {
			List<String> ids = ingredient(element);
			if (!ids.isEmpty()) {
				slots.add(ids);
			}
		}
	}

	private static String resultId(JsonElement result) {
		if (result == null || result.isJsonNull()) {
			return null;
		}
		if (result.isJsonPrimitive()) {
			return result.getAsString();
		}
		if (result.isJsonObject()) {
			JsonObject o = result.getAsJsonObject();
			return o.has("id") ? o.get("id").getAsString() : o.has("item") ? o.get("item").getAsString() : null;
		}
		return null;
	}

	private static int resultCount(JsonElement result, JsonObject recipe) {
		if (result != null && result.isJsonObject() && result.getAsJsonObject().has("count")) {
			return result.getAsJsonObject().get("count").getAsInt();
		}
		return recipe.has("count") && recipe.get("count").isJsonPrimitive() ? recipe.get("count").getAsInt() : 1;
	}
}
