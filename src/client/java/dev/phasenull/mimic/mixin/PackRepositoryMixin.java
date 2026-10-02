package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.assets.AssetPacks;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/** Adds Mimic's asset packs to the client's resource packs (not to datapack repositories). */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
	@Mutable
	@Shadow
	@Final
	private Set<RepositorySource> sources;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void mimic$addAssetPacks(RepositorySource[] given, CallbackInfo ci) {
		if (sources.stream().anyMatch(s -> s instanceof ClientPackSource)) {
			Set<RepositorySource> all = new LinkedHashSet<>(sources);
			all.add(AssetPacks.source());
			sources = all;
		}
	}
}
