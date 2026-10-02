package dev.phasenull.mimic.placeholder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderers for placeholder entity types. Renderers are normally created once per resource reload, before
 * these types exist, so the reload's renderer context is kept and used for types registered later.
 */
public final class PlaceholderRenderers {
	private static final Set<EntityType<?>> TYPES = ConcurrentHashMap.newKeySet();
	private static final Field RENDERERS;
	private static volatile EntityRendererProvider.Context context;

	static {
		try {
			RENDERERS = EntityRenderDispatcher.class.getDeclaredField("renderers");
			RENDERERS.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private PlaceholderRenderers() {}

	static void add(EntityType<?> type) {
		TYPES.add(type);
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> install(client.getEntityRenderDispatcher()));
	}

	/** Called with the renderers a resource reload just created; returns them plus placeholder renderers. */
	public static Map<EntityType<?>, EntityRenderer<?, ?>> withPlaceholders(EntityRendererProvider.Context reloadContext,
			Map<EntityType<?>, EntityRenderer<?, ?>> renderers) {
		context = reloadContext;
		Map<EntityType<?>, EntityRenderer<?, ?>> all = new HashMap<>(renderers);
		for (EntityType<?> type : TYPES) {
			all.put(type, new PlaceholderEntityRenderer(reloadContext));
		}
		return all;
	}

	/** Adds renderers for types registered since the last resource reload. */
	@SuppressWarnings("unchecked")
	private static void install(EntityRenderDispatcher dispatcher) {
		if (context == null) {
			return;
		}
		try {
			Map<EntityType<?>, EntityRenderer<?, ?>> current = (Map<EntityType<?>, EntityRenderer<?, ?>>) RENDERERS.get(dispatcher);
			if (current != null && TYPES.stream().allMatch(current::containsKey)) {
				return;
			}
			Map<EntityType<?>, EntityRenderer<?, ?>> all = current == null ? new HashMap<>() : new HashMap<>(current);
			for (EntityType<?> type : TYPES) {
				all.computeIfAbsent(type, t -> new PlaceholderEntityRenderer(context));
			}
			RENDERERS.set(dispatcher, all);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
