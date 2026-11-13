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
import io.github.longshou.mcyukkuri.item.TreatmentItem;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Shop staff work (docs/FEATURES.md, 宠物店). Every staff member of a shop is the same: when free (awake, not trading, empty-handed) it takes
 * the most pressing piece of work that the shop's real state calls for, in this order: an animal waiting for its buyer, a companion waiting to be
 * sold, a sick or injured animal, a bowl running low, a basket waiting in the arrivals barrel or on the floor, an animal marked to ship out, a toilet or floor waste to
 * clean. Staff choose work from the site's saved orders, bowls, toilets, barrels and baskets.
 * A job walks to its target (through the shop's iron staff doors), works at hand's reach (the same reach test admits and executes), and if it
 * holds something carries it to where it belongs: the counter, a free nest, the arrivals barrel, the waste barrel. A job is transient; what it
 * holds is not: a staff member found holding a basket or waste without a job (restart, a carry that failed) carries it on. A leg that cannot be
 * finished leaves the target to others for a while and anything held where it can be found again.
 */
public final class ShopStaff {
    enum Kind { BUY, SELL, NURSERY, TREAT, SETTLE, SHIP, FEED, CLEAN }
    private enum Phase { GO, WORK, CARRY }
    private static final class Job {
        final UUID staff, shop; final Kind kind;
        Phase phase = Phase.GO;
        UUID animal, token, customer;
        /** GO: the bowl, toilet, waste or barrel worked at; CARRY: the counter, nest or barrel it carries to. */
        BlockPos target;
        BlockPos pursued;
        ShopRegistry.LegacyHatch hatch;
        HatchApproach hatchApproach;
        ItemEntity loose;
        TreatmentItem.Kind dose;
        final Set<BlockPos> tried = new HashSet<>();
        BlockPos approach;
        int leg, stall, timer, waypoint = -1;
        double best = Double.MAX_VALUE;
        Job(UUID staff, UUID shop, Kind kind) { this.staff = staff; this.shop = shop; this.kind = kind; }
    }
    private static final class HatchApproach implements PetLife.Director, MotorOwner {
        private final Vec3 inside;
        private Walk walk;
        private boolean started, active = true;

        HatchApproach(Vec3 inside) { this.inside = inside; }
        @Override public void tick(YukkuriEntity animal) {
            if (started) return;
            started = true;
            walk = Walk.stand(inside, 1.0);
            animal.motor().run(this, walk);
        }
        @Override public void stop(YukkuriEntity animal) {
            if (walk != null) animal.motor().cancel(this, "shop_hatch_closed");
            active = false;
        }
        @Override public String ownerId() { return "shop_legacy_hatch"; }
        @Override public boolean active() { return active; }
    }
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    /** Animals and blocks a staff member could not reach: nobody tries them again before the deadline (transient, at most 64 keys). */
    private static final DeadlineMap<Object> UNREACHABLE = new DeadlineMap<>();
    private record CareTarget(UUID animal) {}
    /** Keep the full leg budget. A stalled route gets a second to clear after each short request to step aside. */
    private static final int LEG = 600, BLOCKED = 20, STALL = 3 * BLOCKED, WORK = 16, RETRY = 1200;
    private static final int MAX_APPROACHES = 8;
    /** A bowl holding fewer portions than this is refilled with one serving of regular grain. */
    private static final int BOWL_LOW = 2, SERVING = 4;
    /** Hand's reach from the staff member's feet to the target's centre, with a clear line from its eyes. */
    private static final double REACH = 2.5, SPEED = .9;
    private static final double COUNTER_REACH = 1.5;

    public static void started(ServerStartingEvent event) { JOBS.clear(); UNREACHABLE.clear(); ShopDoors.clear(); }
    public static void stopped(ServerStoppedEvent event) { JOBS.clear(); UNREACHABLE.clear(); ShopDoors.clear(); }
    /** True while the staff member runs a job: its routine and strolling goals stand aside. */
    public static boolean active(HumanNpc staff) { return JOBS.containsKey(staff.getUUID()); }
    public static boolean busy(HumanNpc npc) { return active(npc) || !npc.getMainHandItem().isEmpty(); }
    /** A staff member on a job passes the iron staff doors of its own shop (and no others). */
    public static boolean opensDoor(HumanNpc staff, BlockPos door) {
        var job = JOBS.get(staff.getUUID());
        if (job == null || !(staff.level() instanceof ServerLevel level)) return false;
        var shop = ShopRegistry.get(level).shop(job.shop);
        return shop != null && !shop.closed() && shop.contains(door.getCenter());
    }

    /** Every 20 ticks per staff member: the restock clock, waiting orders that cannot go on, and new work when free. */
    public static void tick(HumanNpc staff) {
        if (!(staff.level() instanceof ServerLevel level) || active(staff) || staff.isNoAi()) return;
        staff.returnUnusedDose();
        var shop = ShopRegistry.get(level).of(staff);
        if (shop == null) return;
        ShopRestock.tick(level, shop);
        reviewOrders(level, shop);
        if (staff.isTrading() || staff.hurtTime > 0) return;
        if (resume(level, staff, shop)) return;
        if (staff.resting() || !staff.getMainHandItem().isEmpty()) return;
        pick(level, staff, shop);
    }

    /** Orders nobody serves yet are still checked: a buyer who left, nightfall or a lost animal cancels them visibly. */
    static void reviewOrders(ServerLevel level, ShopRegistry.Entry shop) {
        for (var entry : List.copyOf(shop.orders.entrySet())) {
            var id = entry.getKey();
            var order = entry.getValue();
            if (order.token() != null) continue;
            var entity = level.getEntity(id);
            if (!(entity instanceof YukkuriEntity y)) {
                if (YukkuriWorldData.get(level).isCaptured(id) || ShopRestock.shopLoaded(level, shop)) ShopTrade.cancel(level, shop, id, "gone");
                continue;
            }
            String problem = ShopTrade.orderProblem(level, shop, y, order);
            if (problem == null && !y.pet().busyWith().equals(order.kind()) &&
                    !y.pet().lock(order.kind(), order.kind().equals(ShopTrade.HANDOVER) ? null : order.customer()))
                problem = "capture";
            if (problem != null) ShopTrade.cancel(level, shop, id, problem);
        }
    }

    /** Operator handover (command): the animal waits like a bought one and the first free staff member brings it, free of charge, for {@code buyer}. */
    public static boolean handover(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID buyer) {
        var holder = level.getEntitiesOfClass(HumanNpc.class, shop.bounds(), n -> n.isAlive() && ShopRegistry.get(level).of(n) == shop).stream().findFirst().orElse(null);
        return holder != null && ShopTrade.handover(level, shop, y, buyer);
    }

    // ---- choosing work ------------------------------------------------------------------------------------------------

    private static void pick(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop) {
        long now = level.getGameTime();
        if (UNREACHABLE.size() > 64) UNREACHABLE.prune(now);
        var stock = ShopRegistry.liveStock(level, shop);
        for (var entry : shop.orders.entrySet()) {
            var order = entry.getValue();
            if (order.token() != null || !free(entry.getKey(), now) || !(level.getEntity(entry.getKey()) instanceof YukkuriEntity y)) continue;
            var job = new Job(staff.getUUID(), shop.id, order.kind().equals(ShopTrade.SELL) ? Kind.SELL : Kind.BUY);
            job.animal = y.getUUID(); job.customer = order.customer();
            start(staff, job, y.position()); return;
        }
        if (staff.hasMedicine()) for (var y : stock) {
            if (!free(y.getUUID(), now) || UNREACHABLE.active(new CareTarget(y.getUUID()), now)) continue;
            var dose = careDose(shop, y);
            if (dose == null) continue;
            var job = new Job(staff.getUUID(), shop.id, Kind.TREAT);
            job.animal = y.getUUID();
            job.dose = dose;
            start(staff, job, y.position());
            return;
        }
        if (graduate(level, staff, shop)) return;
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
            if (free(item.getUUID(), now) && !shipOut(YukkuriWorldData.get(level).captiveSnapshot(CarrierContents.singleToken(item.getItem())))
                    && (shop.legacy() || destination(level, shop, item.getItem(), staff.position()) != null)) {
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

    private static boolean graduate(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop) {
        if (staff.tickCount % 100 != 3) return false;
        for (var animal : level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds().inflate(16), ShopStaff::readyNursery)) {
            if (!shop.kind.accepts(animal) || !free(animal.getUUID(), level.getGameTime())
                    || freeNest(level, shop, animal.profile().dangerToYukkuri(), animal.position()) == null) continue;
            var job = new Job(staff.getUUID(), shop.id, Kind.NURSERY);
            job.animal = animal.getUUID();
            start(staff, job, animal.position());
            return true;
        }
        return false;
    }

    private static boolean readyNursery(YukkuriEntity animal) {
        return animal.isAlive() && Custody.view(animal).role() == Custody.Role.SHOP_NURSERY
                && (animal.family().adult() || animal.childhood().canLiveIndependently())
                && !animal.pet().busy() && !animal.isPassenger() && !animal.isVehicle();
    }
    private static void start(HumanNpc staff, Job job, Vec3 toward) {
        JOBS.put(staff.getUUID(), job);
        if (job.kind == Kind.BUY || job.kind == Kind.SELL || job.kind == Kind.SHIP || job.kind == Kind.NURSERY)
            staff.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
        GameplayLog.event(staff, "shop", "action=job result=start kind={} phase={} target={} at={} token={}", job.kind, job.phase,
                job.animal != null ? job.animal : job.target, GameplayLog.at(toward), job.token);
    }

    /** A staff member holding a basket or waste with no job carries it on to where it belongs. */
    private static boolean resume(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop) {
        var hand = staff.getMainHandItem();
        if (hand.is(ModItems.WASTE.get())) {
            if (shop.trash() == null || UNREACHABLE.active(shop.trash(), level.getGameTime())) return true;
            var job = new Job(staff.getUUID(), shop.id, Kind.CLEAN); job.phase = Phase.CARRY; job.target = shop.trash();
            start(staff, job, shop.trash().getCenter()); return true;
        }
        if (!hand.is(ModItems.CARRIER.get())) return false;
        UUID token = CarrierContents.singleToken(hand);
        var custody = YukkuriWorldData.get(level);
        if (token == null || !custody.hasToken(token)) {
            staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return false;
        }
        var view = Custody.fromSnapshot(custody.captiveSnapshot(token));
        Job job;
        if (view.ownerKind() == Custody.OwnerKind.PLAYER || shop.orders.values().stream().anyMatch(order -> token.equals(order.token()))) {
            job = new Job(staff.getUUID(), shop.id, Kind.BUY); job.customer = view.owner(); job.target = shop.counter();
        } else {
            job = new Job(staff.getUUID(), shop.id, Kind.SETTLE); job.target = destination(level, shop, hand, staff.position());
            if (job.target == null) job.target = shop.legacy() ? shop.counter() : shop.dock();
            if (job.target == null) return false;
        }
        if (UNREACHABLE.active(job.target, level.getGameTime())) return true;
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
        if (shop == null || shop.closed()) { end(staff, job, "shop_closed"); return; }
        if (staff.isTrading() || staff.hurtTime > 0) {
            if (job.approach != null || !staff.getNavigation().isDone()) interrupt(staff);
            return;
        }
        if (++job.leg > LEG) { fail(level, staff, shop, job, "leg_timeout"); return; }
        switch (job.phase) {
            case GO -> go(level, staff, shop, job);
            case WORK -> work(level, staff, shop, job);
            case CARRY -> carry(level, staff, shop, job);
        }
    }

    /** Keep the job and anything in hand while a player opens the menu or another goal briefly takes movement. */
    public static void interrupt(HumanNpc staff) {
        var job = JOBS.get(staff.getUUID());
        if (job == null) return;
        staff.getNavigation().stop();
        clearApproach(job);
        // The leg budget and the work timer stop with the walk: the pause is recorded, the job goes on once the menu closes or the flinch passes.
        GameplayLog.limited(staff, "shop", "job_paused", 100, "action=job result=paused kind={} phase={} reason={} leg_ticks={}",
                job.kind, job.phase, staff.isTrading() ? "trade" : staff.hurtTime > 0 ? "hurt" : "goal", job.leg);
    }

    private static YukkuriEntity animal(ServerLevel level, Job job) {
        return job.animal != null && level.getEntity(job.animal) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private static Vec3 centre(YukkuriEntity y) { return y.position().add(0, y.getBbHeight() / 2, 0); }

    private static TreatmentItem.Kind careDose(ShopRegistry.Entry shop, YukkuriEntity y) {
        return y != null && shop.contains(y.position()) && ShopTrade.stockOf(y, shop.id)
                && !shop.orders.containsKey(y.getUUID()) && !y.pet().busy() && !y.isPassenger() && !y.isVehicle()
                ? TreatmentItem.neededCare(y) : null;
    }

    /** Why the job's target no longer needs this job, or null. Orders that cannot go on are cancelled here, visibly. */
    private static String stale(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        switch (job.kind) {
            case BUY, SELL -> {
                var y = animal(level, job);
                var order = shop.orders.get(job.animal);
                if (order == null || order.token() != null) return "order_ended";
                if (y == null && !ShopRestock.shopLoaded(level, shop)) return "target_unloaded";
                String problem = y == null ? "gone" : ShopTrade.orderProblem(level, shop, y, order);
                if (problem != null) { ShopTrade.cancel(level, shop, job.animal, problem); return problem; }
            }
            case SHIP -> {
                var y = animal(level, job);
                if (y == null || !ShopTrade.stockOf(y, shop.id) || !shipOut(y) || y.pet().busy() || shop.deposit(y.getUUID()) != null) return "kept";
            }
            case NURSERY -> {
                var y = animal(level, job);
                if (y == null || !readyNursery(y)) return "nursery_changed";
                if (freeNest(level, shop, y.profile().dangerToYukkuri(), y.position()) == null) return "no_nest";
            }
            case TREAT -> {
                if (!level.isDay() || careDose(shop, animal(level, job)) != job.dose) return "care_changed";
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
        if (y != null && job.kind == Kind.BUY && shop.legacy() && !legacyFetch(level, staff, shop, job, y)) return;
        staff.getLookControl().setLookAt(point.x, point.y, point.z, 30, 30);
        if (!reaches(staff, point, block)) { walk(level, staff, shop, job, point); return; }
        staff.getNavigation().stop();
        job.phase = Phase.WORK;
        if (job.kind == Kind.TREAT) {
            if (!staff.reserveDose(y, job.dose)) { fail(level, staff, shop, job, "dose_unavailable"); return; }
            y.life().wakeForService();
            y.getLookControl().setLookAt(staff, 30, 30);
        } else {
            staff.swing(InteractionHand.MAIN_HAND);
        }
        if (y != null && job.kind != Kind.TREAT) {
            y.life().wakeForService(); // a sleeping animal is woken: the order or the move goes on
            y.getLookControl().setLookAt(staff, 30, 30);
            y.playAction("basket_in"); y.speak("basket_enter");
        }
        GameplayLog.event(staff, "shop", "action=job result=reach kind={} target={} staff_at={} target_at={}", job.kind,
                y != null ? y.getUUID() : job.target, GameplayLog.at(staff.position()), GameplayLog.at(point));
    }

    private static boolean legacyFetch(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job, YukkuriEntity y) {
        if (job.hatch == null) job.hatch = ShopRegistry.legacyHatch(level, shop, y);
        if (job.hatch == null) return true;
        var stand = Vec3.atBottomCenterOf(job.hatch.outside());
        if (staff.position().distanceToSqr(stand) > 1.25) {
            if (job.approach == null) {
                var path = staff.shopPath(job.hatch.outside());
                if (path == null || !path.canReach() || !staff.getNavigation().moveTo(path, SPEED)) {
                    fail(level, staff, shop, job, "unreachable"); return false;
                }
                job.approach = job.hatch.outside();
            } else if (staff.getNavigation().isDone() && job.leg > 3) fail(level, staff, shop, job, "unreachable");
            return false;
        }
        if (job.hatchApproach == null) {
            staff.getNavigation().stop();
            ShopDoors.enter(staff, job.hatch.door());
            y.life().wakeForService();
            job.hatchApproach = new HatchApproach(job.hatch.inside());
            y.pet().direct(job.hatchApproach);
        }
        return reaches(staff, centre(y), null);
    }

    private static void work(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        String why = stale(level, staff, shop, job);
        if (why != null) { end(staff, job, why); return; }
        var y = animal(level, job);
        Vec3 point = job.loose != null ? job.loose.position() : y != null ? centre(y) : job.target.getCenter();
        if (!reaches(staff, point, job.loose != null || y != null ? null : job.target)) {
            if (job.kind == Kind.TREAT) job.timer = 0;
            job.phase = Phase.GO;
            clearApproach(job);
            return;
        }
        staff.getNavigation().stop();
        staff.getLookControl().setLookAt(point.x, point.y, point.z, 30, 30);
        ++job.timer;
        if (job.kind == Kind.TREAT) {
            if (job.timer == HumanNpc.DOSE_SWING_TICKS) staff.swing(InteractionHand.MAIN_HAND);
            if (job.timer < HumanNpc.DOSE_TICKS) return;
            if (staff.applyDose(y, job.dose)) end(staff, job, "treated");
            else fail(level, staff, shop, job, "treatment_refused");
            return;
        }
        if (job.timer < WORK) return;
        switch (job.kind) {
            case BUY -> {
                job.token = ShopTrade.captureSale(level, staff, shop, y);
                if (job.token == null) { end(staff, job, "not_bought"); return; }
                if (job.hatch != null) staff.closeShopDoor();
                carryTo(job, shop.counter());
            }
            case SELL -> {
                job.token = ShopTrade.captureSold(level, staff, shop, y);
                if (job.token == null) { end(staff, job, "not_sold"); return; }
                carryTo(job, destinationOr(level, shop, staff));
            }
            case NURSERY -> {
                job.token = YukkuriWorldData.get(level).capture(y, () -> {
                    y.life().releaseHome();
                    Custody.assign(y, shop.id, Custody.OwnerKind.NPC, Custody.Role.STOCK);
                    shop.kind.prepareStock(y);
                });
                if (job.token == null) { end(staff, job, "capture_refused"); return; }
                staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), job.token));
                GameplayLog.event(staff, "shop", "action=nursery result=packed shop={} target={} token={}", shop.id, y.getUUID(), job.token);
                carryTo(job, destinationOr(level, shop, staff));
            }
            case SHIP -> {
                job.token = YukkuriWorldData.get(level).capture(y, y.life()::releaseHome);
                if (job.token == null) { end(staff, job, "capture_refused"); return; }
                staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), job.token));
                GameplayLog.event(staff, "shop", "action=ship_out result=packed shop={} target={} token={}", shop.id, y.getUUID(), job.token);
                carryTo(job, shop.dock());
            }
            case FEED -> {
                var bowl = (CareBlockEntity) level.getBlockEntity(job.target);
                for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
                    int served = staff.drawCareFood(SERVING);
                    bowl.setItem(slot, new ItemStack(shop.kind.grain(), served));
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
        job.phase = Phase.CARRY; job.target = where; job.leg = 0; job.timer = 0;
        job.tried.clear(); job.pursued = null;
        clearApproach(job);
    }
    /** The free nest for the held basket, else the arrivals barrel to wait there for one. */
    private static BlockPos destinationOr(ServerLevel level, ShopRegistry.Entry shop, HumanNpc staff) {
        var nest = destination(level, shop, staff.getMainHandItem(), staff.position());
        return nest != null ? nest : shop.legacy() ? shop.counter() : shop.dock();
    }

    private static void carry(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        var hand = staff.getMainHandItem();
        boolean holding = job.kind == Kind.CLEAN ? hand.is(ModItems.WASTE.get()) : job.token != null && job.token.equals(CarrierContents.singleToken(hand));
        if (!holding) { end(staff, job, "hand_changed"); return; }
        if (job.target == null) { fail(level, staff, shop, job, "no_destination"); return; }
        // A nest taken meanwhile is exchanged for another free one (or the barrel).
        if (level.getBlockEntity(job.target) instanceof CareBlockEntity nest && nest.kind() == CareBlock.Kind.NEST && nest.resident() != null) {
            carryTo(job, destinationOr(level, shop, staff));
            return;
        }
        boolean counter = job.kind == Kind.BUY;
        Vec3 point = counter ? Vec3.atBottomCenterOf(job.target) : job.target.getCenter();
        boolean arrived = counter ? staff.onGround() && atCounter(staff.position(), point) : reaches(staff, point, job.target);
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
        if (job.kind == Kind.CLEAN) { fail(level, staff, shop, job, "no_destination"); return; }
        if (shop.legacy() && job.target.equals(shop.counter())) {
            if (ShopRegistry.releaseBasketNear(level, job.token, staff.blockPosition(), false) == null) {
                fail(level, staff, shop, job, "set_down_blocked"); return;
            }
            staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            end(staff, job, "released");
        } else release(level, staff, shop, job);
    }

    /** The basket is opened at the nest entrance, then the animal claims the bed through ordinary home admission. */
    private static void release(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job) {
        var nest = job.target;
        var y = YukkuriWorldData.get(level).releaseAtNest(job.token, level, nest, staff.getYRot());
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

    /** The active path owns progress around corners. Distance to the faraway work site can increase on a good route. */
    private static void walk(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Job job, Vec3 point) {
        var target = BlockPos.containing(point);
        var navigation = staff.getNavigation();
        if (job.pursued == null || job.pursued.distSqr(target) > 4) {
            job.pursued = target;
            job.tried.clear();
            navigation.stop();
            clearApproach(job);
        }
        LivingEntity obstruction = null;
        if (job.approach != null && !navigation.isDone()) {
            var path = navigation.getPath();
            var toward = path.getNextEntityPos(staff);
            int next = path.getNextNodeIndex();
            double distance = staff.position().distanceTo(toward);
            if (next != job.waypoint || distance < job.best - .08) {
                job.waypoint = next;
                job.best = distance;
                job.stall = 0;
            } else job.stall++;
            if (job.stall > 0 && job.stall % BLOCKED == 0) {
                obstruction = blocker(level, staff, toward);
            }
            // A feeder asks the stock occupying the next pace before it spends a full stall waiting.
            if (obstruction != null || job.kind == Kind.FEED && job.leg % 5 == 0) {
                var inWay = obstruction != null ? obstruction : blocker(level, staff, toward);
                if (inWay instanceof YukkuriEntity animal && !animal.getUUID().equals(job.animal)
                        && ShopTrade.stockOf(animal, shop.id) && YukkuriGroundNavigation.giveWay(animal, staff, toward))
                    GameplayLog.limited(staff, "shop", "give_way", 100, "action=job result=asked_way kind={} animal={} waypoint={}",
                            job.kind, animal.getUUID(), path.getNextNodePos().toShortString());
            }
            if (job.stall < STALL) return;
        }
        if (!staff.onGround() && !staff.isInWater()) return;
        var previous = job.approach;
        navigation.stop();
        clearApproach(job);
        boolean detourToSameStand = obstruction != null && previous != null && !job.tried.contains(previous);
        if (previous != null) job.tried.add(previous);
        if (job.tried.size() >= MAX_APPROACHES) { fail(level, staff, shop, job, "unreachable"); return; }
        boolean counter = job.phase == Phase.CARRY && job.kind == Kind.BUY;
        var touched = job.phase == Phase.CARRY || job.loose == null && job.animal == null ? job.target : null;
        var free = new LinkedHashSet<BlockPos>();
        var occupiedByStock = new LinkedHashSet<BlockPos>();
        for (var cell : BlockPos.withinManhattan(target, 2, 1, 2)) {
            if (!level.hasChunkAt(cell)) continue;
            var stand = Vec3.atBottomCenterOf(cell);
            if (counter && !atCounter(stand, point) || job.tried.contains(cell) && !(detourToSameStand && cell.equals(previous))
                    || !level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) continue;
            var box = staff.getBoundingBox().move(stand.subtract(staff.position()));
            if (level.getBlockCollisions(staff, box).iterator().hasNext()) continue;
            if (!counter && !reaches(staff, stand, point, touched)) continue;
            var occupants = level.getEntitiesOfClass(LivingEntity.class, box, e -> e != staff && e.isAlive() && !e.isSpectator());
            if (occupants.isEmpty()) free.add(cell.immutable());
            else if (job.kind == Kind.FEED && occupants.stream().allMatch(e -> e instanceof YukkuriEntity animal && ShopTrade.stockOf(animal, shop.id)))
                occupiedByStock.add(cell.immutable());
        }
        var obstacle = obstruction == null ? null : obstruction.getBoundingBox();
        var path = free.isEmpty() ? null : staff.shopPath(free, obstacle);
        // A crowded bowl still needs service. If there is no route to a free stand,
        // approach the waiting stock and ask it to yield when actually in the way.
        if ((path == null || !path.canReach()) && !occupiedByStock.isEmpty())
            path = staff.shopPath(occupiedByStock, obstacle);
        if (path != null && path.canReach() && navigation.moveTo(path, SPEED)) {
            job.approach = path.getTarget();
            if (obstruction != null)
                GameplayLog.limited(staff, "shop", "carry_detour", 100, "action=job result=detour kind={} phase={} blocker={} stand={}",
                        job.kind, job.phase, obstruction.getUUID(), job.approach.toShortString());
            return;
        }
        if (obstruction != null || job.kind == Kind.FEED)
            GameplayLog.limited(staff, "shop", "approach_blocked." + job.kind, 100,
                    "action=job result=no_route kind={} phase={} blocker={} free_stands={} stock_stands={}",
                    job.kind, job.phase, obstruction == null ? null : obstruction.getUUID(), free.size(), occupiedByStock.size());
        fail(level, staff, shop, job, "unreachable");
    }

    private static void clearApproach(Job job) {
        job.approach = null;
        job.waypoint = -1;
        job.stall = 0;
        job.best = Double.MAX_VALUE;
    }

    /** Look for a real body in the next pace, including one already pressing into the walker. */
    private static LivingEntity blocker(ServerLevel level, HumanNpc staff, Vec3 toward) {
        var direction = toward.subtract(staff.position()).multiply(1, 0, 1);
        if (direction.lengthSqr() < 1e-6) return null;
        var step = direction.normalize().scale(Math.min(staff.getBbWidth(), direction.length()));
        var body = staff.getBoundingBox();
        var ahead = body.move(step);
        return level.getEntitiesOfClass(LivingEntity.class, body.expandTowards(step),
                        e -> e != staff && e.isAlive() && !e.isSpectator() && e.getBoundingBox().intersects(ahead))
                .stream().min(Comparator.comparingDouble(staff::distanceToSqr)).orElse(null);
    }

    private static boolean atCounter(Vec3 stand, Vec3 counter) {
        var offset = stand.subtract(counter);
        return Math.abs(offset.y) < 1 && offset.horizontalDistanceSqr() <= COUNTER_REACH * COUNTER_REACH;
    }

    /** Hand's reach: near enough and a clear line from the eyes (a hit on the target block itself counts as reaching it). */
    static boolean reaches(HumanNpc staff, Vec3 point, BlockPos block) {
        return reaches(staff, staff.position(), point, block);
    }

    private static boolean reaches(HumanNpc staff, Vec3 stand, Vec3 point, BlockPos block) {
        if (stand.distanceToSqr(point) > REACH * REACH) return false;
        var hit = staff.level().clip(new ClipContext(stand.add(0, staff.getEyeHeight(), 0), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, staff));
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
        if (key != null) UNREACHABLE.put(job.kind == Kind.TREAT ? new CareTarget(job.animal) : key, now + RETRY);
        GameplayLog.limited(staff, "shop", "job_fail." + job.kind + "." + reason, 200, "action=job result=failed kind={} phase={} reason={} target={} token={} leg_ticks={}",
                job.kind, job.phase, reason, key, job.token, job.leg);
        if (job.phase != Phase.CARRY) {
            if (job.kind == Kind.BUY || job.kind == Kind.SELL) ShopTrade.cancel(level, shop, job.animal, "unreachable");
            else staff.swing(InteractionHand.MAIN_HAND);
            end(staff, job, reason);
            return;
        }
        if (job.kind != Kind.BUY && job.kind != Kind.CLEAN && shop.dock() != null && !shop.dock().equals(job.target)
                && !UNREACHABLE.active(shop.dock(), now)) {
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
        clearHatch(staff, job);
        staff.getNavigation().stop();
        staff.closeShopDoor();
        if (job.kind == Kind.TREAT) staff.returnUnusedDose();
        var hand = staff.getMainHandItem();
        if (hand.is(ModItems.CARRIER.get()) && CarrierContents.singleToken(hand) == null) staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(staff, "shop", "action=job result=end kind={} phase={} reason={} token={} leg_ticks={}", job.kind, job.phase, reason, job.token, job.leg);
    }

    /** A staff member that dies drops what it holds where it fell: a sold basket for its buyer, a stock basket for the shop, waste for anyone. */
    public static void staffDied(HumanNpc staff, ServerLevel level) {
        var job = JOBS.remove(staff.getUUID());
        clearHatch(staff, job);
        var hand = staff.getMainHandItem();
        UUID token = CarrierContents.singleToken(hand);
        if (hand.isEmpty() || hand.is(ModItems.CARRIER.get()) && token == null) return;
        var view = token == null ? null : Custody.fromSnapshot(YukkuriWorldData.get(level).captiveSnapshot(token));
        UUID target = view == null ? null : view.owner();
        if (ShopTrade.drop(level, target, staff.position(), hand.copy()) != null) staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        else if (hand.getItem() instanceof TreatmentItem || view != null && view.role() == Custody.Role.STOCK)
            staff.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2.0f);
        else if (view != null) staff.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0f);
        GameplayLog.event(staff, "shop", "action=job result=staff_died kind={} token={} pickup={}", job == null ? null : job.kind, token, target);
    }

    public static void leaving(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof HumanNpc staff && event.getLevel() instanceof ServerLevel) {
            clearHatch(staff, JOBS.remove(staff.getUUID()));
        }
    }

    private static void clearHatch(HumanNpc staff, Job job) {
        if (job != null && job.hatchApproach != null && staff.level() instanceof ServerLevel level) {
            var animal = animal(level, job);
            if (animal != null && animal.pet().director() == job.hatchApproach) animal.pet().direct(null);
        }
        staff.closeShopDoor();
    }

    /** Playground field clear: jobs of removed staff, animals or baskets end (their entities are gone with the field). */
    public static void forget(Set<UUID> ids, Set<UUID> tokens) {
        JOBS.values().removeIf(j -> ids.contains(j.staff) || ids.contains(j.shop) || j.animal != null && ids.contains(j.animal)
                || j.token != null && tokens.contains(j.token));
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

    /** The nearest unclaimed nest of the right kind (predator nests for predators). */
    static BlockPos freeNest(ServerLevel level, ShopRegistry.Entry shop, boolean predator, Vec3 near) {
        long now = level.getGameTime();
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        for (var be : blockEntities(level, shop)) {
            if (!(be instanceof CareBlockEntity nest) || nest.kind() != CareBlock.Kind.NEST || nest.resident() != null
                    || level.getBlockState(nest.getBlockPos()).is(ModBlocks.PREDATOR_NEST.get()) != predator || UNREACHABLE.active(nest.getBlockPos(), now)) continue;
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
    static int openDockSlot(Container dock) {
        for (int slot = 0; slot < dock.getContainerSize(); slot++) {
            var item = dock.getItem(slot);
            if (item.isEmpty() || item.is(ModItems.CARRIER.get()) && CarrierContents.tokens(item).isEmpty()) return slot;
        }
        return -1;
    }

    static boolean holdsBasket(ServerLevel level, UUID token) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof HumanNpc staff && staff.isAlive() && CarrierContents.tokens(staff.getMainHandItem()).contains(token)) return true;
            if (entity instanceof ItemEntity item && item.isAlive() && CarrierContents.tokens(item.getItem()).contains(token)) return true;
        }
        return false;
    }

    static void emptyStockBasket(ServerLevel level, ShopRegistry.Entry shop, UUID token) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof HumanNpc npc && CarrierContents.tokens(npc.getMainHandItem()).contains(token)) {
                var stack = npc.getMainHandItem().copy();
                CarrierContents.remove(stack, token);
                npc.setItemInHand(InteractionHand.MAIN_HAND, stack);
            }
            if (entity instanceof ItemEntity item && CarrierContents.tokens(item.getItem()).contains(token)) {
                var stack = item.getItem().copy();
                CarrierContents.remove(stack, token);
                item.setItem(stack);
                if (CarrierContents.tokens(stack).isEmpty()) item.setTarget(null);
            }
        }
        for (var be : blockEntities(level, shop)) if (be instanceof Container box)
            for (int slot = 0; slot < box.getContainerSize(); slot++)
                if (CarrierContents.tokens(box.getItem(slot)).contains(token)) {
                    var stack = box.getItem(slot).copy();
                    CarrierContents.remove(stack, token);
                    box.setItem(slot, stack);
                    box.setChanged();
                }
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
