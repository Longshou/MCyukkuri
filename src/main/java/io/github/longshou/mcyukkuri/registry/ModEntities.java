package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<io.github.longshou.mcyukkuri.subspecies.HatBoatEntity>> HAT_BOAT =
            TYPES.register("hat_boat", () -> EntityType.Builder.<io.github.longshou.mcyukkuri.subspecies.HatBoatEntity>of(
                    io.github.longshou.mcyukkuri.subspecies.HatBoatEntity::new, MobCategory.MISC)
                    .sized(1.05f, .20f).clientTrackingRange(8).updateInterval(2).build(MCYukkuri.id("hat_boat").toString()));
    public static final DeferredHolder<EntityType<?>,EntityType<io.github.longshou.mcyukkuri.entity.MaterialDrop>> MATERIAL_DROP=TYPES.register("material_drop",
            ()->EntityType.Builder.<io.github.longshou.mcyukkuri.entity.MaterialDrop>of(io.github.longshou.mcyukkuri.entity.MaterialDrop::new,MobCategory.MISC)
                    .sized(.25f,.25f).clientTrackingRange(6).updateInterval(20).build(MCYukkuri.id("material_drop").toString()));

    // Body bounds at 16 model units per block; hats and ribbons are visual accessories.
    public static final DeferredHolder<EntityType<?>,EntityType<io.github.longshou.mcyukkuri.entity.RemainsEntity>> REMAINS=TYPES.register("yukkuri_remains",
            ()->EntityType.Builder.<io.github.longshou.mcyukkuri.entity.RemainsEntity>of(io.github.longshou.mcyukkuri.entity.RemainsEntity::new,MobCategory.MISC)
                    .sized(.75f,.35f).clientTrackingRange(8).updateInterval(3).build(MCYukkuri.id("yukkuri_remains").toString()));
    private static final java.util.Map<YukkuriVariant, DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>>> VARIANTS =
            new java.util.EnumMap<>(YukkuriVariant.class);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> REIMU =
            register(YukkuriVariant.REIMU);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> MARISA =
            register(YukkuriVariant.MARISA);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> HOUSE_REIMU =
            register(YukkuriVariant.HOUSE_REIMU);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> HOUSE_MARISA =
            register(YukkuriVariant.HOUSE_MARISA);

    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> CHEN =
            register(YukkuriVariant.CHEN);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> ALICE =
            register(YukkuriVariant.ALICE);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> PATCHOULI =
            register(YukkuriVariant.PATCHOULI);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> REMILIA =
            register(YukkuriVariant.REMILIA);
    public static final DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> FLANDRE =
            register(YukkuriVariant.FLANDRE);

    private static DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> register(
            YukkuriVariant variant) {
        var holder = TYPES.register(variant.assetId(), () -> EntityType.Builder
                .<YukkuriEntity>of((type, level) -> new YukkuriEntity(type, level, variant), MobCategory.CREATURE)
                .sized(variant.width(), variant.height())
                .eyeHeight(variant.eyeHeight())
                .clientTrackingRange(8)
                .build(MCYukkuri.id(variant.assetId()).toString()));
        VARIANTS.put(variant, holder);
        return holder;
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        for (var variant : YukkuriVariant.values()) event.put(forVariant(variant), YukkuriEntity.createAttributes().build());
    }

    /** Holder lookup is safe during deferred item construction; resolving it remains the caller's responsibility. */
    public static DeferredHolder<EntityType<?>, EntityType<YukkuriEntity>> holderFor(YukkuriVariant variant) {
        return VARIANTS.get(variant);
    }
    public static EntityType<YukkuriEntity> forVariant(YukkuriVariant variant) { return holderFor(variant).get(); }

    public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        for (var variant : YukkuriVariant.values()) {
            if (!variant.naturalSpawn()) continue;
            event.register(forVariant(variant), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (entityType, level, reason, pos, random) -> YukkuriConfig.NATURAL_SPAWNS.get()
                            && level.getRawBrightness(pos, 0) > 8
                            && level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.ANIMALS_SPAWNABLE_ON)
                            && level.getFluidState(pos).isEmpty()
                            && level.getLevel().getEntitiesOfClass(YukkuriEntity.class,
                                    new net.minecraft.world.phys.AABB(pos).inflate(32)).size() < YukkuriConfig.LOCAL_SPAWN_CAP.get(),
                    RegisterSpawnPlacementsEvent.Operation.REPLACE);
        }
    }

    private ModEntities() {}
}
