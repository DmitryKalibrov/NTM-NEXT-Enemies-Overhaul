package com.hbm.enemies.glyphid;

import com.hbm.enemies.Enemies;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import com.hbm.entity.mob.glyphid.GlyphidStats;
import com.hbm.entity.mob.glyphid.ai.GlyphidTargetGoal;
import com.hbm.util.MobUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

/**
 * Иглострел: bio-artillery. A long glyphid with an ant head, two stinger arms, a near-vertical shield full of needle
 * ports and a radar fan of gills behind it, and a translucent womb on its back. It sees and shoots far: stops, opens
 * the radar, lobs volleys of needles with a lead on the target. Out of needles (30) or with the shield broken off it
 * charges, leaps and bites. The womb (hit from behind/above) bursts into acid and takes the poison from the needles;
 * the shield (hit from the front) breaks off with the ports and the radar. Every ~12% of health tears off a leg.
 */
public class EntityGlyphidNeedler extends EntityGlyphid {

    public enum State {
        WALK(0), LEAP(50), BITE(32), AIM(30), SHOOT(40);

        public final int length;

        State(int length) {
            this.length = length;
        }
    }

    static final GlyphidStats.StatBundle STATS = new GlyphidStats.StatBundle(140.0, 0.8, 9.0, 1.5f, 2.0f, 4.0f, 0.3f);
    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(EntityGlyphidNeedler.class, EntityDataSerializers.BYTE);
    /** Torn-off limbs: bit side * 4 + slot (slot 0 = arm, never torn), side 0 = L. */
    private static final EntityDataAccessor<Byte> MISSING = SynchedEntityData.defineId(EntityGlyphidNeedler.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(EntityGlyphidNeedler.class, EntityDataSerializers.BYTE);
    public static final int WOMB_LOST = 1, SHELL_LOST = 2, CHARGING = 4;
    public static final byte EVENT_LIMB_LOST = 70;
    public static final int MAX_NEEDLES = 30;
    private static final double SIGHT = 48, RANGE_MIN = 12, RANGE_MAX = 40;
    private static final float LEG_DAMAGE_SHARE = 0.12f, PART_SHARE = 0.25f;
    private static final Identifier LEG_SPEED = Enemies.id("lost_legs"), CHARGE_SPEED = Enemies.id("charging");
    private static final int ACID = 0xC8D040;

    private int stateTicks, needles = MAX_NEEDLES, shotCooldown = 20, leapCooldown, biteCooldown, landedAt = -1;
    /** Leap: launch speed up and the flat distance flown per unit of horizontal launch speed (air drag 0.91 per tick). */
    private static final double LEAP_UP = 0.7, LEAP_REACH = 9.08;
    private float legDamage, wombDamage, shellDamage;
    // client side
    public int clientStateStart, clientLegLost = -1000, clientLanded = -1;
    public float fanOpen, fanOpenO;
    /** Client: smoothed terrain height under each foot, relative to the feet ([side][slot], blocks). */
    public final double[][] footGround = new double[2][4];

    public EntityGlyphidNeedler(EntityType<? extends EntityGlyphidNeedler> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return EntityGlyphid.createAttributes().add(Attributes.MAX_HEALTH, STATS.health)
                .add(Attributes.MOVEMENT_SPEED, MobUtil.oldAiWalkSpeed(STATS.speed)).add(Attributes.ATTACK_DAMAGE, STATS.damage)
                .add(Attributes.FOLLOW_RANGE, SIGHT).add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void applyEntityAttributes() {
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(STATS.health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MobUtil.oldAiWalkSpeed(STATS.speed));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(STATS.damage);
    }

    /** Its own fighting: no glyphid melee goal, it keeps its distance while it has needles. */
    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new GlyphidTargetGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, (byte) 0);
        builder.define(MISSING, (byte) 0);
        builder.define(FLAGS, (byte) 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key)) {
            clientStateStart = tickCount;
            clientLanded = -1;
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_LIMB_LOST) clientLegLost = tickCount;
        else super.handleEntityEvent(id);
    }

    public State state() {
        return State.values()[entityData.get(STATE)];
    }

    public int missingLimbs() {
        return entityData.get(MISSING) & 0xFF;
    }

    public boolean has(int flag) {
        return (entityData.get(FLAGS) & flag) != 0;
    }

    private void set(int flag, boolean on) {
        int f = entityData.get(FLAGS);
        entityData.set(FLAGS, (byte) (on ? f | flag : f & ~flag));
    }

    private void setState(State state) {
        entityData.set(STATE, (byte) state.ordinal());
        stateTicks = 0;
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return STATS;
    }

    @Override
    public Identifier getSkin() {
        return Enemies.id("textures/entity/glyphid_needler.png");
    }

    @Override
    protected boolean canDig() {
        return false;
    }

    /** Clips other than walking run in place (the leap flies on its own momentum). */
    @Override
    protected boolean isImmobile() {
        return state() != State.WALK || super.isImmobile();
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    private boolean ranged() {
        return needles > 0 && !has(SHELL_LOST);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            fanOpenO = fanOpen;
            if (state() == State.LEAP && clientLanded < 0 && onGround() && tickCount - clientStateStart > NeedlerPose.LEAP_LAUNCH + 2) {
                clientLanded = tickCount;
            }
            boolean open = !has(SHELL_LOST) && (state() == State.AIM || state() == State.SHOOT) && tickCount - clientStateStart >= 4;
            fanOpen = Mth.approach(fanOpen, open ? 1f : 0f, 1f / 18);
            sampleGround();
        }
    }

    private void sampleGround() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double cos = Mth.cos(yaw), sin = Mth.sin(yaw);
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < 4; i++) {
                Vector3d h = NeedlerPose.home(side, i);   // Blender body frame: x right, -y forward
                double g = surface(getX() + h.x * cos + h.y * sin, getZ() + h.x * sin - h.y * cos) - getY();
                footGround[side][i] += (Mth.clamp(g, -1.5, 1.0) - footGround[side][i]) * 0.35;
            }
        }
    }

    private double surface(double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = Mth.floor(getY() + 1.2), bottom = Mth.floor(getY() - 2);
        for (int y = top; y >= bottom; y--) {
            pos.set(Mth.floor(x), y, Mth.floor(z));
            VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
            if (!shape.isEmpty()) return y + shape.max(Direction.Axis.Y);
        }
        return getY() - 1.5;
    }

    private static final float MAX_TURN = 8f;
    private float lastYaw = Float.NaN, lastBodyYaw;

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel server)) return;
        stateTicks++;
        if (shotCooldown > 0) shotCooldown--;
        if (leapCooldown > 0) leapCooldown--;
        if (biteCooldown > 0) biteCooldown--;
        LivingEntity target = getTarget();
        if (target != null && !target.isAlive()) target = null;
        switch (state()) {
            case WALK -> walk(target);
            case AIM -> {
                if (target != null) faceTowards(target);
                if (stateTicks >= State.AIM.length) setState(target != null && ranged() ? State.SHOOT : State.WALK);
            }
            case SHOOT -> {
                if (target != null) faceTowards(target);
                for (int i = 0; i < NeedlerPose.SHOTS.length; i++) {
                    if (stateTicks == NeedlerPose.SHOTS[i] && target != null && needles > 0) fire(server, target, i);
                }
                if (stateTicks >= State.SHOOT.length) {
                    shotCooldown = 20;
                    setState(State.WALK);
                }
            }
            case LEAP -> {
                if (stateTicks < NeedlerPose.LEAP_LAUNCH && target != null) faceTowards(target);
                if (stateTicks == NeedlerPose.LEAP_LAUNCH) launch(target);
                // the stab lands with the body, whenever that is; the rest of the clip runs from there
                if (stateTicks > NeedlerPose.LEAP_LAUNCH + 1 && landedAt < 0 && (onGround() || stateTicks > NeedlerPose.LEAP_LAUNCH + 40)) {
                    landedAt = stateTicks;
                    strike(server, 2.2, 1.3f);
                }
                if (landedAt >= 0 && stateTicks >= landedAt + State.LEAP.length - NeedlerPose.LEAP_STAB) {
                    leapCooldown = 60;
                    setState(State.WALK);
                }
            }
            case BITE -> {
                if (stateTicks < NeedlerPose.BITES[0] && target != null) faceTowards(target);
                for (int b : NeedlerPose.BITES) if (stateTicks == b) strike(server, 1.8, 0.6f);
                if (stateTicks >= State.BITE.length) {
                    biteCooldown = 10;
                    setState(State.WALK);
                }
            }
        }
        boolean flying = state() == State.LEAP && stateTicks >= NeedlerPose.LEAP_LAUNCH && landedAt < 0;
        if (state() != State.WALK && !flying) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
        }
        limitTurn();
    }

    private void walk(LivingEntity target) {
        boolean ranged = ranged();
        if (has(CHARGING) == ranged) setCharging(!ranged);
        if (target == null) {
            if (getNavigation().isDone() && random.nextInt(80) == 0) {
                Vec3 p = position().add(random.nextGaussian() * 8, 0, random.nextGaussian() * 8);
                getNavigation().moveTo(p.x, p.y, p.z, 0.8);
            }
            return;
        }
        double dist = distanceTo(target);
        boolean seen = hasLineOfSight(target);
        if (ranged) {
            if (seen && dist >= RANGE_MIN && dist <= RANGE_MAX) {
                getNavigation().stop();
                faceTowards(target);
                if (shotCooldown <= 0) setState(State.AIM);
            } else if (dist < RANGE_MIN && seen) {
                // too close for artillery: back off
                Vec3 away = position().subtract(target.position()).normalize().scale(6).add(position());
                if (tickCount % 10 == 0) getNavigation().moveTo(away.x, away.y, away.z, 1.2);
            } else if (tickCount % 10 == 0) {
                getNavigation().moveTo(target, 1.0);
            }
            return;
        }
        if (tickCount % 5 == 0) getNavigation().moveTo(target, 1.0);
        if (dist < 3.2 && biteCooldown <= 0) {
            getNavigation().stop();
            setState(State.BITE);
        } else if (dist > 4 && dist < 12 && seen && onGround() && leapCooldown <= 0) {
            getNavigation().stop();
            setState(State.LEAP);
        }
    }

    private void setCharging(boolean on) {
        set(CHARGING, on);
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        if (on) speed.addOrReplacePermanentModifier(new AttributeModifier(CHARGE_SPEED, 0.8, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else speed.removeModifier(CHARGE_SPEED);
    }

    private void limitTurn() {
        if (Float.isNaN(lastYaw)) {
            lastYaw = getYRot();
            lastBodyYaw = yBodyRot;
        }
        setYRot(lastYaw + Mth.clamp(Mth.wrapDegrees(getYRot() - lastYaw), -MAX_TURN, MAX_TURN));
        yBodyRot = lastBodyYaw + Mth.clamp(Mth.wrapDegrees(yBodyRot - lastBodyYaw), -MAX_TURN, MAX_TURN);
        yHeadRot = yBodyRot + Mth.clamp(Mth.wrapDegrees(yHeadRot - yBodyRot), -30f, 30f);
        lastYaw = getYRot();
        lastBodyYaw = yBodyRot;
    }

    private void faceTowards(Entity target) {
        float yaw = (float) (Mth.atan2(target.getZ() - getZ(), target.getX() - getX()) * Mth.RAD_TO_DEG) - 90f;
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
    }

    private Vec3 forward() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    /** Blender body-frame point -> world (model x = Blender x, model y = Blender z, model z = -Blender y). */
    Vec3 toWorld(double bx, double by, double bz) {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double c = Mth.cos(yaw), s = Mth.sin(yaw), mz = -by;
        return new Vec3(getX() + bx * c - mz * s, getY() + bz, getZ() + bx * s + mz * c);
    }

    /** One needle from a port of the shot's group, lobbed at where the target will be. */
    private void fire(ServerLevel level, LivingEntity target, int shot) {
        int count = NeedlerRig.PORTS.length;
        int port = shot + 3 * random.nextInt((count - shot + 2) / 3);
        double[] b = NeedlerRig.PORT_BASE[Math.min(port, count - 1)];
        Vector3d tip = new Vector3d(b[0], b[1], b[2]).fma(0.2, NeedlerPose.SN);
        Vec3 from = toWorld(tip.x, tip.y, tip.z);
        Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 vel = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
        Vec3 shotVel = EntityNeedle.ballistic(from, aim, EntityNeedle.SPEED);
        for (int i = 0; i < 2; i++) {   // lead: aim where it will be after the flight time
            double flight = from.distanceTo(aim) / Math.max(0.1, Math.hypot(shotVel.x, shotVel.z));
            shotVel = EntityNeedle.ballistic(from, aim.add(vel.scale(flight)), EntityNeedle.SPEED);
        }
        EntityNeedle needle = new EntityNeedle(level, this, from, shotVel.add(random.nextGaussian() * 0.012, random.nextGaussian() * 0.012,
                random.nextGaussian() * 0.012), !has(WOMB_LOST));
        level.addFreshEntity(needle);
        needles--;
        playSound(SoundEvents.TRIDENT_THROW.value(), 1.6f, 1.6f + random.nextFloat() * 0.2f);
    }

    /** Jumps to land just short of where the target will be: the speed makes up for the air drag. */
    private void launch(LivingEntity target) {
        landedAt = -1;
        Vec3 aim = target == null ? position().add(forward().scale(6))
                : target.position().add((target.getX() - target.xo) * 18, 0, (target.getZ() - target.zo) * 18);
        Vec3 d = aim.subtract(position());
        double dist = Mth.clamp(Math.hypot(d.x, d.z) - 1.2, 1, 12);
        Vec3 h = new Vec3(d.x, 0, d.z).normalize().scale(dist / LEAP_REACH);
        setDeltaMovement(h.x, LEAP_UP + Mth.clamp(d.y * 0.06, 0, 0.3), h.z);
        hurtMarked = true;
        playSound(SoundEvents.SPIDER_AMBIENT, 2.0f, 0.6f);
    }

    private void strike(ServerLevel level, double reach, float scale) {
        Vec3 centre = position().add(forward().scale(reach)).add(0, 0.8, 0);
        float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * scale;
        for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(1.4, 1.0, 1.4),
                e -> e != this && !(e instanceof EntityGlyphid) && e.isAlive())) {
            hit.hurtServer(level, damageSources().mobAttack(this), damage);
        }
        playSound(SoundEvents.SPIDER_STEP, 2.0f, 0.5f);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Vec3 from = source.getSourcePosition();
        boolean front = false, back = false;
        if (from != null) {
            Vec3 rel = from.subtract(position());
            double h = Math.hypot(rel.x, rel.z), dot = h < 1e-3 ? 0 : (rel.x * forward().x + rel.z * forward().z) / h;
            front = dot > 0.4 && rel.y < 3.0;
            back = dot < -0.3 || rel.y > 3.2;
        }
        boolean shield = front && !has(SHELL_LOST);
        boolean hurt = super.hurtServer(level, source, shield ? amount * 0.5f : amount);   // the shield takes half
        if (!hurt || !isAlive()) return hurt;
        float share = getMaxHealth() * PART_SHARE;
        if (shield && (shellDamage += amount) >= share) breakShell(level);
        if (back && !has(WOMB_LOST) && (wombDamage += amount) >= share) burstWomb(level);
        float legShare = getMaxHealth() * LEG_DAMAGE_SHARE;
        legDamage += amount;
        int lose = (int) (legDamage / legShare) + (source.is(DamageTypeTags.IS_EXPLOSION) ? 1 : 0);
        legDamage %= legShare;
        for (int i = 0; i < lose; i++) tearLeg(level, from);
        return hurt;
    }

    private void breakShell(ServerLevel level) {
        set(SHELL_LOST, true);
        EntityNeedlerPart.launch(level, this, EntityNeedlerPart.SHELL);
        playSound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 2.0f, 0.6f);
        if (state() == State.AIM || state() == State.SHOOT) setState(State.WALK);
    }

    private void burstWomb(ServerLevel level) {
        set(WOMB_LOST, true);
        Vec3 c = toWorld(0, 1.3, 1.35);
        AreaEffectCloud cloud = new AreaEffectCloud(level, c.x, getY() + 0.3, c.z);
        cloud.setOwner(this);
        cloud.setRadius(3.5f);
        cloud.setDuration(100);
        cloud.setRadiusPerTick(-3.5f / 100);
        cloud.setWaitTime(0);
        cloud.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 1));
        cloud.setCustomParticle(new DustParticleOptions(ACID, 1.5f));
        level.addFreshEntity(cloud);
        for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(3),
                e -> e != this && !(e instanceof EntityGlyphid) && e.isAlive())) {
            hit.hurtServer(level, damageSources().mobAttack(this), 6);
        }
        level.sendParticles(new DustParticleOptions(ACID, 2.0f), c.x, c.y, c.z, 80, 1.0, 0.6, 1.0, 0.1);
        playSound(SoundEvents.SLIME_SQUISH, 3.0f, 0.5f);
        playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.0f, 1.4f);
    }

    /** Tears off a leg (slots 1..3) on the side facing the hit (mesh side R is world (cos yaw, 0, sin yaw)). */
    private void tearLeg(ServerLevel level, Vec3 from) {
        int missing = missingLimbs(), legs = 0xEE;
        if ((missing & legs) == legs) return;
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        boolean right = from == null ? random.nextBoolean() : (from.x - getX()) * Mth.cos(yaw) + (from.z - getZ()) * Mth.sin(yaw) > 0;
        int side = right ? 0xE0 : 0x0E;
        if ((missing & side) == side) side ^= legs;
        int pick;
        do pick = random.nextInt(8); while ((side & (1 << pick)) == 0 || (missing & (1 << pick)) != 0);
        missing |= 1 << pick;
        entityData.set(MISSING, (byte) missing);
        level.broadcastEntityEvent(this, EVENT_LIMB_LOST);
        EntityNeedlerPart.launch(level, this, pick);
        playSound(SoundEvents.SLIME_SQUISH, 2.0f, 0.6f);
        applyLegLoss(missing);
    }

    private void applyLegLoss(int missing) {
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        int left = 6 - Integer.bitCount(missing & 0xEE);
        if (speed != null) speed.addOrReplacePermanentModifier(new AttributeModifier(LEG_SPEED,
                Math.max(0.1, left / 6.0) - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** The death clip plays out before the body goes. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= NeedlerPose.DEATH_LENGTH && !level().isClientSide() && !isRemoved()) {
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

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putByte("missing_limbs", (byte) missingLimbs());
        output.putByte("lost_parts", (byte) (entityData.get(FLAGS) & (WOMB_LOST | SHELL_LOST)));
        output.putInt("needles", needles);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        int missing = input.getByteOr("missing_limbs", (byte) 0) & 0xFF;
        entityData.set(MISSING, (byte) missing);
        if (missing != 0) applyLegLoss(missing);
        entityData.set(FLAGS, (byte) (input.getByteOr("lost_parts", (byte) 0) & (WOMB_LOST | SHELL_LOST)));
        needles = input.getIntOr("needles", MAX_NEEDLES);
    }
}
