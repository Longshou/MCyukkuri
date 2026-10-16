package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.saveddata.SavedData;

/** Factory to shop logistics (DEVELOPMENT_PLAN 5.1): known shopkeeper positions plus pending deliveries. A delivery only holds a custody token; the individual stays captive until released exactly once at the shop. */
public final class ShopDeliveries extends SavedData {
    public static final long TRANSIT = 2400;
    public static final int RANGE = 256;
    public record Delivery(UUID token, UUID shopkeeper, BlockPos shopPos, long shipped, UUID factory) {}
    private final Map<UUID, GlobalPos> shops = new HashMap<>();
    private final List<Delivery> pending = new ArrayList<>();

    public static ShopDeliveries get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopDeliveries::new, ShopDeliveries::load), "mcyukkuri_shop_deliveries");
    }
    public static boolean enabled() {
        try { return io.github.longshou.mcyukkuri.human.HumanWorld.FACTORY_SHOP_SHIPPING.get(); } catch (RuntimeException e) { return true; }
    }
    public static boolean factoryOrigin(YukkuriEntity y) { return y.getPersistentData().hasUUID("FactoryOrigin"); }

    /** Called while a shopkeeper ticks, so factories can find the shop without loading it. */
    public static void register(HumanNpc shopkeeper) {
        if (!(shopkeeper.level() instanceof ServerLevel level)) return;
        var pos = GlobalPos.of(level.dimension(), shopkeeper.blockPosition());
        var data = get(level);
        if (!pos.equals(data.shops.put(shopkeeper.getUUID(), pos))) data.setDirty();
    }
    /** Nearest registered shopkeeper in the same dimension within RANGE blocks, or null. */
    public Map.Entry<UUID, GlobalPos> nearestShop(ServerLevel level, BlockPos from) {
        Map.Entry<UUID, GlobalPos> best = null; double bestD = (double) RANGE * RANGE;
        for (var e : shops.entrySet()) {
            if (!e.getValue().dimension().equals(level.dimension())) continue;
            double d = e.getValue().pos().distSqr(from);
            if (d <= bestD) { bestD = d; best = e; }
        }
        return best;
    }
    public int pendingFor(UUID shopkeeper) { return (int) pending.stream().filter(d -> d.shopkeeper.equals(shopkeeper)).count(); }
    public List<Delivery> pending() { return List.copyOf(pending); }
    public void add(Delivery d) { pending.add(d); setDirty(); }

    /** Release at most one due delivery for the shop this keeper or shopkeeper serves; false if nothing arrived. */
    public static boolean arrive(HumanNpc actor, ServerLevel level, long now) {
        var data = get(level);
        if (data.pending.isEmpty()) return false;
        var shop = actor.siteRole().equals("shopkeeper") ? actor : level.getEntitiesOfClass(HumanNpc.class, actor.getBoundingBox().inflate(48),
                n -> n.isAlive() && n.siteRole().equals("shopkeeper")).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
        if (shop == null) return false;
        var custody = YukkuriWorldData.get(level);
        for (var it = data.pending.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (!d.shopkeeper.equals(shop.getUUID())) continue;
            if (!custody.hasToken(d.token)) { it.remove(); data.setDirty(); continue; } // record gone: never invent an animal
            if (now - d.shipped < TRANSIT || ShopPens.stock(level, shop) >= ShopMenu.STOCK_CAPACITY) continue;
            var pen = ShopPens.freePen(level, shop, null, false);
            if (pen == null) continue;
            var y = custody.release(d.token, level, new Vec3(pen.getX() + .5, pen.getY() + 1, pen.getZ() + .5), actor.getYRot());
            if (y == null) continue; // token stays valid; retried on the next tick
            it.remove(); data.setDirty();
            HumanCommerce.assign(y, shop.getUUID(), "stock");
            y.life().releaseHome();
            var clean = HumanCommerce.data(y).copy(); clean.remove("ShopCandidate"); HumanCommerce.set(y, clean);
            if (!y.pet().pedigreeKnown()) y.pet().setPedigree(20 + y.getRandom().nextInt(21));
            y.getPersistentData().putUUID("FactoryOrigin", d.factory);
            y.getNavigation().stop(); y.moveTo(pen.getX() + .5, pen.getY() + .12, pen.getZ() + .5, y.getYRot(), 0);
            actor.swing(InteractionHand.MAIN_HAND);
            y.playAction("look_around"); y.showReaction("cry", "", 100);
            return true;
        }
        return false;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var s = new ListTag();
        shops.forEach((id, pos) -> { var c = new CompoundTag(); c.putUUID("Id", id); c.putString("Dim", pos.dimension().location().toString()); c.putLong("Pos", pos.pos().asLong()); s.add(c); });
        tag.put("Shops", s);
        var p = new ListTag();
        for (var d : pending) { var c = new CompoundTag(); c.putUUID("Token", d.token); c.putUUID("Shop", d.shopkeeper); c.putLong("Pos", d.shopPos.asLong()); c.putLong("Shipped", d.shipped); c.putUUID("Factory", d.factory); p.add(c); }
        tag.put("Pending", p); return tag;
    }
    public static ShopDeliveries load(CompoundTag tag, HolderLookup.Provider registries) {
        var r = new ShopDeliveries();
        for (Tag t : tag.getList("Shops", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t; var dim = net.minecraft.resources.ResourceLocation.tryParse(c.getString("Dim"));
            if (c.hasUUID("Id") && dim != null) r.shops.put(c.getUUID("Id"), GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim), BlockPos.of(c.getLong("Pos"))));
        }
        for (Tag t : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("Token") && c.hasUUID("Shop") && c.hasUUID("Factory")) r.pending.add(new Delivery(c.getUUID("Token"), c.getUUID("Shop"), BlockPos.of(c.getLong("Pos")), c.getLong("Shipped"), c.getUUID("Factory")));
        }
        return r;
    }
}
