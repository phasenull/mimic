package dev.phasenull.mimic.placeholder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The imported jar's model (see {@link GeoModels}) in its resting pose when there is one; otherwise the
 * mod's item named like the entity (an easel entity shows the easel item), sized to the hitbox; otherwise
 * just a shadow and a name tag with the server's entity id (F3+B shows the hitbox).
 */
public class PlaceholderEntityRenderer extends EntityRenderer<Entity, PlaceholderEntityRenderer.State> {
	public static final class State extends EntityRenderState {
		float yaw;
		GeoModels.Model model;
		final net.minecraft.client.renderer.item.ItemStackRenderState item = new net.minecraft.client.renderer.item.ItemStackRenderState();
	}

	private static final java.util.Map<net.minecraft.world.entity.EntityType<?>, java.util.Optional<net.minecraft.world.item.Item>> ITEMS =
		new java.util.concurrent.ConcurrentHashMap<>();

	private final net.minecraft.client.renderer.item.ItemModelResolver itemModels;

	/** The item that looks like an entity type: same name, or "item_" / "_item" around it, in its namespace. */
	private static java.util.Optional<net.minecraft.world.item.Item> lookalikeItem(net.minecraft.world.entity.EntityType<?> type) {
		return ITEMS.computeIfAbsent(type, t -> {
			net.minecraft.resources.Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
			for (String path : new String[] {id.getPath(), "item_" + id.getPath(), id.getPath() + "_item"}) {
				net.minecraft.resources.Identifier itemId = id.withPath(path);
				if (BuiltInRegistries.ITEM.containsKey(itemId)) {
					return java.util.Optional.of(BuiltInRegistries.ITEM.getValue(itemId));
				}
			}
			return java.util.Optional.empty();
		});
	}

	public PlaceholderEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.4f;
		this.itemModels = context.getItemModelResolver();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(Entity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.yaw = entity.getYRot(partialTick);
		state.model = GeoModels.get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).orElse(null);
		state.item.clear();
		if (state.model == null) {
			lookalikeItem(entity.getType()).ifPresent(item -> itemModels.updateForNonLiving(state.item,
				new net.minecraft.world.item.ItemStack(item), net.minecraft.world.item.ItemDisplayContext.FIXED, entity));
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		GeoModels.Model model = state.model;
		if (model != null) {
			poseStack.pushPose();
			poseStack.rotateDegrees(Axis.YP, 180f - state.yaw);
			int light = state.lightCoords;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(model.texture()), (pose, consumer) -> {
				for (GeoModels.Quad quad : model.quads()) {
					float[] c = quad.corners();
					for (int i = 0; i < 4; i++) {
						consumer.addVertex(pose, c[i * 5], c[i * 5 + 1], c[i * 5 + 2]).setColor(-1).setUv(c[i * 5 + 3], c[i * 5 + 4])
							.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, quad.nx(), quad.ny(), quad.nz());
					}
				}
			});
			poseStack.popPose();
		} else if (!state.item.isEmpty()) {
			poseStack.pushPose();
			poseStack.translate(0, state.boundingBoxHeight / 2, 0);
			poseStack.rotateDegrees(Axis.YP, 180f - state.yaw);
			float scale = Math.max(0.5f, Math.max(state.boundingBoxWidth, state.boundingBoxHeight)) * 1.6f;
			poseStack.scale(scale, scale, scale);
			state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	protected boolean shouldShowName(Entity entity, double distanceSq) {
		// With a model, only up close (the id is still useful to tell placeholders apart).
		boolean drawn = GeoModels.get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).isPresent() || lookalikeItem(entity.getType()).isPresent();
		return distanceSq < (drawn ? 8 * 8 : 48 * 48);
	}

	@Override
	protected Component getNameTag(Entity entity) {
		return entity.getName().copy().withStyle(ChatFormatting.LIGHT_PURPLE);
	}
}
