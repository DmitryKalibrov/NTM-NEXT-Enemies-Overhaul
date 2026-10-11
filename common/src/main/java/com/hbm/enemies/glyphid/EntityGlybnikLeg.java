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

/** A Glybnik leg torn off at the knee: flies off to its side, tumbles, lies there for a while. */
public class EntityGlybnikLeg extends Entity {

    private static final EntityDataAccessor<Byte> SLOT = SynchedEntityData.defineId(EntityGlybnikLeg.class, EntityDataSerializers.BYTE);
    private static final int LIFETIME = 300;

    public EntityGlybnikLeg(EntityType<? extends EntityGlybnikLeg> type, Level level) {
        super(type, level);
    }

    /** slot: bits 0-3 = L0..L3, 4-7 = R0..R3; mesh side R lies on model +X. */
    static void launch(ServerLevel level, EntityGlyphidGlybnik owner, int slot) {
        EntityGlybnikLeg leg = new EntityGlybnikLeg(Enemies.GLYBNIK_LEG.get(), level);
        leg.entityData.set(SLOT, (byte) slot);
        float yaw = owner.yBodyRot * Mth.DEG_TO_RAD;
        int s = slot >= 4 ? 1 : -1;
        Vec3 side = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw)).scale(s);
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        double hipZ = -GlybnikPose.HIP_Y[slot % 4] / 16.0;   // model z of the hip (forward +)
        Vec3 knee = owner.position().add(side.scale(21 / 16.0)).add(fwd.scale(hipZ)).add(0, 11 / 16.0, 0);
        leg.snapTo(knee.x, knee.y, knee.z, owner.yBodyRot, 0);
        leg.setDeltaMovement(side.scale(0.35 + owner.getRandom().nextFloat() * 0.15).add(0, 0.4, 0));
        level.addFreshEntity(leg);
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
        if (!onGround()) spin += 22;
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
