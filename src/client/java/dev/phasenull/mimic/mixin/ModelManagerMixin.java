package dev.phasenull.mimic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.placeholder.PlaceholderBlock;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Missing model for variant" is logged once per block state; a placeholder without a model (thousands of
 * states for some mod blocks) flooded the log and stalled resource loading, worst in an IDE console. For
 * placeholders it's one line per block instead.
 */
@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {
	@Unique
	private static final Set<Block> mimic$reported = ConcurrentHashMap.newKeySet();

	@WrapOperation(method = "lambda$createBlockStateToModelDispatch$0", require = 0, at = @At(value = "INVOKE",
		target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"))
	private static void mimic$quietPlaceholders(Logger logger, String message, Object arg, Operation<Void> original) {
		if (arg instanceof BlockState state && state.getBlock() instanceof PlaceholderBlock) {
			if (mimic$reported.add(state.getBlock())) {
				MimicClient.LOGGER.info("[Assets] {}: no model for some or all of its {} states (shown as missing; import its jar for real models)",
					BuiltInRegistries.BLOCK.getKey(state.getBlock()), state.getBlock().getStateDefinition().getPossibleStates().size());
			}
			return;
		}
		original.call(logger, message, arg);
	}
}
