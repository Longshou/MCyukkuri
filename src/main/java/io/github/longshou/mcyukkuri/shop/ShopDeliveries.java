package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.CustodyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Factory to shop logistics (docs/FEATURES.md): known shopkeeper positions plus pending deliveries. A delivery only holds a custody token; the
 * individual stays captive until released exactly once at the shop.
 */
public final class ShopDeliveries extends SavedData {
    public static final long TRANSIT = 2400;
    public static final int RANGE = 256;
    /** Three game days of recall transit or silence from a registered destination. */
    public static final long EXPIRY = 72000;
    public record Delivery(UUID token, UUID shopkeeper, BlockPos shopPos, long shipped, UUID factory) {}
    private final Map<UUID, GlobalPos> shops = new HashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();
    private final List<Delivery> pending = new ArrayList<>();
    /** Unsold stock sent back to a factory: the token waits until the factory worker collects it (docs/FEATURES.md). */
    public record Recall(UUID token, UUID shopkeeper, UUID factory, long shipped) {}
    private final Map<UUID, GlobalPos> factories = new HashMap<>();
    private final Map<UUID, Long> factorySeen = new HashMap<>();
    private final List<Recall> recalls = new ArrayList<>();

    public static ShopDeliveries get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopDeliveries::new, ShopDeliveries::load), "mcyukkuri_shop_deliveries");
    }
    public static boolean enabled() {
        try { return HumanWorld.FACTORY_SHOP_SHIPPING.get(); } catch (RuntimeException e) { return true; }
    }
    public static boolean factoryOrigin(YukkuriEntity y) { return y.getPersistentData().hasUUID("FactoryOrigin"); }

    /** Called while a shopkeeper ticks, so factories can find the shop without loading it. */
    public static void register(HumanNpc shopkeeper) {
        if (!(shopkeeper.level() instanceof ServerLevel level)) return;
        if (CustodyData.get(level).deadNpc(shopkeeper.getUUID())) return;
        var pos = GlobalPos.of(level.dimension(), ShopRegistry.get(level).register(shopkeeper).pos);
        var data = get(level);
        long now = level.getGameTime(), before = data.seen.getOrDefault(shopkeeper.getUUID(), -1L); data.seen.put(shopkeeper.getUUID(), now);
        if (!pos.equals(data.shops.put(shopkeeper.getUUID(), pos)) || now - before > 6000) data.setDirty();
    }
    /** Called while a factory worker ticks, so a shop can find the factory that will take its unsold stock. */
    public static void registerFactory(HumanNpc worker) {
        if (!(worker.level() instanceof ServerLevel level)) return;
        if (CustodyData.get(level).deadNpc(worker.getUUID())) return;
        var data = get(level); var pos = GlobalPos.of(level.dimension(), worker.blockPosition());
        long now = level.getGameTime(), before = data.factorySeen.getOrDefault(worker.getUUID(), -1L); data.factorySeen.put(worker.getUUID(), now);
        if (!pos.equals(data.factories.put(worker.getUUID(), pos)) || now - before > 6000) data.setDirty();
    }
    /** Nearest live factory worker within RANGE blocks, or null. */
    public Map.Entry<UUID, GlobalPos> nearestFactory(ServerLevel level, BlockPos from) {
        Map.Entry<UUID, GlobalPos> best = null; double bestD = (double) RANGE * RANGE; long now = level.getGameTime();
        for (var e : factories.entrySet()) {
            if (CustodyData.get(level).deadNpc(e.getKey())) continue;
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
    public static boolean collect(HumanNpc worker, ServerLevel level, Predicate<UUID> intake) {
        if (CustodyData.get(level).deadNpc(worker.getUUID())) return false;
        var data = get(level);
        if (data.recalls.isEmpty()) return false;
        long now = level.getGameTime();
        for (var it = data.recalls.iterator(); it.hasNext(); ) {
            var r = it.next();
            if (!r.factory.equals(worker.getUUID())) continue;
            if (!YukkuriWorldData.get(level).hasToken(r.token)) {
                it.remove(); data.setDirty();
                GameplayLog.event(worker,"delivery","action=collect_recall result=removed token={} shop={} reason=token_missing",r.token,r.shopkeeper);
                continue;
            }
            if (now - r.shipped < TRANSIT || now - r.shipped > EXPIRY) continue;
            if (intake.test(r.token)) {
                it.remove(); data.setDirty();
                GameplayLog.event(worker,"delivery","action=collect_recall result=committed token={} shop={}",r.token,r.shopkeeper);
                return true;
            }
        }
        return false;
    }
    /** Nearest registered shopkeeper in the same dimension within RANGE blocks, or null. */
    public Map.Entry<UUID, GlobalPos> nearestShop(ServerLevel level, BlockPos from) {
        Map.Entry<UUID, GlobalPos> best = null; double bestD = (double) RANGE * RANGE; long now = level.getGameTime();
        if (shops.keySet().removeIf(id -> seen.getOrDefault(id, -1L) < now - EXPIRY && pending.stream().noneMatch(d -> d.shopkeeper.equals(id))
                && recalls.stream().noneMatch(r -> r.shopkeeper.equals(id)))) setDirty();
        for (var e : shops.entrySet()) {
            if (CustodyData.get(level).deadNpc(e.getKey())) continue;
            if (!e.getValue().dimension().equals(level.dimension()) || seen.getOrDefault(e.getKey(), -1L) < now - EXPIRY) continue;
            double d = e.getValue().pos().distSqr(from);
            if (d <= bestD) { bestD = d; best = e; }
        }
        return best;
    }
    /**
     * Playground field clear only (command/PlaygroundTools): shops and factories whose staff was removed or stood in {@code box} are
     * forgotten together with the deliveries and recalls addressed to them, so nothing is later shipped to, redirected from or
     * released into the rebuilt field. Returns the custody tokens those records held, for the caller to retire.
     */
    public Set<UUID> forget(ServerLevel level, AABB box, Set<UUID> removed) {
        var dimension = level.dimension();
        var gone = new HashSet<UUID>(removed);
        shops.forEach((id, pos) -> { if (pos.dimension().equals(dimension) && box.contains(Vec3.atCenterOf(pos.pos()))) gone.add(id); });
        factories.forEach((id, pos) -> { if (pos.dimension().equals(dimension) && box.contains(Vec3.atCenterOf(pos.pos()))) gone.add(id); });
        var tokens = new HashSet<UUID>();
        boolean changed = shops.keySet().removeAll(gone) | seen.keySet().removeAll(gone)
                | factories.keySet().removeAll(gone) | factorySeen.keySet().removeAll(gone);
        changed |= pending.removeIf(d -> {
            boolean drop = gone.contains(d.shopkeeper) || gone.contains(d.factory) || box.contains(Vec3.atCenterOf(d.shopPos));
            if (drop) tokens.add(d.token);
            return drop;
        });
        changed |= recalls.removeIf(r -> {
            boolean drop = gone.contains(r.shopkeeper) || gone.contains(r.factory);
            if (drop) tokens.add(r.token);
            return drop;
        });
        if (changed) setDirty();
        return tokens;
    }
    public int pendingFor(ServerLevel level, UUID shopkeeper) {
        int transit = (int) pending.stream().filter(d -> d.shopkeeper.equals(shopkeeper)).count();
        var site = shops.get(shopkeeper);
        if (site == null || !level.hasChunkAt(site.pos())) return transit;
        return transit + level.getEntitiesOfClass(ItemEntity.class, new AABB(site.pos()).inflate(24),
                i -> shopkeeper.equals(destination(i.getItem()))).size();
    }
    public List<Delivery> pending() { return List.copyOf(pending); }
    public void add(Delivery d) { pending.add(d); setDirty(); }

    /** Transit becomes a real, pickable basket even when the shop is empty of staff. */
    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        var overworld = server.overworld();
        long now = overworld.getGameTime();
        if (now % 20 != 0) return;
        var data = get(overworld);
        for (var it = data.pending.iterator(); it.hasNext(); ) {
            var d = it.next();
            var site = data.shops.get(d.shopkeeper);
            if (site == null || now - d.shipped < TRANSIT) continue;
            var level = server.getLevel(site.dimension());
            if (level != null && stage(level, d.token, d.shopkeeper, site.pos(), d.factory, false)) {
                it.remove();
                data.setDirty();
            }
        }
        for (var it = data.recalls.iterator(); it.hasNext(); ) {
            var r = it.next();
            var site = data.shops.get(r.shopkeeper);
            if (site == null || now - r.shipped <= EXPIRY) continue;
            var level = server.getLevel(site.dimension());
            if (level != null && stage(level, r.token, r.shopkeeper, site.pos(), r.factory, true)) {
                it.remove();
                data.setDirty();
            }
        }
    }

    private static boolean stage(ServerLevel level, UUID token, UUID shop, BlockPos at, UUID factory, boolean recalled) {
        if (!level.hasChunkAt(at) || ShopCourier.inFlight(token)) return false;
        if (!YukkuriWorldData.get(level).hasToken(token)) return true;
        // Older deliveries can already be in a saved courier's hand when the job is reloaded.
        var nearby = new AABB(at).inflate(48);
        if (!level.getEntitiesOfClass(HumanNpc.class, nearby, n -> CarrierContents.tokens(n.getMainHandItem()).contains(token)).isEmpty()) return false;
        if (!level.getEntitiesOfClass(ItemEntity.class, nearby, i -> CarrierContents.tokens(i.getItem()).contains(token)).isEmpty()) return true;
        var basket = mark(CarrierContents.plain(YukkuriWorldData.get(level), token), shop, factory, recalled);
        if (ShopTrade.drop(level, null, Vec3.atBottomCenterOf(at), basket) == null) return false;
        GameplayLog.world(level, "delivery", "action=arrival result=basket_on_ground token={} shop={} counter={} recall={}",
                token, shop, at.toShortString(), recalled);
        return true;
    }

    /** A bought-back animal waiting at the handoff point can be loaded without moving it through the building. */
    static boolean stageSale(ServerLevel level, HumanNpc shop, YukkuriEntity animal) {
        if (!animal.onGround() || animal.isPassenger() || animal.isVehicle() || ServiceHatch.of(animal) != null) return false;
        var at = animal.position();
        var item = ShopTrade.drop(level, null, at, new ItemStack(ModItems.CARRIER.get()));
        if (item == null) return false;
        var world = YukkuriWorldData.get(level);
        var token = world.capture(animal);
        if (token == null) {
            item.discard();
            return false;
        }
        var basket = item.getItem().copy();
        CarrierContents.add(basket, token, world.captiveSnapshot(token));
        item.setItem(mark(basket, shop.getUUID(), null, false));
        GameplayLog.world(level, "delivery", "action=buyback result=basket_on_ground token={} shop={} arrival={}",
                token, shop.getUUID(), GameplayLog.at(at));
        return true;
    }

    private static ItemStack mark(ItemStack basket, UUID shop, UUID factory, boolean recalled) {
        CustomData.update(DataComponents.CUSTOM_DATA, basket, tag -> {
            tag.putUUID("RestockShop", shop);
            if (factory != null) tag.putUUID("RestockFactory", factory);
            if (recalled) tag.putBoolean("RestockRecall", true);
        });
        return basket;
    }

    static UUID destination(ItemStack basket) {
        var tag = basket.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        var data = tag.copyTag();
        return CarrierContents.singleToken(basket) != null && data.hasUUID("RestockShop") ? data.getUUID("RestockShop") : null;
    }

    static ItemStack plainBasket(ItemStack basket) {
        var copy = basket.copy();
        clearDestination(copy);
        return copy;
    }

    public static void clearDestination(ItemStack basket) {
        if (!basket.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains("RestockShop")) return;
        CustomData.update(DataComponents.CUSTOM_DATA, basket, tag -> {
            tag.remove("RestockShop");
            tag.remove("RestockFactory");
            tag.remove("RestockRecall");
        });
    }

    /** A clerk or keeper collects a parcel from the floor and then uses the ordinary stocking route. */
    public static void arrive(HumanNpc actor, ServerLevel level) {
        if (ShopCourier.busy(actor) || actor.isTrading() || !actor.getMainHandItem().isEmpty()) return;
        var shop = actor.job() == HumanNpc.Job.SHOPKEEPER ? actor : level.getEntitiesOfClass(HumanNpc.class, actor.getBoundingBox().inflate(48),
                n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(actor);
        if (actor.job() == HumanNpc.Job.SHOPKEEPER && ShopCourier.idleKeeper(level, shop) != null) return;
        var basket = level.getEntitiesOfClass(ItemEntity.class, new AABB(actor.home()).inflate(32), item -> {
            UUID destination = destination(item.getItem());
            return destination != null && !item.hasPickUpDelay()
                    && (destination.equals(shop.getUUID()) || !(level.getEntity(destination) instanceof HumanNpc original && original.isAlive()));
        }).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
        if (basket == null || ShopPens.stock(level, shop) >= ShopMenu.STOCK_CAPACITY) return;
        var pen = ShopPens.freePen(level, shop, null, false);
        if (pen != null) ShopCourier.collect(level, actor, shop, basket, pen);
    }

    /** The token is consumed only after a physical set-down. Carry the parcel's provenance across that handoff. */
    public static boolean completed(ServerLevel level, UUID token, ItemStack basket, YukkuriEntity y, HumanNpc shop) {
        var data = get(level);
        var tag = basket.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        UUID factory = tag.hasUUID("RestockFactory") ? tag.getUUID("RestockFactory") : null;
        for (var it = data.pending.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (d.token.equals(token)) { factory = d.factory; it.remove(); data.setDirty(); break; }
        }
        if (tag.getBoolean("RestockRecall")) {
            var commerce = HumanCommerce.data(y).copy();
            commerce.putLong("ForSaleSince", level.getGameTime());
            commerce.remove("StaleCuts");
            HumanCommerce.set(y, commerce);
        }
        if (tag.hasUUID("RestockShop") || factory != null || HumanCommerce.data(y).getBoolean("ShopCandidate")) {
            finishArrival(level, y, shop, factory);
        }
        return factory != null;
    }
    private static void finishArrival(ServerLevel level, YukkuriEntity y, HumanNpc shop, UUID factory) {
        // The token is already released. A dead destination follows the existing no-shop completion.
        if (shop == null || !shop.isAlive() || CustodyData.get(level).deadNpc(shop.getUUID())) {
            clearCandidate(y);
            GameplayLog.event(y,"delivery","action=arrive result=unassigned factory={} reason=shop_unavailable",factory);
            return;
        }
        Custody.assign(y, shop, Custody.Role.STOCK);
        if (!Custody.view(y).ownedBy(shop.getUUID())) {
            clearCandidate(y);
            GameplayLog.event(y,"delivery","action=arrive result=unassigned shop={} factory={} reason=custody_not_assigned",shop.getUUID(),factory);
            return;
        }
        y.life().releaseHome();
        ShopRegistry.get(level).stocked(shop, y);
        clearCandidate(y);
        if (factory != null) y.getPersistentData().putUUID("FactoryOrigin", factory);
        GameplayLog.event(y,"delivery","action=arrive result=stocked shop={} factory={} role={}",shop.getUUID(),factory,Custody.view(y).role());
    }
    private static void clearCandidate(YukkuriEntity y) {
        var clean = HumanCommerce.data(y).copy(); clean.remove("ShopCandidate"); HumanCommerce.set(y, clean);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var s = new ListTag();
        shops.forEach((id, pos) -> { var c = new CompoundTag(); c.putUUID("Id", id); c.putString("Dim", pos.dimension().location().toString());
                c.putLong("Pos", pos.pos().asLong()); c.putLong("Seen", seen.getOrDefault(id, -1L)); s.add(c); });
        tag.put("Shops", s);
        var p = new ListTag();
        for (var d : pending) { var c = new CompoundTag(); c.putUUID("Token", d.token); c.putUUID("Shop", d.shopkeeper); c.putLong("Pos",
                d.shopPos.asLong()); c.putLong("Shipped", d.shipped); c.putUUID("Factory", d.factory); p.add(c); }
        tag.put("Pending", p);
        var f = new ListTag();
        factories.forEach((id, pos) -> { var c = new CompoundTag(); c.putUUID("Id", id); c.putString("Dim", pos.dimension().location().toString());
                c.putLong("Pos", pos.pos().asLong()); c.putLong("Seen", factorySeen.getOrDefault(id, -1L)); f.add(c); });
        tag.put("Factories", f);
        var rc = new ListTag();
        for (var r : recalls) { var c = new CompoundTag(); c.putUUID("Token", r.token); c.putUUID("Shop", r.shopkeeper); c.putUUID("Factory", r.factory); c.putLong("Shipped", r.shipped); rc.add(c); }
        tag.put("Recalls", rc); return tag;
    }
    public static ShopDeliveries load(CompoundTag tag, HolderLookup.Provider registries) {
        var r = new ShopDeliveries();
        for (Tag t : tag.getList("Shops", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t; var dim = ResourceLocation.tryParse(c.getString("Dim"));
            if (c.hasUUID("Id") && dim != null) r.seen.put(c.getUUID("Id"), c.getLong("Seen"));
            if (c.hasUUID("Id") && dim != null) r.shops.put(c.getUUID("Id"), GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(c.getLong("Pos"))));
        }
        for (Tag t : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("Token") && c.hasUUID("Shop") && c.hasUUID("Factory")) r.pending.add(new Delivery(c.getUUID("Token"), c.getUUID("Shop"),
                    BlockPos.of(c.getLong("Pos")), c.getLong("Shipped"), c.getUUID("Factory")));
        }
        for (Tag t : tag.getList("Factories", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t; var dim = ResourceLocation.tryParse(c.getString("Dim"));
            if (!c.hasUUID("Id") || dim == null) continue;
            r.factorySeen.put(c.getUUID("Id"), c.getLong("Seen"));
            r.factories.put(c.getUUID("Id"), GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(c.getLong("Pos"))));
        }
        for (Tag t : tag.getList("Recalls", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("Token") && c.hasUUID("Shop") && c.hasUUID("Factory")) r.recalls.add(new Recall(c.getUUID("Token"), c.getUUID("Shop"), c.getUUID("Factory"), c.getLong("Shipped")));
        }
        return r;
    }
}
