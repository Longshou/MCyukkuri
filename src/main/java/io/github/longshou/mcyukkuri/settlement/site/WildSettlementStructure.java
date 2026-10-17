package io.github.longshou.mcyukkuri.settlement.site;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * Generated settlement ground: one piece holding the plan inputs. Core woodland biomes take it anywhere dry and level;
 * the edge biomes (#mcyukkuri:settlement_edge) only when a woodland or river biome lies within about 40 blocks.
 */
public final class WildSettlementStructure extends Structure {
    public static final MapCodec<WildSettlementStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i), Codec.STRING.fieldOf("tier").forGetter(s -> s.tier.name().toLowerCase(Locale.ROOT)))
            .apply(i, WildSettlementStructure::new));
    public static final TagKey<Biome> EDGE = TagKey.create(Registries.BIOME, MCYukkuri.id("settlement_edge"));
    public static final TagKey<Biome> ANCHOR = TagKey.create(Registries.BIOME, MCYukkuri.id("settlement_anchor"));
    private static final int STEP = 4;
    private final SiteTier tier;

    private WildSettlementStructure(StructureSettings settings, String tier) {
        super(settings);
        var parsed = SiteTier.byName(tier);
        this.tier = parsed == null || !parsed.generates() ? SiteTier.BAND : parsed;
    }

    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        var chunk = c.chunkPos();
        int ox = chunk.getMiddleBlockX(), oz = chunk.getMiddleBlockZ();
        var generator = c.chunkGenerator();
        int surface = generator.getBaseHeight(ox, oz, Heightmap.Types.WORLD_SURFACE_WG, c.heightAccessor(), c.randomState());
        var biome = c.biomeSource().getNoiseBiome(QuartPos.fromBlock(ox), QuartPos.fromBlock(surface), QuartPos.fromBlock(oz), c.randomState().sampler());
        // Cheap rejections first: most start chunks lie in biomes that can never hold a site.
        if (!c.validBiome().test(biome) || biome.is(EDGE) && !nearAnchor(c, ox, oz, surface)) return Optional.empty();
        int reach = tier.bound, n = reach * 2 / STEP + 1;
        int x0 = ox - reach, z0 = oz - reach;
        int[] heights = new int[n * n];
        int core = (int)tier.hedge + 3;
        for (int iz = 0; iz < n; iz++) for (int ix = 0; ix < n; ix++) {
            int x = x0 + ix * STEP, z = z0 + iz * STEP;
            int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, c.heightAccessor(), c.randomState());
            heights[iz * n + ix] = top;
            // Water inside the hedge circle spoils the site; the meadow ring may touch a shore.
            if (Math.hypot(x - ox, z - oz) <= core && top != generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, c.heightAccessor(), c.randomState()))
                return Optional.empty();
        }
        var terrain = new SiteBuilder.GridTerrain(x0, z0, STEP, n, n, heights);
        long seed = c.random().nextLong();
        int rotation = c.random().nextInt(4);
        int palette = SiteWorld.palette(biome);
        var plan = SitePlanner.plan(tier, seed, rotation, ox, oz, palette, terrain, true);
        if (plan == null || plan.level < generator.getSeaLevel() || plan.level + 20 > c.heightAccessor().getMaxBuildHeight()) return Optional.empty();
        var box = new BoundingBox(plan.minX, plan.level - 10, plan.minZ, plan.maxX, plan.level + 20, plan.maxZ);
        var piece = new WildSettlementPiece(tier, seed, rotation, ox, oz, palette, x0, z0, STEP, n, heights, box);
        return Optional.of(new GenerationStub(new BlockPos(ox, plan.level, oz), builder -> builder.addPiece(piece)));
    }

    /** Eight samples on a 40 block ring; one woodland or river biome is enough. */
    private static boolean nearAnchor(GenerationContext c, int ox, int oz, int y) {
        for (int i = 0; i < 8; i++) {
            int x = ox + (int)Math.round(Math.cos(i * Math.PI / 4) * 40), z = oz + (int)Math.round(Math.sin(i * Math.PI / 4) * 40);
            var other = c.biomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), c.randomState().sampler());
            if (other.is(ANCHOR)) return true;
        }
        return false;
    }

    @Override public StructureType<?> type() { return SiteWorld.TYPE.get(); }
}
