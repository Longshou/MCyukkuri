package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Staff carry bought and sold animals between the customer and the pen in a transport basket.
 *  A bought animal goes the other way: the keeper walks to it with an empty basket, it climbs in, and the keeper carries it to the shopkeeper at
 *  the counter, where the buyer receives the basket. An animal in a display booth or stock tank is reached only through its pen's
 *  {@link ServiceHatch}: the keeper opens the door, the animal comes to it and goes into the basket through the doorway (or climbs out of the
 *  basket into the pen there), and the door is closed again; nothing is passed through glass.
 *  One transient job per keeper; the basket in the keeper's main hand is the saved half of the custody, so a restart resumes through {@link #recover}.
 *  A leg that cannot complete leaves the original basket available to pick up. Only a successful set-down consumes its token. */
public final class ShopCourier {
    private enum Kind { SOLD, SETTLE, BUY }
    private enum Phase { FETCH, BASKET, CARRY }
    private static final class Job {
        final UUID keeper, shop, animal; final Kind kind; final BlockPos pen; UUID token, buyer; Phase phase; int price;
        final ShopPerformance.Reservation reservation;
        int leg, timer, stall; double best = 1e9;
        Job(UUID keeper, UUID shop, Kind kind, UUID animal, UUID token, BlockPos pen, ShopPerformance.Reservation reservation) { this.keeper = keeper;
                this.shop = shop; this.kind = kind; this.animal = animal; this.token = token; this.pen = pen; this.reservation = reservation; }
    }
    /** Keeper UUID to its running job. */
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    /** Ticks a single leg may take, stall ticks without getting closer, and attempts to spray before giving up. */
    private static final int LEG = 400, STALL = 120, BASKET_TICKS = 16, WAKE_TICKS = 100;

    public static void started(ServerStartingEvent event) { JOBS.clear(); }
    public static void forget(ServerLevel level, Set<UUID> ids, Set<UUID> tokens) {
        for (var job : List.copyOf(JOBS.values())) if (ids.contains(job.keeper) || job.shop != null && ids.contains(job.shop)
                || job.animal != null && ids.contains(job.animal) || job.token != null && tokens.contains(job.token)) {
            if (job.kind == Kind.BUY) {
                var target = animal(level, job);
                if (target != null) ShopTrade.unlock(target, job.shop, job.buyer, job.price);
                if (level.getEntity(job.keeper) instanceof HumanNpc staff) emptyHand(staff);
                endJob(level, job, "field_clear");
                continue;
            }
            if (job.animal != null) for (var dimension : level.getServer().getAllLevels())
                if (dimension.getEntity(job.animal) instanceof YukkuriEntity animal) { animal.pet().unlock("courier"); animal.pet().unlock("sale"); }
            endJob(level, job, "field_clear");
        }
    }
    public static void stopped(ServerStoppedEvent event) { JOBS.clear(); }

    /** True while the keeper runs a job: its routine and wandering goals stand aside. */
    public static boolean active(HumanNpc keeper) { return JOBS.containsKey(keeper.getUUID()); }
    public static boolean busy(HumanNpc keeper) { return JOBS.containsKey(keeper.getUUID()) || keeper.getMainHandItem().is(ModItems.CARRIER.get()); }
    private static HumanNpc idleStaff(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity animal) {
        var registry = ShopRegistry.get(level);
        return level.getEntitiesOfClass(HumanNpc.class, shop.bounds(), n -> n.isAlive() && n.shopStaff()
                && !n.isNoAi() && n.hurtTime == 0 && !busy(n) && n.getMainHandItem().isEmpty() && registry.of(n) == shop)
                .stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
    }

    static boolean dispatchSale(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity animal, UUID seller, int price,
                                BlockPos pen, ShopPerformance.Reservation reservation) {
        var staff = idleStaff(level, shop, animal);
        if (staff == null) return false;
        var transferred = ShopPerformance.handoff(level, reservation);
        if (transferred == null) return false;
        dispatch(level, staff, shop, Kind.SOLD, animal, seller, price, pen, transferred);
        return true;
    }

    static boolean dispatchPurchase(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity animal, UUID buyer, int price, BlockPos counter) {
        var staff = idleStaff(level, shop, animal);
        if (staff == null) return false;
        dispatch(level, staff, shop, Kind.BUY, animal, buyer, price, counter, null);
        return true;
    }

    private static void dispatch(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, Kind kind, YukkuriEntity animal,
                                 UUID customer, int price, BlockPos pen, ShopPerformance.Reservation reservation) {
        staff.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
        var job = start(level, staff, shop.keeper, kind, Phase.FETCH, animal.getUUID(), null, pen, reservation);
        job.buyer = customer;
        job.price = price;
    }

    /** A keeper that holds a loaded basket without a job (the server restarted mid-delivery) finishes the delivery into a free pen, or sets the animal down at its feet. */
    public static void recover(HumanNpc keeper, ServerLevel level) {
        if (JOBS.containsKey(keeper.getUUID())) return;
        ItemStack hand = keeper.getMainHandItem();
        if (!hand.is(ModItems.CARRIER.get())) return;
        UUID token = CarrierItem.token(hand);
        var custody = YukkuriWorldData.get(level);
        if (token == null || !custody.hasToken(token)) {
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            GameplayLog.event(keeper,"courier","action=recover result=cleared token={} reason={}",token,token==null?"empty_carrier":"token_missing");
            return;
        }
        // A bought animal's basket (its custody is already the buyer's) goes on to that buyer, never back into a pen.
        var bought = Custody.fromSnapshot(custody.captiveSnapshot(token));
        if (bought.ownerKind() == Custody.OwnerKind.PLAYER && bought.owner() != null) {
            var basket = hand.copy();
            if (ShopTrade.drop(level, bought.owner(), keeper.position(), basket) == null) return;
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            GameplayLog.event(keeper,"courier","action=recover result=basket_left_for_buyer token={} buyer={}",token,bought.owner());
            return;
        }
        var shop = ShopRegistry.get(level).shop(bought.owner());
        var variant = io.github.longshou.mcyukkuri.entity.YukkuriVariant.byId(custody.captiveSnapshot(token).getString("id"));
        BlockPos pen = shop == null || variant == null ? null : ShopPens.freePen(level, shop, variant, false);
        if (pen != null) {
            var reservation = ShopPerformance.reserve(level, pen);
            if (reservation != null) { start(level, keeper, shop.keeper, Kind.SETTLE, Phase.CARRY, null, token, pen, reservation); return; }
        }
        if (ShopTrade.drop(level, null, keeper.position(), hand.copy()) == null) return;
        keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(keeper, "courier", "action=recover result=basket_on_ground token={} shop={}", token, bought.owner());
    }

    /** The staff member's held basket survives death as the same item, with the same token. */
    public static void staffDied(HumanNpc staff, ServerLevel level) {
        var hand = staff.getMainHandItem();
        if (!hand.is(ModItems.CARRIER.get())) return;
        var token = CarrierItem.token(hand);
        if (token == null) return;
        var owner = Custody.fromSnapshot(YukkuriWorldData.get(level).captiveSnapshot(token));
        UUID buyer = owner.ownerKind() == Custody.OwnerKind.PLAYER ? owner.owner() : null;
        if (ShopTrade.drop(level, buyer, staff.position(), hand.copy()) == null) return;
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(staff, "courier", "action=death result=basket_on_ground token={} buyer={}", token, buyer);
    }

    private static Job start(ServerLevel level, HumanNpc keeper, UUID shop, Kind kind, Phase phase, UUID animal, UUID token, BlockPos pen, ShopPerformance.Reservation reservation) {
        keeper.closeServiceHatch();
        var job = new Job(keeper.getUUID(), shop, kind, animal, token, pen, reservation);
        job.phase = phase;
        JOBS.put(job.keeper, job);
        GameplayLog.event(keeper,"courier","action=start kind={} phase={} target={} token={} shop={} destination={}",kind,phase,animal,token,job.shop,pen.toShortString());
        var participants = new HashSet<UUID>();
        participants.add(job.keeper);
        if (job.shop != null) participants.add(job.shop);
        if (job.animal != null) participants.add(job.animal);
        if (job.token != null) participants.add(YukkuriWorldData.get(level).captiveSnapshot(job.token).getUUID("UUID"));
        ShopPerformance.schedule(level, participants, age -> step(level, job));
        return job;
    }
    private static boolean endJob(ServerLevel level, Job job, String reason) {
        if (level.getEntity(job.keeper) instanceof HumanNpc keeper) keeper.closeServiceHatch();
        JOBS.remove(job.keeper, job); ShopPerformance.release(job.reservation);
        GameplayLog.world(level,"courier","action=end keeper={} target={} token={} shop={} kind={} phase={} reason={} leg_ticks={}",
                job.keeper,job.animal,job.token,job.shop,job.kind,job.phase,reason,job.leg);
        return true;
    }
    private static void phase(Job job, Phase next) { job.phase = next; job.leg = 0; job.timer = 0; job.stall = 0; job.best = 1e9; }
    private static YukkuriEntity animal(ServerLevel level, Job job) {
        return job.animal != null && level.getEntity(job.animal) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private static boolean stuck(Job job, double distSqr) {
        if (distSqr < job.best - .5) { job.best = distSqr; job.stall = 0; } else job.stall++;
        return job.stall > STALL;
    }

    /** One tick of a job; true when it is over. */
    private static boolean step(ServerLevel level, Job job) {
        if (JOBS.get(job.keeper) != job) return true; // cleared by a server restart
        if (!(level.getEntity(job.keeper) instanceof HumanNpc keeper) || !keeper.isAlive()) return lost(level, job);
        if (job.kind == Kind.BUY && job.token == null) {
            var a = animal(level, job);
            var reason = ShopTrade.unavailable(level, job.shop, a, job.buyer, job.price, job.pen);
            if (reason != null) return cancelPurchase(level, keeper, job, a, reason);
        }
        if (job.kind != Kind.BUY && !ShopPerformance.renew(level, job.reservation)) {
            // A live basket stays in the keeper's hand; recover will select a new free pen.
            var a = animal(level, job);
            if (a != null) { a.pet().unlock("sale"); a.pet().unlock("courier"); }
            return endJob(level, job, "reservation_lost");
        }
        if (keeper.hurtTime > 0) {
            keeper.closeServiceHatch();
            if (job.token == null && job.phase != Phase.FETCH) phase(job, Phase.FETCH);
            return false;
        }
        if (job.kind == Kind.SOLD && job.token == null && !ShopTrade.canSell(level, job.shop, animal(level, job), job.buyer))
            return fallback(level, keeper, job);
        if (++job.leg > LEG) return fallback(level, keeper, job);
        return switch (job.phase) {
            case FETCH -> fetch(level, keeper, job);
            case BASKET -> basket(level, keeper, job);
            case CARRY -> walk(level, keeper, job);
        };
    }
    private static boolean fetch(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job);
        if (a == null) { // gone: nothing to carry
            return endJob(level, job, "target_unavailable");
        }
        keeper.getLookControl().setLookAt(a, 30, 30);
        // A keeper calls the animal to the inside cell with the door shut; the wait is a leg of its own.
        var hatch = ServiceHatch.of(a);
        if (hatch == null || !keeper.servingAt(hatch) || !hatch.attended(keeper)) keeper.closeServiceHatch();
        if (hatch == null ? keeper.distanceToSqr(a) > 6.25 || !keeper.hasLineOfSight(a) : !hatch.attended(keeper)) {
            var target = hatch == null ? a.position() : hatch.staffStand(level);
            if (job.leg % 10 == 1) {
                if (hatch == null) keeper.getNavigation().moveTo(target.x, target.y, target.z, .95); else hatch.approach(keeper, .95);
            }
            return stuck(job, keeper.distanceToSqr(target)) ? fallback(level, keeper, job) : false;
        }
        if (a.life().isSleeping()) a.life().wakeForService();
        if (hatch == null) keeper.getNavigation().stop(); // at a door his path runs on to the middle of the stand cell
        else {
            boolean alreadyCalling = keeper.servingAt(hatch);
            if (!keeper.requestService(hatch)) return false;
            if (!alreadyCalling) job.leg = 0;
            // The existing sale or errand hold directs the answer while the animal's own goals are paused.
            if (a.pet().busy() && !(a.pet().director() instanceof ServiceHatch.Answer answer && answer.hatch().equals(hatch)))
                a.pet().direct(new ServiceHatch.Answer(hatch));
            if (!hatch.ready(keeper, a)) return false;
            hatch.set(level, keeper, true);
            if (!hatch.serves(keeper, a)) return false;
        }
        GameplayLog.event(keeper, "courier", "action=reach kind={} target={} via={} staff_at={} animal_at={}", job.kind, job.animal,
                hatch == null ? "floor" : "hatch", GameplayLog.at(keeper.position()), GameplayLog.at(a.position()));
        keeper.swing(InteractionHand.MAIN_HAND);
        a.playAction("basket_in"); a.speak("basket_enter"); phase(job, Phase.BASKET);
        return false;
    }
    /** In hand's reach: through its open service door when the animal is behind one, else within 2.5 blocks in plain sight. */
    private static boolean inReach(HumanNpc keeper, YukkuriEntity a) {
        var hatch = ServiceHatch.of(a);
        return hatch == null ? keeper.distanceToSqr(a) <= 6.25 && keeper.hasLineOfSight(a) : hatch.serves(keeper, a);
    }
    private static void resumeFetch(HumanNpc keeper, Job job) {
        keeper.closeServiceHatch();
        phase(job, Phase.FETCH);
    }
    private static boolean cancelPurchase(ServerLevel level, HumanNpc keeper, Job job, YukkuriEntity animal, String reason) {
        ShopTrade.cancel(level, job.shop, animal, job.buyer, job.price, reason);
        if (keeper != null) {
            emptyHand(keeper);
            keeper.getNavigation().stop();
        }
        return endJob(level, job, reason);
    }
    private static void emptyHand(HumanNpc keeper) {
        if (keeper.getMainHandItem().is(ModItems.CARRIER.get()) && CarrierItem.token(keeper.getMainHandItem()) == null) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
    private static boolean basket(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job); if (a == null) return endJob(level, job, "target_unavailable");
        if (!inReach(keeper, a)) {
            resumeFetch(keeper, job); return false;
        }
        keeper.getLookControl().setLookAt(a, 30, 30); keeper.getNavigation().stop();
        return ++job.timer < BASKET_TICKS ? false : pickUp(level, keeper, job, a);
    }
    /** The animal goes into the basket the keeper holds. */
    private static boolean pickUp(ServerLevel level, HumanNpc keeper, Job job, YukkuriEntity a) {
        if (job.kind == Kind.BUY) {
            UUID token = ShopTrade.capture(level, keeper, job.shop, a, job.buyer, job.price, job.pen);
            if (token == null) { emptyHand(keeper); return endJob(level, job, "capture_refused"); }
            job.token = token;
            keeper.closeServiceHatch(); // the basket is out through the doorway
            phase(job, Phase.CARRY); return false;
        }
        UUID token = ShopTrade.captureBuyback(level, keeper, job.shop, a, job.buyer, job.price, job.pen);
        if (token == null) return fallback(level, keeper, job);
        job.token = token;
        keeper.closeServiceHatch();
        phase(job, Phase.CARRY); return false;
    }
    private static boolean walk(ServerLevel level, HumanNpc keeper, Job job) {
        if (job.token == null) return endJob(level, job, "no_token");
        if (job.kind == Kind.BUY) {
            if (keeper.blockPosition().equals(job.pen) && keeper.onGround()) return release(level, keeper, job);
            if (job.leg % 10 == 1) {
                var nav = keeper.getNavigation();
                nav.moveTo(nav.createPath(job.pen, 0), .95);
            }
            return stuck(job, keeper.distanceToSqr(Vec3.atBottomCenterOf(job.pen))) ? fallback(level, keeper, job) : false;
        }
        // A pen behind a service door is stocked through it: to the door, open it, set the animal down just inside.
        var hatch = ServiceHatch.around(level, job.pen);
        var target = hatch != null ? hatch.staffStand(level) : Vec3.atBottomCenterOf(job.pen);
        double d = keeper.distanceToSqr(target);
        boolean near = d <= 2.25
                && level.clip(new ClipContext(keeper.getEyePosition(), target.add(0, 1.2, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, keeper)).getType() == HitResult.Type.MISS;
        if (hatch != null ? hatch.attended(keeper) : near) {
            keeper.getNavigation().stop();
            if (hatch != null) {
                if (!keeper.requestService(hatch)) return false;
                if (!hatch.open(level)) {
                    hatch.set(level, keeper, true);
                    var inside = hatch.animalStand();
                    keeper.getLookControl().setLookAt(inside.x, inside.y, inside.z, 30, 30);
                    keeper.swing(InteractionHand.MAIN_HAND);
                    job.timer = 0;
                    return false;
                }
                if (++job.timer < BASKET_TICKS) return false;
            }
            return release(level, keeper, job);
        }
        if (job.leg % 10 == 1) {
            if (hatch == null) keeper.getNavigation().moveTo(target.x, target.y, target.z, .95); else hatch.approach(keeper, .95);
        }
        return stuck(job, d) ? fallback(level, keeper, job) : false;
    }

    /** A failed walk ends at the actual staff position with the still-loaded basket. */
    private static boolean fallback(ServerLevel level, HumanNpc keeper, Job job) {
        GameplayLog.limited(keeper,"courier","fallback",200,"action=fallback target={} token={} phase={} reason={} leg_ticks={} stall_ticks={}",
                job.animal,job.token,job.phase,job.leg>LEG?"leg_timeout":job.stall>STALL?"stalled":"capture_failed",job.leg,job.stall);
        if (job.token != null && job.kind == Kind.BUY) return release(level, keeper, job);
        if (job.token != null) return leaveBasket(level, keeper, job, "staff_cannot_reach_pen");
        if (job.kind == Kind.BUY) return cancelPurchase(level, keeper, job, animal(level, job), "staff_cannot_reach_stock");
        var a = animal(level, job);
        if (a != null) { a.pet().unlock("sale"); a.pet().unlock("courier"); }
        emptyHand(keeper);
        ShopTrade.buybackCancelled(level, job.buyer);
        return endJob(level, job, "fetch_ended");
    }

    private static boolean lost(ServerLevel level, Job job) {
        if (job.kind == Kind.BUY && job.token == null)
            return cancelPurchase(level, null, job, animal(level, job), "staff_unavailable");
        var a = animal(level, job);
        if (a != null) { a.pet().unlock("sale"); a.pet().unlock("courier"); }
        return endJob(level, job, "staff_unavailable");
    }

    private static boolean leaveBasket(ServerLevel level, HumanNpc keeper, Job job, String reason) {
        keeper.closeServiceHatch();
        var held = keeper.getMainHandItem();
        if (!job.token.equals(CarrierItem.token(held))) return endJob(level, job, "basket_left_staff");
        // An unreachable pen has already been tried. Leave a normal pickable basket at the staff member's feet.
        if (ShopTrade.drop(level, null, keeper.position(), held.copy()) == null)
            return endJob(level, job, "basket_retained");
        keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(keeper, "courier", "action=put_down result=basket_on_ground token={} reason={}", job.token, reason);
        return endJob(level, job, reason);
    }

    /** Release only at the reached pen; a blocked set-down leaves the loaded item with custody intact. */
    private static boolean release(ServerLevel level, HumanNpc keeper, Job job) {
        if (job.kind == Kind.BUY) {
            var basket = keeper.getMainHandItem().copy();
            if (!job.token.equals(CarrierItem.token(basket))) return endJob(level, job, "basket_left_staff");
            keeper.getNavigation().stop();
            if (!ShopTrade.delivered(level, keeper, job.buyer, job.animal, job.token, job.price, basket, job.pen))
                return endJob(level, job, "buyer_basket_retained");
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); keeper.swing(InteractionHand.MAIN_HAND);
            return endJob(level, job, "delivered");
        }
        var custody = YukkuriWorldData.get(level);
        if (!custody.hasToken(job.token)) { // already released elsewhere: never invent a second animal
            if (keeper.getMainHandItem().is(ModItems.CARRIER.get())) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return endJob(level, job, "token_missing");
        }

        var hatch = ServiceHatch.around(level, job.pen);
        if (hatch != null && !(keeper.servingAt(hatch) && hatch.open(level) && hatch.attended(keeper)))
            return leaveBasket(level, keeper, job, "hatch_unavailable");
        var at = hatch != null ? hatch.animalStand() : Vec3.atBottomCenterOf(job.pen.above());
        var basket = keeper.getMainHandItem();
        if (!job.token.equals(CarrierItem.token(basket))) return endJob(level, job, "basket_left_staff");
        var y = custody.release(job.token, level, at, keeper.getYRot());
        if (y == null) return leaveBasket(level, keeper, job, "set_down_blocked");
        if (hatch != null) {
            GameplayLog.event(keeper, "courier", "action=restock target={} token={} via=hatch door={}",
                    y.getUUID(), job.token, hatch.door().toShortString());
        }
        y.pet().unlock("sale");
        y.pet().unlock("courier");

        if (hatch != null) y.life().assignHome(GlobalPos.of(level.dimension(), job.pen));
        keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        keeper.swing(InteractionHand.MAIN_HAND);
        if (y.life().sedated()) y.life().shortenDrugSleep(WAKE_TICKS); // wakes in the pen, where the normal waking action plays
        else {
            y.playAction("basket_out");
            y.speak("basket_exit");
        }
        int greeted = 0;
        for (var mate : level.getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(4), o -> o != y && o.isAlive() && !o.pet().busy() && !o.isPanicking() && !o.isPassenger()
                && !o.life().sedated() && !o.life().isSleeping() && (Custody.view(o).role() == Custody.Role.STOCK))) {
            if (greeted++ >= 2) break;
            mate.playAction("greet"); mate.speak("peer");
        }
        GameplayLog.event(y,"courier","action=settle keeper={} token={} shop={}",job.keeper,job.token,job.shop);
        return endJob(level, job, "released");
    }
    private ShopCourier() {}
}
