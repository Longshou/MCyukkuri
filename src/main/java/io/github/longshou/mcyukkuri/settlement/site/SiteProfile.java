package io.github.longshou.mcyukkuri.settlement.site;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * How a settlement looks and stocks in one kind of country (project-original design; the wiki only says that yukkuri use
 * whatever hollows the land offers). The ordinal is saved as the site "palette", so the first four keep their old meaning:
 * 0 oak woods, 1 birch woods, 2 meadow and plains, 3 flower forest.
 * <p>
 * Every wall block must still pass {@code NaturalShelter.earth} (dirt tag, base stone or logs): the burrow survey is the
 * same in every biome.
 */
public enum SiteProfile {
    // name        accept top               soil              hill deep  stock   walls
    FOREST (1.00, Blocks.GRASS_BLOCK, Blocks.DIRT, 5, 0, 1.0, new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL},
            Blocks.MOSS_BLOCK, Blocks.OAK_LEAVES, Blocks.OAK_LOG, Blocks.MOSSY_COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.DEAD_BUSH},
            new Item[]{Items.SHORT_GRASS, Items.BROWN_MUSHROOM, Items.APPLE}, new int[]{4, 3, 2}, true),
    BIRCH  (1.00, Blocks.GRASS_BLOCK, Blocks.DIRT, 5, 0, 1.0, new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL},
            Blocks.MOSS_BLOCK, Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, Blocks.MOSSY_COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SHORT_GRASS},
            new Item[]{Items.SHORT_GRASS, Items.BROWN_MUSHROOM, Items.SWEET_BERRIES}, new int[]{4, 2, 2}, true),
    PLAINS (0.80, Blocks.GRASS_BLOCK, Blocks.DIRT, 5, 0, 0.8, new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.ROOTED_DIRT},
            Blocks.ROOTED_DIRT, Blocks.OAK_LEAVES, Blocks.OAK_LOG, Blocks.COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.DANDELION, Blocks.SHORT_GRASS, Blocks.AZURE_BLUET},
            new Item[]{Items.WHEAT, Items.SHORT_GRASS, Items.CARROT}, new int[]{4, 3, 1}, false),
    FLOWER (1.00, Blocks.GRASS_BLOCK, Blocks.DIRT, 5, 0, 1.0, new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL},
            Blocks.MOSS_BLOCK, Blocks.OAK_LEAVES, Blocks.OAK_LOG, Blocks.MOSSY_COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.FERN, Blocks.LILY_OF_THE_VALLEY},
            new Item[]{Items.SHORT_GRASS, Items.APPLE, Items.SWEET_BERRIES}, new int[]{3, 3, 2}, true),
    /** Spruce country: deeper burrows under a thick mound, berries and mushrooms. */
    TAIGA  (1.00, Blocks.GRASS_BLOCK, Blocks.DIRT, 6, 1, 1.3, new Block[]{Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.ROOTED_DIRT},
            Blocks.MOSS_BLOCK, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, Blocks.COBBLESTONE,
            new Block[]{Blocks.FERN, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SWEET_BERRY_BUSH, Blocks.DEAD_BUSH},
            new Item[]{Items.SWEET_BERRIES, Items.BROWN_MUSHROOM, Items.SHORT_GRASS}, new int[]{4, 3, 2}, true),
    /** Snowfield: the deepest burrows, snow over the mound, the biggest stores. */
    SNOW   (0.70, Blocks.GRASS_BLOCK, Blocks.DIRT, 6, 1, 1.7, new Block[]{Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL},
            Blocks.MOSS_BLOCK, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, Blocks.COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.DEAD_BUSH, Blocks.FERN, Blocks.SHORT_GRASS},
            new Item[]{Items.SWEET_BERRIES, Items.WHEAT, Items.BROWN_MUSHROOM, Items.APPLE}, new int[]{4, 3, 2, 1}, true),
    SAVANNA(0.60, Blocks.GRASS_BLOCK, Blocks.DIRT, 5, 0, 0.9, new Block[]{Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT},
            Blocks.COARSE_DIRT, Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, Blocks.ANDESITE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.SHORT_GRASS, Blocks.DEAD_BUSH, Blocks.SHORT_GRASS},
            new Item[]{Items.WHEAT, Items.SHORT_GRASS, Items.MELON_SLICE}, new int[]{4, 3, 1}, false),
    /** Marsh and mangrove: the mound is built higher so the floor stays dry. */
    SWAMP  (0.60, Blocks.GRASS_BLOCK, Blocks.DIRT, 7, 0, 1.0, new Block[]{Blocks.DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.COARSE_DIRT},
            Blocks.MOSS_BLOCK, Blocks.OAK_LEAVES, Blocks.MANGROVE_LOG, Blocks.MOSSY_COBBLESTONE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SHORT_GRASS, Blocks.BLUE_ORCHID, Blocks.FERN},
            new Item[]{Items.BROWN_MUSHROOM, Items.SHORT_GRASS, Items.MELON_SLICE}, new int[]{4, 3, 1}, true),
    /** Not generated on its own: rock crevices in dune country, for packs that add the biome and for commands. */
    DESERT (0.20, Blocks.SAND, Blocks.SANDSTONE, 4, 0, 0.5, new Block[]{Blocks.ANDESITE, Blocks.TUFF, Blocks.COARSE_DIRT, Blocks.ANDESITE},
            Blocks.COARSE_DIRT, Blocks.OAK_LEAVES, Blocks.OAK_LOG, Blocks.ANDESITE,
            new Block[]{Blocks.DEAD_BUSH, Blocks.DEAD_BUSH, Blocks.AIR, Blocks.AIR},
            new Item[]{Items.WHEAT, Items.SHORT_GRASS}, new int[]{2, 1}, false),
    BADLANDS(0.20, Blocks.RED_SAND, Blocks.TERRACOTTA, 4, 0, 0.5, new Block[]{Blocks.GRANITE, Blocks.TUFF, Blocks.COARSE_DIRT, Blocks.GRANITE},
            Blocks.COARSE_DIRT, Blocks.OAK_LEAVES, Blocks.OAK_LOG, Blocks.GRANITE,
            new Block[]{Blocks.DEAD_BUSH, Blocks.AIR, Blocks.AIR},
            new Item[]{Items.WHEAT, Items.SHORT_GRASS}, new int[]{2, 1}, false),
    /** Windswept hills and stony country: stone shows through, burrows sit under bare rock and turf. */
    HILLS  (0.70, Blocks.GRASS_BLOCK, Blocks.STONE, 6, 0, 1.0, new Block[]{Blocks.STONE, Blocks.ANDESITE, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.TUFF},
            Blocks.MOSS_BLOCK, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, Blocks.ANDESITE,
            new Block[]{Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SHORT_GRASS, Blocks.DEAD_BUSH},
            new Item[]{Items.SHORT_GRASS, Items.SWEET_BERRIES, Items.BROWN_MUSHROOM}, new int[]{4, 2, 2}, true);

    /** Chance (0..1) that a generated start in this country is kept; the rest is the rarity of the biome. */
    public final double accept;
    public final Block top, soil, floor, leaf, log, rock;
    /** Peak height of a burrow mound above the floor (mounds may be higher where the land is). */
    public final int hill;
    /** Extra burrow depth: 0 normal, 1 one more row of beds. */
    public final int deep;
    /** Winter stock multiplier at founding. */
    public final double stock;
    public final Block[] walls, flora;
    public final Item[] food;
    public final int[] foodWeight;
    /** True where podzol, moss and mushrooms belong to the ground. */
    public final boolean damp;

    SiteProfile(double accept, Block top, Block soil, int hill, int deep, double stock, Block[] walls, Block floor, Block leaf, Block log, Block rock,
                Block[] flora, Item[] food, int[] foodWeight, boolean damp) {
        this.accept = accept; this.top = top; this.soil = soil; this.hill = hill; this.deep = deep; this.stock = stock; this.walls = walls;
        this.floor = floor; this.leaf = leaf; this.log = log; this.rock = rock; this.flora = flora; this.food = food; this.foodWeight = foodWeight; this.damp = damp;
    }

    public boolean snowy() { return this == SNOW; }
    /** Dune and mesa country: no leaf cover, only dead brush and stone. */
    public boolean arid() { return this == DESERT || this == BADLANDS; }

    public static SiteProfile byOrdinal(int index) {
        var all = values();
        return all[Math.floorMod(index, all.length)];
    }

    /** The profile of the country a site stands in. */
    public static SiteProfile of(Holder<Biome> biome) {
        if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) return BIRCH;
        if (biome.is(Biomes.FLOWER_FOREST)) return FLOWER;
        if (biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS) || biome.is(Biomes.MEADOW) || biome.is(Biomes.CHERRY_GROVE)) return PLAINS;
        if (biome.is(Biomes.TAIGA) || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) return TAIGA;
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.SNOWY_SLOPES)
                || biome.is(Biomes.ICE_SPIKES) || biome.is(Biomes.SNOWY_BEACH)) return SNOW;
        if (biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA)) return SAVANNA;
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) return SWAMP;
        if (biome.is(Biomes.DESERT)) return DESERT;
        if (biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.WOODED_BADLANDS)) return BADLANDS;
        if (biome.is(Biomes.WINDSWEPT_HILLS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS) || biome.is(Biomes.WINDSWEPT_FOREST)
                || biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) return HILLS;
        return FOREST;
    }
}
