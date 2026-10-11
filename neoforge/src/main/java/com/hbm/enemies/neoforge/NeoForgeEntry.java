package com.hbm.enemies.neoforge;

import com.hbm.enemies.Enemies;
import com.hbm.enemies.client.Renderers;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.Reg;
import java.util.function.Supplier;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Enemies.MOD_ID)
public final class NeoForgeEntry {

    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Enemies.MOD_ID);

    public NeoForgeEntry(IEventBus modBus, Dist dist) {
        NeoForgeRegistrar.addon(Enemies.MOD_ID, modBus, new Addon());
        TABS.register("main", () -> Enemies.tab(CreativeModeTab.builder()).build());
        TABS.register(modBus);
        if (dist.isClient()) modBus.addListener(NeoForgeEntry::renderers);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        Renderers.register(new Renderers.Sink() {
            @Override
            public <T extends Entity> void entity(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
                event.registerEntityRenderer(type, provider);
            }
        });
    }

    private static final class Addon extends Enemies {
        @Override
        protected <T extends LivingEntity> void attributes(Reg.EntityHandle<T> type, Supplier<AttributeSupplier.Builder> attributes) {
            NeoForgeRegistrar.registerLivingAttributes(type, attributes);
        }
    }
}
