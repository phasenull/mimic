package dev.phasenull.mimic.devauth;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import dev.phasenull.mimic.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Swaps the running client's account. Main thread only, and only outside a world. */
public final class SessionSwitcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("mimic/devauth");

	private SessionSwitcher() {}

	public static void useMicrosoft(Minecraft mc, MicrosoftAuth.McSession session) {
		User user = new User(session.name(), UUID.fromString(session.uuid()), session.accessToken(), Optional.empty(), Optional.empty());
		UserApiService api = MinecraftServicesDiscoveryService.create(mc.getProxy(), true).createUserApiService(session.accessToken());
		apply(mc, user, api);
		DevAuth.microsoftSession = true;
	}

	public static void useOffline(Minecraft mc, String name) {
		User user = new User(name, UUIDUtil.createOfflinePlayerUUID(name), "0", Optional.empty(), Optional.empty());
		apply(mc, user, UserApiService.OFFLINE);
		DevAuth.microsoftSession = false;
	}

	private static void apply(Minecraft mc, User user, UserApiService api) {
		if (mc.level != null) {
			throw new IllegalStateException("Can't switch accounts while in a world");
		}
		MinecraftAccessor access = (MinecraftAccessor) mc;
		access.mimic$setUser(user);
		access.mimic$setUserApiService(api);
		access.mimic$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
			try {
				return api.fetchProperties();
			} catch (Exception e) {
				return UserApiService.OFFLINE_PROPERTIES;
			}
		}, Util.nonCriticalIoPool()));
		access.mimic$setProfileFuture(CompletableFuture.supplyAsync(
			() -> mc.services().sessionService().fetchProfile(user.getProfileId(), true), Util.nonCriticalIoPool()));
		access.mimic$setProfileKeyPairManager(ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath()));
		access.mimic$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));
		LOGGER.info("Switched account to {}", user.getName());
	}
}
