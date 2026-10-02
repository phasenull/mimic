package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.MimicBuild;
import dev.phasenull.mimic.debug.FailedServers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A warning on the icon of servers whose last join failed in a way Mimic can't get past yet. */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerEntryMixin {
	@Unique
	private static final int SIZE = 11;

	@Shadow
	@Final
	private ServerData serverData;

	@Inject(method = "extractContent", at = @At("TAIL"))
	private void mimic$warn(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		FailedServers.Failure failure = FailedServers.get(serverData.ip);
		if (failure == null) {
			return;
		}
		ServerSelectionList.OnlineServerEntry self = (ServerSelectionList.OnlineServerEntry) (Object) this;
		// Bottom-right corner of the 32x32 server icon.
		int x = self.getContentX() + 32 - SIZE;
		int y = self.getContentY() + 32 - SIZE;
		g.fill(x, y, x + SIZE, y + SIZE, 0xFF000000);
		g.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0xFFFFAA00);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		g.text(font, "!", x + (SIZE - font.width("!")) / 2 + 1, y + 2, 0xFF000000, false);
		if (mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE) {
			boolean sameBuild = failure.build.equals(MimicBuild.commit());
			g.setTooltipForNextFrame(Component.literal("Mimic: last join failed: " + failure.reason + " (build " + failure.build + ")")
				.append(sameBuild ? "" : "\nMimic changed since (now " + MimicBuild.commit() + "), worth a retry"), mouseX, mouseY);
		}
	}
}
