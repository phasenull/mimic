package dev.phasenull.mimic.compat;

import dev.phasenull.mimic.assets.MockRecipes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import dev.phasenull.mimic.placeholder.PlaceholderItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional JEI support: recipes from imported mod jars in their own category. Only loaded by JEI (through
 * the jei_mod_plugin entrypoint), so without JEI none of this runs.
 */
@JeiPlugin
public class MimicJeiPlugin implements IModPlugin {
	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("mimic", "jar_recipes");
	}

	/**
	 * Placeholder items (server-only items, and items of imported jars) aren't in any creative tab, which is
	 * where JEI gets its item list from.
	 */
	@Override
	public void registerExtraIngredients(IExtraIngredientRegistration registration) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item instanceof PlaceholderItem) {
				stacks.add(new ItemStack(item));
			}
		}
		registration.addExtraItemStacks(stacks);
	}

	@Override
	public void registerCategories(IRecipeCategoryRegistration registration) {
		registration.addRecipeCategories(new JarRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		registration.addRecipes(JarRecipeCategory.TYPE, MockRecipes.all().stream().filter(JarRecipeCategory::showable).toList());
	}
}
