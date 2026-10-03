package dev.phasenull.mimic.placeholder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Stands in for an entity only the server has: moves where the server says; see PlaceholderEntityRenderer. */
public class PlaceholderEntity extends Entity {
	public PlaceholderEntity(EntityType<?> type, Level level) {
		super(type, level);
	}

	public String typeId() {
		return BuiltInRegistries.ENTITY_TYPE.getKey(getType()).toString();
	}

	@Override
	public Component getName() {
		return Component.literal(typeId());
	}

	/** Lets the crosshair target it, so attacks and clicks reach the server (which breaks or opens it). */
	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {}
}
