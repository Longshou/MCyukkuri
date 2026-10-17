package io.github.longshou.mcyukkuri.settlement.site;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registrations of the generated settlement structure and the biome to palette mapping. */
public final class SiteWorld {
    public static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, MCYukkuri.MOD_ID);
    public static final DeferredHolder<StructureType<?>, StructureType<WildSettlementStructure>> TYPE =
            TYPES.register("wild_settlement", () -> () -> WildSettlementStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
            PIECES.register("wild_settlement", () -> (StructurePieceType.ContextlessType) WildSettlementPiece::new);

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        PIECES.register(bus);
    }

    /** The site palette is the ordinal of the {@link SiteProfile} of the country (0 oak woods, 1 birch, 2 plains, 3 flower forest, then taiga, snow, savanna, swamp, ...). */
    public static int palette(Holder<Biome> biome) { return SiteProfile.of(biome).ordinal(); }
    private SiteWorld() {}
}
