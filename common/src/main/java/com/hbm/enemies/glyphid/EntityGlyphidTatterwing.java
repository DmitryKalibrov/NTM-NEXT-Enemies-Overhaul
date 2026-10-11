package com.hbm.enemies.glyphid;

import com.hbm.enemies.Enemies;
import com.hbm.enemies.glyphid.TatterwingPose.Clip;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import com.hbm.entity.mob.glyphid.GlyphidStats;
import com.hbm.entity.mob.glyphid.ai.GlyphidTargetGoal;
import com.hbm.util.MobUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Лоскутник: a flying glyphid on torn leather wings. It climbs high over its prey, circles, then stoops and drives
 * its stinger in like a bee, rips it out, climbs again and repeats until the prey is dead. Without prey it lands
 * and folds its wings up over its back like a dry leaf.
 */
public class EntityGlyphidTatterwing extends EntityGlyphid {

    static final GlyphidStats.StatBundle STATS = new GlyphidStats.StatBundle(45.0, 1.0, 6.0, 0.5f, 0.5f, 1.0f, 0.1f);
    private static final EntityDataAccessor<Byte> CLIP = SynchedEntityData.defineId(EntityGlyphidTatterwing.class, EntityDataSerializers.BYTE);
    private static final double SIGHT = 40, ALTITUDE = 13, CIRCLE = 8, CRUISE = 0.42, DIVE_SPEED = 1.25;

    private int clipTicks, idle;
    private double orbit;
    // client side
    public int clientClipStart;

    public EntityGlyphidTatterwing(EntityType<? extends EntityGlyphidTatterwing> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return EntityGlyphid.createAttributes().add(Attributes.MAX_HEALTH, STATS.health)
                .add(Attributes.MOVEMENT_SPEED, MobUtil.oldAiWalkSpeed(STATS.speed)).add(Attributes.ATTACK_DAMAGE, STATS.damage)
                .add(Attributes.FOLLOW_RANGE, SIGHT);
    }

    @Override
    protected void applyEntityAttributes() {
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(STATS.health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MobUtil.oldAiWalkSpeed(STATS.speed));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(STATS.damage);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new GlyphidTargetGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CLIP, (byte) Clip.REST.ordinal());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (CLIP.equals(key)) clientClipStart = tickCount;
    }

    public Clip clip() {
        return Clip.values()[entityData.get(CLIP)];
    }

    private void setClip(Clip clip) {
        entityData.set(CLIP, (byte) clip.ordinal());
        clipTicks = 0;
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return STATS;
    }

    @Override
    public Identifier getSkin() {
        return Enemies.id("textures/entity/glyphid_tatterwing.png");
    }

    @Override
    protected boolean canDig() {
        return false;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    private boolean flying() {
        Clip c = clip();
        return c != Clip.REST && c != Clip.LAND;
    }

    /** Client: how hard it banks into a turn, -1..1 (+ = turning right), smoothed. */
    public float bank, bankO;

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            bankO = bank;
            float turn = Mth.clamp(Mth.wrapDegrees(yBodyRot - yBodyRotO) / 6f, -1f, 1f);
            bank += (turn - bank) * 0.1f;
        }
    }

    @Override
    public void aiStep() {
        if (level() instanceof ServerLevel server && isAlive()) fly(server);
        super.aiStep();
    }

    private void fly(ServerLevel level) {
        clipTicks++;
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || distanceTo(target) > SIGHT * 1.5)) target = null;
        setNoGravity(flying() || clip() == Clip.LAND && clipTicks < 6);
        switch (clip()) {
            case REST -> {
                if (target != null) setClip(Clip.TAKEOFF);
            }
            case TAKEOFF -> {
                if (clipTicks > 9) steer(position().add(0, 3, 0).add(forward().scale(2)), 0.3);
                if (clipTicks >= Clip.TAKEOFF.length) setClip(Clip.CLIMB);
            }
            case CLIMB -> {
                if (target == null) {
                    wander();
                    break;
                }
                Vec3 perch = orbitPoint(target);
                steer(perch, CRUISE);
                if (position().distanceTo(perch) < 3 || getY() > target.getY() + ALTITUDE - 1) setClip(Clip.GLIDE);
            }
            case GLIDE -> {
                if (target == null) {
                    wander();
                    break;
                }
                orbit += 0.06;
                steer(orbitPoint(target), CRUISE * 0.8);
                // stoop once it has circled a while and sees the prey below
                if (clipTicks > 40 && getY() > target.getY() + 6 && hasLineOfSight(target) && random.nextInt(20) == 0) {
                    setClip(Clip.DIVE);
                    playSound(SoundEvents.PHANTOM_SWOOP, 3.0f, 0.7f);
                }
            }
            case DIVE -> {
                if (target == null) {
                    setClip(Clip.CLIMB);
                    break;
                }
                Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0)
                        .add((target.getX() - target.xo) * 4, 0, (target.getZ() - target.zo) * 4);
                double speed = Math.min(DIVE_SPEED, 0.5 + clipTicks * 0.06);
                steer(aim.subtract(stingTip()).add(position()), speed);
                if (stingTip().distanceTo(target.position().add(0, target.getBbHeight() * 0.5, 0)) < 1.6) sting(level, target);
                else if (horizontalCollision || verticalCollision || clipTicks > 70) setClip(Clip.CLIMB);   // missed
            }
            case STING -> {
                if (target != null && clipTicks < TatterwingPose.STING_PULL) {
                    // stuck in: rides the prey
                    Vec3 hold = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(stingTip().subtract(position()));
                    setDeltaMovement(hold.subtract(position()).scale(0.5));
                } else {
                    steer(position().add(0, 4, 0), 0.5);
                }
                if (clipTicks == TatterwingPose.STING_PULL) playSound(SoundEvents.TRIDENT_RETURN, 1.5f, 0.6f);
                if (clipTicks >= Clip.STING.length) setClip(Clip.CLIMB);
            }
            case HOVER -> {
                if (target != null) {
                    setClip(Clip.CLIMB);
                    break;
                }
                wander();
            }
            case LAND -> {
                setDeltaMovement(getDeltaMovement().multiply(0.5, 1, 0.5));
                if (clipTicks >= Clip.LAND.length) setClip(Clip.REST);
            }
            default -> {}
        }
        if (flying() && clip() != Clip.STING) faceFlight();
    }

    /** Without prey: drift about a little, then come down and fold up. */
    private void wander() {
        if (clip() != Clip.HOVER) setClip(Clip.HOVER);
        idle++;
        Vec3 v = getDeltaMovement().scale(0.9);
        setDeltaMovement(v.x + random.nextGaussian() * 0.01, -0.04, v.z + random.nextGaussian() * 0.01);
        if (onGround() || idle > 200 && verticalCollisionBelow) {
            idle = 0;
            setClip(Clip.LAND);
            playSound(SoundEvents.PHANTOM_FLAP, 1.0f, 0.6f);
        }
    }

    /** A point on a circle high over the prey. */
    private Vec3 orbitPoint(LivingEntity target) {
        return target.position().add(Math.cos(orbit) * CIRCLE, ALTITUDE, Math.sin(orbit) * CIRCLE);
    }

    /** Eases the velocity toward the point at this speed. */
    private void steer(Vec3 to, double speed) {
        Vec3 d = to.subtract(position());
        Vec3 want = d.length() < 1e-3 ? Vec3.ZERO : d.normalize().scale(Math.min(speed, d.length()));
        setDeltaMovement(getDeltaMovement().lerp(want, 0.18));
        hurtMarked = true;
    }

    private Vec3 forward() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    /** Where the stinger is: well below the body, a little ahead when diving. */
    private Vec3 stingTip() {
        return position().add(forward().scale(clip() == Clip.DIVE ? 0.8 : 0.2)).add(0, 0.2, 0);
    }

    private void faceFlight() {
        Vec3 v = getDeltaMovement();
        if (v.horizontalDistanceSqr() < 0.002) return;
        float yaw = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG) - 90f;
        float turned = Mth.approachDegrees(yBodyRot, yaw, 10f);
        setYRot(turned);
        yBodyRot = turned;
        yHeadRot = turned;
    }

    private void sting(ServerLevel level, LivingEntity target) {
        setClip(Clip.STING);
        if (target.hurtServer(level, damageSources().mobAttack(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE))) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0), this);
        }
        playSound(SoundEvents.BEE_STING, 2.0f, 0.5f);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && isAlive() && (clip() == Clip.REST || clip() == Clip.LAND)) setClip(Clip.TAKEOFF);   // startled awake
        return hurt;
    }

    /** The death clip plays out (falling, crumpling) before the body goes. */
    @Override
    protected void tickDeath() {
        setNoGravity(false);
        deathTime++;
        if (deathTime >= Clip.DEATH.length && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return false;
    }
}
