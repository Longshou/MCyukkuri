package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.AbilityModifier;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Prop;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Out of the water by one's own means, the ways in order: its own hat floating upright in reach (swim
 * to the brim, climb in); its own hat lying capsized (swim, right it, let it settle, climb in); its own hat still worn, for a
 * species that uses its hat as a boat (set it down on the open water beside it, climb in); the nearest bank edge it can climb
 * (rise within its climb rise, an open landing, open water in front), approached head-on (swim out in front of the edge, then
 * to it, climb); else pressing toward the nearest bank, aimed again every few seconds and given up for a better way as soon as
 * one applies. An edge whose climb slipped or found no room is avoided for a while ({@link #AVOID_TICKS}), so the next try uses
 * another. The three own-hat ways are only for a body that sails ({@code ChildhoodLife.canSail}: an adult, or a young whose first
 * voyage came back); one that does not only struggles and waits for its parent to lift it out. Satisfied once the body is out of
 * the water, standing or aboard; failing costs what the water costs.
 */
public final class EscapeWater implements Intent {
    /** One run's ticks; a swim to the hat longer than this turns to the bank (AQUATIC rules); how long one press lasts before it
     *  aims again; how long an edge that failed a climb is avoided; the old shore search box (blocks out, down, up). */
    private static final int DEADLINE = 1200, HAT_SWIM = 600, PRESS = 100, AVOID_TICKS = 400, OUT = 6, DOWN = 1, UP = 3;
    /** How far (blocks) its own hat is swum to; clearance (blocks) of the landing box. */
    private static final double HAT_RANGE = 16, CLEAR = .05;
    private static final Set<Reason> AT_HAT = Set.of(Reason.SLIPPED, Reason.NO_CONTACT, Reason.NO_PROGRESS), AT_BANK = Set.of(
            Reason.TOO_FAR, Reason.SLIPPED, Reason.NO_CONTACT, Reason.NO_PROGRESS, Reason.NO_ROOM), PRESSING = Set.of(Reason.TOO_HIGH);

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
    }

    /** The world its runs are keyed on: where the body and its own hat are. */
    public static IntentRunner.Target target(YukkuriEntity body) {
        var hat = PlacedHatEntity.find(body);
        return new IntentRunner.Target(body.position(), hat == null ? null : hat.position(), Set.of());
    }

    @Override public String id() { return "escape_water"; }

    @Override public boolean satisfied() { return !body.isInWaterOrBubble() && (body.onGround() || body.isPassenger()); }

    @Override public long deadline() { return started + DEADLINE; }

    @Override public List<Method> methods() {
        remember();
        var hat = PlacedHatEntity.find(body);
        Vec3 boat = boatSpot();
        Edge bank = climbable();
        BlockPos near = nearest();
        Step toRim = new Step.Do(Swim.to(() -> hat.isRemoved() ? null : ContactGeometry.nearestRim(body, hat).orElse(null), HAT_SWIM));
        return List.of(
                new Way("own_hat", hat(hat, false), AT_HAT, i -> i == 0 ? toRim : i == 1 ? new Step.Do(Climb.onto(hat)) : null),
                new Way("right_own_hat", hat(hat, true), AT_HAT, i -> switch (i) {
                    case 0 -> toRim;
                    case 1 -> new Step.Do(new Right(hat, "self_rescue"));
                    case 2 -> new Step.Wait(hat::steady, 60, "hat_settles");
                    case 3 -> new Step.Do(Climb.onto(hat));
                    default -> null;
                }),
                new Way("hat_boat", boat == null ? boatRefusal() : Feasibility.pass(), AT_HAT, i -> {
                    var placed = i == 0 ? null : PlacedHatEntity.find(body);
                    return i == 0 ? new Step.Do(new PlaceProp(new Afloat(body), boat, body.getYRot()))
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
                new Way("struggle", near == null ? Feasibility.of(Reason.NO_PATH, 0, "no_bank") : Feasibility.pass(), PRESSING, i -> {
                    BlockPos to = i == 0 ? near : better(hat) ? null : nearest();
                    return to == null ? null : new Step.Do(Swim.toward(Vec3.atBottomCenterOf(to), PRESS));
                }));
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
            if (next == 0 && !id.equals("bank")) tried.add(id);
            return steps.apply(next++);
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
                && (last.reason() == Reason.SLIPPED || last.reason() == Reason.NO_ROOM) && avoid.size() < 16)
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
        float[] brim = HatGeometry.brim();
        double apart = body.getBbWidth() / 2 + Math.max(brim[0], brim[1]) * HatScale.of(body) / 16 + CLEAR;
        var hat = new Afloat(body);
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

    /** Its own worn hat set down on the open water beside it, where {@link PlacedHatEntity#setDown} floats it: the hat prop in
     *  every read and in the transfer, its stand an open water surface, the aquatic set-down clip. */
    private record Afloat(YukkuriEntity owner, HatProp hat) implements Prop {
        Afloat(YukkuriEntity owner) { this(owner, new HatProp(owner, false)); }
        @Override public String id() { return hat.id(); }
        @Override public boolean worn() { return hat.worn(); }
        @Override public boolean deployed() { return hat.deployed(); }
        @Override public boolean riders() { return hat.riders(); }
        @Override public boolean empty() { return hat.empty(); }
        @Override public Entity entity() { return hat.entity(); }
        @Override public Feasibility fits(Vec3 at, float yaw) {
            if (!worn() || deployed()) return Feasibility.of(Reason.NO_TOOL, 0, "hat_not_worn");
            if (owner.isPassenger()) return Feasibility.of(Reason.NO_ROOM, 0, "passenger");
            var level = owner.level();
            var spot = BlockPos.containing(at);
            if (!level.hasChunkAt(spot)) return Feasibility.of(Reason.NO_ROOM, 0, "chunk_unloaded");
            if (!level.getFluidState(spot).is(FluidTags.WATER) || !level.getFluidState(spot.above()).isEmpty()
                    || !level.getBlockState(spot.above()).getCollisionShape(level, spot.above()).isEmpty())
                return Feasibility.of(Reason.NO_SUPPORT, 0, "not_open_water " + spot.toShortString());
            return Feasibility.pass();
        }
        @Override public Entity setDown(Vec3 at, float yaw) { return hat.setDown(at, yaw); }
        @Override public boolean takeBack() { return hat.takeBack(); }
        @Override public String clip(boolean on) { return on ? Behaviors.HAT_ON_SELF_AQ : Behaviors.HAT_OFF_SELF_AQ; }
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

    /** The nearest stand reached through the water, climbable or not, an avoided one last: where a struggle presses toward. */
    private BlockPos nearest() {
        BlockPos fallback = null;
        for (var pos : stands()) {
            if (!route(pos)) continue;
            if (!avoid.containsKey(pos)) return pos;
            if (fallback == null) fallback = pos;
        }
        return fallback;
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
