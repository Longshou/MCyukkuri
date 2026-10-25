import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlock;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.SitePlan;
import io.github.longshou.mcyukkuri.settlement.site.SitePlanner;
import io.github.longshou.mcyukkuri.settlement.site.SiteProfile;
import io.github.longshou.mcyukkuri.settlement.site.SiteTier;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Offline preview harness (settlement_site_preview.py): plans and builds one site with the mod's own SitePlanner and
 * SiteBuilder on synthetic ground, in memory, and prints the plan, its slots and every non-air block as JSON lines.
 * No game, no Gradle: the vanilla registries are bootstrapped just far enough for block states.
 * <p>
 * Usage: SiteDump tier seed profile terrain mode   (terrain flat|hill|rock, mode strict|command)
 */
public final class SiteDump {
    private static final Map<Long, BlockState> WORLD = new HashMap<>();
    private static String terrain = "hill";

    /** Natural ground (first free y): flat 64, or a slope with a wavy foot five blocks north of the origin (earth 0.8, rock 0.9 up per block), crest nine blocks up. */
    static int ground(int x, int z) {
        if (terrain.equals("flat")) return 64;
        int foot = -5 + (int)Math.round(1.5 * Math.sin(x / 6.0));
        int d = foot - z;
        return 64 + (d < 0 ? 0 : Math.min(9, (int)(d * (terrain.equals("rock") ? .9 : .8)) + 1));
    }

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        try { Bootstrap.bootStrap(); } catch (Throwable languageMissing) { /* blocks are registered before the language check */ }
        // The forage pile is a mod block: make one unregistered instance and bind the deferred holder to it.
        var registry = MappedRegistry.class;
        var frozen = registry.getDeclaredField("frozen"); frozen.setAccessible(true); frozen.set(BuiltInRegistries.BLOCK, false);
        var holders = registry.getDeclaredField("unregisteredIntrusiveHolders"); holders.setAccessible(true);
        holders.set(BuiltInRegistries.BLOCK, new IdentityHashMap<>());
        Block pile = new ForagePileBlock(BlockBehaviour.Properties.of());
        var bound = DeferredHolder.class.getDeclaredField("holder"); bound.setAccessible(true);
        bound.set(ModBlocks.FORAGE_PILE, Holder.direct(pile));

        var tier = SiteTier.byName(args[0]);
        long seed = Long.parseLong(args[1]);
        var profile = SiteProfile.valueOf(args[2]);
        terrain = args[3];
        boolean strict = args[4].equals("strict");
        for (int x = -44; x <= 44; x++) for (int z = -44; z <= 44; z++) {
            int g = ground(x, z);
            for (int y = 58; y < g; y++) WORLD.put(BlockPos.asLong(x, y, z), y == g - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : (terrain.equals("rock") && y >= 64 ? Blocks.STONE : Blocks.DIRT).defaultBlockState());
        }
        SitePlanner.Ground ground = SiteDump::ground;
        int palette = profile.ordinal();
        SitePlan plan = strict ? SitePlanner.choose(tier, seed, 0, 0, palette, ground, 6)
                : SitePlanner.plan(tier, seed, SitePlanner.bestRotation(tier, seed, 0, 0, palette, ground), 0, 0, palette, ground, false);
        var out = new PrintWriter(new OutputStreamWriter(System.out, "UTF-8"));
        if (plan == null) { out.println("{\"fail\":\"" + SitePlanner.lastFailure() + "\"}"); out.flush(); return; }
        LevelAccessor level = (LevelAccessor)Proxy.newProxyInstance(SiteDump.class.getClassLoader(), new Class<?>[]{LevelAccessor.class}, (proxy, m, a) -> {
            switch (m.getName()) {
                case "getBlockState": return WORLD.getOrDefault(((BlockPos)a[0]).asLong(), Blocks.AIR.defaultBlockState());
                case "setBlock": WORLD.put(((BlockPos)a[0]).asLong(), (BlockState)a[1]); return true;
                case "getMinBuildHeight": return -64;
                case "getMaxBuildHeight": return 320;
                case "hasChunk": return true;
                case "scheduleTick": return null;
                case "hashCode": return 1;
                case "equals": return proxy == a[0];
                case "toString": return "preview";
                default:
                    if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
                    throw new UnsupportedOperationException(m.getName());
            }
        });
        SiteBuilder.build(level, plan, new BoundingBox(plan.minX, 0, plan.minZ, plan.maxX, 200, plan.maxZ), SiteDump::ground, false, false);
        out.println("{\"tier\":\"" + plan.tier + "\",\"rotation\":" + plan.rotation + ",\"ox\":" + plan.ox + ",\"oz\":" + plan.oz + ",\"level\":" + plan.level
                + ",\"profile\":\"" + plan.profile() + "\",\"bound\":" + plan.tier.bound + "}");
        for (var slot : plan.slots()) out.println("{\"slot\":\"" + slot.kind() + "\",\"x\":" + slot.anchor().getX() + ",\"y\":" + slot.anchor().getY()
                + ",\"z\":" + slot.anchor().getZ() + ",\"facing\":\"" + slot.facing().getName() + "\",\"cap\":" + slot.capacity() + "}");
        for (var entry : WORLD.entrySet()) {
            var state = entry.getValue();
            if (state.isAir()) continue;
            var p = BlockPos.of(entry.getKey());
            String id = state.getBlock() == pile ? "forage_pile" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            out.println("{\"b\":\"" + id + "\",\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ() + "}");
        }
        out.flush();
    }
}
