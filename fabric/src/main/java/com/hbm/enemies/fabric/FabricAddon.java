package com.hbm.enemies.fabric;

import com.hbm.enemies.Enemies;
import com.hbm.registration.FabricRegistrar;
import com.hbm.registration.Reg;
import java.util.function.Supplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

/** NTM's "hbm:addon" entrypoint on Fabric. */
public final class FabricAddon extends Enemies {

    @Override
    protected <T extends LivingEntity> void attributes(Reg.EntityHandle<T> type, Supplier<AttributeSupplier.Builder> attributes) {
        FabricRegistrar.registerLivingAttributes(type, attributes);
    }
}
