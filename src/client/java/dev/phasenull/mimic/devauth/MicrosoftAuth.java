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

/**
 * Minecraft Microsoft authentication:
 *
 * Microsoft Live OAuth -> Xbox Live -> XSTS -> Minecraft Services.
 *
 * This uses the Minecraft Java Edition client ID and the official
 * login.live.com OAuth endpoints.
 */
public final class MicrosoftAuth {
	private static final String CLIENT_ID = "00000000402b5328";

	private static final String REDIRECT_URI =
		"https://login.live.com/oauth20_desktop.srf";

	private static final String SCOPE =
		"service::user.auth.xboxlive.com::MBI_SSL";

	private static final String AUTHORIZE_ENDPOINT =
		"https://login.live.com/oauth20_authorize.srf";

	private static final String TOKEN_ENDPOINT =
		"https://login.live.com/oauth20_token.srf";

	private static final String XBL_ENDPOINT =
		"https://user.auth.xboxlive.com/user/authenticate";

	private static final String XSTS_ENDPOINT =
		"https://xsts.auth.xboxlive.com/xsts/authorize";

	private static final String MC_LOGIN_ENDPOINT =
		"https://api.minecraftservices.com/authentication/login_with_xbox";

	private static final String MC_PROFILE_ENDPOINT =
		"https://api.minecraftservices.com/minecraft/profile";

	private static final Gson GSON = new Gson();

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	public record MsTokens(
		String accessToken,
		String refreshToken
	) {}

	public record McSession(
		String accessToken,
		long expiresAtMillis,
		String uuid,
		String name
	) {}

	/**
	 * Kept for compatibility with DevAuth.
	 *
	 * The authorization-code flow does not use Microsoft's device-code
	 * endpoint, so this record is retained only as a compatibility type.
	 */
	public record DeviceCode(
		String deviceCode,
		String userCode,
		String verificationUri,
		String message,
		int intervalSeconds,
		int expiresInSeconds
	) {}

	MicrosoftAuth(String ignoredClientId) {
		// The Minecraft client ID is fixed.
		//
		// We intentionally don't use a caller-provided Entra client ID here.
	}

	/**
	 * Builds the official Minecraft Microsoft login URL.
	 *
	 * The user opens this URL in a browser and signs in.
	 */
	String authorizationUrl() {
		return AUTHORIZE_ENDPOINT
			+ "?client_id=" + encode(CLIENT_ID)
			+ "&response_type=code"
			+ "&redirect_uri=" + encode(REDIRECT_URI)
			+ "&scope=" + encode(SCOPE)
			+ "&display=touch"
			+ "&locale=en";
	}

	/**
	 * Extracts the OAuth authorization code from the redirected URL.
	 *
	 * Expected redirect:
	 *
	 * https://login.live.com/oauth20_desktop.srf?code=...&lc=1033
	 */
	MsTokens exchangeAuthorizationCode(String redirectUrl) throws IOException {
		String code = extractQueryParameter(redirectUrl, "code");

		if (code == null || code.isBlank()) {
			String error = extractQueryParameter(redirectUrl, "error");
			String description = extractQueryParameter(redirectUrl, "error_description");

			if (error != null) {
				throw new IOException(
					"Microsoft login failed: " + error
						+ (description != null ? " - " + description : "")
				);
			}

			throw new IOException(
				"No authorization code found in redirect URL"
			);
		}

		return tokens(postForm(
			TOKEN_ENDPOINT,
			Map.of(
				"client_id", CLIENT_ID,
				"code", code,
				"grant_type", "authorization_code",
				"redirect_uri", REDIRECT_URI,
				"scope", SCOPE
			),
			true
		));
	}

	/**
	 * Refreshes a Microsoft Live OAuth token.
	 */
	MsTokens refresh(String refreshToken) throws IOException {
		return tokens(postForm(
			TOKEN_ENDPOINT,
			Map.of(
				"client_id", CLIENT_ID,
				"refresh_token", refreshToken,
				"grant_type", "refresh_token",
				"scope", SCOPE
			),
			true
		));
	}

	/**
	 * Converts the Microsoft Live access token into a Minecraft session.
	 */
	McSession minecraftLogin(
		String msAccessToken,
		Consumer<String> status
	) throws IOException {

		status.accept("Signing in to Xbox Live");

		JsonObject xbl = postJson(
			XBL_ENDPOINT,
			"""
			{
			  "Properties": {
			    "AuthMethod": "RPS",
			    "SiteName": "user.auth.xboxlive.com",
			    "RpsTicket": "%s"
			  },
			  "RelyingParty": "http://auth.xboxlive.com",
			  "TokenType": "JWT"
			}
			""".formatted(msAccessToken)
		);

		String xblToken = xbl.get("Token").getAsString();

		status.accept("Authorizing Xbox Live account");

		JsonObject xsts = postJson(
			XSTS_ENDPOINT,
			"""
			{
			  "Properties": {
			    "SandboxId": "RETAIL",
			    "UserTokens": [%s]
			  },
			  "RelyingParty": "rp://api.minecraftservices.com/",
			  "TokenType": "JWT"
			}
			""".formatted(GSON.toJson(xblToken))
		);

		String xstsToken = xsts.get("Token").getAsString();

		JsonArray xui = xsts
			.getAsJsonObject("DisplayClaims")
			.getAsJsonArray("xui");

		if (xui == null || xui.isEmpty()) {
			throw new IOException("Xbox Live response did not contain a user hash");
		}

		String uhs = xui
			.get(0)
			.getAsJsonObject()
			.get("uhs")
			.getAsString();

		status.accept("Signing in to Minecraft services");

		JsonObject mc = postJson(
			MC_LOGIN_ENDPOINT,
			GSON.toJson(
				Map.of(
					"identityToken",
					"XBL3.0 x=" + uhs + ";" + xstsToken
				)
			)
		);

		String mcToken = mc.get("access_token").getAsString();

		long expiresAt =
			System.currentTimeMillis()
				+ mc.get("expires_in").getAsLong() * 1000L;

		return sessionFromMinecraftToken(mcToken, expiresAt);
	}

	/**
	 * Builds a session from a Minecraft Services access token.
	 */
	static McSession sessionFromMinecraftToken(
		String mcToken,
		long expiresAt
	) throws IOException {

		HttpRequest profileReq = HttpRequest.newBuilder(
				URI.create(MC_PROFILE_ENDPOINT)
			)
			.header("Authorization", "Bearer " + mcToken)
			.header("Accept", "application/json")
			.GET()
			.build();

		HttpResponse<String> profileRes = send(profileReq);

		if (profileRes.statusCode() == 401) {
			throw new IOException(
				"Token rejected (expired or not a Minecraft access token)"
			);
		}

		if (profileRes.statusCode() == 404) {
			throw new IOException(
				"This account does not own Minecraft Java Edition"
			);
		}

		JsonObject profile = parse(profileRes, true);

		return new McSession(
			mcToken,
			expiresAt,
			dashed(profile.get("id").getAsString()),
			profile.get("name").getAsString()
		);
	}

	private static MsTokens tokens(JsonObject res) throws IOException {
		if (!res.has("access_token")) {
			throw new IOException(
				"Microsoft token response did not contain access_token: "
					+ res
			);
		}

		String accessToken = res.get("access_token").getAsString();

		String refreshToken =
			res.has("refresh_token")
				? res.get("refresh_token").getAsString()
				: null;

		return new MsTokens(accessToken, refreshToken);
	}

	private static String extractQueryParameter(
		String url,
		String parameter
	) {

		try {
			URI uri = URI.create(url);

			String query = uri.getRawQuery();

			if (query == null) {
				return null;
			}

			for (String pair : query.split("&")) {
				int equals = pair.indexOf('=');

				if (equals < 0) {
					continue;
				}

				String key = decode(pair.substring(0, equals));

				if (!parameter.equals(key)) {
					continue;
				}

				return decode(pair.substring(equals + 1));
			}

			return null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(
			value,
			StandardCharsets.UTF_8
		);
	}

	private static String decode(String value) {
		return java.net.URLDecoder.decode(
			value,
			StandardCharsets.UTF_8
		);
	}

	private static JsonObject postForm(
		String url,
		Map<String, String> form,
		boolean requireOk
	) throws IOException {

		String body = form.entrySet()
			.stream()
			.map(e ->
				encode(e.getKey())
					+ "="
					+ encode(e.getValue())
			)
			.collect(Collectors.joining("&"));

		HttpRequest req = HttpRequest.newBuilder(
				URI.create(url)
			)
			.header(
				"Content-Type",
				"application/x-www-form-urlencoded"
			)
			.header("Accept", "application/json")
			.POST(
				HttpRequest.BodyPublishers.ofString(body)
			)
			.build();

		return parse(send(req), requireOk);
	}

	private static JsonObject postJson(
		String url,
		String json
	) throws IOException {

		HttpRequest req = HttpRequest.newBuilder(
				URI.create(url)
			)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(
				HttpRequest.BodyPublishers.ofString(json)
			)
			.build();

		HttpResponse<String> res = send(req);

		if (
			url.contains("xsts")
			&& res.statusCode() == 401
		) {
			throw new IOException(
				xstsError(res.body())
			);
		}

		return parse(res, true);
	}

	private static String xstsError(String body) {
		try {
			JsonObject obj =
				GSON.fromJson(body, JsonObject.class);

			long code =
				obj.get("XErr").getAsLong();

			if (code == 2148916233L) {
				return "This Microsoft account has no Xbox profile; "
					+ "sign in once at minecraft.net first";
			}

			if (code == 2148916235L) {
				return "Xbox Live is not available in this account's country";
			}

			if (code == 2148916238L) {
				return "Child account: an adult must add it to a Microsoft family first";
			}

			return "Xbox authorization failed (XErr " + code + ")";
		} catch (Exception e) {
			return "Xbox authorization failed";
		}
	}

	private static HttpResponse<String> send(
		HttpRequest req
	) throws IOException {

		try {
			return HTTP.send(
				req,
				HttpResponse.BodyHandlers.ofString()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(
				"Interrupted",
				e
			);
		}
	}

	private static JsonObject parse(
		HttpResponse<String> res,
		boolean requireOk
	) throws IOException {

		if (
			requireOk
			&& res.statusCode() / 100 != 2
		) {
			throw new IOException(
				"HTTP "
					+ res.statusCode()
					+ " from "
					+ res.uri().getHost()
					+ ": "
					+ res.body()
			);
		}

		JsonObject obj =
			GSON.fromJson(
				res.body(),
				JsonObject.class
			);

		return obj != null
			? obj
			: new JsonObject();
	}

	private static String dashed(String id) {
		if (
			id.contains("-")
			|| id.length() != 32
		) {
			return id;
		}

		return id.substring(0, 8)
			+ "-"
			+ id.substring(8, 12)
			+ "-"
			+ id.substring(12, 16)
			+ "-"
			+ id.substring(16, 20)
			+ "-"
			+ id.substring(20);
	}
}
