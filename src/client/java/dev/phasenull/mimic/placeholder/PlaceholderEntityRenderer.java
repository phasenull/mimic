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
 * The imported jar's model (see {@link GeoModels}) in its resting pose when there is one; otherwise just a
 * shadow and a name tag with the server's entity id (F3+B shows the hitbox).
 */
public class PlaceholderEntityRenderer extends EntityRenderer<Entity, PlaceholderEntityRenderer.State> {
	public static final class State extends EntityRenderState {
		float yaw;
		GeoModels.Model model;
	}

	public PlaceholderEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.4f;
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
		}
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	protected boolean shouldShowName(Entity entity, double distanceSq) {
		// With a model, only up close (the id is still useful to tell placeholders apart).
		return distanceSq < (GeoModels.get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).isPresent() ? 8 * 8 : 48 * 48);
	}

	@Override
	protected Component getNameTag(Entity entity) {
		return entity.getName().copy().withStyle(ChatFormatting.LIGHT_PURPLE);
	}
}
