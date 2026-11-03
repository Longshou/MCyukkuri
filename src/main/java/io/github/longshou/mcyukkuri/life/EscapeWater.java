package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.AbilityModifier;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/**
 * Out of the water by one's own means, the ways in order: its own hat floating upright in reach (stride into it with a braid
 * stroke when it lies within a beckoning reach, swim to the brim otherwise, climb in); its own hat lying capsized (the same closing, right it, let it settle, climb in); its own hat still worn, for a
 * species that uses its hat as a boat (set it down on the open water beside it, climb in); the nearest bank edge it can climb
 * (rise within its climb rise, an open landing, open water in front), approached head-on (swim out in front of the edge, then
 * to it, climb); else pressing toward the nearest bank, aimed again every few seconds and given up for a better way as soon as
 * one applies. An edge whose climb slipped or found no room is avoided for a while ({@link #AVOID_TICKS}), so the next try uses
 * another. The three own-hat ways are only for a body that sails ({@code ChildhoodLife.canSail}: an adult, or a young whose first
 * voyage came back); one that does not only struggles, toward the rim of its parent's hat afloat when that lies nearer than the
 * bank (where the parent lifts it out), and waits for its parent to lift it. Satisfied once the body stands on a stand it
 * really rests on out of the water with its own footprint clear of it (a rim level with the water is no stand) and its own climb,
 * if one is running, has finished; failing costs what the water costs.
 */
public final class EscapeWater implements Intent {
    /** One run's ticks; a swim to the hat longer than this turns to the bank (AQUATIC rules); how long one press lasts before it
     *  aims again; how long an edge that failed a climb is avoided; the old shore search box (blocks out, down, up). */
    private static final int DEADLINE = 1200, HAT_SWIM = 600, PRESS = 100, AVOID_TICKS = 400, OUT = 6, DOWN = 1, UP = 3;
    /** How far (blocks) its own hat is swum to; clearance (blocks) of the landing box. */
    private static final double HAT_RANGE = 16, CLEAR = .05;
    private static final Set<Reason> AT_HAT = Set.of(Reason.SLIPPED, Reason.NO_CONTACT, Reason.NO_PROGRESS), AT_BANK = Set.of(
            Reason.TOO_FAR, Reason.SLIPPED, Reason.NO_CONTACT, Reason.NO_PROGRESS, Reason.NO_ROOM, Reason.NO_SUPPORT), PRESSING = Set.of(Reason.TOO_HIGH);
    /** The ways that go for the body's own hat: it stops panicking and starts for it (自救援). */
    private static final Set<String> OWN_HAT = Set.of("own_hat", "right_own_hat", "hat_boat");

    /** A bank edge: its stand, the middle of the stand's face toward open water, the water point in front of it, the landing. */
    private record Edge(BlockPos stand, Vec3 edge, Vec3 front, Vec3 land) {}

    private final YukkuriEntity body;
    private final long started;
    /** Edges whose climb ended SLIPPED or NO_ROOM, until when (game tick); kept by the escape goal across runs. */
    private final Map<BlockPos, Long> avoid;
    /** Water routes already asked of the pathfinder in this run, and the stands scanned this tick. */
    private final Map<BlockPos, Boolean> routes = new HashMap<>();
    private List<BlockPos> stands = List.of();
    private long scanned = Long.MIN_VALUE;
    private Edge climbing;
    /** The hat ways and bank edges this run has already begun: a press gives way only to one not tried yet. */
    private final Set<Object> tried = new HashSet<>();

    public EscapeWater(YukkuriEntity body, Map<BlockPos, Long> avoid) {
        this.body = body;
        this.started = body.level().getGameTime();
        this.avoid = avoid;
        GameplayLog.event(body, "water", "escape start at={} support={}", GameplayLog.at(body.position()), support());
    }

    /** The world its runs are keyed on: where the body and its own hat are. */
    public static IntentRunner.Target target(YukkuriEntity body) {
        var hat = PlacedHatEntity.find(body);
        return new IntentRunner.Target(body.position(), hat == null ? null : hat.position(), Set.of());
    }

    @Override public String id() { return "escape_water"; }

    @Override public boolean satisfied() { return safe(body); }

    /** The Goal and intent finish on the same completed climb or supported dry landing. */
    public static boolean safe(YukkuriEntity body) {
        return !(body.motor().current() instanceof Climb)
                && (body.isPassenger() || !body.isInWaterOrBubble() && SurfaceApproach.stands(body, body.position()));
    }

    /** One line per escape, with the stand it ended on (or none): the whole run's outcome in the water log. */
    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.event(body, "water", "escape {} at={} support={}", result.name().toLowerCase(Locale.ROOT),
                GameplayLog.at(body.position()), support());
    }

    /** The stand the body rests on now: aboard a host, on a stand of its own out of the water, or {@code none}. */
    private String support() {
        if (body.isPassenger()) return "aboard=" + body.getVehicle().getId();
        return !body.isInWaterOrBubble() && SurfaceApproach.stands(body, body.position()) ? "stand=" + GameplayLog.at(body.position()) : "none";
    }

    @Override public long deadline() { return started + DEADLINE; }

    @Override public List<Method> methods() {
        remember();
        var hat = PlacedHatEntity.find(body);
        Vec3 boat = boatSpot();
        Edge bank = climbable();
        boolean refuge = rimHat() != null || nearest() != null;
        // Its own hat afloat within a braid's beckoning reach: one stroke draws it in before the last stretch is swum.
        int stroke = hat != null && new Beckon(hat).check(body).ok() ? 1 : 0;
        return List.of(
                new Way("own_hat", hat(hat, false), AT_HAT, i -> switch (i - stroke) {
                    case -1 -> new Step.Do(new Beckon(hat));
                    case 0 -> closeOn(hat);
                    case 1 -> new Step.Do(Climb.onto(hat));
                    default -> null;
                }),
                new Way("right_own_hat", hat(hat, true), AT_HAT, i -> switch (i - stroke) {
                    case -1 -> new Step.Do(new Beckon(hat));
                    case 0 -> closeOn(hat);
                    case 1 -> new Step.Do(new Right(hat, "self_rescue"));
                    case 2 -> new Step.Wait(hat::steady, 60, "hat_settles");
                    case 3 -> new Step.Do(Climb.onto(hat));
                    default -> null;
                }),
                new Way("hat_boat", boat == null ? boatRefusal() : Feasibility.pass(), AT_HAT, i -> {
                    var placed = i == 0 ? null : PlacedHatEntity.find(body);
                    return i == 0 ? new Step.Do(new PlaceProp(new HatProp(body, true, true), boat, body.getYRot()))
                            : placed == null ? null
                            : i == 1 ? new Step.Do(Swim.to(() -> placed.isRemoved() ? null : ContactGeometry.nearestRim(body, placed).orElse(null), HAT_SWIM))
                            : i == 2 ? new Step.Do(Climb.onto(placed)) : null;
                }),
                new Way("bank", bank == null ? Feasibility.of(Reason.TOO_HIGH, 0, "no_climbable_edge") : Feasibility.pass(), AT_BANK, i -> switch (i) {
                    case 0 -> { tried.add(bank.stand()); yield new Step.Do(Swim.to(bank::front, 0)); }
                    case 1 -> new Step.Do(Swim.to(bank::edge, 0));
                    case 2 -> new Step.Do(Climb.onto((climbing = bank).edge(), bank.land()));
                    default -> null;
                }),
                new Way("struggle", refuge ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_bank"), PRESSING,
                        i -> i > 0 && body.isInWaterOrBubble() && better(hat) ? null : press()));
    }

    /** The last stretch to its own hat: swum to the nearest rim, which the climb then holds on to. */
    private Step closeOn(PlacedHatEntity hat) {
        return new Step.Do(Swim.to(() -> hat.isRemoved() ? null : ContactGeometry.nearestRim(body, hat).orElse(null), HAT_SWIM));
    }

    /** One way out: whether it applies, what it shrinks, and its i-th step (null once it has no more). */
    private final class Way implements Method {
        private final String id;
        private final Feasibility applicable;
        private final Set<Reason> reduces;
        private final IntFunction<Step> steps;
        private int next;

        private Way(String id, Feasibility applicable, Set<Reason> reduces, IntFunction<Step> steps) {
            this.id = id;
            this.applicable = applicable;
            this.reduces = reduces;
            this.steps = steps;
        }

        @Override public String id() { return id; }
        @Override public Feasibility applicable() { return applicable; }
        @Override public Set<Reason> reduces() { return reduces; }
        @Override public Step next() {
            Step step = steps.apply(next);
            // Swimming ends at water exit; keep the intent while the body falls back or reaches dry support.
            if (step instanceof Step.Do action && action.action() instanceof Swim && !body.isInWaterOrBubble())
                return new Step.Do(new Await(() -> body.isInWaterOrBubble() || satisfied(), 20, "", "water_contact"));
            if (next == 0) {
                if (!id.equals("bank")) tried.add(id);
                if (OWN_HAT.contains(id)) body.showReaction("teary", "self_rescue_hat", 36);
            }
            next++;
            return step;
        }
    }

    /** A press gives way when a way not tried in this run has opened: its own hat in reach, or another climbable edge. */
    private boolean better(PlacedHatEntity hat) {
        if (!tried.contains("own_hat") && hat(hat, false).ok() || !tried.contains("right_own_hat") && hat(hat, true).ok()
                || !tried.contains("hat_boat") && boatSpot() != null) return true;
        Edge edge = climbable();
        return edge != null && !tried.contains(edge.stand());
    }

    /** The edge of the last climb, avoided for a while when that climb slipped or found no room. */
    private void remember() {
        long now = body.level().getGameTime();
        avoid.values().removeIf(until -> until <= now);
        var last = body.motor().last();
        if (climbing != null && body.motor().current() == null && last != null
                && (last.reason() == Reason.SLIPPED || last.reason() == Reason.NO_ROOM || last.reason() == Reason.NO_SUPPORT)
                && avoid.size() < 16)
            avoid.put(climbing.stand(), now + AVOID_TICKS);
        climbing = null;
    }

    private Feasibility hat(PlacedHatEntity hat, boolean capsized) {
        if (hat == null || hat.isRemoved() || hat.capsized() != capsized) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_hat");
        if (!body.childhood().canSail()) return Feasibility.of(Reason.NO_TOOL, 0, "cannot_sail");
        double far = hat.distanceTo(body) - HAT_RANGE;
        return far > 0 ? Feasibility.of(Reason.TOO_FAR, far, "hat_far") : Feasibility.pass();
    }

    /** Where its worn hat would lie on the open water beside it, set down to climb aboard, or null: a species that uses its hat
     *  as a boat (the {@code hat_boat} ability), the hat worn, able to climb; the spot as far out as the hat reaches, so they do
     *  not overlap, in front of it first and then round it. */
    private Vec3 boatSpot() {
        if (!AbilityModifier.flag(body, AbilityModifier.HAT_BOAT) || !body.childhood().canSail() || !body.life().custody().worn()
                || BodyAbility.of(body).climbRise() <= 0) return null;
        float[] brim = HatGeometry.wornForm(body).brim();
        double apart = body.getBbWidth() / 2 + Math.max(brim[0], brim[1]) * HatScale.of(body) / 16 + CLEAR;
        var hat = new HatProp(body, true, true);
        for (int i = 0; i < 8; i++) {
            double yaw = Math.toRadians(body.getYRot() + i * 45);
            double x = body.getX() - Math.sin(yaw) * apart, z = body.getZ() + Math.cos(yaw) * apart;
            for (int dy : new int[]{0, 1, -1}) {
                var at = new Vec3(x, body.getBlockY() + dy + .5, z);
                if (hat.fits(at, body.getYRot()).ok()) return at;
            }
        }
        return null;
    }

    /** Why its worn hat is no boat here, in the order {@link #boatSpot} asks. */
    private Feasibility boatRefusal() {
        if (!AbilityModifier.flag(body, AbilityModifier.HAT_BOAT)) return Feasibility.of(Reason.NO_TOOL, 0, "no_hat_boat");
        if (!body.childhood().canSail()) return Feasibility.of(Reason.NO_TOOL, 0, "cannot_sail");
        if (!body.life().custody().worn()) return Feasibility.of(Reason.NO_TOOL, 0, "hat_not_worn");
        if (BodyAbility.of(body).climbRise() <= 0) return Feasibility.of(Reason.TOO_HIGH, 0, "cannot_climb");
        return Feasibility.of(Reason.NO_ROOM, 0, "no_open_water_beside");
    }

    /** The nearest edge the body can climb now: within its climb rise, landing clear, not avoided, reached through the water. */
    private Edge climbable() {
        double rise = BodyAbility.of(body).climbRise();
        for (var pos : stands()) {
            if (pos.getY() - body.getY() > rise || avoid.containsKey(pos)) continue;
            Edge edge = edge(pos);
            if (edge != null && route(pos)) return edge;
        }
        return null;
    }

    /** One struggle step: toward the rim of a parent's hat afloat when that lies nearer than the nearest bank stand, else pressing
     *  toward the stand. */
    private Step press() {
        var hat = rimHat();
        if (hat != null) return atRim(hat);
        BlockPos bank = nearest();
        return bank == null ? null : new Step.Do(Swim.toward(Vec3.atBottomCenterOf(bank), PRESS));
    }

    /** Swim to the rim; once the mouth holds it, float there until it drifts out of reach, is lost or capsizes, or the wait is up.
     *  The next step checks sight and whether this hat is still the nearest refuge. */
    private Step atRim(PlacedHatEntity hat) {
        Supplier<Vec3> rim = () -> ContactGeometry.nearestRim(body, hat).orElseThrow();
        if (!ContactGeometry.canGrip(body, rim.get())) return new Step.Do(Swim.to(rim, HAT_SWIM));
        long from = body.level().getGameTime();
        return new Step.Wait(() -> hat.isRemoved() || hat.capsized() || ContactGeometry.gripGap(body, rim.get(), false) > 0
                || body.level().getGameTime() - from >= PRESS,
                PRESS + 1, "wait_for_parent");
    }

    /** The parent's hat afloat when its rim lies nearer than the nearest bank stand, else null. */
    private PlacedHatEntity rimHat() {
        var hat = parentHat();
        BlockPos bank = nearest();
        return hat != null && (bank == null || ContactGeometry.nearestRim(body, hat).orElseThrow().distanceToSqr(body.position())
                <= Vec3.atBottomCenterOf(bank).distanceToSqr(body.position())) ? hat : null;
    }

    /** The nearest hat afloat of a living parent within {@link #HAT_RANGE}, for a young that cannot sail (a body that sails has its
     *  own hat ways): the parent lifts it out of the water from there, so it waits at the rim rather than swimming on to a bank
     *  it has no breath to reach, until the help its incident was given has ended without getting it out. */
    private PlacedHatEntity parentHat() {
        if (body.childhood().canSail() || body.family().adult() || !(body.level() instanceof ServerLevel level)) return null;
        var ledger = AskLedger.server();
        String incident = ledger.openOf(body.getUUID(), "water");
        if (incident != null && ledger.helpEnded(incident)) return null;
        PlacedHatEntity nearest = null;
        for (UUID id : new UUID[]{body.family().mother(), body.family().father()}) {
            if (id == null || !(level.getEntity(id) instanceof YukkuriEntity parent) || !parent.isAlive()) continue;
            var hat = PlacedHatEntity.find(parent);
            if (hat != null && hat.behavior().afloat(hat) && hat.distanceTo(body) <= HAT_RANGE
                    && (nearest == null || hat.distanceToSqr(body) < nearest.distanceToSqr(body))) nearest = hat;
        }
        return nearest;
    }

    /** Prefer a routed stand, an avoided one last; only without a route does a weak struggle press toward an unrouted stand. */
    private BlockPos nearest() {
        BlockPos routed = null, unrouted = null;
        for (var pos : stands()) {
            if (route(pos)) {
                if (!avoid.containsKey(pos)) return pos;
                if (routed == null) routed = pos;
            } else if (unrouted == null || avoid.containsKey(unrouted) && !avoid.containsKey(pos)) unrouted = pos;
        }
        return routed != null ? routed : unrouted;
    }

    private boolean route(BlockPos pos) { return routes.computeIfAbsent(pos, p -> body.life().reachable(p, true)); }

    /** The stand's face toward open water nearest the body, with room to land behind it, or null. */
    private Edge edge(BlockPos stand) {
        Edge best = null;
        var level = body.level();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos out = stand.relative(d);
            if (!level.getFluidState(out.below()).is(FluidTags.WATER) || !level.getBlockState(out).getCollisionShape(level, out).isEmpty()) continue;
            Vec3 n = new Vec3(d.getStepX(), 0, d.getStepZ()), edge = Vec3.atBottomCenterOf(stand).add(n.scale(.5));
            Vec3 front = edge.add(n.scale(body.getBbWidth() / 2 + .2)), land = edge.subtract(n.scale(body.getBbWidth() / 2 + .05));
            if (!level.noCollision(body, body.getBoundingBox().move(land.subtract(body.position()).add(0, CLEAR, 0)))) continue;
            if (best == null || front.distanceToSqr(body.position()) < best.front().distanceToSqr(body.position())) best = new Edge(stand, edge, front, land);
        }
        return best;
    }

    /** Every dry stand of the search box, nearest first; scanned once a tick. */
    private List<BlockPos> stands() {
        long now = body.level().getGameTime();
        if (scanned == now) return stands;
        scanned = now;
        var out = new ArrayList<BlockPos>();
        var at = body.blockPosition();
        for (var pos : BlockPos.betweenClosed(at.offset(-OUT, -DOWN, -OUT), at.offset(OUT, UP, OUT)))
            if (body.level().hasChunkAt(pos) && body.life().dryStand(pos)) out.add(pos.immutable());
        out.sort(Comparator.comparingDouble(pos -> body.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        return stands = out;
    }
}
