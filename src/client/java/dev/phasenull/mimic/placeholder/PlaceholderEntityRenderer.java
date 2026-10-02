package dev.phasenull.mimic.placeholder;

import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Shadow plus a name tag with the server's entity id (F3+B shows the hitbox). */
public class PlaceholderEntityRenderer extends EntityRenderer<Entity, EntityRenderState> {
	public PlaceholderEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.4f;
	}

	@Override
	public EntityRenderState createRenderState() {
		return new EntityRenderState();
	}

	@Override
	protected boolean shouldShowName(Entity entity, double distanceSq) {
		return distanceSq < 48 * 48;
	}

	@Override
	protected Component getNameTag(Entity entity) {
		return entity.getName().copy().withStyle(ChatFormatting.LIGHT_PURPLE);
	}
}
