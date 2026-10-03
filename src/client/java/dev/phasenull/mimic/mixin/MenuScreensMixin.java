package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.placeholder.PlaceholderMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's open_screen for a placeholder menu type: opened by Mimic (see PlaceholderMenus). */
@Mixin(MenuScreens.class)
public abstract class MenuScreensMixin {
	@Inject(method = "create", at = @At("HEAD"), cancellable = true)
	private static void mimic$placeholder(MenuType<?> type, Minecraft client, int containerId, Component title, CallbackInfo ci) {
		if (PlaceholderMenus.isPlaceholder(type)) {
			PlaceholderMenus.open(type, client, containerId, title);
			ci.cancel();
		}
	}
}
