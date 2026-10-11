package com.hbm.enemies.mixin;

import com.hbm.blockentity.BlockEntityGlyphidSpawner;
import com.hbm.enemies.glyphid.HiveEvolution;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hive evolution: the spawner keeps its loaded age and tier, buffs what it spawns and raises the new castes. */
@Mixin(BlockEntityGlyphidSpawner.class)
public abstract class GlyphidSpawnerMixin extends BlockEntity {

    @Unique
    private long ntmeo$age;
    @Unique
    private int ntmeo$tier = -1;

    private GlyphidSpawnerMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow
    private static byte subtype(BlockState state) {
        throw new AssertionError();
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void ntmeo$grow(CallbackInfo ci) {
        if (!(level instanceof ServerLevel server)) return;
        ntmeo$age++;
        if (ntmeo$tier < 0 || ntmeo$age % 200 == 0) {
            int tier = HiveEvolution.tier(server, worldPosition, ntmeo$age);
            if (ntmeo$tier >= 0 && tier > ntmeo$tier) HiveEvolution.announce(server, worldPosition);
            ntmeo$tier = tier;
        }
    }

    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean ntmeo$buff(ServerLevel server, Entity entity, Operation<Boolean> original) {
        HiveEvolution.buff(entity, ntmeo$tier);
        return original.call(server, entity);
    }

    /** A full cooldown follows every finished spawn cycle. */
    @Inject(method = "delay", at = @At("HEAD"))
    private void ntmeo$castes(ServerLevel server, boolean fullCooldown, CallbackInfo ci) {
        if (fullCooldown) HiveEvolution.raiseCaste(server, worldPosition, subtype(getBlockState()), ntmeo$tier);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void ntmeo$save(ValueOutput output, CallbackInfo ci) {
        output.putLong("ntmeo_age", ntmeo$age);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void ntmeo$load(ValueInput input, CallbackInfo ci) {
        ntmeo$age = input.getLongOr("ntmeo_age", 0L);
    }
}
