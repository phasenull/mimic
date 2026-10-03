package dev.phasenull.mimic.compat;

import dev.phasenull.mimic.assets.MockRecipes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.Identifier;

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

	@Override
	public void registerCategories(IRecipeCategoryRegistration registration) {
		registration.addRecipeCategories(new JarRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		registration.addRecipes(JarRecipeCategory.TYPE, MockRecipes.all().stream().filter(JarRecipeCategory::showable).toList());
	}
}
