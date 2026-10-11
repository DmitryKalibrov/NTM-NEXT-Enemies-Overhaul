package com.hbm.enemies.glyphid;

import com.hbm.enemies.Enemies;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import com.hbm.entity.mob.glyphid.GlyphidStats;
import com.hbm.util.MobUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

/**
 * Глыбник: a crab-glyphid under a dome of rock plates with spore pods. Rams with its head, puffs
 * spore clouds, hides in its shell between attacks. Every ~12% of health lost tears off a leg; each
 * lost leg slows it, and once it can't stand it lies on its side and only roars and puffs spores.
 * The client plays the clip of the synced {@link State}; the maths lives in the renderer.
 */
public class EntityGlyphidGlybnik extends EntityGlyphid {

    /** Clips; the renderer and the server agree on their length and the ticks things happen at. */
    public enum State {
        WALK(0), BASH(40), SPORES(60), HIDE(80), COLLAPSED(100);

        public final int length;

        State(int length) {
            this.length = length;
        }
    }

    static final GlyphidStats.StatBundle STATS = new GlyphidStats.StatBundle(300.0, 0.6, 18.0, 2.0f, 3.0f, 6.0f, 0.4f);
    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(EntityGlyphidGlybnik.class, EntityDataSerializers.BYTE);
    /** Torn-off legs: bits 0-3 = L0..L3, bits 4-7 = R0..R3 (front to back, mesh sides). */
    private static final EntityDataAccessor<Byte> MISSING = SynchedEntityData.defineId(EntityGlyphidGlybnik.class, EntityDataSerializers.BYTE);
    public static final byte EVENT_LEG_LOST = 70;
    private static final float LEG_DAMAGE_SHARE = 0.12f;
    private static final int BASH_STRIKE = 14;
    private static final int[] SPORE_BURSTS = {46, 54};
    private static final int[] HIDE_BURSTS = {34, 42, 50};
    private static final int[] COLLAPSED_BURSTS = {36, 44, 86, 94};
    private static final int[] COLLAPSED_ROARS = {14, 62};
    private static final Identifier LEG_SPEED = Enemies.id("lost_legs");
    private static final int SPORE_COLOR = 0xE8963C;

    private int stateTicks;
    private int bashCooldown, sporeCooldown = 60;
    private float legDamage;
    // client side: when the current state started and when the last leg came off
    public int clientStateStart, clientLegLost = -1000;

    public EntityGlyphidGlybnik(EntityType<? extends EntityGlyphidGlybnik> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return EntityGlyphid.createAttributes().add(Attributes.MAX_HEALTH, STATS.health)
                .add(Attributes.MOVEMENT_SPEED, MobUtil.oldAiWalkSpeed(STATS.speed)).add(Attributes.ATTACK_DAMAGE, STATS.damage)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void applyEntityAttributes() {
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(STATS.health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MobUtil.oldAiWalkSpeed(STATS.speed));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(STATS.damage);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, (byte) 0);
        builder.define(MISSING, (byte) 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key)) clientStateStart = tickCount;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_LEG_LOST) clientLegLost = tickCount;
        else super.handleEntityEvent(id);
    }

    public State state() {
        return State.values()[entityData.get(STATE)];
    }

    public int missingLegs() {
        return entityData.get(MISSING) & 0xFF;
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
        return Enemies.id("textures/entity/glyphid_glybnik.png");
    }

    @Override
    protected boolean canDig() {
        return false;
    }

    /** Anything but walking runs its clip in place; LivingEntity then skips goals and movement. */
    @Override
    protected boolean isImmobile() {
        return state() != State.WALK || super.isImmobile();
    }

    /** The goal's bite starts a head-butt instead. */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        if (state() == State.WALK && bashCooldown <= 0) startBash(target);
        return false;
    }

    /** Client: smoothed terrain height under each standing claw, relative to the feet ([side][slot], blocks). */
    public final double[][] footGround = new double[2][4];

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) sampleGround();
    }

    private void sampleGround() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double cos = Mth.cos(yaw), sin = Mth.sin(yaw);
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < 4; i++) {
                Vector3d h = GlybnikPose.home(side, i);   // Blender body frame: x right, -y forward
                double g = surface(getX() + h.x * cos + h.y * sin, getZ() + h.x * sin - h.y * cos) - getY();
                footGround[side][i] += (Mth.clamp(g, -1.5, 1.0) - footGround[side][i]) * 0.35;
            }
        }
    }

    /** Top of the first solid collision shape at or below a little above the feet. */
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

    /** Degrees per tick. MoveControl turns up to 90 per tick: with a 3-block hitbox a path node right under
     * the body flips direction every tick and it spins on the spot. A heavy body turns slowly instead. */
    private static final float MAX_TURN = 6f;
    private float lastYaw = Float.NaN, lastBodyYaw;

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel server)) return;
        stateTicks++;
        tickSpores(server);
        if (bashCooldown > 0) bashCooldown--;
        if (sporeCooldown > 0) sporeCooldown--;
        LivingEntity target = getTarget();
        switch (state()) {
            case WALK -> {
                if (target == null) break;
                double dist = distanceTo(target);
                if (dist < 4.5 && bashCooldown <= 0) startBash(target);
                else if (dist < 14 && sporeCooldown <= 0 && random.nextInt(30) == 0) {
                    // hides more often when hurt
                    setState(random.nextFloat() < (getHealth() < getMaxHealth() * 0.6f ? 0.7f : 0.4f) ? State.HIDE : State.SPORES);
                }
            }
            case BASH -> {
                if (stateTicks < BASH_STRIKE && target != null) faceTowards(target);   // turns during the wind-up
                if (stateTicks == BASH_STRIKE) strike(server);
                if (stateTicks >= State.BASH.length) {
                    bashCooldown = 50;
                    setState(State.WALK);
                }
            }
            case SPORES -> {
                for (int t : SPORE_BURSTS) if (stateTicks == t) puffSpores(server);
                if (stateTicks >= State.SPORES.length) endSporeState();
            }
            case HIDE -> {
                for (int t : HIDE_BURSTS) if (stateTicks == t) puffSpores(server);
                if (stateTicks >= State.HIDE.length) endSporeState();
            }
            case COLLAPSED -> {
                int t = stateTicks % State.COLLAPSED.length;
                for (int b : COLLAPSED_BURSTS) if (t == b) puffSpores(server);
                for (int r : COLLAPSED_ROARS) if (t == r) playSound(SoundEvents.RAVAGER_ROAR, 4.0f, 0.55f);
            }
        }
        if (state() != State.WALK) setDeltaMovement(0, getDeltaMovement().y, 0);
        limitTurn();
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

    private void endSporeState() {
        sporeCooldown = 160 + random.nextInt(80);
        setState(State.WALK);
    }

    private void startBash(Entity target) {
        getNavigation().stop();
        setState(State.BASH);
    }

    private Vec3 forward() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    private void strike(ServerLevel level) {
        Vec3 centre = position().add(forward().scale(2.5)).add(0, 1, 0);
        float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
        for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(1.6, 1.2, 1.6),
                e -> e != this && !(e instanceof EntityGlyphid) && e.isAlive())) {
            if (hit.hurtServer(level, damageSources().mobAttack(this), damage)) {
                Vec3 push = forward().scale(1.4);
                hit.push(push.x, 0.5, push.z);
                hit.hurtMarked = true;
            }
        }
        playSound(SoundEvents.RAVAGER_ATTACK, 2.0f, 0.6f);
    }

    /**
     * A burst of spores: plumes shoot up out of the pods on the shell, then a cloud settles around it, spreads from
     * about 1.5 to 4.5 blocks, drifts with a slow wind and thins out. It poisons and blinds whatever breathes it,
     * except glyphids: the Glybnik and its kin live with these spores.
     */
    private void puffSpores(ServerLevel level) {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double c = Mth.cos(yaw), s = Mth.sin(yaw);
        DustParticleOptions dust = new DustParticleOptions(SPORE_COLOR, 2.2f);
        for (int pod = 0; pod < 9; pod++) {
            // a pod somewhere on the shell top (model x across, z along the body)
            double mx = (random.nextDouble() - 0.5) * 2.4, mz = (random.nextDouble() - 0.6) * 2.4;
            double x = getX() + mx * c - mz * s, z = getZ() + mx * s + mz * c, y = getY() + 2.9 + random.nextDouble() * 0.4;
            for (int k = 0; k < 7; k++) {   // count 0: the offsets are the particle's velocity
                level.sendParticles(dust, x, y, z, 0, mx * 0.08 + random.nextGaussian() * 0.04, 0.3 + random.nextDouble() * 0.3,
                        mz * 0.08 + random.nextGaussian() * 0.04, 1.0);
            }
        }
        float wind = random.nextFloat() * Mth.TWO_PI;
        clouds.add(new SporeCloud(position(), new Vec3(Mth.cos(wind), 0, Mth.sin(wind)).scale(0.015)));
        playSound(SoundEvents.PUFFER_FISH_BLOW_UP, 2.0f, 0.5f);
        playSound(SoundEvents.SNIFFER_EGG_CRACK, 1.5f, 0.6f);
    }

    private static final int CLOUD_LIFE = 110;
    private final java.util.List<SporeCloud> clouds = new java.util.ArrayList<>();

    /** A settling spore cloud this Glybnik puffed out. */
    private static final class SporeCloud {
        Vec3 centre;
        final Vec3 drift;
        int age;

        SporeCloud(Vec3 centre, Vec3 drift) {
            this.centre = centre;
            this.drift = drift;
        }

        double radius() {
            return 1.5 + 3.0 * Math.min(1.0, age / 35.0);
        }
    }

    private void tickSpores(ServerLevel level) {
        DustParticleOptions dust = new DustParticleOptions(SPORE_COLOR, 1.8f);
        for (java.util.Iterator<SporeCloud> it = clouds.iterator(); it.hasNext(); ) {
            SporeCloud cloud = it.next();
            if (++cloud.age > CLOUD_LIFE) {
                it.remove();
                continue;
            }
            cloud.centre = cloud.centre.add(cloud.drift);
            double r = cloud.radius(), fade = 1 - Math.max(0, cloud.age - 70) / 40.0;
            if (cloud.age % 2 == 0) {
                int n = (int) (r * r * 1.2 * fade);
                for (int i = 0; i < n; i++) {
                    double a = random.nextDouble() * Mth.TWO_PI, d = Math.sqrt(random.nextDouble()) * r;
                    level.sendParticles(dust, cloud.centre.x + Math.cos(a) * d, cloud.centre.y + 0.2 + random.nextDouble() * 2.2,
                            cloud.centre.z + Math.sin(a) * d, 0, 0, -0.01, 0, 1.0);
                }
                if (random.nextInt(3) == 0) {
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPORE_BLOSSOM_AIR, cloud.centre.x, cloud.centre.y + 1.2,
                            cloud.centre.z, (int) (4 * fade) + 1, r * 0.6, 0.8, r * 0.6, 0.0);
                }
            }
            if (cloud.age % 5 != 0) continue;
            AABB box = new AABB(cloud.centre, cloud.centre).inflate(r, 0, r).expandTowards(0, 2.6, 0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !(e instanceof EntityGlyphid)
                    && e.position().subtract(cloud.centre).horizontalDistanceSqr() < r * r)) {
                e.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0), this);
                e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), this);
            }
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (state() == State.HIDE) amount *= 0.25f;   // shut in its shell
        boolean hurt = super.hurtServer(level, source, amount);
        if (!hurt || !isAlive()) return hurt;
        float share = getMaxHealth() * LEG_DAMAGE_SHARE;
        legDamage += amount;
        int lose = (int) (legDamage / share) + (source.is(DamageTypeTags.IS_EXPLOSION) ? 1 : 0);
        legDamage %= share;
        Vec3 from = source.getSourcePosition();
        for (int i = 0; i < lose; i++) tearLeg(level, from);
        return hurt;
    }

    /** Tears off a leg on the side facing the hit (mesh side R is world direction (cos yaw, 0, sin yaw)). */
    private void tearLeg(ServerLevel level, Vec3 from) {
        int missing = missingLegs();
        if (missing == 0xFF) return;
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        boolean right = from == null ? random.nextBoolean()
                : (from.x - getX()) * Mth.cos(yaw) + (from.z - getZ()) * Mth.sin(yaw) > 0;
        int sideBits = right ? 0xF0 : 0x0F;
        if ((missing & sideBits) == sideBits) sideBits ^= 0xFF;
        int pick;
        do pick = random.nextInt(8); while ((sideBits & (1 << pick)) == 0 || (missing & (1 << pick)) != 0);
        missing |= 1 << pick;
        entityData.set(MISSING, (byte) missing);
        level.broadcastEntityEvent(this, EVENT_LEG_LOST);
        EntityGlybnikLeg.launch(level, this, pick);
        playSound(SoundEvents.SLIME_SQUISH, 2.0f, 0.5f);
        applyLegLoss(missing);
    }

    private void applyLegLoss(int missing) {
        int left = 4 - Integer.bitCount(missing & 0x0F), right = 4 - Integer.bitCount(missing & 0xF0);
        if (left + right < 4 || left == 0 || right == 0) {
            if (state() != State.COLLAPSED) setState(State.COLLAPSED);
            return;
        }
        AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.addOrReplacePermanentModifier(new AttributeModifier(LEG_SPEED,
                (left + right) / 8.0 - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
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
        output.putByte("missing_legs", (byte) missingLegs());
        output.putBoolean("collapsed", state() == State.COLLAPSED);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        int missing = input.getByteOr("missing_legs", (byte) 0) & 0xFF;
        entityData.set(MISSING, (byte) missing);
        if (missing != 0) applyLegLoss(missing);
        if (input.getBooleanOr("collapsed", false)) setState(State.COLLAPSED);
    }
}
