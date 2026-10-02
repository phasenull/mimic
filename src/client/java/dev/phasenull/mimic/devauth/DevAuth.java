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
import java.util.Set;
import java.util.function.Consumer;

/**
 * Real Microsoft login for the dev client only. First sign-in happens in the in-game Account screen
 * (browser sign-in, then paste the redirect URL); later launches reuse the saved login.
 */
public final class DevAuth {
	private static final Logger LOGGER = LoggerFactory.getLogger("mimic/devauth");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Kept in the user's home, never in the project, so tokens can't end up in git.
	private static final Path CACHE = Path.of(System.getProperty("user.home"), ".mimic", "devauth.json");
	private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000L;
	private static final Set<String> SESSION_ARGS = Set.of("--username", "--uuid", "--accessToken", "--xuid", "--userType");

	private static class Cache {
		String refreshToken;
		String mcAccessToken;
		long mcExpiresAt;
		String uuid;
		String name;
	}

	static volatile boolean microsoftSession;

	private DevAuth() {}

	public static boolean isDev() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	public static boolean isMicrosoftSession() {
		return microsoftSession;
	}

	/** Launch-time hook: swaps the dev account's session args for the saved Microsoft login, if any. */
	public static String[] apply(String[] args) {
		if (!isDev()) {
			return args;
		}
		if (!hasSavedLogin()) {
			LOGGER.info("No saved Microsoft login; use the Account button on the title screen to sign in");
			return args;
		}
		try {
			MicrosoftAuth.McSession session = signInSaved(LOGGER::info);
			LOGGER.info("Signed in as {}", session.name());
			microsoftSession = true;
			return withSession(args, session);
		} catch (Exception e) {
			LOGGER.error("Microsoft sign-in failed, starting offline: {}", e.getMessage());
			return args;
		}
	}

	/** URL to open in the browser. {@code chooseAccount} forces Microsoft's account picker. */
	public static String authorizationUrl(boolean chooseAccount) {
		String url = auth().authorizationUrl();
		return chooseAccount ? url + "&prompt=select_account" : url;
	}

	/** Blocking. Finishes a browser sign-in from the URL the browser was redirected to. */
	public static MicrosoftAuth.McSession finishSignIn(String redirectUrl, Consumer<String> status) throws IOException {
		requireDev();
		status.accept("Exchanging sign-in code");
		MicrosoftAuth auth = auth();
		MicrosoftAuth.MsTokens ms = auth.exchangeAuthorizationCode(redirectUrl.trim());
		return loginAndSave(auth, ms, status);
	}

	/** Blocking. Uses the saved Minecraft token, or refreshes it through the saved Microsoft login. */
	public static MicrosoftAuth.McSession signInSaved(Consumer<String> status) throws IOException {
		requireDev();
		Cache cache = readCache();
		if (cache == null) {
			throw new IOException("No saved login");
		}
		if (cache.mcAccessToken != null && cache.mcExpiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
			return new MicrosoftAuth.McSession(cache.mcAccessToken, cache.mcExpiresAt, cache.uuid, cache.name);
		}
		if (cache.refreshToken == null) {
			throw new IOException("Saved login expired; sign in again");
		}
		status.accept("Refreshing saved login");
		MicrosoftAuth auth = auth();
		return loginAndSave(auth, auth.refresh(cache.refreshToken), status);
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

	private static MicrosoftAuth auth() {
		return new MicrosoftAuth(null);
	}

	private static void requireDev() throws IOException {
		if (!isDev()) {
			throw new IOException("Dev auth only works in development sessions");
		}
	}

	private static MicrosoftAuth.McSession loginAndSave(MicrosoftAuth auth, MicrosoftAuth.MsTokens ms, Consumer<String> status) throws IOException {
		MicrosoftAuth.McSession session = auth.minecraftLogin(ms.accessToken(), status);
		Cache fresh = new Cache();
		fresh.refreshToken = ms.refreshToken();
		fresh.mcAccessToken = session.accessToken();
		fresh.mcExpiresAt = session.expiresAtMillis();
		fresh.uuid = session.uuid();
		fresh.name = session.name();
		writeCache(fresh);
		return session;
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
