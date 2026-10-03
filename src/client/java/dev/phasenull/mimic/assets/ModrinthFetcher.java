package dev.phasenull.mimic.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.debug.ConnectionDebug;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Finds a server mod's jar on Modrinth (by mod id, for the server's loader and version) and downloads it
 * for import. Only Modrinth's API and CDN are contacted, nothing is downloaded without the user confirming,
 * the file is checked against Modrinth's SHA-512, and the jar is only ever read as data (assets, recipes,
 * class scans) and deleted after import.
 */
public final class ModrinthFetcher {
	private static final String API = "https://api.modrinth.com/v2";
	private static final Path DOWNLOADS = FabricLoader.getInstance().getConfigDir().resolve("mimic").resolve("downloads");
	private static final long MAX_BYTES = 200L * 1024 * 1024;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** A file found for a mod. */
	public record Found(String project, String slug, String version, String fileName, String url, long size, String sha512,
		List<String> gameVersions, List<String> loaders, boolean exactVersion) {}

	private ModrinthFetcher() {}

	/** Looks the mod up; completes with null when Modrinth has nothing that fits. */
	public static CompletableFuture<Found> find(String modId, String serverKind) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject project = project(modId);
				if (project == null) {
					return null;
				}
				String loader = loader(serverKind);
				String version = serverVersion();
				Found exact = version == null ? null : pick(project, loader, version);
				return exact != null ? exact : pick(project, loader, null);
			} catch (IOException | InterruptedException | RuntimeException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
	}

	/** Downloads the file, checks its hash, and returns where it was saved (delete it after importing). */
	public static CompletableFuture<Path> download(Found found) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				URI uri = URI.create(found.url());
				if (!uri.getScheme().equals("https") || !(uri.getHost().equals("cdn.modrinth.com") || uri.getHost().endsWith(".modrinth.com"))) {
					throw new IOException("Not a Modrinth download: " + uri.getHost());
				}
				if (found.size() > MAX_BYTES) {
					throw new IOException("File is too large (" + found.size() / 1024 / 1024 + " MB)");
				}
				Files.createDirectories(DOWNLOADS);
				Path file = DOWNLOADS.resolve(found.fileName().replaceAll("[^a-zA-Z0-9._+-]", "_"));
				HttpResponse<InputStream> response = HTTP.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					throw new IOException("Download failed: HTTP " + response.statusCode());
				}
				MessageDigest digest = MessageDigest.getInstance("SHA-512");
				try (InputStream in = response.body()) {
					Path partial = DOWNLOADS.resolve(file.getFileName() + ".part");
					try (var out = Files.newOutputStream(partial)) {
						byte[] buffer = new byte[64 * 1024];
						long total = 0;
						int n;
						while ((n = in.read(buffer)) > 0) {
							total += n;
							if (total > MAX_BYTES) {
								throw new IOException("Download is larger than Modrinth said");
							}
							digest.update(buffer, 0, n);
							out.write(buffer, 0, n);
						}
					}
					String hash = HexFormat.of().formatHex(digest.digest());
					if (found.sha512() != null && !found.sha512().equalsIgnoreCase(hash)) {
						Files.deleteIfExists(partial);
						throw new IOException("The file's hash doesn't match Modrinth's; not using it");
					}
					Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
				}
				return file;
			} catch (IOException | InterruptedException | java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
	}

	private static JsonObject project(String modId) throws IOException, InterruptedException {
		JsonElement direct = get("/project/" + encode(modId));
		if (direct != null && direct.isJsonObject()) {
			return direct.getAsJsonObject();
		}
		// Mod ids often differ from Modrinth slugs (e.g. "computercraft" is "cc-tweaked"): search instead.
		JsonElement search = get("/search?limit=10&facets=" + encode("[[\"project_type:mod\"]]") + "&query=" + encode(modId.replace('_', ' ')));
		if (search == null) {
			return null;
		}
		JsonArray hits = search.getAsJsonObject().getAsJsonArray("hits");
		String wanted = normalize(modId);
		JsonObject best = null;
		for (JsonElement hit : hits) {
			JsonObject h = hit.getAsJsonObject();
			if (normalize(h.get("slug").getAsString()).equals(wanted) || normalize(h.get("title").getAsString()).equals(wanted)) {
				best = h;
				break;
			}
		}
		if (best == null && !hits.isEmpty()) {
			best = hits.get(0).getAsJsonObject();
		}
		if (best == null) {
			return null;
		}
		JsonElement project = get("/project/" + encode(best.get("project_id").getAsString()));
		return project != null && project.isJsonObject() ? project.getAsJsonObject() : null;
	}

	private static Found pick(JsonObject project, String loader, String gameVersion) throws IOException, InterruptedException {
		StringBuilder query = new StringBuilder("/project/" + encode(project.get("id").getAsString()) + "/version?");
		if (loader != null) {
			query.append("loaders=").append(encode("[\"" + loader + "\"]")).append('&');
		}
		if (gameVersion != null) {
			query.append("game_versions=").append(encode("[\"" + gameVersion + "\"]"));
		}
		JsonElement versions = get(query.toString());
		if (versions == null || !versions.isJsonArray() || versions.getAsJsonArray().isEmpty()) {
			return null;
		}
		JsonObject version = versions.getAsJsonArray().get(0).getAsJsonObject();
		JsonObject file = null;
		for (JsonElement f : version.getAsJsonArray("files")) {
			if (f.getAsJsonObject().get("primary").getAsBoolean() || file == null) {
				file = f.getAsJsonObject();
			}
		}
		if (file == null) {
			return null;
		}
		JsonObject hashes = file.getAsJsonObject("hashes");
		return new Found(project.get("title").getAsString(), project.get("slug").getAsString(), version.get("version_number").getAsString(),
			file.get("filename").getAsString(), file.get("url").getAsString(), file.get("size").getAsLong(),
			hashes != null && hashes.has("sha512") ? hashes.get("sha512").getAsString() : null,
			strings(version.getAsJsonArray("game_versions")), strings(version.getAsJsonArray("loaders")), gameVersion != null);
	}

	private static List<String> strings(JsonArray array) {
		return array == null ? List.of() : array.asList().stream().map(JsonElement::getAsString).toList();
	}

	private static JsonElement get(String path) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request(URI.create(API + path)), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() != 200) {
			throw new IOException("Modrinth answered HTTP " + response.statusCode());
		}
		return JsonParser.parseString(response.body());
	}

	private static HttpRequest request(URI uri) {
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
			.header("User-Agent", "phasenull/mimic/" + MimicBuild.commit() + " (github.com/phasenull/mimic)").build();
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static String loader(String serverKind) {
		if (serverKind == null) {
			return null;
		}
		return switch (serverKind.toLowerCase(Locale.ROOT)) {
			case "fabric" -> "fabric";
			case "neoforge" -> "neoforge";
			case "forge" -> "forge";
			default -> null;
		};
	}

	/** The server's Minecraft version: ViaFabricPlus's target if it translates, else this client's. */
	static String serverVersion() {
		try {
			Object connection = ConnectionDebug.trackedConnection();
			if (connection != null) {
				Object target = connection.getClass().getMethod("viaFabricPlus$getTargetVersion").invoke(connection);
				if (target != null) {
					String name = (String) target.getClass().getMethod("getName").invoke(target);
					// Via groups protocol-identical releases ("1.20-1.20.1"): the newest one is the usual target.
					return name.contains("-") ? name.substring(name.lastIndexOf('-') + 1) : name;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// No ViaFabricPlus: same version as this client.
		}
		return SharedConstants.getCurrentVersion().name();
	}
}
