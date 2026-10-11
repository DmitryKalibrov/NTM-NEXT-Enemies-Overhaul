package com.hbm.enemies;

import com.hbm.enemies.glyphid.EntityGlybnikLeg;
import com.hbm.enemies.glyphid.EntityGlyphidGlybnik;
import com.hbm.enemies.glyphid.EntityGlyphidNeedler;
import com.hbm.enemies.glyphid.EntityGlyphidTatterwing;
import com.hbm.enemies.glyphid.EntityNeedle;
import com.hbm.enemies.glyphid.EntityNeedlerPart;
import com.hbm.items.special.ItemSpawnEgg;
import com.hbm.registration.Addon;
import com.hbm.registration.Reg;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;

/**
 * NTM addon. Living attributes go through NTM's loader-specific registrar, so each loader runs a
 * subclass that implements {@link #attributes}.
 */
public abstract class Enemies implements Addon {

    public static final String MOD_ID = "ntm_enemies_overhaul";

    public static Reg.EntityHandle<EntityGlyphidGlybnik> GLYBNIK;
    public static Reg.EntityHandle<EntityGlybnikLeg> GLYBNIK_LEG;
    public static Reg.EntityHandle<EntityGlyphidNeedler> NEEDLER;
    public static Reg.EntityHandle<EntityNeedlerPart> NEEDLER_PART;
    public static Reg.EntityHandle<EntityNeedle> NEEDLE;
    public static Reg.EntityHandle<EntityGlyphidTatterwing> TATTERWING;
    private static final List<Reg.Handle<ItemSpawnEgg>> EGGS = new ArrayList<>();

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    protected abstract <T extends LivingEntity> void attributes(Reg.EntityHandle<T> type, Supplier<AttributeSupplier.Builder> attributes);

    @Override
    public void register() {
        Reg.Namespace ns = Reg.in(MOD_ID);
        GLYBNIK = mob(ns, "glyphid_glybnik", EntityType.Builder.of(EntityGlyphidGlybnik::new, MobCategory.MONSTER).sized(3.0f, 2.6f),
                EntityGlyphidGlybnik::createAttributes, 0x6E6670, 0xE8963C);
        GLYBNIK_LEG = ns.entity("glybnik_leg", () -> EntityType.Builder.<EntityGlybnikLeg>of(EntityGlybnikLeg::new, MobCategory.MISC)
                .noLootTable().noSave().sized(0.8f, 0.4f).clientTrackingRange(8).updateInterval(2)
                .build(ResourceKey.create(Registries.ENTITY_TYPE, id("glybnik_leg"))));
        NEEDLER = mob(ns, "glyphid_needler", EntityType.Builder.of(EntityGlyphidNeedler::new, MobCategory.MONSTER).sized(2.4f, 2.6f),
                EntityGlyphidNeedler::createAttributes, 0x7B7480, 0xF09A3C);
        NEEDLER_PART = ns.entity("needler_part", () -> EntityType.Builder.<EntityNeedlerPart>of(EntityNeedlerPart::new, MobCategory.MISC)
                .noLootTable().noSave().sized(0.8f, 0.4f).clientTrackingRange(8).updateInterval(2)
                .build(ResourceKey.create(Registries.ENTITY_TYPE, id("needler_part"))));
        NEEDLE = ns.entity("needle", () -> EntityType.Builder.<EntityNeedle>of(EntityNeedle::new, MobCategory.MISC)
                .noLootTable().sized(0.25f, 0.25f).clientTrackingRange(8).updateInterval(1)
                .build(ResourceKey.create(Registries.ENTITY_TYPE, id("needle"))));
        TATTERWING = mob(ns, "glyphid_tatterwing", EntityType.Builder.of(EntityGlyphidTatterwing::new, MobCategory.MONSTER).sized(1.6f, 1.8f),
                EntityGlyphidTatterwing::createAttributes, 0x6E4034, 0xDED2BA);
    }

    private <T extends LivingEntity> Reg.EntityHandle<T> mob(Reg.Namespace ns, String name, EntityType.Builder<T> builder,
                                                          Supplier<AttributeSupplier.Builder> attributes, int primary, int secondary) {
        Reg.EntityHandle<T> type = ns.entity(name, () -> builder.notInPeaceful().clientTrackingRange(8).updateInterval(3)
                .build(ResourceKey.create(Registries.ENTITY_TYPE, id(name))));
        attributes(type, attributes);
        EGGS.add(ns.item(name + "_spawn_egg", ItemSpawnEgg::new, () -> new Item.Properties()
                .delayedComponent(DataComponents.ENTITY_DATA, ctx -> TypedEntityData.of(type.get(), new CompoundTag()))));
        return type;
    }

    /** The creative tab; each loader registers it its own way. */
    public static CreativeModeTab.Builder tab(CreativeModeTab.Builder builder) {
        return builder.title(Component.translatable("itemGroup." + MOD_ID))
                .icon(() -> new ItemStack(EGGS.getFirst().get()))
                .displayItems((params, out) -> {
                    for (Reg.Handle<ItemSpawnEgg> egg : EGGS) out.accept(new ItemStack(egg.get()));
                });
    }
}
