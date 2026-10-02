package dev.phasenull.mimic.placeholder;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/** Under the crosshair: what server-only block or entity the player is looking at. */
public final class PlaceholderHud {
	private static final int TITLE = 0xFFFF77FF;
	private static final int DETAIL = 0xFFCCCCCC;

	private PlaceholderHud() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
			Identifier.fromNamespaceAndPath("mimic", "placeholder_info"), PlaceholderHud::extract);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		List<String> lines = lines(client, client.hitResult);
		int y = g.guiHeight() / 2 + 14;
		for (int i = 0; i < lines.size(); i++) {
			g.centeredText(client.font, lines.get(i), g.guiWidth() / 2, y, i == 0 ? TITLE : DETAIL);
			y += client.font.lineHeight + 1;
		}
	}

	private static List<String> lines(Minecraft client, HitResult hit) {
		List<String> lines = new ArrayList<>();
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = client.level.getBlockState(pos);
			if (state.getBlock() instanceof PlaceholderBlock block) {
				int index = block.getStateDefinition().getPossibleStates().indexOf(state);
				lines.add("Server-only block: " + BuiltInRegistries.BLOCK.getKey(block));
				lines.add("state " + (index + 1) + "/" + block.guess().states() + ", " + block.guess().source());
			}
		} else if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof PlaceholderEntity entity) {
			lines.add("Server-only entity: " + entity.typeId());
			lines.add("#" + entity.getId() + " at " + entity.blockPosition().toShortString());
		}
		return lines;
	}
}
