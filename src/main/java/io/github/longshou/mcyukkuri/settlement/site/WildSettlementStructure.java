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
 * Generated settlement ground: one piece holding the plan inputs. A site is a hill-foot settlement: it is only placed where
 * the clearing lies level at the foot of a slope that the burrows can be dug into ({@link SitePlanner#choose}), dry, below
 * the snow line. Core biomes take it wherever that holds; the edge biomes (#mcyukkuri:settlement_edge) only when a woodland
 * or river biome lies within about 40 blocks.
 */
public final class WildSettlementStructure extends Structure {
    public static final MapCodec<WildSettlementStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i), Codec.STRING.fieldOf("tier").forGetter(s -> s.tier.name().toLowerCase(Locale.ROOT)))
            .apply(i, WildSettlementStructure::new));
    public static final TagKey<Biome> EDGE = TagKey.create(Registries.BIOME, MCYukkuri.id("settlement_edge"));
    public static final TagKey<Biome> ANCHOR = TagKey.create(Registries.BIOME, MCYukkuri.id("settlement_anchor"));
    /** Height grid spacing, and how far the clearing may move from the chunk middle to sit at the foot of the slope. */
    private static final int STEP = 4, SHIFT = 6;
    private final SiteTier tier;

    private WildSettlementStructure(StructureSettings settings, String tier) {
        super(settings);
        var parsed = SiteTier.byName(tier);
        this.tier = parsed == null || !parsed.generates() ? SiteTier.BAND : parsed;
    }

    private static int surface(GenerationContext c, int x, int z) {
        return c.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, c.heightAccessor(), c.randomState());
    }

    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        var chunk = c.chunkPos();
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        var generator = c.chunkGenerator();
        int surface = surface(c, cx, cz);
        var biome = c.biomeSource().getNoiseBiome(QuartPos.fromBlock(cx), QuartPos.fromBlock(surface), QuartPos.fromBlock(cz), c.randomState().sampler());
        // Cheap rejections first: most start chunks lie in biomes that can never hold a site.
        if (!c.validBiome().test(biome) || biome.is(EDGE) && !nearAnchor(c, cx, cz, surface)) return Optional.empty();
        // Rarer country (savanna, bare hills, the dark forest) keeps only a share of its starts.
        if (c.random().nextDouble() >= SiteProfile.accept(biome)) return Optional.empty();
        // No slope within ten blocks, no burrows to dig: flat country is left to the lone wanderers and natural hollows.
        int high = surface;
        for (int i = 0; i < 8; i++)
            high = Math.max(high, surface(c, cx + (int)Math.round(Math.cos(i * Math.PI / 4) * 10), cz + (int)Math.round(Math.sin(i * Math.PI / 4) * 10)));
        if (high - surface < 3) return Optional.empty();
        int reach = tier.bound + SHIFT, n = reach * 2 / STEP + 1;
        int x0 = cx - reach, z0 = cz - reach;
        int[] heights = new int[n * n];
        boolean[] wet = new boolean[n * n];
        for (int iz = 0; iz < n; iz++) for (int ix = 0; ix < n; ix++) {
            int x = x0 + ix * STEP, z = z0 + iz * STEP, top = surface(c, x, z);
            heights[iz * n + ix] = top;
            wet[iz * n + ix] = top != generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, c.heightAccessor(), c.randomState());
        }
        var terrain = new SiteBuilder.GridTerrain(x0, z0, STEP, n, n, heights);
        long seed = c.random().nextLong();
        int palette = SiteWorld.palette(biome);
        var plan = SitePlanner.choose(tier, seed, cx, cz, palette, terrain, SHIFT);
        if (plan == null || plan.level < generator.getSeaLevel() || plan.level + 24 > c.heightAccessor().getMaxBuildHeight()) return Optional.empty();
        // Water inside the trodden ground spoils the site (the meadow ring may touch a shore); so does lying snow.
        for (int iz = 0; iz < n; iz++) for (int ix = 0; ix < n; ix++)
            if (wet[iz * n + ix] && Math.hypot(x0 + ix * STEP - plan.ox, z0 + iz * STEP - plan.oz) <= tier.hedge + 3) return Optional.empty();
        if (biome.value().coldEnoughToSnow(new BlockPos(plan.ox, plan.level, plan.oz))) return Optional.empty();
        var box = new BoundingBox(plan.minX, plan.level - 10, plan.minZ, plan.maxX, plan.level + 24, plan.maxZ);
        var piece = new WildSettlementPiece(tier, seed, plan.rotation, plan.ox, plan.oz, palette, x0, z0, STEP, n, heights, box);
        return Optional.of(new GenerationStub(new BlockPos(plan.ox, plan.level, plan.oz), builder -> builder.addPiece(piece)));
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
