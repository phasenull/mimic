package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.assets.AssetPacks;
import dev.phasenull.mimic.assets.FilePicker;
import dev.phasenull.mimic.placeholder.PlaceholderBlock;
import dev.phasenull.mimic.placeholder.StateGuess;
import dev.phasenull.mimic.placeholder.StateOverrides;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One server-only block, item or entity: what Mimic shows for it, and its texture. A PNG can be chosen with
 * the file dialog or dragged onto the window; it goes into Mimic's own resource pack.
 */
public class PlaceholderEntryScreen extends Screen {
	private static final int TOP = 40;

	private final Screen parent;
	private final String registry;
	private final Identifier id;
	private String message = "";
	private EditBox states;
	private int messageColor = CommonColors.GRAY;

	public PlaceholderEntryScreen(Screen parent, String registry, Identifier id) {
		super(Component.literal(id.toString()));
		this.parent = parent;
		this.registry = registry;
		this.id = id;
	}

	private boolean texturable() {
		return registry.equals("minecraft:block") || registry.equals("minecraft:item");
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		int y = height - 100;
		Button choose = addRenderableWidget(Button.builder(Component.literal("Choose PNG..."),
			b -> FilePicker.pick("PNG image", "png", this::useTexture)).bounds(x, y, 200, 20).build());
		Button clear = addRenderableWidget(Button.builder(Component.literal("Remove my texture"), b -> clearTexture())
			.bounds(x, y + 24, 200, 20).build());
		choose.active = texturable();
		clear.active = texturable() && AssetPacks.hasUserTexture(registry, id);
		if (registry.equals("minecraft:block")) {
			// State count set by hand for this server: wins over everything Mimic works out itself.
			int fieldY = TOP + 5 * (font.lineHeight + 3) + 4;
			states = addRenderableWidget(new EditBox(font, x, fieldY, 60, 20, Component.literal("States")));
			states.setMaxLength(6);
			Integer override = StateOverrides.get(id.toString());
			states.setValue(override == null ? "" : override.toString());
			addRenderableWidget(Button.builder(Component.literal("Set"), b -> setStates()).bounds(x + 64, fieldY, 66, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
				StateOverrides.set(id.toString(), null);
				show("Cleared. Applies when you rejoin.", 0xFF55FF55);
				rebuildWidgets();
			}).bounds(x + 134, fieldY, 66, 20).build());
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, y + 52, 98, 20).build());
		Button recipes = addRenderableWidget(Button.builder(Component.literal("Recipes (" + RecipesScreen.count(id.toString()) + ")"),
			b -> minecraft.gui.setScreen(RecipesScreen.create(this, id.toString()))).bounds(x + 102, y + 52, 98, 20).build());
		recipes.active = texturable();
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		files.stream().filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).findFirst().ifPresent(this::useTexture);
	}

	private void setStates() {
		try {
			int count = Integer.parseInt(states.getValue());
			if (count < 1) {
				throw new NumberFormatException();
			}
			StateOverrides.set(id.toString(), count);
			show("Set to " + count + " states for this server. Applies when you rejoin.", 0xFF55FF55);
		} catch (NumberFormatException e) {
			show("Enter a number of states (1 or more)", 0xFFFF5555);
		}
	}

	private void useTexture(Path png) {
		if (!texturable()) {
			return;
		}
		try {
			AssetPacks.setTexture(registry, id, png);
			show("Using " + png.getFileName() + ". Reloading resources...", 0xFF55FF55);
			minecraft.reloadResourcePacks();
		} catch (IOException e) {
			MimicClient.LOGGER.warn("Could not use {} for {}", png, id, e);
			show("Could not use that file: " + e.getMessage(), 0xFFFF5555);
		}
		rebuildWidgets();
	}

	private void clearTexture() {
		try {
			AssetPacks.clearTexture(registry, id);
			show("Removed. Reloading resources...", 0xFF55FF55);
			minecraft.reloadResourcePacks();
		} catch (IOException e) {
			show("Could not remove it: " + e.getMessage(), 0xFFFF5555);
		}
		rebuildWidgets();
	}

	private void show(String text, int color) {
		message = text;
		messageColor = color;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 15, CommonColors.WHITE);
		int y = TOP;
		for (String line : lines()) {
			g.centeredText(font, font.plainSubstrByWidth(line, width - 20), width / 2, y, CommonColors.LIGHT_GRAY);
			y += font.lineHeight + 3;
		}
		if (texturable()) {
			g.centeredText(font, "Or drag a PNG onto this window.", width / 2, height - 116, CommonColors.GRAY);
		}
		g.centeredText(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 130, messageColor);
	}

	private List<String> lines() {
		List<String> lines = new ArrayList<>();
		lines.add("Server-only " + registry.replace("minecraft:", "").replace('_', ' '));
		switch (registry) {
			case "minecraft:block" -> {
				if (BuiltInRegistries.BLOCK.getValue(id) instanceof PlaceholderBlock block) {
					lines.add("Placeholder block now: " + block.guess().states() + " states (" + block.guess().source() + ")");
				} else {
					lines.add("No placeholder (yet): join a Fabric server that has it");
				}
				StateGuess next = StateGuess.of(id);
				lines.add("Next join: " + next.states() + " states (" + next.source() + ")");
				lines.add("States by hand (this server; empty = automatic):");
				lines.add(textureStatus("blockstates/" + id.getPath() + ".json"));
			}
			case "minecraft:item" -> {
				lines.add(BuiltInRegistries.ITEM.containsKey(id) ? "Shown as a placeholder item" : "No placeholder (yet)");
				lines.add(textureStatus("items/" + id.getPath() + ".json"));
			}
			case "minecraft:entity_type" -> {
				lines.add("Shown as its id above a shadow (F3+B shows its hitbox)");
				lines.add("Entity textures and models aren't supported yet");
			}
			default -> lines.add("Mimic only replaces blocks, items and entities");
		}
		return lines;
	}

	private String textureStatus(String assetPath) {
		if (AssetPacks.hasUserTexture(registry, id)) {
			return "Texture: your PNG";
		}
		if (AssetPacks.find(id.getNamespace(), assetPath).isPresent()) {
			return "Texture: from the imported mod jar";
		}
		return "Texture: none (missing-texture pattern). Choose a PNG, or import the mod's jar";
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
