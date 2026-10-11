package com.hbm.enemies.glyphid;

import com.hbm.enemies.Enemies;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** A Needler's needle: a lobbed bone spike, no air drag so the shooter's ballistics hold. Poisons while the womb lives. */
public class EntityNeedle extends ThrowableProjectile {

    static final double SPEED = 1.7, GRAVITY = 0.05;
    private static final float DAMAGE = 4;
    private boolean poison;

    public EntityNeedle(EntityType<? extends EntityNeedle> type, Level level) {
        super(type, level);
    }

    EntityNeedle(Level level, LivingEntity owner, Vec3 from, Vec3 velocity, boolean poison) {
        this(Enemies.NEEDLE.get(), level);
        setOwner(owner);
        setPos(from);
        setDeltaMovement(velocity);
        updateRotation();
        xRotO = getXRot();
        yRotO = getYRot();
        this.poison = poison;
    }

    /** Launch velocity reaching {@code to} from {@code from} at this speed on the flatter arc (45 degrees if out of range). */
    static Vec3 ballistic(Vec3 from, Vec3 to, double speed) {
        double dx = to.x - from.x, dz = to.z - from.z, d = Math.max(0.5, Math.hypot(dx, dz)), h = to.y - from.y;
        double v2 = speed * speed, disc = v2 * v2 - GRAVITY * (GRAVITY * d * d + 2 * h * v2);
        double angle = disc < 0 ? Math.PI / 4 : Math.atan((v2 - Math.sqrt(disc)) / (GRAVITY * d));
        double c = Math.cos(angle) * speed;
        return new Vec3(dx / d * c, Math.sin(angle) * speed, dz / d * c);
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    @Override
    protected float getAirDrag() {
        return 1.0f;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && tickCount > 200) discard();
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !(target instanceof EntityGlyphid);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!(level() instanceof ServerLevel level)) return;
        Entity target = hit.getEntity();
        if (target.hurtServer(level, damageSources().mobProjectile(this, getOwner() instanceof LivingEntity l ? l : null), DAMAGE)
                && poison && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0), this);
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        playSound(SoundEvents.ARROW_HIT, 0.6f, 1.6f);
        if (!level().isClientSide()) discard();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putBoolean("poison", poison);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        poison = input.getBooleanOr("poison", true);
    }
}
