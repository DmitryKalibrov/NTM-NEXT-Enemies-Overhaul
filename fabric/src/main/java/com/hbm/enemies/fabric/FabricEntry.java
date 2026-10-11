package com.hbm.enemies.fabric;

import com.hbm.enemies.Enemies;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Entities and items come in through NTM's "hbm:addon" entrypoint; this only adds the creative tab. */
public final class FabricEntry implements ModInitializer {

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Enemies.id("main"), Enemies.tab(FabricCreativeModeTab.builder()).build());
    }
}
