package dev.phasenull.mimic.mixin.via;

import dev.phasenull.mimic.bypass.ViaPassthrough;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ViaVersion's id tables: identity for registries the server synced itself (see {@link ViaPassthrough}). */
@Pseudo
@Mixin(targets = "com.viaversion.viaversion.api.data.MappingDataBase", remap = false)
public abstract class MappingDataBaseMixin {
	@Inject(method = {"getNewBlockStateId", "getNewBlockId", "getOldBlockId"}, at = @At("HEAD"), cancellable = true)
	private void mimic$blocks(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:block")) {
			cir.setReturnValue(id);
		}
	}

	@Inject(method = {"getNewItemId", "getOldItemId"}, at = @At("HEAD"), cancellable = true)
	private void mimic$items(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:item")) {
			cir.setReturnValue(id);
		}
	}

	@Inject(method = {"getNewSoundId", "getOldSoundId"}, at = @At("HEAD"), cancellable = true)
	private void mimic$sounds(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:sound_event")) {
			cir.setReturnValue(id);
		}
	}

	@Inject(method = "getNewParticleId", at = @At("HEAD"), cancellable = true)
	private void mimic$particles(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:particle_type")) {
			cir.setReturnValue(id);
		}
	}
}
