package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.assets.MockRecipes;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** What makes an item and what it's used in, from imported jars' recipe files (display only). */
public final class RecipesScreen {
	private RecipesScreen() {}

	public static int count(String itemId) {
		return MockRecipes.makes(itemId).size() + MockRecipes.uses(itemId).size();
	}

	public static Screen create(Screen parent, String itemId) {
		List<TextListScreen.Row> rows = new ArrayList<>();
		rows.add(TextListScreen.Row.text("From the imported jar's recipe files; the server may use different ones."));
		List<MockRecipes.Recipe> makes = MockRecipes.makes(itemId);
		rows.add(TextListScreen.Row.header("Made by (" + makes.size() + ")"));
		makes.forEach(r -> add(rows, r));
		List<MockRecipes.Recipe> uses = MockRecipes.uses(itemId);
		rows.add(TextListScreen.Row.header("Used in (" + uses.size() + ")"));
		uses.forEach(r -> add(rows, r));
		if (makes.isEmpty() && uses.isEmpty()) {
			rows.add(TextListScreen.Row.text("No recipes. Import the mod's jar from its Server mods page."));
		}
		return new TextListScreen(parent, Component.literal("Recipes: " + itemId), rows);
	}

	private static void add(List<TextListScreen.Row> rows, MockRecipes.Recipe r) {
		String result = r.output() == null ? "?" : (r.count() > 1 ? r.count() + "x " : "") + shortId(r.output());
		rows.add(TextListScreen.Row.text(label(r) + " -> " + result, r.id() + "  (" + r.jar() + ")"));
		switch (r.kind()) {
			case SHAPED -> {
				for (int row = 0; row * r.width() < r.inputs().size(); row++) {
					List<String> cells = new ArrayList<>();
					for (int col = 0; col < r.width(); col++) {
						cells.add(slot(r.inputs().get(row * r.width() + col)));
					}
					rows.add(TextListScreen.Row.text("   [ " + String.join(" | ", cells) + " ]"));
				}
			}
			default -> rows.add(TextListScreen.Row.text("   " + r.inputs().stream().map(RecipesScreen::slot).collect(Collectors.joining(" + "))));
		}
	}

	private static String label(MockRecipes.Recipe r) {
		return switch (r.kind()) {
			case SHAPED -> "Crafting (shaped " + r.width() + "x" + (r.inputs().size() / Math.max(1, r.width())) + ")";
			case SHAPELESS -> "Crafting (shapeless)";
			case COOKING -> switch (r.type().substring(r.type().indexOf(':') + 1)) {
				case "blasting" -> "Blasting";
				case "smoking" -> "Smoking";
				case "campfire_cooking" -> "Campfire";
				default -> "Smelting";
			};
			case STONECUTTING -> "Stonecutting";
			case OTHER -> r.type();
		};
	}

	private static String slot(List<String> alternatives) {
		if (alternatives.isEmpty()) {
			return "-";
		}
		String first = shortId(alternatives.getFirst());
		return alternatives.size() == 1 ? first : first + " (or " + (alternatives.size() - 1) + " more)";
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id.startsWith("#minecraft:") ? "#" + id.substring(11) : id;
	}
}
