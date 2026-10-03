package dev.phasenull.mimic.placeholder;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A server-only mod's container (a backpack, a machine), shown as a plain grid: the server's container
 * slots, then the player's inventory. The slot count isn't known until the server sends the contents,
 * so slots are made then, assuming the usual layout (the container's slots first, the player's 36 last).
 * Clicks go to the server as usual; it decides what happens and sends the result back.
 */
public final class PlaceholderMenu extends AbstractContainerMenu {
	public static final int PLAYER_SLOTS = 36;
	/** Container slots shown; any beyond this still exist (so the server's slot numbers line up) but off screen. */
	public static final int SHOWN = 54;

	private final Inventory inventory;
	private int containerSlots = -1;

	public PlaceholderMenu(MenuType<?> type, int containerId, Inventory inventory) {
		super(type, containerId);
		this.inventory = inventory;
	}

	public int containerSlots() {
		return Math.max(containerSlots, 0);
	}

	private void build(int total) {
		containerSlots = Math.max(0, total - PLAYER_SLOTS);
		SimpleContainer container = new SimpleContainer(Math.max(containerSlots, 1));
		for (int i = 0; i < containerSlots; i++) {
			boolean shown = i < SHOWN;
			addSlot(new Slot(container, i, shown ? 8 + (i % 9) * 18 : -10000, shown ? 18 + (i / 9) * 18 : -10000));
		}
		int top = 140;
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, top + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, 8 + col * 18, top + 58));
		}
	}

	@Override
	public void initializeContents(int stateId, List<ItemStack> items, ItemStack carried) {
		if (containerSlots < 0) {
			build(items.size());
		}
		super.initializeContents(stateId, items.subList(0, Math.min(items.size(), slots.size())), carried);
	}

	@Override
	public void setItem(int slot, int stateId, ItemStack stack) {
		if (slot >= 0 && slot < slots.size()) {
			super.setItem(slot, stateId, stack);
		}
	}

	@Override
	public void setData(int id, int value) {
		// Progress bars and other values only the mod's own screen knows how to show.
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		// The server moves the stack and sends the result.
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
