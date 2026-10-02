package dev.phasenull.mimic.devauth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Microsoft device-code login -> Xbox Live -> XSTS -> Minecraft services. */
final class MicrosoftAuth {
	private static final String MS_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
	private static final String SCOPE = "XboxLive.signin offline_access";
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	record MsTokens(String accessToken, String refreshToken) {}

	record McSession(String accessToken, long expiresAtMillis, String uuid, String name) {}

	record DeviceCode(String deviceCode, String userCode, String verificationUri, String message, int intervalSeconds, int expiresInSeconds) {}

	private final String clientId;

	MicrosoftAuth(String clientId) {
		this.clientId = clientId;
	}

	DeviceCode requestDeviceCode() throws IOException {
		JsonObject res = postForm(MS_BASE + "devicecode", Map.of("client_id", clientId, "scope", SCOPE), true);
		return new DeviceCode(
			res.get("device_code").getAsString(),
			res.get("user_code").getAsString(),
			res.get("verification_uri").getAsString(),
			res.has("message") ? res.get("message").getAsString() : null,
			res.has("interval") ? res.get("interval").getAsInt() : 5,
			res.get("expires_in").getAsInt());
	}

	MsTokens pollDeviceCode(DeviceCode code) throws IOException {
		long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
		int interval = code.intervalSeconds();
		while (System.currentTimeMillis() < deadline) {
			sleep(interval);
			JsonObject res = postForm(MS_BASE + "token", Map.of(
				"grant_type", "urn:ietf:params:oauth:grant-type:device_code",
				"client_id", clientId,
				"device_code", code.deviceCode()), false);
			if (res.has("access_token")) {
				return tokens(res);
			}
			String error = res.has("error") ? res.get("error").getAsString() : "unknown";
			switch (error) {
				case "authorization_pending" -> {}
				case "slow_down" -> interval += 5;
				default -> throw new IOException("Microsoft login failed: " + error
					+ (res.has("error_description") ? " - " + res.get("error_description").getAsString() : ""));
			}
		}
		throw new IOException("Microsoft login timed out");
	}

	MsTokens refresh(String refreshToken) throws IOException {
		return tokens(postForm(MS_BASE + "token", Map.of(
			"grant_type", "refresh_token",
			"client_id", clientId,
			"refresh_token", refreshToken,
			"scope", SCOPE), true));
	}

	McSession minecraftLogin(String msAccessToken, Consumer<String> status) throws IOException {
		status.accept("Signing in to Xbox Live");
		JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", """
			{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=%s"},
			 "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""".formatted(msAccessToken));
		String xblToken = xbl.get("Token").getAsString();

		JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", """
			{"Properties":{"SandboxId":"RETAIL","UserTokens":[%s]},
			 "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}""".formatted(GSON.toJson(xblToken)));
		String xstsToken = xsts.get("Token").getAsString();
		JsonArray xui = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui");
		String uhs = xui.get(0).getAsJsonObject().get("uhs").getAsString();

		status.accept("Signing in to Minecraft services");
		JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
			GSON.toJson(Map.of("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken)));
		String mcToken = mc.get("access_token").getAsString();
		long expiresAt = System.currentTimeMillis() + mc.get("expires_in").getAsLong() * 1000L;

		HttpRequest profileReq = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
			.header("Authorization", "Bearer " + mcToken).GET().build();
		HttpResponse<String> profileRes = send(profileReq);
		if (profileRes.statusCode() == 404) {
			throw new IOException("This Microsoft account does not own Minecraft Java Edition");
		}
		JsonObject profile = parse(profileRes, true);
		return new McSession(mcToken, expiresAt, dashed(profile.get("id").getAsString()), profile.get("name").getAsString());
	}

	private static MsTokens tokens(JsonObject res) {
		return new MsTokens(res.get("access_token").getAsString(), res.get("refresh_token").getAsString());
	}

	private static String dashed(String id) {
		if (id.contains("-") || id.length() != 32) {
			return id;
		}
		return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
	}

	private JsonObject postForm(String url, Map<String, String> form, boolean requireOk) throws IOException {
		String body = form.entrySet().stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return parse(send(req), requireOk);
	}

	private JsonObject postJson(String url, String json) throws IOException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		HttpResponse<String> res = send(req);
		if (url.contains("xsts") && res.statusCode() == 401) {
			throw new IOException(xstsError(res.body()));
		}
		return parse(res, true);
	}

	private static String xstsError(String body) {
		try {
			long code = GSON.fromJson(body, JsonObject.class).get("XErr").getAsLong();
			if (code == 2148916233L) return "This Microsoft account has no Xbox profile; sign in once at minecraft.net first";
			if (code == 2148916235L) return "Xbox Live is not available in this account's country";
			if (code == 2148916238L) return "Child account: an adult must add it to a Microsoft family first";
			return "Xbox authorization failed (XErr " + code + ")";
		} catch (Exception e) {
			return "Xbox authorization failed";
		}
	}

	private static HttpResponse<String> send(HttpRequest req) throws IOException {
		try {
			return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static JsonObject parse(HttpResponse<String> res, boolean requireOk) throws IOException {
		if (requireOk && res.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + res.statusCode() + " from " + res.uri().getHost() + ": " + res.body());
		}
		JsonObject obj = GSON.fromJson(res.body(), JsonObject.class);
		return obj != null ? obj : new JsonObject();
	}

	private static void sleep(int seconds) throws IOException {
		try {
			Thread.sleep(seconds * 1000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}
}
