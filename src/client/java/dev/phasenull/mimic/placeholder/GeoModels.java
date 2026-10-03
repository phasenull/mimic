package dev.phasenull.mimic.placeholder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.assets.AssetPacks;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Entity models from imported jars in the Bedrock/GeckoLib geometry format (*.geo.json), which many mods
 * use for their mobs. A model is baked once in its resting pose (no animations) into quads in entity
 * space (blocks, y up, facing -z), ready to draw with the entity's texture. Models written in Java code
 * can't be read this way; those entities keep the plain placeholder look.
 */
public final class GeoModels {
	/** One textured quad: 4 corners (x, y, z, u, v) and a normal. */
	public record Quad(float[] corners, float nx, float ny, float nz) {}

	public record Model(List<Quad> quads, Identifier texture) {}

	private static final Map<Identifier, Optional<Model>> CACHE = new ConcurrentHashMap<>();

	private GeoModels() {}

	public static void clear() {
		CACHE.clear();
	}

	/** The model for an entity type, if an imported jar has one. */
	public static Optional<Model> get(Identifier entityType) {
		return CACHE.computeIfAbsent(entityType, GeoModels::load);
	}

	private static Optional<Model> load(Identifier type) {
		try {
			Optional<Path> geo = find(type.getNamespace(), "", type.getPath(), ".geo.json");
			if (geo.isEmpty()) {
				return Optional.empty();
			}
			JsonObject root = JsonParser.parseString(Files.readString(geo.get())).getAsJsonObject();
			JsonObject geometry = geometry(root);
			if (geometry == null) {
				return Optional.empty();
			}
			float texW = 64;
			float texH = 64;
			JsonObject description = geometry.has("description") ? geometry.getAsJsonObject("description") : geometry;
			if (description.has("texture_width")) {
				texW = description.get("texture_width").getAsFloat();
				texH = description.get("texture_height").getAsFloat();
			} else if (geometry.has("texturewidth")) {
				texW = geometry.get("texturewidth").getAsFloat();
				texH = geometry.get("textureheight").getAsFloat();
			}
			List<Quad> quads = new ArrayList<>();
			bake(geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray(), texW, texH, quads);
			Identifier texture = texture(type);
			MimicClient.LOGGER.info("[Entities] {}: model {} ({} faces), texture {}", type, geo.get().getFileName(), quads.size(), texture);
			return quads.isEmpty() ? Optional.empty() : Optional.of(new Model(quads, texture));
		} catch (IOException | RuntimeException e) {
			MimicClient.LOGGER.warn("[Entities] Could not read the model of {}", type, e);
			return Optional.empty();
		}
	}

	/** "minecraft:geometry": [...] (format 1.12+) or "geometry.name": {...} (older). */
	private static JsonObject geometry(JsonObject root) {
		if (root.has("minecraft:geometry")) {
			JsonArray list = root.getAsJsonArray("minecraft:geometry");
			return list.isEmpty() ? null : list.get(0).getAsJsonObject();
		}
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
				return e.getValue().getAsJsonObject();
			}
		}
		return null;
	}

	/** A file named "<name><suffix>" anywhere under the namespace's assets (best: exact name, else containing it). */
	private static Optional<Path> find(String namespace, String folder, String name, String suffix) {
		Path best = null;
		for (Path pack : AssetPacks.jarFolders()) {
			Path dir = pack.resolve("assets").resolve(namespace).resolve(folder);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path file : (Iterable<Path>) files::iterator) {
					String fileName = file.getFileName().toString();
					if (!fileName.endsWith(suffix)) {
						continue;
					}
					String base = fileName.substring(0, fileName.length() - suffix.length());
					if (base.equals(name)) {
						return Optional.of(file);
					}
					if (best == null && (base.startsWith(name + "_") || base.endsWith("_" + name))) {
						best = file;
					}
				}
			} catch (IOException ignored) {
				// Unreadable pack: skip.
			}
		}
		return Optional.ofNullable(best);
	}

	private static Identifier texture(Identifier type) {
		Optional<Path> png = find(type.getNamespace(), "textures/entity", type.getPath(), ".png");
		if (png.isPresent()) {
			for (Path pack : AssetPacks.jarFolders()) {
				Path textures = pack.resolve("assets").resolve(type.getNamespace()).resolve("textures");
				if (png.get().startsWith(textures)) {
					return Identifier.fromNamespaceAndPath(type.getNamespace(),
						"textures/" + textures.relativize(png.get()).toString().replace('\\', '/'));
				}
			}
		}
		return Identifier.fromNamespaceAndPath(type.getNamespace(), "textures/entity/" + type.getPath() + ".png");
	}

	private static void bake(JsonArray bones, float texW, float texH, List<Quad> out) {
		Map<String, JsonObject> byName = new HashMap<>();
		Map<String, List<JsonObject>> children = new HashMap<>();
		List<JsonObject> roots = new ArrayList<>();
		for (JsonElement b : bones) {
			JsonObject bone = b.getAsJsonObject();
			byName.put(bone.get("name").getAsString(), bone);
		}
		for (JsonObject bone : byName.values()) {
			String parent = bone.has("parent") ? bone.get("parent").getAsString() : null;
			if (parent != null && byName.containsKey(parent)) {
				children.computeIfAbsent(parent, k -> new ArrayList<>()).add(bone);
			} else {
				roots.add(bone);
			}
		}
		for (JsonObject root : roots) {
			bakeBone(root, new Matrix4f(), children, texW, texH, out, 0);
		}
	}

	private static void bakeBone(JsonObject bone, Matrix4f parent, Map<String, List<JsonObject>> children, float texW, float texH,
			List<Quad> out, int depth) {
		if (depth > 64) {
			return;
		}
		Matrix4f m = new Matrix4f(parent);
		rotateAround(m, vec(bone, "pivot"), vec(bone, "rotation"));
		if (bone.has("cubes")) {
			for (JsonElement c : bone.getAsJsonArray("cubes")) {
				JsonObject cube = c.getAsJsonObject();
				Matrix4f cm = new Matrix4f(m);
				if (cube.has("rotation")) {
					rotateAround(cm, cube.has("pivot") ? vec(cube, "pivot") : new float[3], vec(cube, "rotation"));
				}
				bakeCube(cube, cm, texW, texH, out);
			}
		}
		for (JsonObject child : children.getOrDefault(bone.get("name").getAsString(), List.of())) {
			bakeBone(child, m, children, texW, texH, out, depth + 1);
		}
	}

	/** Bedrock pivot/rotation (x mirrored, degrees, applied z then y then x like GeckoLib). */
	private static void rotateAround(Matrix4f m, float[] pivot, float[] rotation) {
		if (rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0) {
			return;
		}
		float px = -pivot[0] / 16f;
		float py = pivot[1] / 16f;
		float pz = pivot[2] / 16f;
		m.translate(px, py, pz);
		m.rotateZ((float) Math.toRadians(rotation[2]));
		m.rotateY((float) Math.toRadians(-rotation[1]));
		m.rotateX((float) Math.toRadians(-rotation[0]));
		m.translate(-px, -py, -pz);
	}

	private static float[] vec(JsonObject o, String key) {
		if (!o.has(key) || !o.get(key).isJsonArray()) {
			return new float[3];
		}
		JsonArray a = o.getAsJsonArray(key);
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static void bakeCube(JsonObject cube, Matrix4f m, float texW, float texH, List<Quad> out) {
		float[] o = vec(cube, "origin");
		float[] s = vec(cube, "size");
		float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0;
		boolean mirror = cube.has("mirror") && cube.get("mirror").getAsBoolean();
		float x0 = (-(o[0] + s[0]) - inflate) / 16f;
		float x1 = (-o[0] + inflate) / 16f;
		float y0 = (o[1] - inflate) / 16f;
		float y1 = (o[1] + s[1] + inflate) / 16f;
		float z0 = (o[2] - inflate) / 16f;
		float z1 = (o[2] + s[2] + inflate) / 16f;

		// Face corners in order top-left, top-right, bottom-right, bottom-left as seen from outside.
		float[][] front = {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}};
		float[][] back = {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}};
		float[][] plusX = {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}};
		float[][] minusX = {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}};
		float[][] top = {{x1, y1, z1}, {x0, y1, z1}, {x0, y1, z0}, {x1, y1, z0}};
		float[][] bottom = {{x1, y0, z0}, {x0, y0, z0}, {x0, y0, z1}, {x1, y0, z1}};

		JsonElement uv = cube.get("uv");
		if (uv != null && uv.isJsonObject()) {
			JsonObject faces = uv.getAsJsonObject();
			face(faces, "north", front, 0, 0, -1, m, texW, texH, out);
			face(faces, "south", back, 0, 0, 1, m, texW, texH, out);
			face(faces, "east", plusX, 1, 0, 0, m, texW, texH, out);
			face(faces, "west", minusX, -1, 0, 0, m, texW, texH, out);
			face(faces, "up", top, 0, 1, 0, m, texW, texH, out);
			face(faces, "down", bottom, 0, -1, 0, m, texW, texH, out);
			return;
		}
		float u = 0;
		float v = 0;
		if (uv != null && uv.isJsonArray()) {
			u = uv.getAsJsonArray().get(0).getAsFloat();
			v = uv.getAsJsonArray().get(1).getAsFloat();
		}
		// Box UV: [side +x][front][side -x][back] below [top][bottom], like vanilla's cube layout.
		float w = (float) Math.floor(s[0]);
		float h = (float) Math.floor(s[1]);
		float d = (float) Math.floor(s[2]);
		float[][] sideA = mirror ? minusX : plusX;
		float[][] sideB = mirror ? plusX : minusX;
		quad(front, rect(u + d, v + d, w, h, mirror), 0, 0, -1, m, texW, texH, out);
		quad(sideA, rect(u, v + d, d, h, mirror), mirror ? -1 : 1, 0, 0, m, texW, texH, out);
		quad(sideB, rect(u + d + w, v + d, d, h, mirror), mirror ? 1 : -1, 0, 0, m, texW, texH, out);
		quad(back, rect(u + 2 * d + w, v + d, w, h, mirror), 0, 0, 1, m, texW, texH, out);
		quad(top, rect(u + d, v, w, d, mirror), 0, 1, 0, m, texW, texH, out);
		quad(bottom, rect(u + d + w, v, w, d, mirror), 0, -1, 0, m, texW, texH, out);
	}

	private static float[] rect(float u, float v, float w, float h, boolean mirror) {
		return mirror ? new float[] {u + w, v, u, v + h} : new float[] {u, v, u + w, v + h};
	}

	private static void face(JsonObject faces, String name, float[][] corners, float nx, float ny, float nz, Matrix4f m,
			float texW, float texH, List<Quad> out) {
		if (!faces.has(name) || !faces.get(name).isJsonObject()) {
			return;
		}
		JsonObject f = faces.getAsJsonObject(name);
		float[] uv = f.has("uv") ? new float[] {f.getAsJsonArray("uv").get(0).getAsFloat(), f.getAsJsonArray("uv").get(1).getAsFloat()} : new float[2];
		float[] size = f.has("uv_size") ? new float[] {f.getAsJsonArray("uv_size").get(0).getAsFloat(), f.getAsJsonArray("uv_size").get(1).getAsFloat()} : new float[2];
		quad(corners, new float[] {uv[0], uv[1], uv[0] + size[0], uv[1] + size[1]}, nx, ny, nz, m, texW, texH, out);
	}

	/** Bakes one face: corners through the bone matrix, UVs (u0, v0, u1, v1) in texture pixels. */
	private static void quad(float[][] corners, float[] uv, float nx, float ny, float nz, Matrix4f m, float texW, float texH, List<Quad> out) {
		float[] data = new float[20];
		float[][] uvs = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		Vector3f p = new Vector3f();
		for (int i = 0; i < 4; i++) {
			m.transformPosition(corners[i][0], corners[i][1], corners[i][2], p);
			data[i * 5] = p.x;
			data[i * 5 + 1] = p.y;
			data[i * 5 + 2] = p.z;
			data[i * 5 + 3] = uvs[i][0] / texW;
			data[i * 5 + 4] = uvs[i][1] / texH;
		}
		Vector3f n = new Matrix3f(m).transform(new Vector3f(nx, ny, nz)).normalize();
		out.add(new Quad(data, n.x, n.y, n.z));
	}
}
