package dev.phasenull.mimic.compat;

import dev.phasenull.mimic.assets.MockRecipes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Any recipe from a jar on a 3x3 grid with an output slot; labelled as the jar's (the server may differ). */
public class JarRecipeCategory implements IRecipeCategory<MockRecipes.Recipe> {
	@SuppressWarnings({"unchecked", "rawtypes"})
	static final IRecipeType<MockRecipes.Recipe> TYPE = IRecipeType.create("mimic", "jar_recipes", (Class) MockRecipes.Recipe.class);
	private static final int WIDTH = 120;
	private static final int HEIGHT = 64;

	private final IDrawable icon;

	JarRecipeCategory(IGuiHelper gui) {
		this.icon = gui.createDrawableItemStack(new ItemStack(Items.CRAFTING_TABLE));
	}

	/** Recipes whose output this client can show (a real item or a placeholder). */
	static boolean showable(MockRecipes.Recipe recipe) {
		return recipe.output() != null && item(recipe.output()) != null;
	}

	@Override
	public IRecipeType<MockRecipes.Recipe> getRecipeType() {
		return TYPE;
	}

	@Override
	public Component getTitle() {
		return Component.literal("Mimic: recipes from mod jars");
	}

	@Override
	public int getWidth() {
		return WIDTH;
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public IDrawable getIcon() {
		return icon;
	}

	@Override
	public void setRecipe(IRecipeLayoutBuilder builder, MockRecipes.Recipe recipe, IFocusGroup focuses) {
		List<List<String>> inputs = recipe.inputs();
		boolean single = recipe.kind() == MockRecipes.Kind.COOKING || recipe.kind() == MockRecipes.Kind.STONECUTTING;
		int width = recipe.kind() == MockRecipes.Kind.SHAPED ? recipe.width() : 3;
		for (int i = 0; i < inputs.size() && i < 9; i++) {
			int x = single ? 18 : (i % width) * 18;
			int y = single ? 18 : (i / width) * 18;
			builder.addInputSlot(x + 1, y + 1).setStandardSlotBackground().addItemStacks(stacks(inputs.get(i)));
		}
		Item output = item(recipe.output());
		if (output != null) {
			builder.addOutputSlot(95, 19).setOutputSlotBackground().addItemStacks(List.of(new ItemStack(output, Math.max(1, recipe.count()))));
		}
	}

	@Override
	public void draw(MockRecipes.Recipe recipe, IRecipeSlotsView slots, GuiGraphicsExtractor g, double mouseX, double mouseY) {
		var font = Minecraft.getInstance().font;
		g.text(font, "->", 68, 23, 0xFF808080, false);
		String type = recipe.type().substring(recipe.type().indexOf(':') + 1);
		g.text(font, font.plainSubstrByWidth(type + " (jar)", WIDTH), 0, HEIGHT - 9, 0xFF808080, false);
	}

	private static List<ItemStack> stacks(List<String> alternatives) {
		List<ItemStack> stacks = new ArrayList<>();
		for (String alternative : alternatives) {
			if (alternative.startsWith("#")) {
				Identifier tag = Identifier.tryParse(alternative.substring(1));
				if (tag != null) {
					for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag))) {
						stacks.add(new ItemStack(holder.value()));
					}
				}
			} else {
				Item item = item(alternative);
				if (item != null) {
					stacks.add(new ItemStack(item));
				}
			}
		}
		return stacks;
	}

	private static Item item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}
}
