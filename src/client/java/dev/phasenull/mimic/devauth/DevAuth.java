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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Real Microsoft login for the dev client only. Enabled when a Microsoft app client ID is set via
 * the MIMIC_DEVAUTH_CLIENT_ID env var or the mimic.devauth.clientId system property.
 */
public final class DevAuth {
	private static final Logger LOGGER = LoggerFactory.getLogger("mimic/devauth");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Kept in the user's home, never in the project, so tokens can't end up in git.
	private static final Path CACHE = Path.of(System.getProperty("user.home"), ".mimic", "devauth.json");
	private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".mimic", "token.txt");
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

	static volatile boolean microsoftSession;

	private DevAuth() {}

	public static boolean isMicrosoftSession() {
		return microsoftSession;
	}

	public static boolean isDev() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	/** A Minecraft access token the user supplied directly (env var, system property, or ~/.mimic/token.txt). */
	public static String providedToken() {
		String token = System.getProperty("mimic.devauth.token");
		if (token == null || token.isBlank()) {
			token = System.getenv("MIMIC_DEVAUTH_TOKEN");
		}
		if ((token == null || token.isBlank()) && Files.exists(TOKEN_FILE)) {
			try {
				token = Files.readString(TOKEN_FILE).trim();
			} catch (IOException e) {
				LOGGER.warn("Could not read {}: {}", TOKEN_FILE, e.getMessage());
			}
		}
		return token == null || token.isBlank() ? null : token.trim();
	}

	/** Validates a user-supplied Minecraft token and returns its session. Not cached (can't be refreshed). */
	public static MicrosoftAuth.McSession signInWithToken(String mcToken) throws IOException {
		if (!isDev()) {
			throw new IOException("Dev auth only works in development sessions");
		}
		return MicrosoftAuth.sessionFromMinecraftToken(mcToken, 0L);
	}

	public static String clientId() {
		String id = System.getProperty("mimic.devauth.clientId");
		if (id == null || id.isBlank()) {
			id = System.getenv("MIMIC_DEVAUTH_CLIENT_ID");
		}
		return id == null || id.isBlank() ? null : id.trim();
	}

	/** Launch-time hook: swaps the dev account's session args for a Microsoft session when configured. */
	public static String[] apply(String[] args) {
		if (!isDev()) {
			return args;
		}
		String token = providedToken();
		if (token == null && clientId() == null) {
			LOGGER.info("Dev auth off (provide MIMIC_DEVAUTH_TOKEN, or MIMIC_DEVAUTH_CLIENT_ID to sign in with Microsoft)");
			return args;
		}
		try {
			MicrosoftAuth.McSession session = token != null
				? signInWithToken(token)
				: signIn(false, DevAuth::announce, LOGGER::info, () -> false);
			LOGGER.info("Signed in as {}", session.name());
			microsoftSession = true;
			return withSession(args, session);
		} catch (Exception e) {
			LOGGER.error("Dev sign-in failed, starting offline: {}", e.getMessage());
			return args;
		}
	}

	/**
	 * Blocking. Reuses the saved login unless {@code forceNew}; otherwise runs the device-code flow,
	 * handing the code to {@code onCode}.
	 */
	public static MicrosoftAuth.McSession signIn(boolean forceNew, Consumer<MicrosoftAuth.DeviceCode> onCode,
			Consumer<String> status, BooleanSupplier cancelled) throws IOException {
		if (!isDev()) {
			throw new IOException("Dev auth only works in development sessions");
		}
		String clientId = clientId();
		if (clientId == null) {
			throw new IOException("Set MIMIC_DEVAUTH_CLIENT_ID to sign in with Microsoft");
		}
		MicrosoftAuth auth = new MicrosoftAuth(clientId);
		Cache cache = forceNew ? null : readCache();
		if (cache != null && !clientId.equals(cache.clientId)) {
			cache = null;
		}

		if (cache != null && cache.mcAccessToken != null && cache.mcExpiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
			return new MicrosoftAuth.McSession(cache.mcAccessToken, cache.mcExpiresAt, cache.uuid, cache.name);
		}

		MicrosoftAuth.MsTokens ms = null;
		if (cache != null && cache.refreshToken != null) {
			try {
				status.accept("Refreshing saved login");
				ms = auth.refresh(cache.refreshToken);
			} catch (IOException e) {
				LOGGER.warn("Saved Microsoft login expired, signing in again");
			}
		}
		if (ms == null) {
			status.accept("Requesting sign-in code");
			MicrosoftAuth.DeviceCode code = auth.requestDeviceCode();
			onCode.accept(code);
			ms = auth.pollDeviceCode(code, cancelled);
		}

		MicrosoftAuth.McSession session = auth.minecraftLogin(ms.accessToken(), status);
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

	public static boolean hasSavedLogin() {
		return Files.exists(CACHE);
	}

	public static void forgetSavedLogin() {
		try {
			Files.deleteIfExists(CACHE);
		} catch (IOException e) {
			LOGGER.warn("Could not delete saved login: {}", e.getMessage());
		}
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
