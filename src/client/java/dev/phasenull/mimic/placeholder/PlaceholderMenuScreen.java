package dev.phasenull.mimic.placeholder;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** A plain grid for {@link PlaceholderMenu}: panel, slot frames, title, and a note when slots don't fit. */
public final class PlaceholderMenuScreen extends AbstractContainerScreen<PlaceholderMenu> {
	private static final int PANEL = 0xFFC6C6C6;
	private static final int EDGE_LIGHT = 0xFFFFFFFF;
	private static final int EDGE_DARK = 0xFF555555;
	private static final int SLOT = 0xFF8B8B8B;
	private static final int SLOT_DARK = 0xFF373737;

	public PlaceholderMenuScreen(PlaceholderMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 222);
		inventoryLabelY = 128;
	}

	@Override
	public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, EDGE_LIGHT);
		g.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, EDGE_LIGHT);
		g.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, EDGE_DARK);
		g.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, EDGE_DARK);
		for (Slot slot : menu.slots) {
			if (slot.x < 0) {
				continue;
			}
			int x = leftPos + slot.x - 1;
			int y = topPos + slot.y - 1;
			g.fill(x, y, x + 18, y + 18, SLOT_DARK);
			g.fill(x + 1, y + 1, x + 18, y + 18, EDGE_LIGHT);
			g.fill(x + 1, y + 1, x + 17, y + 17, SLOT);
		}
		super.extractContents(g, mouseX, mouseY, partialTick);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		int hidden = menu.containerSlots() - PlaceholderMenu.SHOWN;
		String note = menu.containerSlots() == 0 ? "loading..." : hidden > 0 ? "+" + hidden + " slots hidden" : "plain view";
		g.text(font, note, imageWidth - 8 - font.width(note), inventoryLabelY, 0xFF7A3F00, false);
	}
}
