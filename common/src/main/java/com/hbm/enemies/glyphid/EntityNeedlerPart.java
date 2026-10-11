package com.hbm.enemies.glyphid;

import com.hbm.enemies.Enemies;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** A piece knocked off a Needler: a leg below the knee (slot = side * 4 + i) or the whole shield with its radar. */
public class EntityNeedlerPart extends Entity {

    public static final int SHELL = 8;
    private static final EntityDataAccessor<Byte> SLOT = SynchedEntityData.defineId(EntityNeedlerPart.class, EntityDataSerializers.BYTE);
    private static final int LIFETIME = 300;

    public EntityNeedlerPart(EntityType<? extends EntityNeedlerPart> type, Level level) {
        super(type, level);
    }

    static void launch(ServerLevel level, EntityGlyphidNeedler owner, int slot) {
        EntityNeedlerPart part = new EntityNeedlerPart(Enemies.NEEDLER_PART.get(), level);
        part.entityData.set(SLOT, (byte) slot);
        // the entity sits at the piece's centre; the renderer draws the piece around it
        Vector3d c = slot == SHELL ? new Vector3d(NeedlerPose.SHIELD_C) : NeedlerPose.lowerCentre(slot / 4, slot % 4);
        Vec3 at = owner.toWorld(c.x, c.y, c.z);
        part.snapTo(at.x, at.y, at.z, owner.yBodyRot, 0);
        float yaw = owner.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 out = slot == SHELL ? new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)).scale(0.25)            // tips forward
                : new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw)).scale((slot >= 4 ? 1 : -1) * (0.35 + owner.getRandom().nextFloat() * 0.15));
        part.setDeltaMovement(out.add(0, slot == SHELL ? 0.25 : 0.4, 0));
        level.addFreshEntity(part);
    }

    public int slot() {
        return entityData.get(SLOT);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SLOT, (byte) 0);
    }

    /** Tumble angle in degrees: turns while airborne, freezes once it lies on the ground. */
    public float spin, spinO;

    @Override
    public void tick() {
        super.tick();
        spinO = spin;
        if (!onGround()) spin += slot() == SHELL ? 9 : 22;
        Vec3 v = getDeltaMovement().add(0, -0.04, 0);
        move(MoverType.SELF, v);
        setDeltaMovement(onGround() ? v.multiply(0.5, 0, 0.5) : v.scale(0.98));
        if (!level().isClientSide() && tickCount > LIFETIME) discard();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}
}
