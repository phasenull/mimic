package dev.phasenull.mimic.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("user")
	void mimic$setUser(User user);

	@Mutable
	@Accessor("userApiService")
	void mimic$setUserApiService(UserApiService service);

	@Mutable
	@Accessor("userPropertiesFuture")
	void mimic$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

	@Mutable
	@Accessor("profileFuture")
	void mimic$setProfileFuture(CompletableFuture<ProfileResult> future);

	@Mutable
	@Accessor("profileKeyPairManager")
	void mimic$setProfileKeyPairManager(ProfileKeyPairManager manager);

	@Accessor("reportingContext")
	void mimic$setReportingContext(ReportingContext context);
}
