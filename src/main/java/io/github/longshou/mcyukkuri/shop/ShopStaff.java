package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Shop staff work (docs/FEATURES.md, 宠物店). Every staff member of a shop is the same: when free (awake, not trading, empty-handed) it takes
 * the most pressing piece of work that the shop's real state calls for, in this order: an animal waiting for its buyer, a companion waiting to be
 * sold, a bowl running low, a basket waiting in the arrivals barrel or on the floor, an animal marked to ship out, a toilet or floor waste to
 * clean. Nothing is queued: the work is read from the animals' own order locks, the bowls, toilets, barrels and baskets each time.
 * A job walks to its target (through the shop's iron staff doors), works at hand's reach (the same reach test admits and executes), and if it
 * holds something carries it to where it belongs: the counter, a free nest, the arrivals barrel, the waste barrel. A job is transient; what it
 * holds is not: a staff member found holding a basket or waste without a job (restart, a carry that failed) carries it on. A leg that cannot be
 * finished leaves the target to others for a while and anything held where it can be found again.
 */
public final class ShopStaff {
    enum Kind { BUY, SELL, SETTLE, SHIP, FEED, CLEAN }
    private enum Phase { GO, WORK, CARRY }
    private static final class Job {
        final UUID staff, shop; final Kind kind;
        Phase phase = Phase.GO;
        UUID animal, token, customer;
        /** GO: the bowl, toilet, waste or barrel worked at; CARRY: the counter, nest or barrel it carries to. */
        BlockPos target;
        ItemEntity loose;
        int leg, stall, timer; double best = Double.MAX_VALUE;
        Job(UUID staff, UUID shop, Kind kind) { this.staff = staff; this.shop = shop; this.kind = kind; }
    }
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    /** Operator handovers (the shop buy command) not yet picked up: animal to the UUID it is handed to. Transient like the jobs. */
    private static final Map<UUID, UUID> OPERATOR = new HashMap<>();
    /** Animals and blocks a staff member could not reach: nobody tries them again before the deadline (transient, at most 64 keys). */
    private static final DeadlineMap<Object> UNREACHABLE = new DeadlineMap<>();
    /** Ticks a leg may take, ticks without getting closer, ticks of handling at reach, and how long an unreachable target is left alone. */
    private static final int LEG = 600, STALL = 140, WORK = 16, RETRY = 1200, BLOCKED = 20;
    /** A bowl holding fewer portions than this is refilled with one serving of regular grain. */
    private static final int BOWL_LOW = 2, SERVING = 4;
    /** Hand's reach from the staff member's feet to the target's centre, with a clear line from its eyes. */
    private static final double REACH = 2.5, SPEED = .9;

    public static void started(ServerStartingEvent event) { JOBS.clear(); OPERATOR.clear(); UNREACHABLE.clear(); }
    public static void stopped(ServerStoppedEvent event) { JOBS.clear(); OPERATOR.clear(); UNREACHABLE.clear(); }
    /** True while the staff member runs a job: its routine and strolling goals stand aside. */
    public static boolean active(HumanNpc staff) { return JOBS.containsKey(staff.getUUID()); }
    public static boolean busy(HumanNpc npc) { return active(npc) || !npc.getMainHandItem().isEmpty(); }
    /** A staff member on a job passes the iron staff doors of its own shop (and no others). */
    public static boolean opensDoor(HumanNpc staff, BlockPos door) {
        var job = JOBS.get(staff.getUUID());
        if (job == null || !(staff.level() instanceof ServerLevel level)) return false;
        var shop = ShopRegistry.get(level).shop(job.shop);
        return shop != null && shop.contains(door.getCenter());
    }

    /** Every 20 ticks per staff member: the restock clock, waiting orders that cannot go on, and new work when free. */
    public static void tick(HumanNpc staff) {
        if (!(staff.level() instanceof ServerLevel level) || active(staff) || staff.isNoAi()) return;
        var shop = ShopRegistry.get(level).of(staff);
        if (shop == null) return;
        ShopRestock.tick(level, shop);
        reviewOrders(level, shop);
        if (resume(level, staff, shop)) return;
        if (staff.isTrading() || staff.resting() || staff.hurtTime > 0 || !staff.getMainHandItem().isEmpty()) return;
        pick(level, staff, shop);
    }

    /** Orders nobody serves yet are still checked: a buyer who left, nightfall or a lost animal cancels them visibly. */
    private static void reviewOrders(ServerLevel level, ShopRegistry.Entry shop) {
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds().inflate(2), a -> a.isAlive() && a.pet().busy())) {
            if (claimed(y.getUUID())) continue;
            String kind = y.pet().busyWith();
            if (kind.equals(ShopTrade.HANDOVER) && !OPERATOR.containsKey(y.getUUID())) ShopTrade.cancel(level, shop, y, "gone"); // its operator record was lost (restart)
            else if (kind.equals(ShopTrade.SALE) && ShopTrade.stockOf(y, shop.id)) {
                String problem = ShopTrade.orderProblem(level, shop, y);
                if (problem != null) ShopTrade.cancel(level, shop, y, problem);
            } else if (kind.equals(ShopTrade.SELL) && y.pet().ownedBy(y.pet().lockedBy())) {
                String problem = ShopTrade.sellProblem(level, shop, y);
                if (problem != null) ShopTrade.cancel(level, shop, y, problem);
            }
        }
    }

    /** Operator handover (command): the animal waits like a bought one and the first free staff member brings it, free of charge, for {@code buyer}. */
    public static boolean handover(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID buyer) {
        var holder = level.getEntitiesOfClass(HumanNpc.class, shop.bounds(), n -> n.isAlive() && ShopRegistry.get(level).of(n) == shop).stream().findFirst().orElse(null);
        if (holder == null || !ShopTrade.handover(level, shop, y, holder)) return false;
        OPERATOR.put(y.getUUID(), buyer);
        return true;
    }

    // ---- choosing work ------------------------------------------------------------------------------------------------

    private static void pick(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop) {
        long now = level.getGameTime();
        if (UNREACHABLE.size() > 64) UNREACHABLE.prune(now);
        var stock = ShopRegistry.liveStock(level, shop);
        for (var y : stock) if (ShopTrade.order(y) && free(y.getUUID(), now)) {
            var job = new Job(staff.getUUID(), shop.id, Kind.BUY); job.animal = y.getUUID();
            job.customer = y.pet().busyWith().equals(ShopTrade.HANDOVER) ? OPERATOR.remove(y.getUUID()) : y.pet().lockedBy();
            start(staff, job, y.position()); return;
        }
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds(), a -> a.isAlive() && a.pet().busyWith().equals(ShopTrade.SELL)
                && a.pet().ownedBy(a.pet().lockedBy()) && shop.contains(a.position())))
            if (free(y.getUUID(), now)) {
                var job = new Job(staff.getUUID(), shop.id, Kind.SELL); job.animal = y.getUUID(); job.customer = y.pet().lockedBy();
                start(staff, job, y.position()); return;
            }
        var blocks = blockEntities(level, shop);
        if (staff.careFood() > 0) for (var be : blocks)
            if (be instanceof CareBlockEntity bowl && bowl.kind() == CareBlock.Kind.BOWL && bowl.foodCount() < BOWL_LOW && free(bowl.getBlockPos(), now)) {
                var job = new Job(staff.getUUID(), shop.id, Kind.FEED); job.target = bowl.getBlockPos();
                start(staff, job, bowl.getBlockPos().getCenter()); return;
            }
        var dock = dockBasket(level, shop);
        if (dock >= 0 && free(shop.dock(), now) && destination(level, shop, ((Container) level.getBlockEntity(shop.dock())).getItem(dock), staff.position()) != null) {
            var job = new Job(staff.getUUID(), shop.id, Kind.SETTLE); job.target = shop.dock();
            start(staff, job, shop.dock().getCenter()); return;
        }
        for (var item : level.getEntitiesOfClass(ItemEntity.class, shop.bounds(), i -> i.isAlive() && shopBasket(level, shop, i.getItem())))
            if (free(item.getUUID(), now) && destination(level, shop, item.getItem(), staff.position()) != null) {
                var job = new Job(staff.getUUID(), shop.id, Kind.SETTLE); job.loose = item; job.animal = item.getUUID();
                start(staff, job, item.position()); return;
            }
        if (shop.dock() != null) for (var y : stock) if (shipOut(y) && !y.pet().busy() && shop.deposit(y.getUUID()) == null && free(y.getUUID(), now)) {
            var job = new Job(staff.getUUID(), shop.id, Kind.SHIP); job.animal = y.getUUID();
            start(staff, job, y.position()); return;
        }
        if (shop.trash() != null) for (var be : blocks) {
            boolean dirty = be instanceof CareBlockEntity toilet && toilet.kind() == CareBlock.Kind.TOILET && toilet.waste() >= 1
                    || be instanceof PoopBlockEntity poop && poop.count() >= 1;
            if (dirty && free(be.getBlockPos(), now)) {
                var job = new Job(staff.getUUID(), shop.id, Kind.CLEAN); job.target = be.getBlockPos();
                start(staff, job, be.getBlockPos().getCenter()); return;
            }
        }
    }

    /** Nobody works on it now and nobody has just failed to reach it. */
    private static boolean free(Object key, long now) {
        if (UNREACHABLE.active(key, now)) return false;
        return JOBS.values().stream().noneMatch(j -> key.equals(j.animal) || key.equals(j.target) || j.loose != null && key.equals(j.loose.getUUID()));
    }
    private static boolean claimed(UUID animal) { return JOBS.values().stream().anyMatch(j -> animal.equals(j.animal)); }

    private static void start(HumanNpc staff, Job job, Vec3 toward) {
        JOBS.put(staff.getUUID(), job);
        if (job.kind == Kind.BUY || job.kind == Kind.SELL || job.kind == Kind.SHIP) staff.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
        GameplayLog.event(staff, "shop", "action=job result=start kind={} phase={} target={} at={} token={}", job.kind, job.phase,
                job.animal != null ? job.animal : job.target, GameplayLog.at(toward), job.token);
    }

    /** A staff member holding a basket or waste with no job carries it on to where it belongs. */
    private static boolean resume(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop) {
        var hand = staff.getMainHandItem();
        if (hand.is(ModItems.WASTE.get())) {
            if (shop.trash() == null) return false;
            var job = new Job(staff.getUUID(), shop.id, Kind.CLEAN); job.phase = Phase.CARRY; job.target = shop.trash();
            start(staff, job, shop.trash().getCenter()); return true;
        }
        if (!hand.is(ModItems.CARRIER.get())) return false;
        UUID token = CarrierContents.singleToken(hand);
        var custody = YukkuriWorldData.get(level);
        if (token == null || !custody.hasToken(token)) {
            staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // an empty basket is put away
            return false;
        }
        var view = Custody.fromSnapshot(custody.captiveSnapshot(token));
        Job job;
        if (view.ownerKind() == Custody.OwnerKind.PLAYER) {
            job = new Job(staff.getUUID(), shop.id, Kind.BUY); job.customer = view.owner(); job.target = shop.counter();
        } else {
            job = new Job(staff.getUUID(), shop.id, Kind.SETTLE); job.target = destination(level, shop, hand, staff.position());
            if (job.target == null) job.target = shop.dock();
            if (job.target == null) return false;
        }
        job.token = token; job.phase = Phase.CARRY;
        start(staff, job, job.target.getCenter());
        return true;
    }

    // ---- running a job ------------------------------------------------------------------------------------------------

    /** One tick of the staff member's job (its courier goal), while it has one. */
    public static void step(HumanNpc staff) {
        var job = JOBS.get(staff.getUUID());
        if (job == null || !(staff.level() instanceof ServerLevel level)) return;
        var shop = ShopRegistry.get(level).shop(job.shop);
        if (shop == null) { end(staff, job, "shop_closed"); return; }
        if (staff.hurtTime > 0) { staff.getNavigation().stop(); return; } // it flinches, then goes on
        if (++job.leg > LEG) { fail(level, staff, shop, job, "leg_timeout"); return; }
        switch (job.phase) {
            case GO -> go(level, staff, shop, job);
            case WORK -> work(level, staff, shop, job);
            case CARRY -> carry(level, staff, shop, job);
        }
    }

    private static YukkuriEntity animal(ServerLevel level, Job job) {
        return job.animal != null && level.getEntity(job.animal) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private static Vec3 centre(YukkuriEntity y) { return y.position().add(0, y.getBbHeight() / 2, 0); }

    /** Why the job's target no longer needs this job, or null. Orders that cannot go on are cancelled here, visibly. */
    private static String stale(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        switch (job.kind) {
            case BUY -> {
                var y = animal(level, job);
                if (y == null || !ShopTrade.order(y)) return "order_ended";
                String problem = ShopTrade.orderProblem(level, shop, y);
                if (problem != null) { ShopTrade.cancel(level, shop, y, problem); return problem; }
            }
            case SELL -> {
                var y = animal(level, job);
                if (y == null || !y.pet().busyWith().equals(ShopTrade.SELL)) return "order_ended";
                String problem = ShopTrade.sellProblem(level, shop, y);
                if (problem != null) { ShopTrade.cancel(level, shop, y, problem); return problem; }
            }
            case SHIP -> {
                var y = animal(level, job);
                if (y == null || !ShopTrade.stockOf(y, shop.id) || !shipOut(y) || y.pet().busy() || shop.deposit(y.getUUID()) != null) return "kept";
            }
            case FEED -> {
                if (!(level.getBlockEntity(job.target) instanceof CareBlockEntity bowl) || bowl.foodCount() >= BOWL_LOW || staff.careFood() <= 0) return "fed";
            }
            case CLEAN -> {
                var be = level.getBlockEntity(job.target);
                if (!(be instanceof CareBlockEntity toilet && toilet.waste() >= 1 || be instanceof PoopBlockEntity poop && poop.count() >= 1)) return "clean";
            }
            case SETTLE -> {
                if (job.loose != null ? !job.loose.isAlive() : dockBasket(level, shop) < 0) return "basket_gone";
            }
        }
        return null;
    }

    private static void go(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        String why = stale(level, staff, shop, job);
        if (why != null) { end(staff, job, why); return; }
        var y = animal(level, job);
        Vec3 point = job.loose != null ? job.loose.position() : y != null ? centre(y) : job.target.getCenter();
        BlockPos block = job.loose != null || y != null ? null : job.target;
        staff.getLookControl().setLookAt(point.x, point.y, point.z, 30, 30);
        if (!reaches(staff, point, block)) { walk(level, staff, shop, job, point); return; }
        staff.getNavigation().stop();
        job.phase = Phase.WORK; job.timer = 0;
        staff.swing(InteractionHand.MAIN_HAND);
        if (y != null) {
            y.life().wakeForService(); // a sleeping animal is woken: the order or the move goes on
            y.getLookControl().setLookAt(staff, 30, 30);
            y.playAction("basket_in"); y.speak("basket_enter");
        }
        GameplayLog.event(staff, "shop", "action=job result=reach kind={} target={} staff_at={} target_at={}", job.kind,
                y != null ? y.getUUID() : job.target, GameplayLog.at(staff.position()), GameplayLog.at(point));
    }

    private static void work(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        String why = stale(level, staff, shop, job);
        if (why != null) { end(staff, job, why); return; }
        var y = animal(level, job);
        Vec3 point = job.loose != null ? job.loose.position() : y != null ? centre(y) : job.target.getCenter();
        if (!reaches(staff, point, job.loose != null || y != null ? null : job.target)) { job.phase = Phase.GO; return; } // it moved out of reach
        staff.getNavigation().stop();
        staff.getLookControl().setLookAt(point.x, point.y, point.z, 30, 30);
        if (++job.timer < WORK) return;
        switch (job.kind) {
            case BUY -> {
                job.token = ShopTrade.captureSale(level, staff, shop, y, job.customer);
                if (job.token == null) { end(staff, job, "not_bought"); return; }
                carryTo(job, shop.counter());
            }
            case SELL -> {
                job.token = ShopTrade.captureSold(level, staff, shop, y);
                if (job.token == null) { end(staff, job, "not_sold"); return; }
                carryTo(job, destinationOr(level, shop, staff));
            }
            case SHIP -> {
                y.life().releaseHome();
                job.token = YukkuriWorldData.get(level).capture(y);
                if (job.token == null) { end(staff, job, "capture_refused"); return; }
                staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), job.token));
                GameplayLog.event(staff, "shop", "action=ship_out result=packed shop={} target={} token={}", shop.id, y.getUUID(), job.token);
                carryTo(job, shop.dock());
            }
            case FEED -> {
                var bowl = (CareBlockEntity) level.getBlockEntity(job.target);
                for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
                    int served = staff.drawCareFood(SERVING);
                    bowl.setItem(slot, new ItemStack(ModItems.GRAIN_REGULAR.get(), served));
                    GameplayLog.event(staff, "npc-care", "action=refill_bowl target={} transferred={} food_left={}", job.target.toShortString(), served, staff.careFood());
                    break;
                }
                staff.swing(InteractionHand.MAIN_HAND);
                end(staff, job, "fed");
            }
            case CLEAN -> {
                var be = level.getBlockEntity(job.target);
                var waste = be instanceof CareBlockEntity toilet ? toilet.takeWaste(Integer.MAX_VALUE) : ((PoopBlockEntity) be).takeWhole(Integer.MAX_VALUE);
                if (waste.isEmpty()) { end(staff, job, "clean"); return; }
                staff.setItemInHand(InteractionHand.MAIN_HAND, waste);
                GameplayLog.event(staff, "npc-care", "action=clean_toilet source={} at={} units={}", be instanceof CareBlockEntity ? "toilet" : "floor",
                        job.target.toShortString(), waste.getCount());
                carryTo(job, shop.trash());
            }
            case SETTLE -> {
                ItemStack basket;
                if (job.loose != null) { basket = job.loose.getItem().copy(); job.loose.discard(); }
                else { var dock = (Container) level.getBlockEntity(shop.dock()); int slot = dockBasket(level, shop); basket = dock.removeItemNoUpdate(slot); dock.setChanged(); }
                staff.setItemInHand(InteractionHand.MAIN_HAND, basket);
                job.token = CarrierContents.singleToken(basket);
                GameplayLog.event(staff, "shop", "action=settle result=lifted shop={} token={} from={}", shop.id, job.token, job.loose != null ? "floor" : "dock");
                job.loose = null; job.animal = null;
                carryTo(job, destinationOr(level, shop, staff));
            }
        }
    }

    private static void carryTo(Job job, BlockPos where) {
        job.phase = Phase.CARRY; job.target = where; job.leg = 0; job.stall = 0; job.best = Double.MAX_VALUE;
    }
    /** The free nest for the held basket, else the arrivals barrel to wait there for one. */
    private static BlockPos destinationOr(ServerLevel level, ShopRegistry.Entry shop, HumanNpc staff) {
        var nest = destination(level, shop, staff.getMainHandItem(), staff.position());
        return nest != null ? nest : shop.dock();
    }

    private static void carry(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        var hand = staff.getMainHandItem();
        boolean holding = job.kind == Kind.CLEAN ? hand.is(ModItems.WASTE.get()) : job.token != null && job.token.equals(CarrierContents.singleToken(hand));
        if (!holding) { end(staff, job, "hand_changed"); return; }
        if (job.target == null) { fail(level, staff, shop, job, "no_destination"); return; }
        // A nest taken meanwhile is exchanged for another free one (or the barrel).
        if (level.getBlockEntity(job.target) instanceof CareBlockEntity nest && nest.kind() == CareBlock.Kind.NEST && nest.resident() != null)
            job.target = destinationOr(level, shop, staff);
        boolean counter = job.kind == Kind.BUY;
        Vec3 point = counter ? Vec3.atBottomCenterOf(job.target) : job.target.getCenter();
        boolean arrived = counter ? staff.onGround() && staff.position().distanceToSqr(point) <= 1 : reaches(staff, point, job.target);
        if (!arrived) { walk(level, staff, shop, job, point); return; }
        staff.getNavigation().stop();
        staff.getLookControl().setLookAt(point.x, point.y, point.z, 30, 30);
        if (counter) {
            if (ShopTrade.handOver(level, staff, shop, job.customer, hand, job.token)) end(staff, job, "delivered");
            else fail(level, staff, shop, job, "handoff_blocked");
            return;
        }
        if (level.getBlockEntity(job.target) instanceof Container barrel && !(barrel instanceof CareBlockEntity)) {
            var left = insert(barrel, hand.copy());
            staff.setItemInHand(InteractionHand.MAIN_HAND, left);
            staff.swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(staff, "shop", "action={} result=stored shop={} token={} barrel={} full={}", job.kind == Kind.CLEAN ? "dispose_waste" : "stow_basket",
                    shop.id, job.token, job.target.toShortString(), !left.isEmpty());
            if (!left.isEmpty()) { fail(level, staff, shop, job, "barrel_full"); return; }
            end(staff, job, "stored");
            return;
        }
        release(level, staff, shop, job);
    }

    /** The basket is opened on the nest: the animal climbs out onto its bed, which becomes its home. */
    private static void release(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        var nest = job.target;
        var shape = level.getBlockState(nest).getCollisionShape(level, nest);
        var stand = new Vec3(nest.getX() + .5, nest.getY() + (shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y)), nest.getZ() + .5);
        var y = YukkuriWorldData.get(level).release(job.token, level, stand, staff.getYRot());
        if (y == null) { fail(level, staff, shop, job, "set_down_blocked"); return; }
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        staff.swing(InteractionHand.MAIN_HAND);
        var tag = HumanCommerce.data(y).copy();
        if (!tag.contains(ShopTrade.FOR_SALE_SINCE)) { tag.putLong(ShopTrade.FOR_SALE_SINCE, level.getGameTime()); HumanCommerce.set(y, tag); }
        boolean home = y.life().assignHome(GlobalPos.of(level.dimension(), nest));
        y.playAction("basket_out"); y.speak("basket_exit");
        GameplayLog.event(y, "shop", "action=settle result=released shop={} token={} nest={} home={} staff={}", shop.id, job.token, nest.toShortString(), home, staff.getUUID());
        end(staff, job, "released");
    }

    /** Walks toward the point; a block target is approached on a free floor cell beside it (never by climbing onto a barrel or into a bowl). */
    private static void walk(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job, Vec3 point) {
        double d = staff.position().distanceToSqr(point);
        if (d < job.best - .25) { job.best = d; job.stall = 0; } else if (++job.stall > STALL) { fail(level, staff, shop, job, "stalled"); return; }
        // Held up for a second: an idle animal right against it (in a doorway, say) is asked to make way, as animals do for each other.
        if (job.stall > BLOCKED && job.stall % 10 == 0)
            for (var y : level.getEntitiesOfClass(YukkuriEntity.class, staff.getBoundingBox().inflate(.6, 0, .6), YukkuriEntity::isAlive))
                if (YukkuriGroundNavigation.giveWay(y, staff))
                    GameplayLog.limited(staff, "shop", "give_way", 100, "action=job result=asked_way kind={} animal={}", job.kind, y.getUUID());
        if (job.leg % 10 != 1) return;
        var to = point;
        if (job.loose == null && animal(level, job) == null && job.target != null && !(job.phase == Phase.CARRY && job.kind == Kind.BUY)) {
            BlockPos stand = null; double best = Double.MAX_VALUE;
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var c = job.target.relative(dir);
                if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                        || !level.getBlockState(c.below()).isFaceSturdy(level, c.below(), net.minecraft.core.Direction.UP)) continue;
                double e = staff.position().distanceToSqr(Vec3.atBottomCenterOf(c));
                if (e < best) { best = e; stand = c; }
            }
            if (stand != null) to = Vec3.atBottomCenterOf(stand);
        }
        staff.getNavigation().moveTo(to.x, to.y, to.z, SPEED);
    }

    /** Hand's reach: near enough and a clear line from the eyes (a hit on the target block itself counts as reaching it). */
    static boolean reaches(HumanNpc staff, Vec3 point, BlockPos block) {
        if (staff.position().distanceToSqr(point) > REACH * REACH) return false;
        var hit = staff.level().clip(new ClipContext(staff.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, staff));
        return hit.getType() == HitResult.Type.MISS || block != null && hit.getBlockPos().equals(block);
    }

    /**
     * A leg that cannot be finished: the target is left to others for a while. An order not yet in a basket is cancelled with the reason;
     * something held goes where it can be found again (a stock basket to the arrivals barrel, else on the floor where only the buyer, or no
     * customer at all, may pick it up; waste on the floor).
     */
    private static void fail(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job, String reason) {
        long now = level.getGameTime();
        Object key = job.phase == Phase.CARRY ? job.target : job.animal != null ? job.animal : job.target;
        if (key != null) UNREACHABLE.put(key, now + RETRY);
        GameplayLog.limited(staff, "shop", "job_fail." + job.kind + "." + reason, 200, "action=job result=failed kind={} phase={} reason={} target={} token={} leg_ticks={}",
                job.kind, job.phase, reason, key, job.token, job.leg);
        if (job.phase != Phase.CARRY) {
            var y = animal(level, job);
            if (y != null && (job.kind == Kind.BUY || job.kind == Kind.SELL)) ShopTrade.cancel(level, shop, y, "unreachable");
            end(staff, job, reason);
            return;
        }
        if (job.kind != Kind.BUY && job.kind != Kind.CLEAN && shop.dock() != null && !shop.dock().equals(job.target)) {
            carryTo(job, shop.dock()); // try the barrel instead
            return;
        }
        var hand = staff.getMainHandItem().copy();
        UUID target = job.kind == Kind.BUY ? job.customer : job.kind == Kind.CLEAN ? null : shop.id;
        if (ShopTrade.drop(level, target, staff.position(), hand) != null) {
            staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            GameplayLog.event(staff, "shop", "action=job result=put_down kind={} token={} at={} pickup={}", job.kind, job.token, GameplayLog.at(staff.position()), target);
        }
        end(staff, job, reason);
    }

    private static void end(HumanNpc staff, Job job, String reason) {
        JOBS.remove(staff.getUUID(), job);
        staff.getNavigation().stop();
        var hand = staff.getMainHandItem();
        if (hand.is(ModItems.CARRIER.get()) && CarrierContents.singleToken(hand) == null) staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(staff, "shop", "action=job result=end kind={} phase={} reason={} token={} leg_ticks={}", job.kind, job.phase, reason, job.token, job.leg);
    }

    /** A staff member that dies drops what it holds where it fell: a sold basket for its buyer, a stock basket for the shop, waste for anyone. */
    public static void staffDied(HumanNpc staff, ServerLevel level) {
        var job = JOBS.remove(staff.getUUID());
        var hand = staff.getMainHandItem();
        UUID token = CarrierContents.singleToken(hand);
        if (hand.isEmpty() || hand.is(ModItems.CARRIER.get()) && token == null) return;
        UUID target = null;
        if (token != null) {
            var view = Custody.fromSnapshot(YukkuriWorldData.get(level).captiveSnapshot(token));
            target = view.owner();
        }
        if (ShopTrade.drop(level, target, staff.position(), hand.copy()) != null) staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(staff, "shop", "action=job result=staff_died kind={} token={} pickup={}", job == null ? null : job.kind, token, target);
    }

    /** Playground field clear: jobs of removed staff, animals or baskets end (their entities are gone with the field). */
    public static void forget(Set<UUID> ids, Set<UUID> tokens) {
        JOBS.values().removeIf(j -> ids.contains(j.staff) || ids.contains(j.shop) || j.animal != null && ids.contains(j.animal)
                || j.token != null && tokens.contains(j.token));
        OPERATOR.keySet().removeAll(ids);
        UNREACHABLE.clear();
    }

    // ---- the shop's things ------------------------------------------------------------------------------------------------

    /** Block entities inside the footprint (bowls, nests, toilets, floor waste, barrels), from the loaded chunks it covers. */
    static List<BlockEntity> blockEntities(ServerLevel level, ShopRegistry.Entry shop) {
        var box = shop.bounds(); var out = new ArrayList<BlockEntity>();
        for (int cx = (int) Math.floor(box.minX) >> 4; cx <= (int) Math.floor(box.maxX) >> 4; cx++)
            for (int cz = (int) Math.floor(box.minZ) >> 4; cz <= (int) Math.floor(box.maxZ) >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) for (var be : chunk.getBlockEntities().values()) if (shop.contains(be.getBlockPos().getCenter())) out.add(be);
            }
        return out;
    }

    /** The nearest free nest of the right kind (predator nests for predators), not promised to a basket on its way. */
    static BlockPos freeNest(ServerLevel level, ShopRegistry.Entry shop, boolean predator, Vec3 near) {
        long now = level.getGameTime();
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        for (var be : blockEntities(level, shop)) {
            if (!(be instanceof CareBlockEntity nest) || nest.kind() != CareBlock.Kind.NEST || nest.resident() != null
                    || level.getBlockState(nest.getBlockPos()).is(ModBlocks.PREDATOR_NEST.get()) != predator || !free(nest.getBlockPos(), now)) continue;
            double d = nest.getBlockPos().getCenter().distanceToSqr(near);
            if (d < bestD) { bestD = d; best = nest.getBlockPos(); }
        }
        return best;
    }

    /** Where a held or waiting stock basket goes: a free nest for its animal; an animal marked to ship out goes to the barrel. */
    static BlockPos destination(ServerLevel level, ShopRegistry.Entry shop, ItemStack basket, Vec3 near) {
        UUID token = CarrierContents.singleToken(basket);
        if (token == null) return null;
        var snapshot = YukkuriWorldData.get(level).captiveSnapshot(token);
        if (shipOut(snapshot)) return shop.dock();
        return freeNest(level, shop, predator(snapshot), near);
    }
    /** Whether the basketed individual (its saved snapshot) is a predator species, which lives on predator nests. */
    static boolean predator(net.minecraft.nbt.CompoundTag snapshot) {
        String id = snapshot.getString("id");
        for (var v : YukkuriVariant.values()) if (id.endsWith(":" + v.assetId())) return SpeciesProfile.of(v).dangerToYukkuri();
        return false;
    }
    /** A basket holding one of this shop's stock animals. */
    static boolean shopBasket(ServerLevel level, ShopRegistry.Entry shop, ItemStack stack) {
        UUID token = CarrierContents.singleToken(stack);
        if (!stack.is(ModItems.CARRIER.get()) || token == null || !YukkuriWorldData.get(level).hasToken(token)) return false;
        var view = Custody.fromSnapshot(YukkuriWorldData.get(level).captiveSnapshot(token));
        return view.role() == Custody.Role.STOCK && view.ownedBy(shop.id);
    }
    /** The slot of a stock basket in the arrivals barrel that is not shipping out, or -1. */
    static int dockBasket(ServerLevel level, ShopRegistry.Entry shop) {
        if (shop.dock() == null || !(level.getBlockEntity(shop.dock()) instanceof Container dock)) return -1;
        for (int slot = 0; slot < dock.getContainerSize(); slot++) {
            var stack = dock.getItem(slot);
            if (shopBasket(level, shop, stack) && !shipOut(YukkuriWorldData.get(level).captiveSnapshot(CarrierContents.singleToken(stack)))) return slot;
        }
        return -1;
    }
    static boolean shipOut(YukkuriEntity y) { return HumanCommerce.data(y).getBoolean(ShopTrade.SHIP_OUT); }
    static boolean shipOut(net.minecraft.nbt.CompoundTag snapshot) {
        return snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce").getBoolean(ShopTrade.SHIP_OUT);
    }
    /** Puts the stack into the container (merging first), returning what did not fit. */
    static ItemStack insert(Container box, ItemStack stack) {
        for (int slot = 0; slot < box.getContainerSize() && !stack.isEmpty(); slot++) {
            var there = box.getItem(slot);
            if (there.isEmpty()) { box.setItem(slot, stack.copy()); stack.setCount(0); }
            else if (ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < there.getMaxStackSize()) {
                int moved = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
                there.grow(moved); stack.shrink(moved);
            }
        }
        box.setChanged();
        return stack;
    }
    private ShopStaff() {}
}
