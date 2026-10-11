package com.hbm.enemies.glyphid;

import com.hbm.data.MobData;
import com.hbm.enemies.Enemies;
import com.hbm.entity.mob.glyphid.EntityGlyphid;
import com.hbm.handler.pollution.PollutionHandler;
import com.hbm.handler.pollution.PollutionType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

/**
 * Hives grow up: a spawner's tier comes from how long it has been loaded plus the soot around it.
 * Every tier makes its glyphids tougher; from tier 1 the hive may raise a Needler or a Tatterwing, from tier 2 a Glybnik.
 */
public final class HiveEvolution {

    public static final int MAX_TIER = 3;
    /** Loaded ticks per tier: one, three and seven days. */
    private static final long[] TIER_AGE = {0, 24_000, 72_000, 168_000};
    private static final float CASTE_CHANCE = 0.15f;

    private HiveEvolution() {}

    public static int tier(long age, float soot) {
        int t = 0;
        for (int i = 1; i <= MAX_TIER; i++) if (age >= TIER_AGE[i]) t = i;
        t += soot >= 25 ? 2 : soot >= 10 ? 1 : 0;
        return Math.min(t, MAX_TIER);
    }

    public static int tier(ServerLevel level, BlockPos pos, long age) {
        return tier(age, PollutionHandler.getPollution(level, pos, PollutionType.SOOT));
    }

    /** A hive moved up a tier: let the player hear it. */
    public static void announce(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4.0f, 0.5f);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 60, 2.0, 1.0, 2.0, 0.02);
    }

    /** Called for every glyphid a spawner adds: health and damage grow with the tier. */
    public static void buff(Entity entity, int tier) {
        if (tier <= 0 || !(entity instanceof EntityGlyphid glyphid)) return;
        modify(glyphid.getAttribute(Attributes.MAX_HEALTH), 0.25 * tier);
        modify(glyphid.getAttribute(Attributes.ATTACK_DAMAGE), 0.15 * tier);
        glyphid.setHealth(glyphid.getMaxHealth());
    }

    private static void modify(AttributeInstance attribute, double amount) {
        if (attribute == null) return;
        attribute.addOrReplacePermanentModifier(new AttributeModifier(Enemies.id("hive_tier"), amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** After a spawn cycle: the hive may raise one caste it has grown into (Needler and Tatterwing tier 1+, Glybnik 2+), one of each per 48 blocks. */
    public static void raiseCaste(ServerLevel level, BlockPos pos, byte subtype, int tier) {
        if (tier < 1 || level.getRandom().nextFloat() > CASTE_CHANCE) return;
        int pick = level.getRandom().nextInt(tier >= 2 ? 3 : 2);
        Class<? extends EntityGlyphid> caste = pick == 0 ? EntityGlyphidNeedler.class : pick == 1 ? EntityGlyphidTatterwing.class : EntityGlyphidGlybnik.class;
        if (!level.getEntitiesOfClass(caste, new AABB(pos).inflate(48)).isEmpty()) return;
        int range = MobData.SPAWN_RANGE.get();
        EntityGlyphid glyphid = (pick == 0 ? Enemies.NEEDLER : pick == 1 ? Enemies.TATTERWING : Enemies.GLYBNIK).get().create(level, EntitySpawnReason.SPAWNER);
        if (glyphid == null) return;
        for (int attempt = 0; attempt < 6; attempt++) {
            double x = pos.getX() + 0.5 + (level.getRandom().nextDouble() - level.getRandom().nextDouble()) * range;
            double z = pos.getZ() + 0.5 + (level.getRandom().nextDouble() - level.getRandom().nextDouble()) * range;
            glyphid.snapTo(x, pos.getY(), z, level.getRandom().nextFloat() * 360f, 0f);
            if (!level.noCollision(glyphid) || level.containsAnyLiquid(glyphid.getBoundingBox())) continue;
            if (subtype != 0) glyphid.setSubtype(subtype);
            buff(glyphid, tier);
            level.addFreshEntity(glyphid);
            return;
        }
    }
}
