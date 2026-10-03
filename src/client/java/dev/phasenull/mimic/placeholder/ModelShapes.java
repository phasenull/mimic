package dev.phasenull.mimic.placeholder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.assets.AssetPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outline and collision shapes for placeholder blocks, built from the imported jar's block-state and model
 * files: the model's cuboid elements (following parents, vanilla ones included), turned like the variant.
 * Without a model (or with one that has no elements), a placeholder stays a full block. Element rotations
 * (at most 45 degrees) are ignored; shapes are close, not exact.
 */
public final class ModelShapes {
	/** Outline and collision shape of one state. */
	private record Shape(VoxelShape outline, VoxelShape collision, boolean full) {}

	private static final Shape FULL = new Shape(Shapes.block(), Shapes.block(), true);
	private static final int MAX_PARENTS = 16;
	private static final Map<BlockState, Shape> CACHE = new ConcurrentHashMap<>();
	/** Parsed files, shared by all states (some mod blocks have thousands). */
	private static final Map<Identifier, Optional<JsonObject>> DEFINITIONS = new ConcurrentHashMap<>();
	private static final Map<String, Optional<List<double[]>>> ELEMENTS = new ConcurrentHashMap<>();

	private ModelShapes() {}

	/** Forgets computed shapes (after a jar import or a texture change). */
	public static void clear() {
		CACHE.clear();
		DEFINITIONS.clear();
		ELEMENTS.clear();
	}

	public static VoxelShape outline(BlockState state) {
		return shape(state).outline();
	}

	public static VoxelShape collision(BlockState state) {
		return shape(state).collision();
	}

	public static boolean full(BlockState state) {
		return shape(state).full();
	}

	private static Shape shape(BlockState state) {
		Shape shape = CACHE.get(state);
		if (shape == null) {
			try {
				shape = compute(state);
			} catch (RuntimeException e) {
				MimicClient.LOGGER.debug("[Shapes] {}", state, e);
				shape = FULL;
			}
			CACHE.put(state, shape);
		}
		return shape;
	}

	private record Placed(String model, int x, int y) {}

	private static Shape compute(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Optional<JsonObject> definition = DEFINITIONS.computeIfAbsent(id,
			k -> AssetPacks.readJson(k.getNamespace(), "blockstates/" + k.getPath() + ".json"));
		if (definition.isEmpty()) {
			return FULL;
		}
		List<Placed> placed = new ArrayList<>();
		JsonObject json = definition.get();
		if (json.has("variants") && json.get("variants").isJsonObject()) {
			for (Map.Entry<String, JsonElement> variant : json.getAsJsonObject("variants").entrySet()) {
				if (matchesKey(state, variant.getKey())) {
					addPlaced(variant.getValue(), placed);
					break;
				}
			}
		} else if (json.has("multipart") && json.get("multipart").isJsonArray()) {
			for (JsonElement part : json.getAsJsonArray("multipart")) {
				JsonObject p = part.getAsJsonObject();
				if (!p.has("when") || matchesWhen(state, p.get("when"))) {
					addPlaced(p.get("apply"), placed);
				}
			}
		}
		if (placed.isEmpty()) {
			return FULL;
		}
		VoxelShape collision = Shapes.empty();
		double[] bounds = {16, 16, 16, 0, 0, 0};
		boolean anyElements = false;
		for (Placed p : placed) {
			List<double[]> boxes = ELEMENTS.computeIfAbsent(p.model(), m -> Optional.ofNullable(elements(m))).orElse(null);
			if (boxes == null) {
				// A model without elements (e.g. a plain cube or a block-entity model): treat as a full block.
				return FULL;
			}
			for (double[] box : boxes) {
				anyElements = true;
				double[] turned = rotate(box, p.x(), p.y());
				for (int i = 0; i < 3; i++) {
					bounds[i] = Math.min(bounds[i], turned[i]);
					bounds[i + 3] = Math.max(bounds[i + 3], turned[i + 3]);
				}
				if (turned[3] - turned[0] > 0.01 && turned[4] - turned[1] > 0.01 && turned[5] - turned[2] > 0.01) {
					collision = Shapes.or(collision, box(turned));
				}
			}
		}
		if (!anyElements) {
			return FULL;
		}
		// Flat models (plants, panes drawn as planes) still need something to aim at.
		VoxelShape outline = collision.isEmpty() ? box(new double[] {bounds[0], bounds[1], bounds[2],
			Math.max(bounds[3], bounds[0] + 1), Math.max(bounds[4], bounds[1] + 1), Math.max(bounds[5], bounds[2] + 1)}) : collision;
		boolean full = !collision.isEmpty() && Shapes.joinIsNotEmpty(Shapes.block(), collision, net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST) == false;
		return new Shape(outline, collision, full);
	}

	private static VoxelShape box(double[] b) {
		return Shapes.box(clamp(b[0]) / 16, clamp(b[1]) / 16, clamp(b[2]) / 16, clamp(b[3]) / 16, clamp(b[4]) / 16, clamp(b[5]) / 16);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(16, v));
	}

	private static void addPlaced(JsonElement apply, List<Placed> out) {
		if (apply == null) {
			return;
		}
		JsonObject o = apply.isJsonArray() ? (apply.getAsJsonArray().isEmpty() ? null : apply.getAsJsonArray().get(0).getAsJsonObject())
			: apply.isJsonObject() ? apply.getAsJsonObject() : null;
		if (o != null && o.has("model")) {
			out.add(new Placed(o.get("model").getAsString(), o.has("x") ? o.get("x").getAsInt() : 0, o.has("y") ? o.get("y").getAsInt() : 0));
		}
	}

	/** "facing=north,lit=true" (or "" for every state). */
	private static boolean matchesKey(BlockState state, String key) {
		if (key.isEmpty() || key.equals("normal")) {
			return true;
		}
		for (String condition : key.split(",")) {
			int eq = condition.indexOf('=');
			if (eq < 0 || !valueIs(state, condition.substring(0, eq), List.of(condition.substring(eq + 1)))) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesWhen(BlockState state, JsonElement when) {
		if (!when.isJsonObject()) {
			return true;
		}
		JsonObject o = when.getAsJsonObject();
		if (o.has("OR")) {
			for (JsonElement e : o.getAsJsonArray("OR")) {
				if (matchesWhen(state, e)) {
					return true;
				}
			}
			return false;
		}
		if (o.has("AND")) {
			for (JsonElement e : o.getAsJsonArray("AND")) {
				if (!matchesWhen(state, e)) {
					return false;
				}
			}
			return true;
		}
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String value = e.getValue().getAsString();
			boolean negate = value.startsWith("!");
			boolean matches = valueIs(state, e.getKey(), List.of((negate ? value.substring(1) : value).split("\\|")));
			if (matches == negate) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean valueIs(BlockState state, String property, List<String> values) {
		for (Property p : state.getProperties()) {
			if (p.getName().equals(property)) {
				return values.contains(p.getName(state.getValue(p)));
			}
		}
		return false;
	}

	/** The model's element boxes (from-to, 0-16), following parents; null if it has none. */
	private static List<double[]> elements(String model) {
		String current = model;
		for (int depth = 0; depth < MAX_PARENTS && current != null; depth++) {
			JsonObject json = readModel(current);
			if (json == null) {
				return null;
			}
			if (json.has("elements") && json.get("elements").isJsonArray()) {
				List<double[]> boxes = new ArrayList<>();
				for (JsonElement element : json.getAsJsonArray("elements")) {
					JsonObject e = element.getAsJsonObject();
					JsonArray from = e.getAsJsonArray("from");
					JsonArray to = e.getAsJsonArray("to");
					boxes.add(new double[] {from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
						to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()});
				}
				return boxes;
			}
			current = json.has("parent") ? json.get("parent").getAsString() : null;
		}
		return null;
	}

	private static JsonObject readModel(String model) {
		Identifier id = Identifier.tryParse(model);
		if (id == null || id.getPath().startsWith("builtin/")) {
			return null;
		}
		Optional<JsonObject> fromPacks = AssetPacks.readJson(id.getNamespace(), "models/" + id.getPath() + ".json");
		if (fromPacks.isPresent()) {
			return fromPacks.get();
		}
		if (id.getNamespace().equals("mimic")) {
			// Mimic's own look-alike models (see BlockEntityLooks).
			Optional<java.nio.file.Path> own = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("mimic")
				.flatMap(c -> c.findPath("assets/mimic/models/" + id.getPath() + ".json"));
			if (own.isPresent()) {
				try {
					return JsonParser.parseString(java.nio.file.Files.readString(own.get())).getAsJsonObject();
				} catch (Exception e) {
					return null;
				}
			}
		}
		IoSupplier<InputStream> vanilla = Minecraft.getInstance().getVanillaPackResources().fullResources()
			.getResource(PackType.CLIENT_RESOURCES, id.withPath(p -> "models/" + p + ".json"));
		if (vanilla == null) {
			return null;
		}
		try (InputStream in = vanilla.get()) {
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	/** Turns a box like a block-state variant: x rotation first, then y, in 90 degree steps. */
	private static double[] rotate(double[] b, int xDegrees, int yDegrees) {
		double[] out = b.clone();
		for (int i = 0; i < Math.floorMod(xDegrees / 90, 4); i++) {
			// (y, z) -> (z, 16 - y)
			double y1 = out[2];
			double y2 = out[5];
			double z1 = 16 - out[4];
			double z2 = 16 - out[1];
			out = new double[] {out[0], Math.min(y1, y2), Math.min(z1, z2), out[3], Math.max(y1, y2), Math.max(z1, z2)};
		}
		for (int i = 0; i < Math.floorMod(yDegrees / 90, 4); i++) {
			// (x, z) -> (16 - z, x)
			double x1 = 16 - out[5];
			double x2 = 16 - out[2];
			double z1 = out[0];
			double z2 = out[3];
			out = new double[] {Math.min(x1, x2), out[1], Math.min(z1, z2), Math.max(x1, x2), out[4], Math.max(z1, z2)};
		}
		return out;
	}
}
