package dev.phasenull.mimic.placeholder;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.phasenull.mimic.assets.AssetPacks;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The properties a placeholder block gets, i.e. how many block states it takes up. Without the mod's own
 * data, the vanilla shape a block id suggests ("_stairs", "_slab"...) is the best guess; anything else gets
 * a single state.
 */
public record StateGuess(List<Property<?>> properties, String source) {
	private static final StateGuess SINGLE = new StateGuess(List.of(), "no properties (unknown shape)");

	public int states() {
		int count = 1;
		for (Property<?> property : properties) {
			count *= property.getPossibleValues().size();
		}
		return count;
	}

	/** From an imported jar's blockstate file when there is one, else from the name. */
	public static StateGuess of(Identifier id) {
		return fromAssets(id).orElseGet(() -> fromName(id.getPath()));
	}

	/**
	 * Property names and values used in the block's blockstate file (variant keys, multipart conditions).
	 * Vanilla properties with the same name and values are used as-is, so their value order (and so the
	 * state order) matches the server's; others become boolean, integer or plain named-value properties.
	 */
	static Optional<StateGuess> fromAssets(Identifier id) {
		Optional<JsonObject> json = AssetPacks.readJson(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
		if (json.isEmpty()) {
			return Optional.empty();
		}
		Map<String, LinkedHashSet<String>> values = new TreeMap<>();
		JsonObject root = json.get();
		if (root.has("variants")) {
			for (String key : root.getAsJsonObject("variants").keySet()) {
				for (String pair : key.split(",")) {
					int eq = pair.indexOf('=');
					if (eq > 0) {
						values.computeIfAbsent(pair.substring(0, eq).trim(), k -> new LinkedHashSet<>()).add(pair.substring(eq + 1).trim());
					}
				}
			}
		}
		if (root.has("multipart")) {
			for (JsonElement part : root.getAsJsonArray("multipart")) {
				if (part.isJsonObject() && part.getAsJsonObject().has("when")) {
					collectWhen(part.getAsJsonObject().get("when"), values);
				}
			}
		}
		List<Property<?>> properties = new ArrayList<>();
		values.forEach((name, set) -> properties.add(property(name, new ArrayList<>(set))));
		return Optional.of(new StateGuess(properties, "from the imported jar's blockstate file"));
	}

	private static void collectWhen(JsonElement when, Map<String, LinkedHashSet<String>> values) {
		if (!when.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : when.getAsJsonObject().entrySet()) {
			if (e.getKey().equals("OR") || e.getKey().equals("AND")) {
				e.getValue().getAsJsonArray().forEach(c -> collectWhen(c, values));
			} else if (e.getValue().isJsonPrimitive()) {
				for (String v : e.getValue().getAsString().replace("!", "").split("\\|")) {
					values.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(v);
				}
			}
		}
	}

	private static Property<?> property(String name, List<String> values) {
		// Smallest match: "facing" is both the 4-way horizontal and the 6-way property.
		Property<?> best = null;
		for (Property<?> vanilla : VANILLA) {
			if (vanilla.getName().equals(name) && vanillaValues(vanilla).containsAll(values)
					&& (best == null || vanilla.getPossibleValues().size() < best.getPossibleValues().size())) {
				best = vanilla;
			}
		}
		if (best != null) {
			return best;
		}
		if (values.stream().allMatch(v -> v.equals("true") || v.equals("false"))) {
			return BooleanProperty.create(name);
		}
		if (values.stream().allMatch(v -> v.matches("\\d+"))) {
			int min = values.stream().mapToInt(Integer::parseInt).min().orElse(0);
			int max = values.stream().mapToInt(Integer::parseInt).max().orElse(0);
			return IntegerProperty.create(name, min, Math.max(max, min + 1));
		}
		return new StringProperty(name, values);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Set<String> vanillaValues(Property property) {
		Set<String> names = new java.util.HashSet<>();
		for (Object v : property.getPossibleValues()) {
			names.add(property.getName((Comparable) v));
		}
		return names;
	}

	private static final List<Property<?>> VANILLA = vanillaProperties();

	private static List<Property<?>> vanillaProperties() {
		List<Property<?>> list = new ArrayList<>();
		for (Field field : BlockStateProperties.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && Property.class.isAssignableFrom(field.getType())) {
				try {
					list.add((Property<?>) field.get(null));
				} catch (IllegalAccessException ignored) {
					// Public fields.
				}
			}
		}
		return list;
	}

	public static StateGuess fromName(String path) {
		if (path.endsWith("_stairs")) {
			return named("stairs", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF, BlockStateProperties.STAIRS_SHAPE,
				BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_slab")) {
			return named("slab", BlockStateProperties.SLAB_TYPE, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_wall")) {
			return named("wall", BlockStateProperties.UP, BlockStateProperties.EAST_WALL, BlockStateProperties.NORTH_WALL,
				BlockStateProperties.SOUTH_WALL, BlockStateProperties.WEST_WALL, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_fence_gate")) {
			return named("fence gate", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.IN_WALL, BlockStateProperties.OPEN,
				BlockStateProperties.POWERED);
		}
		if (path.endsWith("_fence") || path.endsWith("_pane") || path.endsWith("_bars")) {
			return named("fence/pane", BlockStateProperties.EAST, BlockStateProperties.NORTH, BlockStateProperties.SOUTH,
				BlockStateProperties.WEST, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_trapdoor")) {
			return named("trapdoor", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF, BlockStateProperties.OPEN,
				BlockStateProperties.POWERED, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_door")) {
			return named("door", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.DOUBLE_BLOCK_HALF, BlockStateProperties.DOOR_HINGE,
				BlockStateProperties.OPEN, BlockStateProperties.POWERED);
		}
		if (path.endsWith("_button")) {
			return named("button", BlockStateProperties.ATTACH_FACE, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.POWERED);
		}
		if (path.endsWith("_pressure_plate")) {
			return named("pressure plate", BlockStateProperties.POWERED);
		}
		if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae") || path.endsWith("_pillar")) {
			return named("log/pillar", BlockStateProperties.AXIS);
		}
		if (path.endsWith("_leaves")) {
			return named("leaves", BlockStateProperties.DISTANCE, BlockStateProperties.PERSISTENT, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_sapling")) {
			return named("sapling", BlockStateProperties.STAGE);
		}
		return SINGLE;
	}

	private static StateGuess named(String shape, Property<?>... properties) {
		return new StateGuess(List.of(properties), "guessed from the name (" + shape + ")");
	}
}
