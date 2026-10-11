package com.hbm.enemies.client;

import com.hbm.enemies.Enemies;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Every renderer of the addon; each loader passes its own registration hook. */
public final class Renderers {

    /** Where a loader registers entity renderers (Fabric's registry, NeoForge's RegisterRenderers event). */
    public interface Sink {
        <T extends Entity> void entity(EntityType<? extends T> type, EntityRendererProvider<T> provider);
    }

    private Renderers() {}

    public static void register(Sink sink) {
        sink.entity(Enemies.GLYBNIK.get(), RenderGlybnik::new);
        sink.entity(Enemies.GLYBNIK_LEG.get(), RenderGlybnikLeg::new);
        sink.entity(Enemies.NEEDLER.get(), RenderNeedler::new);
        sink.entity(Enemies.NEEDLER_PART.get(), RenderNeedlerPart::new);
        sink.entity(Enemies.NEEDLE.get(), RenderNeedle::new);
        sink.entity(Enemies.TATTERWING.get(), RenderTatterwing::new);
    }
}
