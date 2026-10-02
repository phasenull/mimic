package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.placeholder.Placeholders;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Block-state ids the client doesn't have (a server-only block has more states than its placeholder) read
 * as Mimic's unknown block instead of failing, which would drop the whole chunk.
 */
@Mixin(IdMapper.class)
public abstract class IdMapperMixin<T> {
	@Shadow
	public abstract T byId(int id);

	@SuppressWarnings("unchecked")
	public T byIdOrThrow(int id) {
		T value = byId(id);
		if (value != null) {
			return value;
		}
		if ((Object) this == Block.BLOCK_STATE_REGISTRY && Placeholders.unknownState() != null) {
			return (T) Placeholders.unknownState();
		}
		throw new IllegalArgumentException("No value with id " + id);
	}
}
