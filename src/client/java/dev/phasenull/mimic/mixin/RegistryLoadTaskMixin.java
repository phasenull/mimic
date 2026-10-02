package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.bypass.RegistryStandIns;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.Stream;

/** Server-sent registry entries this client can't parse would fail the whole join; see {@link RegistryStandIns}. */
@Mixin(RegistryLoadTask.class)
public abstract class RegistryLoadTaskMixin<T> {
	@Shadow
	protected abstract ResourceKey<? extends Registry<T>> registryKey();

	@SuppressWarnings("rawtypes")
	@ModifyVariable(method = "registerElements", at = @At("HEAD"), argsOnly = true)
	private Stream mimic$standInForUnparsable(Stream elements) {
		if (!getClass().getName().equals("net.minecraft.resources.NetworkRegistryLoadTask")) {
			return elements;
		}
		return RegistryStandIns.apply(elements, registryKey().identifier().toString());
	}
}
