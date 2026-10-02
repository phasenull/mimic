package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.MimicClient;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/** Drops tags for registries this client doesn't have (added by server mods); vanilla would crash on them. */
@Mixin(ClientboundUpdateTagsPacket.class)
public abstract class ClientboundUpdateTagsPacketMixin {
	@Inject(method = "tags", at = @At("RETURN"), cancellable = true)
	private void mimic$dropUnknownRegistries(
			CallbackInfoReturnable<Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>> cir) {
		Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = cir.getReturnValue();
		Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> known = new HashMap<>();
		tags.forEach((key, payload) -> {
			if (BuiltInRegistries.REGISTRY.containsKey(key.identifier()) || RegistrySynchronization.isNetworkable(key)) {
				known.put(key, payload);
			}
		});
		if (known.size() != tags.size()) {
			MimicClient.LOGGER.debug("Dropped tags for {} unknown registries", tags.size() - known.size());
			cir.setReturnValue(known);
		}
	}
}
