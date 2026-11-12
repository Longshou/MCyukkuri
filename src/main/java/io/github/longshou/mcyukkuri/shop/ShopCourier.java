package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** A keeper carries animals to their pens in a transport basket instead of teleporting them: sold animals, factory arrivals and nursery graduates (sprayed to sleep first).
 *  A bought animal goes the other way: the keeper walks to it with an empty basket (reaching over a display booth's wall), it climbs in, and the
 *  keeper carries it to the shopkeeper at the counter, where the buyer receives the basket.
 *  One transient job per keeper; the basket in the keeper's main hand is the saved half of the custody, so a restart resumes through {@link #recover}.
 *  Every leg has a timeout that falls back to a direct (POOF) placement, and a token is only ever consumed by a successful release. */
public final class ShopCourier {
    private enum Kind { SOLD, GRADUATE, CARRY, RECALL, BUY }
    private enum Phase { FETCH, SPRAY, WAIT_SLEEP, BASKET, CARRY }
    private static final class Job {
        final UUID keeper, shop, animal; final Kind kind; final BlockPos pen; UUID token, factory, buyer; Phase phase; int price;
        final ShopPerformance.Reservation reservation;
        int leg, timer, fail, stall, relFail, gone; double best = 1e9; Vec3 last = Vec3.ZERO;
        Job(UUID keeper, UUID shop, Kind kind, UUID animal, UUID token, BlockPos pen, ShopPerformance.Reservation reservation) { this.keeper = keeper;
                this.shop = shop; this.kind = kind; this.animal = animal; this.token = token; this.pen = pen; this.reservation = reservation; }
    }
    /** Keeper UUID to its running job. */
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    /** Tokens a job is carrying right now (the deliveries list must not hand them out a second time). */
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    /** Ticks a single leg may take, stall ticks without getting closer, and attempts to spray before giving up. */
    private static final int LEG = 400, STALL = 120, SPRAY_TRIES = 8, BASKET_TICKS = 16, WAKE_TICKS = 100;
    /** A keeper who has walked as near as the floor lets him reaches over a pen wall this far (blocks, horizontally; a player's reach is 4.5). */
    private static final double REACH_OVER = 4;

    public static void started(ServerStartingEvent event) { JOBS.clear(); IN_FLIGHT.clear(); }
    public static void forget(ServerLevel level, Set<UUID> ids, Set<UUID> tokens) {
        for (var job : List.copyOf(JOBS.values())) if (ids.contains(job.keeper) || job.shop != null && ids.contains(job.shop)
                || job.animal != null && ids.contains(job.animal) || job.token != null && tokens.contains(job.token)) {
            if (job.animal != null) for (var dimension : level.getServer().getAllLevels())
                if (dimension.getEntity(job.animal) instanceof YukkuriEntity animal) { animal.pet().unlock("courier"); animal.pet().unlock("sale"); }
            endJob(level, job, "field_clear");
        }
    }
    public static void stopped(ServerStoppedEvent event) { JOBS.clear(); IN_FLIGHT.clear(); }

    /** True while the keeper runs a job: its routine and wandering goals stand aside. */
    public static boolean active(HumanNpc keeper) { return JOBS.containsKey(keeper.getUUID()); }
    public static boolean busy(HumanNpc keeper) { return JOBS.containsKey(keeper.getUUID()) || ShopDate.holding(keeper.getUUID()) || keeper.getMainHandItem().is(ModItems.CARRIER.get()); }
    public static boolean inFlight(UUID token) { return IN_FLIGHT.contains(token); }
    public static boolean hasKeeper(ServerLevel level, HumanNpc shop) {
        return !level.getEntitiesOfClass(HumanNpc.class, shop.getBoundingBox().inflate(48), n -> n.isAlive() && n.job() == HumanNpc.Job.KEEPER).isEmpty();
    }
    public static HumanNpc idleKeeper(ServerLevel level, HumanNpc shop) {
        return level.getEntitiesOfClass(HumanNpc.class, shop.getBoundingBox().inflate(48), n -> n.isAlive() && n.job() == HumanNpc.Job.KEEPER && !busy(n) && !n.isTrading())
                .stream().min(Comparator.comparingDouble(shop::distanceToSqr)).orElse(null);
    }

    /** A factory delivery (already a token): the keeper takes the basket and walks it to the pen. */
    public static boolean carry(ServerLevel level, HumanNpc keeper, HumanNpc shop, UUID token, BlockPos pen) {
        if (busy(keeper) || IN_FLIGHT.contains(token) || !YukkuriWorldData.get(level).hasToken(token)) return false;
        var reservation = ShopPerformance.reserve(level, pen);
        if (reservation == null) return false;
        keeper.setItemInHand(InteractionHand.MAIN_HAND, carrierStack(level, token));
        start(level, keeper, shop, Kind.CARRY, Phase.CARRY, null, token, pen, reservation); return true;
    }
    /** A nursery graduate: the keeper walks to it, sprays it to sleep, then carries it. The animal is locked meanwhile. */
    public static boolean graduate(ServerLevel level, HumanNpc keeper, HumanNpc shop, YukkuriEntity young, BlockPos pen) {
        if (busy(keeper)) return false;
        var reservation = ShopPerformance.reserve(level, pen);
        if (reservation == null) return false;
        if (!young.pet().lock("courier", shop.getUUID())) { ShopPerformance.release(reservation); return false; }
        start(level, keeper, shop, Kind.GRADUATE, Phase.FETCH, young.getUUID(), null, pen, reservation); return true;
    }
    /** Unsold stock on its way to a factory: the keeper sprays it to sleep, carries it to the counter and hands the basket over; the factory collects it later. */
    public static boolean recall(ServerLevel level, HumanNpc keeper, HumanNpc shop, YukkuriEntity stale, UUID factory) {
        if (busy(keeper)) return false;
        var reservation = ShopPerformance.reserve(level, shop.blockPosition());
        if (reservation == null) return false;
        if (!stale.pet().lock("courier", shop.getUUID())) { ShopPerformance.release(reservation); return false; }
        start(level, keeper, shop, Kind.RECALL, Phase.FETCH, stale.getUUID(), null, shop.blockPosition(), reservation);
        JOBS.get(keeper.getUUID()).factory = factory; return true;
    }
    /** A sold animal after its farewell: 1 a keeper took the job (it reserved the pen from now on), 0 all keepers are busy, try again, -1 no keeper or animal: use the direct move. */
    public static int dispatchSale(ServerLevel level, HumanNpc shop, UUID animal, BlockPos pen, ShopPerformance.Reservation reservation) {
        if (!(level.getEntity(animal) instanceof YukkuriEntity a) || !a.isAlive() || !hasKeeper(level, shop)) return -1;
        var keeper = idleKeeper(level, shop);
        if (keeper == null) return 0;
        if (!ShopPerformance.owns(level, pen, reservation)) return -1;
        var transferred = ShopPerformance.handoff(level, reservation);
        if (transferred == null) return -1;
        start(level, keeper, shop, Kind.SOLD, Phase.FETCH, animal, null, pen, transferred); return 1;
    }
    /** A bought animal after its joy: 1 a keeper took the job (he walks to it with an empty basket, the counter is reserved for the handover),
     *  0 all keepers are busy, try again, -1 the shop has no keeper: the clerk hands over at the counter himself. The animal stays locked for the sale. */
    public static int dispatchPurchase(ServerLevel level, HumanNpc shop, YukkuriEntity animal, UUID buyer, int price) {
        if (!hasKeeper(level, shop)) return -1;
        var keeper = idleKeeper(level, shop);
        if (keeper == null) return 0;
        var reservation = ShopPerformance.reserve(level, shop.blockPosition());
        if (reservation == null) return 0;
        keeper.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
        start(level, keeper, shop, Kind.BUY, Phase.FETCH, animal.getUUID(), null, shop.blockPosition(), reservation);
        var job = JOBS.get(keeper.getUUID()); job.buyer = buyer; job.price = price;
        return 1;
    }

    /** A keeper that holds a loaded basket without a job (the server restarted mid-delivery) finishes the delivery into a free pen, or sets the animal down at its feet. */
    public static void recover(HumanNpc keeper, ServerLevel level) {
        if (JOBS.containsKey(keeper.getUUID()) || ShopDate.holding(keeper.getUUID())) return; // a date scene's empty display basket is not ours to clear
        ItemStack hand = keeper.getMainHandItem();
        if (!hand.is(ModItems.CARRIER.get())) return;
        UUID token = CarrierItem.token(hand);
        var custody = YukkuriWorldData.get(level);
        if (token == null || !custody.hasToken(token)) {
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            GameplayLog.event(keeper,"courier","action=recover result=cleared token={} reason={}",token,token==null?"empty_carrier":"token_missing");
            return;
        }
        if (IN_FLIGHT.contains(token)) return;
        // A bought animal's basket (its custody is already the buyer's) goes on to that buyer, never back into a pen.
        var bought = Custody.fromData(custody.captiveSnapshot(token).getCompound("NeoForgeData").getCompound("HumanCommerce"));
        if (bought.ownerKind() == Custody.OwnerKind.PLAYER && bought.owner() != null) {
            var basket = hand.copy();
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ShopMenu.deliver(level, bought.owner(), keeper.position(), basket);
            GameplayLog.event(keeper,"courier","action=recover result=delivered_to_buyer token={} buyer={}",token,bought.owner());
            return;
        }
        var shop = level.getEntitiesOfClass(HumanNpc.class, keeper.getBoundingBox().inflate(48), n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER)
                .stream().min(Comparator.comparingDouble(keeper::distanceToSqr)).orElse(null);
        BlockPos pen = shop == null ? null : ShopPens.freePen(level, shop, null, false);
        if (pen != null) {
            var reservation = ShopPerformance.reserve(level, pen);
            if (reservation != null) { start(level, keeper, shop, Kind.CARRY, Phase.CARRY, null, token, pen, reservation); return; }
        }
        var y = place(level, token, keeper.position(), keeper.getYRot(), null);
        if (y == null) return; // retried on the next keeper tick
        keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        y.pet().unlock("sale"); y.pet().unlock("courier");
        ShopDeliveries.completed(level, token, y, shop);
        if (y.life().sedated()) y.life().shortenDrugSleep(WAKE_TICKS);
        GameplayLog.event(keeper,"courier","action=recover result=released target={} token={} shop={}",y.getUUID(),token,shop==null?null:shop.getUUID());
    }

    private static void start(ServerLevel level, HumanNpc keeper, HumanNpc shop, Kind kind, Phase phase, UUID animal, UUID token, BlockPos pen, ShopPerformance.Reservation reservation) {
        var job = new Job(keeper.getUUID(), shop == null ? null : shop.getUUID(), kind, animal, token, pen, reservation);
        job.phase = phase; job.last = keeper.position();
        JOBS.put(job.keeper, job);
        if (token != null) IN_FLIGHT.add(token);
        GameplayLog.event(keeper,"courier","action=start kind={} phase={} target={} token={} shop={} destination={}",kind,phase,animal,token,job.shop,pen.toShortString());
        var participants = new HashSet<UUID>();
        participants.add(job.keeper);
        if (job.shop != null) participants.add(job.shop);
        if (job.animal != null) participants.add(job.animal);
        if (job.token != null) participants.add(YukkuriWorldData.get(level).captiveSnapshot(job.token).getUUID("UUID"));
        ShopPerformance.schedule(level, participants, age -> step(level, job));
    }
    private static boolean endJob(ServerLevel level, Job job, String reason) {
        JOBS.remove(job.keeper, job); ShopPerformance.release(job.reservation);
        if (job.token != null) IN_FLIGHT.remove(job.token);
        GameplayLog.world(level,"courier","action=end keeper={} target={} token={} shop={} kind={} phase={} reason={} leg_ticks={} release_failures={}",
                job.keeper,job.animal,job.token,job.shop,job.kind,job.phase,reason,job.leg,job.relFail);
        return true;
    }
    private static void phase(Job job, Phase next) { job.phase = next; job.leg = 0; job.timer = 0; job.stall = 0; job.best = 1e9; }
    private static ItemStack carrierStack(ServerLevel level, UUID token) {
        ItemStack stack = new ItemStack(ModItems.CARRIER.get());
        var snapshot = YukkuriWorldData.get(level).captiveSnapshot(token);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { tag.putUUID("CaptiveToken", token); if (snapshot.hasUUID("UUID")) tag.putUUID("EntityUUID", snapshot.getUUID("UUID")); });
        return stack;
    }
    private static YukkuriEntity animal(ServerLevel level, Job job) {
        return job.animal != null && level.getEntity(job.animal) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    /** Release at the base position or a block beside it. */
    private static YukkuriEntity place(ServerLevel level, UUID token, Vec3 base, float yaw, ShopPerformance.Reservation reservation) {
        var custody = YukkuriWorldData.get(level);
        for (int[] o : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            var at = base.add(o[0], 0, o[1]);
            var cell = BlockPos.containing(at);
            if (ShopPerformance.reserved(level, cell) && !ShopPerformance.owns(level, cell, reservation)
                    || ShopPerformance.reserved(level, cell.below()) && !ShopPerformance.owns(level, cell.below(), reservation)) continue;
            var y = custody.release(token, level, at, yaw);
            if (y != null) return y;
        }
        return null;
    }
    private static boolean stuck(Job job, double distSqr) {
        if (distSqr < job.best - .5) { job.best = distSqr; job.stall = 0; } else job.stall++;
        return job.stall > STALL;
    }

    /** One tick of a job; true when it is over. */
    private static boolean step(ServerLevel level, Job job) {
        if (JOBS.get(job.keeper) != job) return true; // cleared by a server restart
        boolean reserved = ShopPerformance.renew(level, job.reservation);
        if (!(level.getEntity(job.keeper) instanceof HumanNpc keeper) || !keeper.isAlive()) return lost(level, job);
        job.gone = 0; job.last = keeper.position();
        if (!reserved) {
            // A live basket stays in the keeper's hand; recover will select a new free pen (or, bought, deliver it to the buyer).
            var a = animal(level, job);
            if (a != null) { a.pet().unlock("sale"); a.pet().unlock("courier"); }
            if (job.kind == Kind.BUY && job.token == null) { ShopTrade.refund(level, keeper, job.buyer, job.animal, job.price, keeper.position(), "reservation_lost"); emptyHand(keeper); }
            return endJob(level, job, "reservation_lost");
        }
        if (++job.leg > LEG) return fallback(level, keeper, job);
        return switch (job.phase) {
            case FETCH -> fetch(level, keeper, job);
            case SPRAY -> spray(level, keeper, job);
            case WAIT_SLEEP -> waitSleep(level, keeper, job);
            case BASKET -> basket(level, keeper, job);
            case CARRY -> walk(level, keeper, job);
        };
    }
    private static boolean fetch(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job);
        if (a == null) { // gone: nothing to carry
            if (job.kind == Kind.BUY) { ShopTrade.refund(level, keeper, job.buyer, job.animal, job.price, keeper.position(), "target_unavailable"); emptyHand(keeper); }
            return endJob(level, job, "target_unavailable");
        }
        keeper.getLookControl().setLookAt(a, 30, 30);
        double d = keeper.distanceToSqr(a);
        if (d > 6.25 && !(job.kind == Kind.BUY && reachesOver(keeper, a))) {
            if (job.leg % 10 == 1) keeper.getNavigation().moveTo(a, .95);
            return stuck(job, d) ? fallback(level, keeper, job) : false;
        }
        keeper.getNavigation().stop();
        if (job.kind == Kind.SOLD || job.kind == Kind.BUY) {
            keeper.swing(InteractionHand.MAIN_HAND); a.playAction("basket_in"); a.speak("basket_enter"); phase(job, Phase.BASKET);
            // A bought one leaves its shelf: the other stock that see it go answer on their own goals.
            if (job.kind == Kind.BUY && level.getEntity(job.shop) instanceof HumanNpc shop) ShopWindow.departure(level, a, shop, keeper, job.buyer);
        } else phase(job, Phase.SPRAY);
        return false;
    }
    /** The keeper has walked as near as the floor lets him (his path is done) and the animal is within an arm over a pen wall. */
    private static boolean reachesOver(HumanNpc keeper, YukkuriEntity a) {
        double dx = keeper.getX() - a.getX(), dz = keeper.getZ() - a.getZ();
        return keeper.getNavigation().isDone() && dx * dx + dz * dz <= REACH_OVER * REACH_OVER && Math.abs(keeper.getY() - a.getY()) <= 2.5;
    }
    private static void emptyHand(HumanNpc keeper) {
        if (keeper.getMainHandItem().is(ModItems.CARRIER.get()) && CarrierItem.token(keeper.getMainHandItem()) == null) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
    private static boolean spray(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job); if (a == null) return endJob(level, job, "target_unavailable");
        keeper.getLookControl().setLookAt(a, 30, 30);
        if (keeper.distanceToSqr(a) > 12.25) { phase(job, Phase.FETCH); return false; } // it wandered off
        keeper.getNavigation().stop();
        if (job.timer++ % 10 != 0) return false;
        if (a.life().sedated() || a.life().isSleeping()) { phase(job, Phase.WAIT_SLEEP); return false; }
        keeper.swing(InteractionHand.MAIN_HAND);
        level.sendParticles(ParticleTypes.CLOUD, keeper.getX(), keeper.getEyeY() - .3, keeper.getZ(), 6, .2, .1, .2, .01);
        if (a.life().spray()) phase(job, Phase.WAIT_SLEEP);
        else if (++job.fail >= SPRAY_TRIES) return fallback(level, keeper, job);
        return false;
    }
    private static boolean waitSleep(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job); if (a == null) return endJob(level, job, "target_unavailable");
        keeper.getLookControl().setLookAt(a, 30, 30); keeper.getNavigation().stop();
        if (a.life().sedated() ? a.life().sprayDelay() == 0 : a.life().isSleeping()) return pickUp(level, keeper, job, a);
        return false;
    }
    private static boolean basket(ServerLevel level, HumanNpc keeper, Job job) {
        var a = animal(level, job); if (a == null) return endJob(level, job, "target_unavailable");
        keeper.getLookControl().setLookAt(a, 30, 30); keeper.getNavigation().stop();
        return ++job.timer < BASKET_TICKS ? false : pickUp(level, keeper, job, a);
    }
    /** The animal goes into the basket the keeper holds. */
    private static boolean pickUp(ServerLevel level, HumanNpc keeper, Job job, YukkuriEntity a) {
        if (job.kind == Kind.BUY) { // it changes hands as it goes into the basket; a refused capture refunds the buyer
            UUID token = ShopTrade.capture(level, keeper, job.shop, a, job.buyer, job.price);
            if (token == null) { emptyHand(keeper); return endJob(level, job, "capture_refused_refunded"); }
            job.token = token; IN_FLIGHT.add(token);
            keeper.setItemInHand(InteractionHand.MAIN_HAND, HumanCommerce.carrier(a, token));
            phase(job, Phase.CARRY); return false;
        }
        UUID token = YukkuriWorldData.get(level).capture(a);
        if (token == null) return fallback(level, keeper, job);
        job.token = token; IN_FLIGHT.add(token);
        keeper.setItemInHand(InteractionHand.MAIN_HAND, HumanCommerce.carrier(a, token));
        phase(job, Phase.CARRY); return false;
    }
    private static boolean walk(ServerLevel level, HumanNpc keeper, Job job) {
        if (job.token == null) return endJob(level, job, "no_token");
        // A bought animal goes to the shopkeeper wherever he stands now; everything else to its pen.
        var target = job.kind == Kind.BUY && level.getEntity(job.shop) instanceof HumanNpc shop && shop.isAlive() ? shop.position() : Vec3.atBottomCenterOf(job.pen);
        double dx = keeper.getX() - target.x, dz = keeper.getZ() - target.z, d = dx * dx + dz * dz;
        if (d <= 9 && Math.abs(keeper.getY() - target.y) <= 2.5) return release(level, keeper, job, false);
        if (job.leg % 10 == 1) keeper.getNavigation().moveTo(target.x, target.y, target.z, .95);
        return stuck(job, d) ? fallback(level, keeper, job) : false;
    }

    /** A leg ran out of time or the animal could not be handled: put it in the pen the old way (POOF). */
    private static boolean fallback(ServerLevel level, HumanNpc keeper, Job job) {
        GameplayLog.limited(keeper,"courier","fallback",200,"action=fallback target={} token={} phase={} reason={} leg_ticks={} stall_ticks={} spray_failures={}",
                job.animal,job.token,job.phase,job.leg>LEG?"leg_timeout":job.stall>STALL?"stalled":job.fail>=SPRAY_TRIES?"spray_failed":"capture_failed",job.leg,job.stall,job.fail);
        if (job.token != null) return release(level, keeper, job, true);
        if (job.kind == Kind.BUY) { // the keeper could not get there: the old handover, the animal climbs into a basket where it stands
            var a = animal(level, job); emptyHand(keeper);
            if (a == null) { ShopTrade.refund(level, keeper, job.buyer, job.animal, job.price, keeper.position(), "target_unavailable"); return endJob(level, job, "fallback_refunded"); }
            a.playAction("basket_in");
            UUID token = ShopTrade.capture(level, keeper, job.shop, a, job.buyer, job.price);
            if (token != null) ShopTrade.delivered(level, keeper, job.buyer, job.animal, token, job.price, HumanCommerce.carrier(a, token), a.position(), "fallback");
            return endJob(level, job, "fallback_handed_over");
        }
        var a = animal(level, job);
        if (a != null && job.kind == Kind.RECALL) { a.pet().unlock("courier"); a = null; } // the recall simply fails; the animal stays on the shelf and is picked again later
        if (a != null && ShopPerformance.owns(level, job.pen, job.reservation)) {
            a.pet().unlock("sale"); a.pet().unlock("courier");
            level.sendParticles(ParticleTypes.POOF, a.getX(), a.getY() + .3, a.getZ(), 8, .25, .15, .25, .02);
            a.getNavigation().stop(); a.moveTo(job.pen.getX() + .5, job.pen.getY() + .12, job.pen.getZ() + .5, a.getYRot(), 0);
            GameplayLog.event(a,"courier","action=fallback result=placed keeper={} kind={} destination={}",job.keeper,job.kind,job.pen.toShortString());
            level.sendParticles(ParticleTypes.CLOUD, a.getX(), a.getY() + .3, a.getZ(), 8, .25, .15, .25, .02);
        }
        if (keeper != null && keeper.getMainHandItem().is(ModItems.CARRIER.get()) &&
                CarrierItem.token(keeper.getMainHandItem()) == null) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return endJob(level, job, job.kind==Kind.RECALL?"recall_cancelled":"fallback_finished");
    }
    /** The keeper is gone (dead, or its chunk unloaded). Unloaded: the basket stays in its hand and recovery resumes it later. Dead: the animal is set down where the keeper stood. */
    private static boolean lost(ServerLevel level, Job job) {
        boolean unloaded = !level.hasChunkAt(BlockPos.containing(job.last));
        if (unloaded && job.kind != Kind.BUY) return endJob(level, job, "keeper_unloaded");
        if (!unloaded && ++job.gone < 3) return false;
        if (job.token == null) {
            var a = animal(level, job); if (a != null) { a.pet().unlock("sale"); a.pet().unlock("courier"); }
            // A purchase that never reached the basket is refunded; the animal stays on its shelf.
            if (job.kind == Kind.BUY) ShopTrade.refund(level, a, job.buyer, job.animal, job.price, job.last, "keeper_unavailable");
            return endJob(level, job, unloaded ? "keeper_unloaded" : "keeper_unavailable_no_token");
        }
        if (unloaded) return endJob(level, job, "keeper_unloaded"); // the loaded basket stays in his hand; recover hands it to the buyer
        var y = place(level, job.token, job.last, 0, job.reservation);
        if (y == null) return ++job.relFail > 200 && endJob(level, job, "keeper_unavailable_release_exhausted");
        if (job.kind == Kind.BUY) { // already the buyer's: it climbs out where the keeper fell and stays the buyer's companion
            y.pet().unlock("sale");
            GameplayLog.event(y,"shop","action=buy_live result=released_at_keeper buyer={} token={}",job.buyer,job.token);
            return endJob(level, job, "keeper_unavailable_released_to_buyer");
        }
        return settle(level, null, job, y, false, false);
    }
    /** Put the carried animal in its pen. A failed attempt (no room) is retried; after a while it is set down next to the keeper instead. */
    private static boolean release(ServerLevel level, HumanNpc keeper, Job job, boolean direct) {
        if (job.kind == Kind.BUY) { // at the counter (or, stuck on the way, where he stands) the buyer receives the loaded basket
            var basket = keeper.getMainHandItem().copy();
            keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); keeper.swing(InteractionHand.MAIN_HAND);
            ShopTrade.delivered(level, keeper, job.buyer, job.animal, job.token, job.price, basket, keeper.position(), direct ? "fallback" : "keeper");
            return endJob(level, job, "delivered");
        }
        var custody = YukkuriWorldData.get(level);
        if (job.kind == Kind.RECALL && custody.hasToken(job.token) && job.factory != null) { // the basket changes hands at the counter; the token waits for the factory
            ShopDeliveries.get(level).addRecall(new ShopDeliveries.Recall(job.token, job.shop, job.factory, level.getGameTime()));
            if (keeper.getMainHandItem().is(ModItems.CARRIER.get())) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            keeper.swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(keeper,"delivery","action=recall result=queued token={} shop={} factory={}",job.token,job.shop,job.factory);
            return endJob(level, job, "recall_queued");
        }
        if (!custody.hasToken(job.token)) { // already released elsewhere: never invent a second animal
            if (keeper.getMainHandItem().is(ModItems.CARRIER.get())) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return endJob(level, job, "token_missing");
        }
        var y = job.relFail < 30 && ShopPerformance.owns(level, job.pen, job.reservation)
                ? custody.release(job.token, level, new Vec3(job.pen.getX() + .5, job.pen.getY() + 1, job.pen.getZ() + .5), keeper.getYRot()) : null;
        boolean atPen = y != null;
        if (y == null && job.relFail >= 30) y = place(level, job.token, keeper.position(), keeper.getYRot(), job.reservation);
        if (y == null) return ++job.relFail > 200 && endJob(level, job, "release_exhausted_carrier_retained"); // the basket stays in the keeper's hand; recovery tries again later
        return settle(level, keeper, job, y, atPen, direct);
    }
    private static boolean settle(ServerLevel level, HumanNpc keeper, Job job, YukkuriEntity y, boolean atPen, boolean direct) {
        y.pet().unlock("sale"); y.pet().unlock("courier");
        if (atPen) { y.getNavigation().stop(); Motor.relocate(y, new Vec3(job.pen.getX() + .5, job.pen.getY() + .12, job.pen.getZ() + .5), y.getYRot(), "courier_settle"); }
        if (direct) level.sendParticles(ParticleTypes.POOF, y.getX(), y.getY() + .3, y.getZ(), 8, .25, .15, .25, .02);
        if (keeper != null) {
            if (keeper.getMainHandItem().is(ModItems.CARRIER.get())) keeper.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            keeper.swing(InteractionHand.MAIN_HAND);
        }
        var shopEntity = job.shop == null ? null : level.getEntity(job.shop);
        boolean factory = ShopDeliveries.completed(level, job.token, y, shopEntity instanceof HumanNpc shop ? shop : null);
        if (y.life().sedated()) y.life().shortenDrugSleep(WAKE_TICKS); // wakes in the pen, where the normal waking action plays
        else {
            y.playAction("basket_out");
            if (factory) y.showReaction("cry", "basket_exit", 100); else y.speak("basket_exit");
        }
        int greeted = 0;
        for (var mate : level.getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(4), o -> o != y && o.isAlive() && !o.pet().busy() && !o.isPanicking() && !o.isPassenger()
                && !o.life().sedated() && !o.life().isSleeping() && (Custody.view(o).role() == Custody.Role.STOCK))) {
            if (greeted++ >= 2) break;
            mate.playAction("greet"); mate.speak("peer");
        }
        GameplayLog.event(y,"courier","action=settle keeper={} token={} shop={} at_pen={} direct={} factory_delivery={}",job.keeper,job.token,job.shop,atPen,direct,factory);
        return endJob(level, job, "released");
    }
    private ShopCourier() {}
}
