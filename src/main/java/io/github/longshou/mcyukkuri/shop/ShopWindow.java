package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
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
 * strikes a proud pose, shies away, or sulks once days on the shelf and pleas nobody answered have worn its
 * hope down. A plea that ends with the customer walking off ends in a letdown and is remembered for a while; a neighbour carried off
 * in its sight is seen off by relation. Nothing is saved: a restart only drops the moment under way. Moving to the glass or away from
 * the customer is a motor Walk, facing is the look control, and the clips and faces are the moment rows of the reported activity.
 */
public final class ShopWindow implements MotorOwner {
    public enum Mode { APPEAL, SHOW_OFF, POLITE, PROUD, SHY, SULK }
    public enum Witness { FAREWELL, ENVY, ME_TOO, WATCH }
    private enum Phase { NOTICE, APPROACH, PERFORM, AFTER, WITNESS }
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
    /** Rejections remembered at most, and the time after which one is forgotten (project design). */
    private static final int REJECTIONS = 4, FORGIVE_TICKS = 2400;
    /** Small variation between responses whose drives are close. */
    private static final double VARIATION = 3;
    /** Days on the shelf over which the hope of being chosen fades out: three restocks (project design). */
    private static final int HOPE_DAYS = 9;

    private final YukkuriEntity entity;
    private Phase phase;
    private Mode mode;
    private After after;
    private Witness witness;
    private LivingEntity customer, candidate;
    private Entity watched;
    private YukkuriEntity departing, leaving;
    /** The last neighbour whose sale it watched: one sale is answered once. */
    private UUID witnessed;
    private BlockPos glass;
    private Action action;
    private long until, sessionStart, performStart, lastSeen, lastRestand, nextScan, cooldownUntil, forgiveAt, departedAt;
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
                case POLITE -> Behaviors.WINDOW_POLITE;
                case PROUD -> Behaviors.WINDOW_PROUD;
                case SHY -> Behaviors.WINDOW_SHY;
                case SULK -> Behaviors.WINDOW_SULK;
            };
            case AFTER -> after == After.LETDOWN ? Behaviors.WINDOW_LETDOWN : Behaviors.WINDOW_HMPH;
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
        if (StatusEvents.trace()) GameplayLog.event(entity, "shop", "window sight customer={}", seen.getUUID());
        candidate = seen;
        return true;
    }

    public boolean canContinue() { return phase != null && stock() && ready(); }

    public void start() {
        long now = entity.level().getGameTime();
        if (leaving != null) beginWitness(leaving, now);
        else if (candidate != null) beginSession(candidate, now);
        candidate = null; leaving = null;
    }

    public void tick() {
        long now = entity.level().getGameTime();
        boolean scan = now >= nextScan;
        if (scan) nextScan = now + SCAN_TICKS;
        if (scan && phase != Phase.WITNESS && phase != Phase.AFTER) {
            var sale = findSale();
            if (sale != null) { // a customer session gives way to a neighbour being sold
                logEnd("witness", now, false);
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
        }
    }

    /** Goal stop and every interrupt: drops the moment, keeps what it learned (rejections) and rests a little. */
    public void stop() {
        if (phase == null) return;
        long now = entity.level().getGameTime();
        if (phase == Phase.NOTICE || phase == Phase.APPROACH || phase == Phase.PERFORM) logEnd("interrupted", now, false);
        // a need of its own (a meal, sleep, a fright) cuts a sale it is watching short; whether it saw the basket go is kept in the line
        if (phase == Phase.WITNESS) GameplayLog.event(entity, "shop", "window witness end kind={} reason=interrupted departed={} ticks={}", witness,
                departedAt >= 0, now - sessionStart);
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

    /** People who come to look at the stock: players and residents. */
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
        return customer(c) && !c.isRemoved() && c.level() == entity.level() && horizontal(c) <= KEEP && Math.abs(c.getY() - entity.getY()) <= HEIGHT
                && GlassSight.sees(entity, c);
    }

    // ---- drives -------------------------------------------------------------------------------------------------------

    /** Hope of being chosen: fades with whole days on the shelf over {@link #HOPE_DAYS}, and with each unanswered plea. */
    private double hope() {
        int days = Math.max(0, CommodityLife.forSaleDays(entity));
        return Math.clamp(1 - days / (double) HOPE_DAYS, 0, 1) * (1 - .2 * rejections);
    }

    private double variation() { return (entity.getRandom().nextDouble() * 2 - 1) * VARIATION; }

    private Mode choose(LivingEntity c) {
        var p = entity.life().personality();
        var bond = p.relation(c.getUUID());
        double esteem = SocialDisposition.selfEsteem(entity);
        double confidence = SocialDisposition.confidence(esteem);
        double wary = SocialDisposition.humanWariness(entity, c, bond);
        double warmth = SocialDisposition.ramp(p.friendliness(), 30, 80);
        double familiar = SocialDisposition.ramp(bond.closeness(), 0, 20);
        double hope = hope();
        // The existing performances compete on comparable weights. Attention-seeking needs hope,
        // good manners and familiarity favour a bow, and wariness can outweigh both.
        double appeal = 32 * hope + 30 * warmth + 24 * confidence - 35 * wary - 18 * familiar + variation();
        double polite = (20 + .4 * p.selfControl() + 20 * warmth + 30 * familiar + 18 * wary)
                * (.7 + .3 * hope) + variation();
        double standTall = confidence * (70 + 24 * (1 - warmth)) * (1 - wary) * (.6 + .4 * hope) + variation();
        double shy = 100 * wary + 20 * (1 - confidence) + variation();
        double sulk = 82 * (1 - hope) + .2 * (100 - p.selfControl()) - 10 + variation();
        Mode chosen = appeal >= standTall && appeal >= shy && appeal >= sulk && appeal >= polite
                ? confidence >= .45 && warmth >= .65 ? Mode.SHOW_OFF : Mode.APPEAL
                : polite >= standTall && polite >= shy && polite >= sulk ? Mode.POLITE
                : standTall >= shy && standTall >= sulk ? Mode.PROUD : shy >= sulk ? Mode.SHY : Mode.SULK;
        GameplayLog.event(entity, "shop", "window start mode={} customer={} kind={} days={} rejections={} hope={} esteem={} wariness={} closeness={} fear={} grade={} friendliness={} self_control={} appeal={} polite={} stand_tall={} shy={} sulk={}",
                chosen, c.getUUID(), c instanceof Player ? "player" : "resident", Math.max(0, CommodityLife.forSaleDays(entity)), rejections,
                hope, esteem, wary, bond.closeness(), bond.fear(), BadgeSocial.visibleRank(entity).id(),
                p.friendliness(), p.selfControl(), appeal, polite, standTall, shy, sulk);
        return chosen;
    }

    private static String round(double v) { return String.format(Locale.ROOT, "%.2f", v); }

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
        if (!present(customer)) { leave(now, "customer_left"); return; }
        switch (mode) {
            case APPEAL, SHOW_OFF -> { entity.speak("shop_appeal"); if (!toGlass(now)) beginPerformance(now); }
            case POLITE -> { entity.speak("hello"); beginPerformance(now); }
            case PROUD -> { if (BadgeSocial.visibleRank(entity).id() > 0) entity.speak("badge_proud"); beginPerformance(now); }
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
        if (now - sessionStart > 400 + 4L * p.boldness() + 2L * p.friendliness() && here) {
            leave(now, "attention_spent"); return;
        }
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
        boolean pleaded = reason.equals("customer_left") && performStart >= sessionStart
                && (mode == Mode.APPEAL || mode == Mode.SHOW_OFF) && now - performStart >= MIN_PLEA;
        boolean reserved = pleaded && reservedByCustomer();
        if (pleaded && !reserved) {
            rejections = Math.min(REJECTIONS, rejections + 1); forgiveAt = now + FORGIVE_TICKS;
            logEnd(reason, now, false);
            entity.speak("plea_sad");
            after = After.LETDOWN; phase(Phase.AFTER, now + LETDOWN_TICKS);
        } else if (mode == Mode.PROUD && performStart >= sessionStart && reason.equals("customer_left")) {
            logEnd(reason, now, false);
            after = After.HMPH; phase(Phase.AFTER, now + HMPH_TICKS);
        } else {
            logEnd(reason, now, reserved);
            finish(now);
        }
    }

    private boolean reservedByCustomer() {
        var level = (ServerLevel) entity.level();
        var shop = ShopRegistry.get(level).shopAt(level, entity.position());
        var deposit = shop == null ? null : shop.deposit(entity.getUUID());
        return deposit != null && deposit.buyer().equals(customer.getUUID());
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
                o -> o != entity && o.isAlive() && ShopTrade.order(o) && !o.getUUID().equals(witnessed) && ShopTrade.stockOf(o, shop)))
            if (GlassSight.sees(entity, o)) return o;
        return null;
    }

    private void beginWitness(YukkuriEntity sold, long now) {
        var level = (ServerLevel) entity.level();
        witnessed = sold.getUUID();
        // The buyer is whoever holds the sale; it may be away (an operator's purchase) or out of sight.
        var buyer = sold.pet().lockedBy() == null ? null : level.getPlayerByUUID(sold.pet().lockedBy());
        var p = entity.life().personality();
        var peerBond = p.relation(sold.getUUID());
        var buyerBond = p.relation(buyer == null ? null : buyer.getUUID());
        double peerWariness = SocialDisposition.wariness(entity, peerBond);
        double buyerWariness = buyer == null ? 0 : SocialDisposition.humanWariness(entity, buyer, buyerBond);
        double esteem = SocialDisposition.selfEsteem(entity);
        double confidence = SocialDisposition.confidence(esteem);
        double hope = hope();
        boolean kin = Bonds.kin(entity, sold, EnumSet.of(Bonds.Kin.BIO, Bonds.Kin.CARE, Bonds.Kin.MATE));
        boolean friend = peerBond.closeness() >= BondLevels.AFFINITY_CLOSE;
        int rank = BadgeSocial.visibleRank(entity).id(), soldRank = BadgeSocial.visibleRank(sold).id();
        double envy = (.65 * SocialDisposition.ramp(soldRank - rank, 0, 3)
                + .45 * confidence * (1 - SocialDisposition.ramp(p.friendliness(), 35, 75))
                + .2 * SocialDisposition.ramp(-peerBond.closeness(), 0, 20)) * (1 - peerWariness);
        double join = buyer != null && present(buyer)
                ? hope * (1 - buyerWariness) * (.65 * confidence + .35 * SocialDisposition.ramp(buyerBond.closeness(), 0, 20)) : 0;
        witness = (kin || friend) && peerWariness < .5 ? Witness.FAREWELL
                : envy >= .4 && envy >= join ? Witness.ENVY : join >= .35 ? Witness.ME_TOO : Witness.WATCH;
        departing = sold; watched = witness == Witness.ME_TOO ? buyer : sold;
        sessionStart = now; departedAt = -1;
        phase(Phase.WITNESS, now + WITNESS_TICKS);
        switch (witness) {
            case ENVY -> { if (soldRank > rank) entity.speak("badge_envy"); }
            case ME_TOO -> entity.speak("shop_appeal");
            default -> {}
        }
        GameplayLog.event(entity, "shop", "window witness kind={} sold={} kin={} friend={} buyer={} grade={} sold_grade={} esteem={} wariness={} closeness={} fear={} buyer_wariness={} buyer_closeness={} hope={}",
                witness, sold.getUUID(), kin, friend, buyer == null ? null : buyer.getUUID(), rank, soldRank, round(esteem),
                round(peerWariness), peerBond.closeness(), peerBond.fear(), round(buyerWariness), buyerBond.closeness(), round(hope));
    }

    private void witnessing(long now) {
        if (departedAt < 0 && (!departing.isAlive() || departing.isRemoved() || !ShopTrade.order(departing))) {
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
                        n -> n.isAlive() && CarrierContents.tokens(n.getMainHandItem()).stream()
                                .anyMatch(token -> departing.getUUID().equals(custody.captiveIdentity(token))))
                .stream().findFirst().orElse(null);
    }

    // ---- shared -------------------------------------------------------------------------------------------------------

    private void look(Entity target) { if (target != null) entity.getLookControl().setLookAt(target, 30, 30); }

    private void phase(Phase next, long end) { phase = next; until = end; }

    private void cancelAction(String why) {
        if (action != null) entity.motor().cancel(this, why);
        action = null;
    }

    private void clear() {
        phase = null; mode = null; after = null; witness = null; customer = null; watched = null; departing = null; glass = null; action = null;
    }

    private void logEnd(String reason, long now, boolean reserved) {
        long performTicks = performStart < sessionStart ? 0 : now - performStart;
        GameplayLog.event(entity, "shop", "window end mode={} reason={} ticks={} rejections={} customer={} perform_ticks={} reserved={}",
                mode, reason, now - sessionStart, rejections, customer.getUUID(), performTicks, reserved);
    }
}
