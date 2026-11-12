package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Shop stock at its window (docs/FEATURES.md, 宠物店): a customer it can see - a player or a resident, in the open or behind one glass
 * cell - is met the way this individual's own drives choose. It hurries to the glass and pleads (a bold and friendly one shows off),
 * strikes a proud pose (a silver or gold badge), shies away, or sulks once days on the shelf and pleas nobody answered have worn its
 * hope down. A plea that ends with the customer walking off ends in a letdown and is remembered for a while; a neighbour carried off
 * in its sight is seen off by relation. Nothing is saved: a restart only drops the moment under way. Moving to the glass or away from
 * the customer is a motor Walk, facing is the look control, and the clips and faces are the moment rows of the reported activity.
 */
public final class ShopWindow implements MotorOwner {
    public enum Mode { APPEAL, SHOW_OFF, PROUD, SHY, SULK }
    public enum Witness { FAREWELL, ENVY, ME_TOO, WATCH }
    private enum Phase { NOTICE, APPROACH, PERFORM, AFTER, WITNESS, HATCH }
    private enum After { LETDOWN, HMPH }

    /** A customer is noticed within RANGE blocks horizontally (and 4 vertically); it is gone beyond KEEP or out of sight for GONE_TICKS. */
    private static final double RANGE = 8, KEEP = 9, HEIGHT = 4;
    /** A glass stand nearer than this is "already there": the body pleads where it stands. */
    private static final double STAND_NEAR = .6;
    private static final int SCAN_TICKS = 10, NOTICE_TICKS = 20, GONE_TICKS = 40, APPROACH_TICKS = 120, RESTAND_TICKS = 40, RESTANDS = 6;
    /** A plea shorter than this was not yet a plea: the customer leaving then is no rejection. */
    private static final int MIN_PLEA = 60;
    /** Moment lengths of the after-beats (droop 4 s, ignore 1.6 s) so a loop never restarts on screen. */
    private static final int LETDOWN_TICKS = 80, HMPH_TICKS = 32;
    /** A neighbour is watched during the fetch and for a short tail after it climbs into the basket. */
    private static final int WITNESS_TICKS = 500, REACTION_TICKS = 160, WITNESS_TAIL = 70;
    /** Sale sightings are within this many blocks. */
    private static final double SALE_RANGE = 10;
    /** Staff at its pen's open service door is answered this long at most; then a short rest before it answers the door again. */
    private static final int HATCH_TICKS = 600, HATCH_REST = 40;
    /** Rejections remembered at most, and the time after which one is forgotten (project design). */
    private static final int REJECTIONS = 4, FORGIVE_TICKS = 2400;
    /** Drive weights (project design, docs/FEATURES.md): each mode a continuous score, the largest wins after a jitter of up to 6. */
    private static final double JITTER = 6, CRUEL_FEAR = 40;

    private final YukkuriEntity entity;
    private Phase phase;
    private Mode mode;
    private After after;
    private Witness witness;
    private LivingEntity customer, candidate;
    private Entity watched;
    private YukkuriEntity departing, leaving;
    /** Its pen's service door, open with staff at it (the HATCH answer). */
    private ServiceHatch hatch, calling;
    /** The last neighbour whose sale it watched: one sale is answered once. */
    private UUID witnessed;
    private BlockPos glass;
    private Action action;
    private long until, sessionStart, performStart, lastSeen, lastRestand, nextScan, cooldownUntil, forgiveAt, departedAt, hatchRestUntil;
    private int restands, rejections;

    public ShopWindow(YukkuriEntity entity) { this.entity = entity; }

    @Override public String ownerId() { return Behaviors.SHOP_WINDOW; }
    @Override public boolean active() { return phase != null; }

    /** The behaviour shown while active; the moment rows of these ids carry the clips, faces and symbols. */
    public String activity() {
        return switch (phase) {
            case NOTICE -> Behaviors.WINDOW_NOTICE;
            case APPROACH -> mode == Mode.SHY ? Behaviors.WINDOW_SHY : Behaviors.WINDOW_APPROACH;
            case PERFORM -> switch (mode) {
                case APPEAL -> Behaviors.WINDOW_APPEAL;
                case SHOW_OFF -> Behaviors.WINDOW_SHOW_OFF;
                case PROUD -> Behaviors.WINDOW_PROUD;
                case SHY -> Behaviors.WINDOW_SHY;
                case SULK -> Behaviors.WINDOW_SULK;
            };
            case AFTER -> after == After.LETDOWN ? Behaviors.WINDOW_LETDOWN : Behaviors.WINDOW_HMPH;
            case HATCH -> action != null ? Behaviors.WINDOW_APPROACH : Behaviors.WINDOW_HATCH;
            // Envy and "me too" burst out while the neighbour is being fetched, a farewell when it climbs into the basket; otherwise it watches.
            case WITNESS -> witness == Witness.FAREWELL && departedAt >= 0 ? Behaviors.WINDOW_FAREWELL
                    : entity.level().getGameTime() - sessionStart >= REACTION_TICKS ? Behaviors.WINDOW_WATCH
                    : witness == Witness.ENVY ? Behaviors.WINDOW_ENVY : witness == Witness.ME_TOO ? Behaviors.WINDOW_APPEAL : Behaviors.WINDOW_WATCH;
        };
    }

    /** The current answer to a customer, or null when it is not at its window (the shop menu shows it). */
    public Mode mode() { return phase == Phase.NOTICE || phase == Phase.APPROACH || phase == Phase.PERFORM ? mode : null; }
    public boolean letDown() { return phase == Phase.AFTER && after == After.LETDOWN; }

    // ---- the goal ---------------------------------------------------------------------------------------------------

    public boolean canUse() {
        long now = entity.level().getGameTime();
        forgive(now);
        if (now < nextScan) return false;
        nextScan = now + SCAN_TICKS;
        if (!stock()) return false;
        // Staff opening its pen's service door is answered first: that is where it is fed, treated and handed over.
        calling = calledToHatch();
        if (calling != null) return true;
        // A neighbour being sold outweighs the rest it takes between customers.
        leaving = findSale();
        if (leaving != null) return ready();
        if (now < cooldownUntil) return false;
        var seen = findCustomer();
        if (seen == null) return false;
        String busy = busy();
        if (busy != null) {
            GameplayLog.limited(entity, "shop", "window_busy." + busy, 600, "window customer unanswered customer={} reason={}", seen.getUUID(), busy);
            return false;
        }
        candidate = seen;
        return true;
    }

    public boolean canContinue() { return phase != null && stock() && (phase == Phase.HATCH ? answersHatch() : ready()); }

    public void start() {
        long now = entity.level().getGameTime();
        if (calling != null) beginHatch(calling, now);
        else if (leaving != null) beginWitness(leaving, now);
        else if (candidate != null) beginSession(candidate, now);
        candidate = null; leaving = null; calling = null;
    }

    public void tick() {
        long now = entity.level().getGameTime();
        boolean scan = now >= nextScan;
        if (scan) nextScan = now + SCAN_TICKS;
        if (scan && phase != Phase.HATCH) {
            var door = calledToHatch();
            if (door != null) { // staff at its door outweighs a customer, a neighbour's sale and an after-beat
                if (phase == Phase.NOTICE || phase == Phase.APPROACH || phase == Phase.PERFORM) log("end", "hatch", now);
                cancelAction("hatch");
                beginHatch(door, now);
            }
        }
        if (scan && phase != Phase.WITNESS && phase != Phase.AFTER && phase != Phase.HATCH) {
            var sale = findSale();
            if (sale != null) { // a customer session gives way to a neighbour being sold
                log("end", "witness", now);
                cancelAction("witness");
                beginWitness(sale, now);
            }
        }
        if (phase == null) return;
        switch (phase) {
            case NOTICE -> {
                look(customer);
                if (now >= until) afterNotice(now);
            }
            case APPROACH -> approaching(now);
            case PERFORM -> perform(now, scan);
            case AFTER -> { if (now >= until) finish(now); }
            case WITNESS -> witnessing(now);
            case HATCH -> hatching(now);
        }
    }

    /** Goal stop and every interrupt: drops the moment, keeps what it learned (rejections) and rests a little. */
    public void stop() {
        if (phase == null) return;
        long now = entity.level().getGameTime();
        if (phase == Phase.NOTICE || phase == Phase.APPROACH || phase == Phase.PERFORM) log("end", "interrupted", now);
        cancelAction("shop_window_stopped");
        clear();
        cooldownUntil = Math.max(cooldownUntil, now + 100);
    }

    // ---- customers ----------------------------------------------------------------------------------------------------

    private boolean stock() { return Custody.view(entity).role() == Custody.Role.STOCK; }

    /** Free to answer a customer: awake, fed enough to have no urgent need, on its feet, not handled or held. */
    private boolean ready() { return busy() == null; }

    /** What keeps it from answering, or null. */
    private String busy() {
        if (!entity.isAlive() || entity.isNoAi()) return "inactive";
        if (entity.isPassenger()) return "carried";
        if (entity.isPanicking()) return "panic";
        if (entity.isCommandPlaying()) return "command";
        if (entity.pet().busy()) return "handled";
        if (entity.operations().active()) return "operation";
        if (entity.life().isSleeping()) return "asleep";
        if (entity.life().mealTicks() > 0) return "eating";
        if (entity.life().hasUrgentNeed()) return "urgent_need";
        if (entity.hurtTime > 0) return "hurt";
        if (entity.family().active()) return "family";
        if (entity.isInWaterOrBubble()) return "water";
        return null;
    }

    /** People who come to look at the stock: players, and residents (caretakers or a cruel Oniisan); never the staff. */
    private static boolean customer(LivingEntity e) {
        return e.isAlive() && (e instanceof Player p && !p.isSpectator() || e instanceof HumanNpc n && n.job() == HumanNpc.Job.RESIDENT);
    }

    private double horizontal(Entity e) {
        double dx = e.getX() - entity.getX(), dz = e.getZ() - entity.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private LivingEntity findCustomer() {
        LivingEntity best = null; double bestDistance = Double.MAX_VALUE;
        for (var c : entity.level().getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(RANGE, HEIGHT, RANGE), ShopWindow::customer)) {
            double d = entity.distanceToSqr(c);
            if (d < bestDistance && horizontal(c) <= RANGE && GlassSight.sees(entity, c)) { best = c; bestDistance = d; }
        }
        return best;
    }

    private boolean present(LivingEntity c) {
        return c.isAlive() && !c.isRemoved() && c.level() == entity.level() && horizontal(c) <= KEEP && Math.abs(c.getY() - entity.getY()) <= HEIGHT
                && GlassSight.sees(entity, c);
    }

    // ---- drives -------------------------------------------------------------------------------------------------------

    /** Hope of being chosen: fades with whole days on the shelf toward the stale-stock recall day, and with each unanswered plea. */
    private double hope() {
        int days = Math.max(0, CommodityLife.forSaleDays(entity));
        return Math.clamp(1 - days / (double) CommodityLife.recallDays(), 0, 1) * (1 - .2 * rejections);
    }

    private double jitter() { return (entity.getRandom().nextDouble() * 2 - 1) * JITTER; }

    private Mode choose(LivingEntity c) {
        var p = entity.life().personality();
        double hope = hope();
        double fear = p.fear(c.getUUID()) + (c instanceof HumanNpc n && n.attitude() == HumanNpc.Attitude.CRUEL ? CRUEL_FEAR : 0);
        int grade = entity.pet().effectiveGrade().id();
        double want = .4 * p.friendliness() + .4 * p.boldness() + 40 * hope - .6 * fear + jitter();
        // Posing is showing off to be chosen too: a badge holder long passed over loses the heart for it (60 % of it at no hope left).
        double pride = grade >= BadgeGrade.SILVER.id()
                ? (25 + 10 * grade + .3 * (100 - p.friendliness()) + .2 * p.boldness()) * (.6 + .4 * hope) + jitter() : Double.NEGATIVE_INFINITY;
        double shy = 1.1 * (100 - p.boldness()) - 15 + .8 * fear + jitter();
        double sulk = 90 * (1 - hope) + .5 * (100 - p.selfControl()) - 30 + jitter();
        Mode chosen = want >= pride && want >= shy && want >= sulk
                ? p.boldness() >= 65 && p.friendliness() >= 60 ? Mode.SHOW_OFF : Mode.APPEAL
                : pride >= shy && pride >= sulk ? Mode.PROUD : shy >= sulk ? Mode.SHY : Mode.SULK;
        GameplayLog.event(entity, "shop", "window start mode={} customer={} kind={} days={} rejections={} hope={} want={} pride={} shy={} sulk={}",
                chosen, c.getUUID(), c instanceof Player ? "player" : "resident", Math.max(0, CommodityLife.forSaleDays(entity)), rejections,
                round(hope), round(want), round(pride), round(shy), round(sulk));
        return chosen;
    }

    private static String round(double v) { return Double.isInfinite(v) ? "none" : String.format(Locale.ROOT, "%.1f", v); }

    private void forgive(long now) {
        if (rejections > 0 && now >= forgiveAt) { rejections--; forgiveAt = now + FORGIVE_TICKS; }
    }

    // ---- a customer session -------------------------------------------------------------------------------------------

    private void beginSession(LivingEntity c, long now) {
        customer = c; sessionStart = now; performStart = -1; lastSeen = now; restands = 0; glass = null;
        mode = choose(c);
        phase(Phase.NOTICE, now + NOTICE_TICKS);
    }

    private void afterNotice(long now) {
        switch (mode) {
            case APPEAL, SHOW_OFF -> { entity.speak("shop_appeal"); if (!toGlass(now)) beginPerformance(now); }
            case PROUD -> { if (entity.pet().effectiveGrade().id() >= BadgeGrade.SILVER.id()) entity.speak("badge_proud"); beginPerformance(now); }
            case SHY -> { entity.speak("shy"); if (!retreat(now)) beginPerformance(now); }
            case SULK -> { entity.speak("request_sulk"); beginPerformance(now); }
        }
    }

    /** Walks to its side of the glass the customer is seen through; false when it pleads where it stands (in the open, already there, no way). */
    private boolean toGlass(long now) {
        var hit = GlassSight.glassBetween(entity, entity.getEyePosition(), customer);
        if (hit == null) return false;
        glass = hit.getBlockPos().immutable();
        var stand = GlassSight.stand(entity, customer, glass);
        if (stand == null || entity.distanceToSqr(stand) <= STAND_NEAR * STAND_NEAR) return false;
        return walk(Walk.stand(stand, 1.1), stand, now);
    }

    /** A shy one backs away from the customer inside its pen when there is room; otherwise it hides its face where it is. */
    private boolean retreat(long now) {
        var away = DefaultRandomPos.getPosAway(entity, 3, 1, customer.position());
        if (away == null || entity.distanceToSqr(away) <= 1) return false;
        return walk(new Walk(away), away, now);
    }

    private boolean walk(Walk move, Vec3 to, long now) {
        if (!entity.motor().run(this, move)) {
            GameplayLog.limited(entity, "shop", "window_walk_refused", 200, "window walk refused mode={} to={} outcome={}",
                    mode, GameplayLog.at(to), entity.motor().refusal());
            return false;
        }
        action = move; lastRestand = now;
        phase(Phase.APPROACH, now + APPROACH_TICKS);
        return true;
    }

    private void approaching(long now) {
        if (action != null && entity.motor().current() == action && now < until) return;
        if (action != null && entity.motor().current() == action) cancelAction("approach_slow");
        var outcome = entity.motor().last();
        if (!(outcome instanceof Outcome.Done))
            GameplayLog.limited(entity, "shop", "window_walk_ended", 200, "window walk ended mode={} outcome={}", mode, outcome);
        action = null;
        beginPerformance(now);
    }

    private void beginPerformance(long now) {
        if (performStart < sessionStart) performStart = now;
        phase(Phase.PERFORM, 0);
    }

    private void perform(long now, boolean scan) {
        if (!scan) { look(customer); return; }
        boolean here = present(customer);
        if (here) lastSeen = now;
        else if (now - lastSeen > GONE_TICKS) { leave(now, "customer_left"); return; }
        var p = entity.life().personality();
        // Attention span (project design): 20-50 s, longer for the bold and the friendly.
        if (now - sessionStart > 400 + 4L * p.boldness() + 2L * p.friendliness()) { leave(now, "attention_spent"); return; }
        look(customer);
        if (!here || mode != Mode.APPEAL && mode != Mode.SHOW_OFF || restands >= RESTANDS || now - lastRestand < RESTAND_TICKS) return;
        lastRestand = now;
        var hit = GlassSight.glassBetween(entity, entity.getEyePosition(), customer);
        if (hit == null || hit.getBlockPos().equals(glass)) return;
        // The customer moved along the window: follow to the glass in front of them.
        var stand = GlassSight.stand(entity, customer, hit.getBlockPos());
        if (stand == null || entity.distanceToSqr(stand) <= 1) return;
        glass = hit.getBlockPos().immutable(); restands++;
        walk(Walk.stand(stand, 1.1), stand, now);
    }

    private void leave(long now, String reason) {
        boolean pleaded = (mode == Mode.APPEAL || mode == Mode.SHOW_OFF) && now - performStart >= MIN_PLEA;
        if (pleaded) {
            rejections = Math.min(REJECTIONS, rejections + 1); forgiveAt = now + FORGIVE_TICKS;
            log("end", reason, now);
            entity.speak("plea_sad");
            after = After.LETDOWN; phase(Phase.AFTER, now + LETDOWN_TICKS);
        } else if (mode == Mode.PROUD && reason.equals("customer_left")) {
            log("end", reason, now);
            after = After.HMPH; phase(Phase.AFTER, now + HMPH_TICKS);
        } else {
            log("end", reason, now);
            finish(now);
        }
    }

    private void finish(long now) {
        cancelAction("shop_window_done");
        // A short rest before it answers anyone again: 15-45 s, shorter for the bold (project design).
        cooldownUntil = now + 300 + 6L * (100 - entity.life().personality().boldness());
        clear();
    }

    // ---- a neighbour being sold ---------------------------------------------------------------------------------------

    /** A stock neighbour of the same shop, in sight (in the open or through one glass cell), that has just been bought (held for the sale)
     *  and whose sale this one has not answered yet. */
    private YukkuriEntity findSale() {
        var shop = Custody.view(entity).owner();
        if (shop == null) return null;
        for (var o : entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(SALE_RANGE, HEIGHT, SALE_RANGE),
                o -> o != entity && o.isAlive() && o.pet().busyWith().equals("sale") && !o.getUUID().equals(witnessed) && ShopMenu.stockOf(o, shop)))
            if (GlassSight.sees(entity, o)) return o;
        return null;
    }

    private void beginWitness(YukkuriEntity sold, long now) {
        var level = (ServerLevel) entity.level();
        witnessed = sold.getUUID();
        // The buyer is whoever holds the sale; it may be away (an operator's purchase) or out of sight.
        var buyer = sold.pet().lockedBy() == null ? null : level.getPlayerByUUID(sold.pet().lockedBy());
        var p = entity.life().personality();
        boolean kin = Bonds.kin(entity, sold, EnumSet.of(Bonds.Kin.BIO, Bonds.Kin.CARE, Bonds.Kin.MATE));
        boolean friend = YukkuriWorldData.get(level).affinity(entity.getUUID(), sold.getUUID()) >= BondLevels.AFFINITY_CLOSE;
        boolean outranked = sold.pet().effectiveGrade().id() > entity.pet().effectiveGrade().id();
        witness = kin || friend ? Witness.FAREWELL
                : outranked || p.selfControl() < 40 && p.friendliness() < 50 ? Witness.ENVY
                : buyer != null && hope() > .4 && p.boldness() >= 50 && GlassSight.sees(entity, buyer) ? Witness.ME_TOO : Witness.WATCH;
        departing = sold; watched = witness == Witness.ME_TOO ? buyer : sold;
        sessionStart = now; departedAt = -1;
        phase(Phase.WITNESS, now + WITNESS_TICKS);
        switch (witness) {
            case ENVY -> entity.speak(outranked ? "badge_envy" : "badge_envy_snark");
            case ME_TOO -> entity.speak("shop_appeal");
            default -> {}
        }
        GameplayLog.event(entity, "shop", "window witness kind={} sold={} kin={} friend={} outranked={} buyer={}",
                witness, sold.getUUID(), kin, friend, outranked, buyer == null ? null : buyer.getUUID());
    }

    private void witnessing(long now) {
        if (departedAt < 0 && (!departing.isAlive() || departing.isRemoved() || !departing.pet().busyWith().equals("sale"))) {
            var custody = YukkuriWorldData.get((ServerLevel) entity.level());
            if (custody.isCaptured(departing.getUUID())) {
                departedAt = now;
                if (witness != Witness.ME_TOO) watched = basketHolder(custody);
            } else {
                GameplayLog.event(entity, "shop", "window witness end kind={} reason=sale_off ticks={}", witness, now - sessionStart);
                witnessed = null;
                finish(now);
                return;
            }
        }
        if (watched != null && watched.isAlive() && !watched.isRemoved() && watched.level() == entity.level()) look(watched);
        if (now >= until || departedAt >= 0 && now - departedAt >= WITNESS_TAIL) {
            GameplayLog.event(entity, "shop", "window witness end kind={} reason={} ticks={}", witness,
                    departedAt >= 0 ? "departed" : "timeout", now - sessionStart);
            finish(now);
        }
    }

    /** The staff member holding this particular animal's basket, or null. */
    private Entity basketHolder(YukkuriWorldData custody) {
        return entity.level().getEntitiesOfClass(HumanNpc.class, departing.getBoundingBox().inflate(4),
                        n -> n.isAlive() && n.getMainHandItem().is(ModItems.CARRIER.get()))
                .stream().filter(n -> {
                    var token = CarrierItem.token(n.getMainHandItem());
                    if (token == null) return false;
                    var snapshot = custody.captiveSnapshot(token);
                    return snapshot.hasUUID("UUID") && snapshot.getUUID("UUID").equals(departing.getUUID());
                }).findFirst().orElse(null);
    }

    // ---- its pen's service door -------------------------------------------------------------------------------------

    /** Free to go to the door: as for a customer, also hungry, tired or hurt (the door is where it is fed and treated), or held by its own
     *  shop for a sale or a keeper's errand (the door is where those end). */
    private boolean answersHatch() {
        String busy = busy();
        return busy == null || busy.equals("urgent_need") || busy.equals("hurt")
                || busy.equals("handled") && (entity.pet().busyWith().equals("sale") || entity.pet().busyWith().equals("courier"));
    }

    /** Its pen's service door, standing open with staff at it, when it is free to answer; else null. */
    private ServiceHatch calledToHatch() {
        if (entity.level().getGameTime() < hatchRestUntil || !answersHatch()) return null;
        var door = ServiceHatch.of(entity);
        return door != null && door.attendant(entity.level()) != null ? door : null;
    }

    private void beginHatch(ServiceHatch door, long now) {
        hatch = door; sessionStart = now;
        phase(Phase.HATCH, now + HATCH_TICKS);
        var stand = door.animalStand();
        double dx = entity.getX() - stand.x, dz = entity.getZ() - stand.z;
        var walk = Walk.stand(stand, 1.1);
        boolean walking = dx * dx + dz * dz > .09 && entity.motor().run(this, walk);
        if (walking) action = walk;
        GameplayLog.event(entity, "shop", "window hatch answer door={} stand={} walking={}", door.door().toShortString(), GameplayLog.at(stand), walking);
    }

    private void hatching(long now) {
        var staff = hatch.attendant(entity.level());
        if (staff == null || now >= until) {
            GameplayLog.event(entity, "shop", "window hatch end reason={} ticks={} at={}", staff == null ? "door_shut" : "timeout", now - sessionStart,
                    GameplayLog.at(entity.position()));
            cancelAction("hatch_done");
            clear();
            hatchRestUntil = now + HATCH_REST;
            return;
        }
        if (action != null && entity.motor().current() == action) return; // on its way to the door
        if (action != null) {
            if (!(entity.motor().last() instanceof Outcome.Done))
                GameplayLog.limited(entity, "shop", "window_hatch_walk", 200, "window hatch walk ended outcome={}", entity.motor().last());
            action = null;
        }
        look(staff);
    }

    // ---- shared -------------------------------------------------------------------------------------------------------

    private void look(Entity target) { if (target != null) entity.getLookControl().setLookAt(target, 30, 30); }

    private void phase(Phase next, long end) { phase = next; until = end; }

    private void cancelAction(String why) {
        if (action != null) entity.motor().cancel(this, why);
        action = null;
    }

    private void clear() {
        phase = null; mode = null; after = null; witness = null; customer = null; watched = null; departing = null; glass = null; action = null; hatch = null;
    }

    private void log(String what, String reason, long now) {
        GameplayLog.event(entity, "shop", "window {} mode={} reason={} ticks={} rejections={}", what, mode, reason, now - sessionStart, rejections);
    }
}
