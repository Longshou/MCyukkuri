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
    /** A delivery older than this (3 game days) is resolved: redirected to another live shop, else returned to its factory. */
    public static final long EXPIRY = 72000;
    public record Delivery(UUID token, UUID shopkeeper, BlockPos shopPos, long shipped, UUID factory) {}
    private final Map<UUID, GlobalPos> shops = new HashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();
    private final List<Delivery> pending = new ArrayList<>();
    /** Unsold stock sent back to a factory: the token waits until the factory worker collects it (docs/PET_SHOP.md section 13). */
    public record Recall(UUID token, UUID shopkeeper, UUID factory, long shipped) {}
    private final Map<UUID, GlobalPos> factories = new HashMap<>();
    private final Map<UUID, Long> factorySeen = new HashMap<>();
    private final List<Recall> recalls = new ArrayList<>();

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
        long now = level.getGameTime(), before = data.seen.getOrDefault(shopkeeper.getUUID(), -1L); data.seen.put(shopkeeper.getUUID(), now);
        if (!pos.equals(data.shops.put(shopkeeper.getUUID(), pos)) || now - before > 6000) data.setDirty();
    }
    /** Called while a factory worker ticks, so a shop can find the factory that will take its unsold stock. */
    public static void registerFactory(HumanNpc worker) {
        if (!(worker.level() instanceof ServerLevel level)) return;
        var data = get(level); var pos = GlobalPos.of(level.dimension(), worker.blockPosition());
        long now = level.getGameTime(), before = data.factorySeen.getOrDefault(worker.getUUID(), -1L); data.factorySeen.put(worker.getUUID(), now);
        if (!pos.equals(data.factories.put(worker.getUUID(), pos)) || now - before > 6000) data.setDirty();
    }
    /** Nearest live factory worker within RANGE blocks, or null. */
    public Map.Entry<UUID, GlobalPos> nearestFactory(ServerLevel level, BlockPos from) {
        Map.Entry<UUID, GlobalPos> best = null; double bestD = (double) RANGE * RANGE; long now = level.getGameTime();
        for (var e : factories.entrySet()) {
            if (!e.getValue().dimension().equals(level.dimension()) || factorySeen.getOrDefault(e.getKey(), -1L) < now - EXPIRY) continue;
            double d = e.getValue().pos().distSqr(from);
            if (d <= bestD) { bestD = d; best = e; }
        }
        return best;
    }
    public int recallsFor(UUID factory) { return (int) recalls.stream().filter(r -> r.factory.equals(factory)).count(); }
    public void addRecall(Recall r) { recalls.add(r); setDirty(); }
    public List<Recall> recalls() { return List.copyOf(recalls); }
    /** The factory worker takes one waiting recall once the transit time has passed; {@code intake} releases the individual at the factory and reports success. */
    public static boolean collect(HumanNpc worker, ServerLevel level, java.util.function.Predicate<UUID> intake) {
        var data = get(level);
        if (data.recalls.isEmpty()) return false;
        long now = level.getGameTime();
        for (var it = data.recalls.iterator(); it.hasNext(); ) {
            var r = it.next();
            if (!r.factory.equals(worker.getUUID())) continue;
            if (!YukkuriWorldData.get(level).hasToken(r.token)) { it.remove(); data.setDirty(); continue; }
            if (now - r.shipped < TRANSIT) continue;
            if (intake.test(r.token)) { it.remove(); data.setDirty(); return true; }
        }
        return false;
    }
    /** A recall the factory never collected (3 game days) goes back on the shelf it came from, hungrier than it left. */
    private static void returnStale(HumanNpc shop, ServerLevel level, long now) {
        var data = get(level); var custody = YukkuriWorldData.get(level);
        for (var it = data.recalls.iterator(); it.hasNext(); ) {
            var r = it.next();
            if (!r.shopkeeper.equals(shop.getUUID()) || now - r.shipped <= EXPIRY) continue;
            if (!custody.hasToken(r.token)) { it.remove(); data.setDirty(); continue; }
            var pen = ShopPens.freePen(level, shop, null, false);
            if (pen == null) continue;
            var y = custody.release(r.token, level, new Vec3(pen.getX() + .5, pen.getY() + 1, pen.getZ() + .5), shop.getYRot());
            if (y == null) continue;
            it.remove(); data.setDirty();
            y.pet().unlock("sale"); y.pet().unlock("courier");
            HumanCommerce.assign(y, shop.getUUID(), "stock");
            var tag = HumanCommerce.data(y).copy(); tag.putLong("ForSaleSince", now); tag.remove("StaleCuts"); HumanCommerce.set(y, tag);
            y.life().releaseHome(); y.getNavigation().stop(); y.moveTo(pen.getX() + .5, pen.getY() + .12, pen.getZ() + .5, y.getYRot(), 0);
            y.showReaction("cry", "", 100); return;
        }
    }

    /** Nearest registered shopkeeper in the same dimension within RANGE blocks, or null. */
    public Map.Entry<UUID, GlobalPos> nearestShop(ServerLevel level, BlockPos from) {
        Map.Entry<UUID, GlobalPos> best = null; double bestD = (double) RANGE * RANGE; long now = level.getGameTime();
        if (shops.keySet().removeIf(id -> seen.getOrDefault(id, -1L) < now - EXPIRY && pending.stream().noneMatch(d -> d.shopkeeper.equals(id)))) setDirty();
        for (var e : shops.entrySet()) {
            if (!e.getValue().dimension().equals(level.dimension()) || seen.getOrDefault(e.getKey(), -1L) < now - EXPIRY) continue;
            double d = e.getValue().pos().distSqr(from);
            if (d <= bestD) { bestD = d; best = e; }
        }
        return best;
    }
    public int pendingFor(UUID shopkeeper) { return (int) pending.stream().filter(d -> d.shopkeeper.equals(shopkeeper)).count(); }
    public List<Delivery> pending() { return List.copyOf(pending); }
    public void add(Delivery d) { pending.add(d); setDirty(); }

    /** Overdue deliveries (shop gone silent, or never had room) are redirected to another live shop in range, else the captive goes back to its factory, else to the wild at the shop. A token is only consumed by a successful release. */
    public static void resolveExpired(ServerLevel level, long now) {
        var data = get(level);
        if (data.pending.stream().noneMatch(d -> now - d.shipped > EXPIRY)) return;
        var custody = YukkuriWorldData.get(level);
        for (var it = data.pending.listIterator(); it.hasNext(); ) {
            var d = it.next();
            if (now - d.shipped <= EXPIRY || ShopCourier.inFlight(d.token)) continue;
            if (!custody.hasToken(d.token)) { it.remove(); data.setDirty(); continue; }
            if (!level.hasChunkAt(d.shopPos)) continue;
            Map.Entry<UUID, GlobalPos> alt = null; double best = (double) RANGE * RANGE;
            for (var e : data.shops.entrySet()) {
                if (e.getKey().equals(d.shopkeeper) || !e.getValue().dimension().equals(level.dimension()) || data.seen.getOrDefault(e.getKey(), -1L) < now - EXPIRY) continue;
                double dist = e.getValue().pos().distSqr(d.shopPos);
                if (dist <= best) { best = dist; alt = e; }
            }
            if (alt != null) { it.set(new Delivery(d.token, alt.getKey(), alt.getValue().pos(), now, d.factory)); data.setDirty(); continue; }
            var origin = level.getEntity(d.factory) instanceof HumanNpc n && n.isAlive() ? n : null;
            var base = origin != null ? origin.position() : Vec3.atBottomCenterOf(d.shopPos);
            YukkuriEntity y = null;
            for (int[] o : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                y = custody.release(d.token, level, base.add(o[0], 0, o[1]), 0);
                if (y != null) break;
            }
            if (y == null) continue;
            it.remove(); data.setDirty();
            if (origin != null) HumanCommerce.assign(y, origin.getUUID(), "nursery");
            else { HumanCommerce.set(y, new CompoundTag()); y.life().releaseHome(); io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("Undeliverable factory juvenile {} released unowned at {}", y.getUUID(), d.shopPos); }
            var clean = HumanCommerce.data(y).copy(); clean.remove("ShopCandidate"); HumanCommerce.set(y, clean);
        }
    }

    /** Release at most one due delivery for the shop this keeper or shopkeeper serves; false if nothing arrived. */
    public static boolean arrive(HumanNpc actor, ServerLevel level, long now) {
        var data = get(level);
        if (!data.recalls.isEmpty() && actor.job() == HumanNpc.Job.SHOPKEEPER) returnStale(actor, level, now);
        if (data.pending.isEmpty()) return false;
        resolveExpired(level, now);
        var shop = actor.job() == HumanNpc.Job.SHOPKEEPER ? actor : level.getEntitiesOfClass(HumanNpc.class, actor.getBoundingBox().inflate(48),
                n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
        if (shop == null) return false;
        var custody = YukkuriWorldData.get(level);
        boolean keepers = ShopCourier.hasKeeper(level, shop);
        var courier = keepers ? ShopCourier.idleKeeper(level, shop) : null;
        for (var it = data.pending.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (!d.shopkeeper.equals(shop.getUUID()) || ShopCourier.inFlight(d.token)) continue;
            if (!custody.hasToken(d.token)) { it.remove(); data.setDirty(); continue; } // record gone: never invent an animal
            if (now - d.shipped < TRANSIT || ShopPens.stock(level, shop) >= ShopMenu.STOCK_CAPACITY) continue;
            var pen = ShopPens.freePen(level, shop, null, false);
            if (pen == null) continue;
            if (keepers) { // a keeper carries it over; the record stays until the animal is set down, and while every keeper is busy the delivery waits
                if (courier != null && ShopCourier.carry(level, courier, shop, d.token, pen)) return true;
                continue;
            }
            var y = custody.release(d.token, level, new Vec3(pen.getX() + .5, pen.getY() + 1, pen.getZ() + .5), actor.getYRot());
            if (y == null) continue; // token stays valid; retried on the next tick
            it.remove(); data.setDirty();
            finishArrival(y, shop, d.factory);
            y.getNavigation().stop(); y.moveTo(pen.getX() + .5, pen.getY() + .12, pen.getZ() + .5, y.getYRot(), 0);
            actor.swing(InteractionHand.MAIN_HAND);
            y.playAction("look_around"); y.showReaction("cry", "", 100);
            return true;
        }
        return false;
    }
    /** The animal of a delivery has been set down (by a keeper, or recovered): a factory arrival becomes unbadged stock. Returns whether it was a factory arrival. */
    public static boolean completed(ServerLevel level, UUID token, YukkuriEntity y, HumanNpc shop) {
        var data = get(level); UUID factory = null; boolean found = false;
        for (var it = data.pending.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (d.token.equals(token)) { factory = d.factory; found = true; it.remove(); data.setDirty(); break; }
        }
        if (!found && !HumanCommerce.data(y).getBoolean("ShopCandidate")) return false;
        if (shop != null) finishArrival(y, shop, factory);
        else { var clean = HumanCommerce.data(y).copy(); clean.remove("ShopCandidate"); HumanCommerce.set(y, clean); }
        return true;
    }
    private static void finishArrival(YukkuriEntity y, HumanNpc shop, UUID factory) {
        HumanCommerce.assign(y, shop.getUUID(), "stock");
        y.life().releaseHome();
        var clean = HumanCommerce.data(y).copy(); clean.remove("ShopCandidate"); HumanCommerce.set(y, clean);
        if (!y.pet().pedigreeKnown()) y.pet().setPedigree(20 + y.getRandom().nextInt(21));
        if (factory != null) y.getPersistentData().putUUID("FactoryOrigin", factory);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var s = new ListTag();
        shops.forEach((id, pos) -> { var c = new CompoundTag(); c.putUUID("Id", id); c.putString("Dim", pos.dimension().location().toString()); c.putLong("Pos", pos.pos().asLong()); c.putLong("Seen", seen.getOrDefault(id, -1L)); s.add(c); });
        tag.put("Shops", s);
        var p = new ListTag();
        for (var d : pending) { var c = new CompoundTag(); c.putUUID("Token", d.token); c.putUUID("Shop", d.shopkeeper); c.putLong("Pos", d.shopPos.asLong()); c.putLong("Shipped", d.shipped); c.putUUID("Factory", d.factory); p.add(c); }
        tag.put("Pending", p);
        var f = new ListTag();
        factories.forEach((id, pos) -> { var c = new CompoundTag(); c.putUUID("Id", id); c.putString("Dim", pos.dimension().location().toString()); c.putLong("Pos", pos.pos().asLong()); c.putLong("Seen", factorySeen.getOrDefault(id, -1L)); f.add(c); });
        tag.put("Factories", f);
        var rc = new ListTag();
        for (var r : recalls) { var c = new CompoundTag(); c.putUUID("Token", r.token); c.putUUID("Shop", r.shopkeeper); c.putUUID("Factory", r.factory); c.putLong("Shipped", r.shipped); rc.add(c); }
        tag.put("Recalls", rc); return tag;
    }
    public static ShopDeliveries load(CompoundTag tag, HolderLookup.Provider registries) {
        var r = new ShopDeliveries();
        for (Tag t : tag.getList("Shops", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t; var dim = net.minecraft.resources.ResourceLocation.tryParse(c.getString("Dim"));
            if (c.hasUUID("Id") && dim != null) r.seen.put(c.getUUID("Id"), c.getLong("Seen"));
            if (c.hasUUID("Id") && dim != null) r.shops.put(c.getUUID("Id"), GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim), BlockPos.of(c.getLong("Pos"))));
        }
        for (Tag t : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("Token") && c.hasUUID("Shop") && c.hasUUID("Factory")) r.pending.add(new Delivery(c.getUUID("Token"), c.getUUID("Shop"), BlockPos.of(c.getLong("Pos")), c.getLong("Shipped"), c.getUUID("Factory")));
        }
        for (Tag t : tag.getList("Factories", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t; var dim = net.minecraft.resources.ResourceLocation.tryParse(c.getString("Dim"));
            if (!c.hasUUID("Id") || dim == null) continue;
            r.factorySeen.put(c.getUUID("Id"), c.getLong("Seen"));
            r.factories.put(c.getUUID("Id"), GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim), BlockPos.of(c.getLong("Pos"))));
        }
        for (Tag t : tag.getList("Recalls", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("Token") && c.hasUUID("Shop") && c.hasUUID("Factory")) r.recalls.add(new Recall(c.getUUID("Token"), c.getUUID("Shop"), c.getUUID("Factory"), c.getLong("Shipped")));
        }
        return r;
    }
}
