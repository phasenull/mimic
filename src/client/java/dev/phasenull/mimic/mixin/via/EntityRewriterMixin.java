package dev.phasenull.mimic.mixin.via;

import dev.phasenull.mimic.bypass.ViaPassthrough;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity type ids: identity when the server synced its own entity type numbering (see {@link ViaPassthrough}). */
@Pseudo
@Mixin(targets = "com.viaversion.viaversion.rewriter.EntityRewriter", remap = false)
public abstract class EntityRewriterMixin {
	@Inject(method = "newEntityId", at = @At("HEAD"), cancellable = true)
	private void mimic$entityTypes(int id, CallbackInfoReturnable<Integer> cir) {
		if (ViaPassthrough.active("minecraft:entity_type")) {
			cir.setReturnValue(id);
		}
	}
}
