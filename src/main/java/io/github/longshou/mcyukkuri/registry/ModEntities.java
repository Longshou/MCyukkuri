package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MCYukkuri.MOD_ID);

    // Geometry uses 16 units per block. Bounds include the selected ribbons and hat.
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> REIMU =
            register("reimu", 1.05F, 0.75F, 0.35F);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> MARISA =
            register("marisa", 1.20F, 1.15F, 0.35F);

    private static DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> register(
            String species, float width, float height, float eyeHeight) {
        return TYPES.register(species, () -> EntityType.Builder
                .<YukkuriEntity>of((type, level) -> new YukkuriEntity(type, level, species), MobCategory.CREATURE)
                .sized(width, height)
                .eyeHeight(eyeHeight)
                .clientTrackingRange(8)
                .build(MCYukkuri.id(species).toString()));
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(REIMU.get(), YukkuriEntity.createAttributes().build());
        event.put(MARISA.get(), YukkuriEntity.createAttributes().build());
    }

    private ModEntities() {}
}
