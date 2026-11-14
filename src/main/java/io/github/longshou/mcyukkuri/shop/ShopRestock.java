package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.GrowthTiming;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * At each restock, outgoing captives are released beyond the shop footprint. Staff basket the next outgoing adults
 * and settle arriving young. Floor stock and captive stock share one capacity count.
 */
public final class ShopRestock {
    static final long PERIOD = 3 * ShopStock.DAY;
    /** Both shops keep at most two grown animals in generated stock; the rest are young. */
    static final int KEEP_ADULTS = 2;
    /** Chance that an arrival for a free land nest is a grown animal while the shop keeps fewer than {@link #KEEP_ADULTS}. */
    private static final float ADULT_CHANCE = .05f;
    private static final List<YukkuriVariant> LAND = List.of(YukkuriVariant.REIMU, YukkuriVariant.REIMU, YukkuriVariant.REIMU, YukkuriVariant.MARISA,
            YukkuriVariant.MARISA, YukkuriVariant.MARISA, YukkuriVariant.CHEN, YukkuriVariant.CHEN, YukkuriVariant.ALICE, YukkuriVariant.ALICE,
            YukkuriVariant.PATCHOULI, YukkuriVariant.MYON);
    private static final List<YukkuriVariant> PREDATORS = List.of(YukkuriVariant.REMILIA, YukkuriVariant.FLANDRE);

    /** From any staff member's tick: the first restock is one period after the shop opens, then every period. */
    static void tick(ServerLevel level, ShopRegistry.Entry shop) {
        long now = level.getGameTime();
        tend(level, shop, now);
        reconcileReservations(level, shop);
        if (shop.dock() == null) return;
        if (shop.nextRestock() == 0) { ShopRegistry.get(level).restocked(shop, now + PERIOD); return; }
        if (now < shop.nextRestock() || !level.isDay()) return;
        restock(level, shop);
    }

    /**
     * The shop's own bookkeeping each staff tick: stock bound to one of its staff members (the template binds it to the counter clerk)
     * becomes the shop site's stock. Stock starts its days on the shelf when the shop first sees it.
     */
    private static void tend(ServerLevel level, ShopRegistry.Entry shop, long now) {
        var registry = ShopRegistry.get(level);
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds(), a -> a.isAlive() && Custody.view(a).role() == Custody.Role.STOCK
                && !Custody.view(a).ownedBy(shop.id))) {
            var owner = Custody.view(y).owner();
            if (owner == null || !(level.getEntity(owner) instanceof HumanNpc staff) || registry.of(staff) != shop) continue;
            Custody.assignRaw(y, shop.id, Custody.OwnerKind.NPC, Custody.Role.STOCK.id());
            GameplayLog.event(y, "shop", "action=adopt shop={} from_staff={}", shop.id, owner);
        }
        for (var y : ShopRegistry.liveStock(level, shop)) {
            var tag = HumanCommerce.data(y);
            if (tag.contains(ShopTrade.FOR_SALE_SINCE)) continue;
            if (shop.kind == ShopKind.ONI) {
                if (y.mind().preventionRemaining() == 0) shop.kind.prepareStock(y);
                GameplayLog.event(y, "shop", "action=oni_stock shop={} form={} nutrition={} hygiene={} prevention={} price={} stage={} age={}",
                        shop.id, y.subspecies().assetId(), y.life().nutrition(), y.life().hygiene(),
                        y.mind().preventionRemaining(), ShopPricing.price(shop.kind, y), y.family().stageId(), y.family().age());
            }
            tag = tag.copy(); tag.putLong(ShopTrade.FOR_SALE_SINCE, now); HumanCommerce.set(y, tag);
        }
    }

    static void reconcileReservations(ServerLevel level, ShopRegistry.Entry shop) {
        var registry = ShopRegistry.get(level);
        var custody = YukkuriWorldData.get(level);
        long now = level.getGameTime();
        for (var id : List.copyOf(shop.deposits.keySet())) {
            var deposit = shop.deposit(id);
            var entity = level.getEntity(id);
            boolean onSale = entity instanceof YukkuriEntity y && y.isAlive() && ShopTrade.stockOf(y, shop.id) && shop.contains(y.position());
            boolean captured = custody.isCaptured(id);
            if (!onSale && !captured) {
                if (entity != null || shopLoaded(level, shop)) registry.refund(shop, id);
                continue;
            }
            if (now < deposit.since() + PERIOD) continue;
            registry.endDeposit(shop, id);
            registry.recordSpend(level, shop, deposit.buyer(), deposit.paid());
            if (shop.orders.containsKey(id)) ShopTrade.cancel(level, shop, id, "reservation");
            var name = entity instanceof YukkuriEntity y ? y.getDisplayName() : net.minecraft.network.chat.Component.translatable("shop.mcyukkuri.reserve.animal");
            registry.tell(level, deposit.buyer(), "reserve.expired", name, deposit.paid());
            GameplayLog.world(level, "shop", "action=reserve result=lapsed shop={} target={} buyer={} deposit_kept={}",
                    shop.id, id, deposit.buyer(), deposit.paid());
        }
    }

    /** One restock now (also the operator command). Returns how many arrived. */
    public static int restock(ServerLevel level, ShopRegistry.Entry shop) {
        if (shop.closed() || shop.dock() == null || !shopLoaded(level, shop)) return 0;
        var registry = ShopRegistry.get(level);
        reconcileReservations(level, shop);
        registry.restocked(shop, level.getGameTime() + PERIOD);
        var custody = YukkuriWorldData.get(level);
        var stock = ShopRegistry.liveStock(level, shop);
        var dock = level.getBlockEntity(shop.dock()) instanceof Container c ? c : null;
        int shipped = 0, waitingLand = 0, waitingPredator = 0;
        var departure = ShopRegistry.departure(level, shop);
        if (dock != null && departure != null) for (int slot = 0; slot < dock.getContainerSize(); slot++) {
            var stack = dock.getItem(slot);
            if (!ShopStaff.shopBasket(level, shop, stack)) continue;
            var token = CarrierContents.singleToken(stack);
            if (ShopStaff.shipOut(custody.captiveSnapshot(token))) {
                var species = custody.captiveSnapshot(token).getString("id");
                ShopRegistry.releaseStockBasket(level, token, departure);
                if (custody.hasToken(token)) continue;
                dock.setItem(slot, new ItemStack(stack.getItem()));
                shipped++;
                GameplayLog.world(level, "shop", "action=restock result=shipped shop={} token={} species={}", shop.id, token, species);
            }
        }
        for (var item : level.getEntitiesOfClass(ItemEntity.class, shop.bounds(),
                i -> departure != null && i.isAlive() && ShopStaff.shopBasket(level, shop, i.getItem()))) {
            var token = CarrierContents.singleToken(item.getItem());
            var snapshot = custody.captiveSnapshot(token);
            if (!ShopStaff.shipOut(snapshot)) continue;
            ShopRegistry.releaseStockBasket(level, token, departure);
            if (custody.hasToken(token)) continue;
            item.setItem(new ItemStack(item.getItem().getItem()));
            item.setTarget(null);
            shipped++;
            GameplayLog.world(level, "shop", "action=restock result=shipped shop={} token={} species={}", shop.id, token, snapshot.getString("id"));
        }
        int waitingAdults = 0;
        for (var token : ShopRegistry.stockBaskets(level, shop)) {
            var snapshot = custody.captiveSnapshot(token);
            if (ShopStaff.shipOut(snapshot)) continue;
            if (ShopStaff.predator(snapshot)) waitingPredator++; else waitingLand++;
            if ("adult".equals(FamilyLife.savedStageId(snapshot.getCompound("YukkuriFamily")))) waitingAdults++;
        }
        // Adults beyond the two kept go out next: the longest on the shelf first; reserved ones and those being bought stay.
        int adults = waitingAdults + (int) stock.stream().filter(y -> y.family().adult() && !ShopStaff.shipOut(y)).count(), marked = 0;
        var candidates = stock.stream().filter(y -> y.family().adult() && shop.deposit(y.getUUID()) == null
                && !shop.orders.containsKey(y.getUUID()) && !y.pet().busy() && !ShopStaff.shipOut(y))
                .sorted(Comparator.comparingInt(CommodityLife::forSaleDays).reversed()).toList();
        for (var y : candidates) {
            if (adults - marked <= KEEP_ADULTS) break;
            var tag = HumanCommerce.data(y).copy(); tag.putBoolean(ShopTrade.SHIP_OUT, true); HumanCommerce.set(y, tag);
            marked++;
            GameplayLog.event(y, "shop", "action=restock result=ship_marked shop={} days={}", shop.id, CommodityLife.forSaleDays(y));
        }
        int leavingLand = 0, leavingPredator = 0, landStock = 0, predatorStock = 0;
        for (var y : stock) {
            boolean predator = SpeciesProfile.of(y.getVariant()).dangerToYukkuri(), leaving = ShopStaff.shipOut(y);
            if (predator) { predatorStock++; if (leaving) leavingPredator++; } else { landStock++; if (leaving) leavingLand++; }
        }
        // Places: the pet shop's nests, the oni shop's pens (it keeps no beds).
        int land = Math.max(0, ShopStaff.places(level, shop, false) - (landStock - leavingLand) - waitingLand);
        int predators = Math.max(0, ShopStaff.places(level, shop, true) - (predatorStock - leavingPredator) - waitingPredator);
        int keptAdults = adults - marked, arrived = 0;
        var random = ModRandom.world(level);
        for (int i = 0; i < land + predators && dock != null; i++) {
            int slot = ShopStaff.openDockSlot(dock);
            if (slot < 0) break;
            var returned = dock.getItem(slot);
            if (!returned.isEmpty()) {
                if (ShopTrade.drop(level, null, Vec3.atBottomCenterOf(shop.dock().above()), returned.copy()) == null) break;
                dock.setItem(slot, ItemStack.EMPTY);
            }
            boolean predator = i >= land;
            boolean adult = !predator && keptAdults < KEEP_ADULTS && random.nextFloat() < ADULT_CHANCE;
            var variant = (predator ? PREDATORS : LAND).get(random.nextInt((predator ? PREDATORS : LAND).size()));
            var basket = arrive(level, shop, variant, adult, random);
            if (basket == null) break;
            dock.setItem(slot, basket);
            dock.setChanged();
            if (adult) keptAdults++;
            arrived++;
        }
        GameplayLog.world(level, "shop", "action=restock result=done shop={} stock={} adults={} ship_marked={} shipped={} free_land={} free_predator={} arrived={} next={}",
                shop.id, stock.size(), adults, marked, shipped, land, predators, arrived, shop.nextRestock());
        return arrived;
    }

    /** All chunks of the footprint hold their entities: only then is an absent reserved animal really gone. */
    static boolean shopLoaded(ServerLevel level, ShopRegistry.Entry shop) {
        var box = shop.bounds();
        int lastChunkX = ((int) Math.ceil(box.maxX) - 1) >> 4;
        int lastChunkZ = ((int) Math.ceil(box.maxZ) - 1) >> 4;
        for (int cx = (int) Math.floor(box.minX) >> 4; cx <= lastChunkX; cx++)
            for (int cz = (int) Math.floor(box.minZ) >> 4; cz <= lastChunkZ; cz++)
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return false;
        return true;
    }

    /**
     * A new individual from the wholesaler, packed in a basket, young unless an adult was asked for.
     * The temperament and condition reflect the shop's stock. It joins the world at the barrel for the tick of packing.
     */
    private static ItemStack arrive(ServerLevel level, ShopRegistry.Entry shop, YukkuriVariant variant, boolean adult, RandomSource random) {
        var y = ModEntities.forVariant(variant).create(level);
        if (y == null) return null;
        var beside = shop.dock();
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
            if (level.getBlockState(shop.dock().relative(d)).isAir()) { beside = shop.dock().relative(d); break; }
        var at = Vec3.atBottomCenterOf(beside);
        Motor.relocate(y, at, 0, "shop_arrival");
        y.finalizeSpawn(level, level.getCurrentDifficultyAt(shop.dock()), MobSpawnType.COMMAND, null);
        y.setPersistenceRequired();
        y.family().initializeGrowthAge(adult ? GrowthTiming.ADULT_START + random.nextInt(48_000) : youngAge(random));
        if (shop.kind == ShopKind.ONI) {
            if (variant == YukkuriVariant.REIMU && random.nextInt(3) == 0)
                y.subspecies().set(SubspeciesLife.Kind.WASA_REIMU, SubspeciesLife.Congenital.NORMAL);
            else if ((variant == YukkuriVariant.REIMU || variant == YukkuriVariant.MARISA) && random.nextInt(4) == 0)
                y.subspecies().set(SubspeciesLife.Kind.NORMAL, SubspeciesLife.Congenital.values()[1 + random.nextInt(3)]);
            y.life().personality().applyBirthProfile(new YukkuriPersonality.BirthProfile(between(random, 10, 40), between(random, 10, 45),
                    between(random, 15, 90), between(random, 25, 75), between(random, 5, 35)));
            y.life().setNeed("nutrition", between(random, 28, 42));
            y.life().setNeed("hygiene", between(random, 30, 44));
            shop.kind.prepareStock(y);
        } else y.life().personality().applyBirthProfile(new YukkuriPersonality.BirthProfile(between(random, 45, 85), between(random, 55, 90),
                between(random, 25, 80), between(random, 45, 85), between(random, 55, 90)));
        // Shop-raised stock is used to fine food and turns its nose up sooner (舌肥, FG-03).
        y.life().setPickiness(y.life().pickiness() + 18);
        Custody.assignRaw(y, shop.id, Custody.OwnerKind.NPC, Custody.Role.STOCK.id());
        if (!level.addFreshEntity(y)) return null;
        var token = YukkuriWorldData.get(level).capture(y);
        if (token == null) return null; // it stays in the back room as stock, where staff and the roster see it
        GameplayLog.event(y, "shop", "action=restock result=arrival shop={} stage={} adult={} token={}", shop.id, y.family().stageId(), adult, token);
        return CarrierContents.plain(YukkuriWorldData.get(level), token);
    }

    /** Choose the young stage first so the short baby interval is represented as often as either later stage. */
    private static long youngAge(RandomSource random) {
        return switch (random.nextInt(3)) {
            case 0 -> 48_000 + random.nextInt((int) GrowthTiming.BABY_END - 48_000);
            case 1 -> GrowthTiming.BABY_END + random.nextInt((int) (GrowthTiming.SUBADULT_START - GrowthTiming.BABY_END));
            default -> GrowthTiming.SUBADULT_START + random.nextInt((int) (GrowthTiming.ADULT_START - GrowthTiming.SUBADULT_START));
        };
    }
    private static int between(RandomSource random, int low, int high) { return low + random.nextInt(high - low + 1); }

    private ShopRestock() {}
}
