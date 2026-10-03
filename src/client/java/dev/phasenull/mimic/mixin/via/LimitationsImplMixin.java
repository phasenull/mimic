package dev.phasenull.mimic.mixin.via;

import dev.phasenull.mimic.bypass.ViaPassthrough;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ViaFabricPlus hides items that don't exist in the server's version (creative tabs, and so JEI). Items the
 * server listed in its registry sync exist there, e.g. a backport mod's items on an older version.
 */
@Pseudo
@Mixin(targets = "com.viaversion.viafabricplus.protocoltranslator.LimitationsImpl", remap = false)
public abstract class LimitationsImplMixin {
	@Inject(method = "itemExistsInConnection", at = @At("HEAD"), cancellable = true)
	private void mimic$serverHasIt(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (ViaPassthrough.serverHasItem(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
			cir.setReturnValue(true);
		}
	}
}
