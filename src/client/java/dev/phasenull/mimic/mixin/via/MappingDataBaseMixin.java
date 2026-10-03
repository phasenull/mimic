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

	/**
	 * Via's sound rewriter reads the table directly (not through getNewSoundId), so it gets an identity
	 * table too; one without an upper bound, since the server's own (mod) sounds are numbered past the
	 * vanilla ones and an out-of-range id makes Via drop the packet.
	 */
	@Inject(method = "getSoundMappings", at = @At("HEAD"), cancellable = true)
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void mimic$soundTable(CallbackInfoReturnable cir) {
		if (ViaPassthrough.active("minecraft:sound_event")) {
			Object identity = mimic$identity(getClass().getClassLoader());
			if (identity != null) {
				cir.setReturnValue(identity);
			}
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static volatile Object mimic$identity;

	/** Via's IdentityMappings, made by reflection (Via is only on the runtime classpath). */
	@org.spongepowered.asm.mixin.Unique
	private static Object mimic$identity(ClassLoader loader) {
		Object identity = mimic$identity;
		if (identity == null) {
			try {
				identity = Class.forName("com.viaversion.viaversion.api.data.IdentityMappings", true, loader)
					.getConstructor(int.class, int.class).newInstance(Integer.MAX_VALUE, Integer.MAX_VALUE);
				mimic$identity = identity;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return identity;
	}

	@Inject(method = "getNewParticleId", at = @At("HEAD"), cancellable = true)
	private void mimic$particles(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:particle_type")) {
			cir.setReturnValue(id);
		}
	}
}
