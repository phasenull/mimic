package dev.phasenull.mimic.devauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Real Microsoft login for the dev client only. Enabled when a Microsoft app client ID is set via
 * the MIMIC_DEVAUTH_CLIENT_ID env var or the mimic.devauth.clientId system property.
 */
public final class DevAuth {
	private static final Logger LOGGER = LoggerFactory.getLogger("mimic/devauth");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Kept in the user's home, never in the project, so tokens can't end up in git.
	private static final Path CACHE = Path.of(System.getProperty("user.home"), ".mimic", "devauth.json");
	private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000L;
	private static final Set<String> SESSION_ARGS = Set.of("--username", "--uuid", "--accessToken", "--xuid", "--userType");

	private static class Cache {
		String clientId;
		String refreshToken;
		String mcAccessToken;
		long mcExpiresAt;
		String uuid;
		String name;
	}

	private DevAuth() {}

	public static String[] apply(String[] args) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return args;
		}
		String clientId = clientId();
		if (clientId == null) {
			LOGGER.info("Dev auth off (set MIMIC_DEVAUTH_CLIENT_ID to sign in with Microsoft)");
			return args;
		}
		try {
			MicrosoftAuth.McSession session = login(clientId);
			LOGGER.info("Signed in as {}", session.name());
			return withSession(args, session);
		} catch (Exception e) {
			LOGGER.error("Microsoft sign-in failed, starting offline: {}", e.getMessage());
			return args;
		}
	}

	private static String clientId() {
		String id = System.getProperty("mimic.devauth.clientId");
		if (id == null || id.isBlank()) {
			id = System.getenv("MIMIC_DEVAUTH_CLIENT_ID");
		}
		return id == null || id.isBlank() ? null : id.trim();
	}

	private static MicrosoftAuth.McSession login(String clientId) throws IOException {
		MicrosoftAuth auth = new MicrosoftAuth(clientId);
		Cache cache = readCache();
		if (cache != null && !clientId.equals(cache.clientId)) {
			cache = null;
		}

		if (cache != null && cache.mcAccessToken != null && cache.mcExpiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
			return new MicrosoftAuth.McSession(cache.mcAccessToken, cache.mcExpiresAt, cache.uuid, cache.name);
		}

		MicrosoftAuth.MsTokens ms = null;
		if (cache != null && cache.refreshToken != null) {
			try {
				ms = auth.refresh(cache.refreshToken);
			} catch (IOException e) {
				LOGGER.warn("Saved Microsoft login expired, signing in again");
			}
		}
		if (ms == null) {
			MicrosoftAuth.DeviceCode code = auth.requestDeviceCode();
			announce(code);
			ms = auth.pollDeviceCode(code);
		}

		MicrosoftAuth.McSession session = auth.minecraftLogin(ms.accessToken(), LOGGER::info);
		Cache fresh = new Cache();
		fresh.clientId = clientId;
		fresh.refreshToken = ms.refreshToken();
		fresh.mcAccessToken = session.accessToken();
		fresh.mcExpiresAt = session.expiresAtMillis();
		fresh.uuid = session.uuid();
		fresh.name = session.name();
		writeCache(fresh);
		return session;
	}

	private static void announce(MicrosoftAuth.DeviceCode code) {
		String line = "=".repeat(64);
		LOGGER.warn(line);
		LOGGER.warn("MICROSOFT SIGN-IN: open {} and enter code {}", code.verificationUri(), code.userCode());
		LOGGER.warn(line);
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			try {
				new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", code.verificationUri()).start();
			} catch (IOException ignored) {
				// The code is in the log either way.
			}
		}
	}

	private static String[] withSession(String[] args, MicrosoftAuth.McSession session) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (SESSION_ARGS.contains(args[i])) {
				i++;
				continue;
			}
			out.add(args[i]);
		}
		out.addAll(List.of("--username", session.name(), "--uuid", session.uuid(), "--accessToken", session.accessToken()));
		return out.toArray(String[]::new);
	}

	private static Cache readCache() {
		try {
			return Files.exists(CACHE) ? GSON.fromJson(Files.readString(CACHE), Cache.class) : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static void writeCache(Cache cache) {
		try {
			Files.createDirectories(CACHE.getParent());
			Files.writeString(CACHE, GSON.toJson(cache));
		} catch (IOException e) {
			LOGGER.warn("Could not save dev auth cache: {}", e.getMessage());
		}
	}
}
