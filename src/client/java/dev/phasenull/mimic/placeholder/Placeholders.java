package dev.phasenull.mimic.placeholder;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registers placeholders for server-only blocks, items and entity types under the server's ids, while the
 * server's registry ids are being applied. With a real entry at the server's id the world shows something
 * there (a missing-texture block, an entity name tag) instead of an invisible "ghost", and textures can be
 * attached to it. Entries stay registered for the session; other servers simply don't use them.
 */
public final class Placeholders {
	private static final Field FROZEN;
	private static final Field INTRUSIVE;
	private static final Method BIND_TAGS;

	static {
		try {
			FROZEN = MappedRegistry.class.getDeclaredField("frozen");
			INTRUSIVE = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
			BIND_TAGS = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
			FROZEN.setAccessible(true);
			INTRUSIVE.setAccessible(true);
			BIND_TAGS.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static final Map<ResourceKey<?>, Set<Identifier>> REGISTERED = new ConcurrentHashMap<>();

	private Placeholders() {}

	/** True for ids that only exist on this client as a placeholder (i.e. the client doesn't really have them). */
	public static boolean isPlaceholder(Registry<?> registry, Identifier id) {
		return REGISTERED.getOrDefault(registry.key(), Set.of()).contains(id);
	}

	/** True when the client has {@code id} for real (not just as a placeholder). */
	public static boolean hasReal(Registry<?> registry, Identifier id) {
		return registry.containsKey(id) && !isPlaceholder(registry, id);
	}

	/** Registers a placeholder for {@code id} in {@code registry} if it's one Mimic can stand in for. */
	public static boolean ensure(Registry<?> registry, Identifier id) {
		if (registry.containsKey(id)) {
			return true;
		}
		try {
			if (registry == BuiltInRegistries.BLOCK) {
				registerBlock(id);
			} else if (registry == BuiltInRegistries.ITEM) {
				ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
				register(BuiltInRegistries.ITEM, key, () -> new PlaceholderItem(new Item.Properties().setId(key), id.toString()));
			} else if (registry == BuiltInRegistries.ENTITY_TYPE) {
				ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
				PlaceholderRenderers.add(register(BuiltInRegistries.ENTITY_TYPE, key,
					() -> EntityType.Builder.<PlaceholderEntity>of(PlaceholderEntity::new, MobCategory.MISC)
						.sized(0.8f, 0.8f).noSummon().clientTrackingRange(8).build(key)));
			} else {
				return false;
			}
			REGISTERED.computeIfAbsent(registry.key(), k -> ConcurrentHashMap.newKeySet()).add(id);
			return true;
		} catch (RuntimeException e) {
			MimicClient.LOGGER.warn("[Placeholder] Could not register {} in {}", id, registry.key().identifier(), e);
			return false;
		}
	}

	private static void registerBlock(Identifier id) {
		StateGuess guess = StateGuess.of(id);
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		PlaceholderBlock.PENDING.set(guess.properties());
		try {
			Block block = register(BuiltInRegistries.BLOCK, key,
				() -> new PlaceholderBlock(BlockBehaviour.Properties.of().setId(key).strength(1.5f), guess));
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				state.initCache();
			}
		} finally {
			PlaceholderBlock.PENDING.remove();
		}
		if (guess.states() > 1) {
			JoinStatus.info("[Placeholder] {}: {} states, {}", id, guess.states(), guess.source());
		}
	}

	/** Opens the frozen registry just long enough to add one entry (the value's constructor needs it open too). */
	@SuppressWarnings("unchecked")
	private static <T, V extends T> V register(Registry<T> registry, ResourceKey<T> key, Supplier<V> factory) {
		try {
			FROZEN.set(registry, false);
			INTRUSIVE.set(registry, new IdentityHashMap<>());
			V value = factory.get();
			Holder.Reference<T> holder = ((WritableRegistry<T>) registry).register(key, value, RegistrationInfo.BUILT_IN);
			BIND_TAGS.invoke(holder, List.of());
			return value;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		} finally {
			try {
				INTRUSIVE.set(registry, null);
				FROZEN.set(registry, true);
			} catch (IllegalAccessException ignored) {
				// Made accessible in the static initializer.
			}
		}
	}
}
