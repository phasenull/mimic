package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.devauth.DevAuth;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Main.class)
public abstract class MainMixin {
	@ModifyVariable(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
	private static String[] mimic$devAuth(String[] args) {
		return DevAuth.apply(args);
	}
}
