package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Out of the water by one's own means (refactor WP5, design 8.3), the ways in order: its own hat floating upright in reach (swim
 * to the brim, climb in); its own hat lying capsized (swim, right it, climb in); the nearest bank edge within its climb rise
 * (swim, climb); else pressing toward the nearest bank until strength or progress gives out. Satisfied once the body is out of
 * the water, standing or aboard; a failed escape costs what the water costs (immersion damage, drowning).
 */
public final class EscapeWater implements Intent {
    /** One run's ticks; a swim to the hat longer than this turns to the bank (AQUATIC rules); the old shore search box (blocks
     *  out, down, up) and how many of its stands are route-checked; how far (blocks) the own hat is swum to. */
    private static final int DEADLINE = 1200, HAT_SWIM = 600, OUT = 6, DOWN = 1, UP = 3, ROUTES = 8;
    private static final double HAT_RANGE = 16;
    private static final Set<Reason> AT_HAT = Set.of(Reason.SLIPPED, Reason.NO_CONTACT), AT_BANK = Set.of(Reason.TOO_FAR,
            Reason.SLIPPED, Reason.NO_CONTACT, Reason.NO_PROGRESS), PRESSING = Set.of(Reason.TOO_HIGH, Reason.NO_ROOM);

    private final YukkuriEntity body;
    private final long started;

    public EscapeWater(YukkuriEntity body) {
        this.body = body;
        this.started = body.level().getGameTime();
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
        var hat = PlacedHatEntity.find(body);
        var stands = stands();
        BlockPos climbable = nearest(stands, true), any = climbable != null ? climbable : nearest(stands, false);
        Supplier<Action> toRim = () -> Swim.to(() -> hat.isRemoved() ? null : ContactGeometry.nearestRim(body, hat).orElse(null), HAT_SWIM);
        return List.of(
                new Way("own_hat", hat(hat, false), AT_HAT, toRim, () -> Climb.onto(hat)),
                new Way("right_own_hat", hat(hat, true), AT_HAT, toRim, () -> new Right(hat, "self_rescue"), () -> Climb.onto(hat)),
                new Way("bank", climbable == null ? Feasibility.of(Reason.TOO_HIGH, 0, "no_climbable_bank") : Feasibility.pass(), AT_BANK,
                        () -> Swim.to(() -> edge(climbable), 0), () -> Climb.onto(edge(climbable), onto(climbable))),
                new Way("struggle", any == null ? Feasibility.of(Reason.NO_PATH, 0, "no_bank") : Feasibility.pass(), PRESSING,
                        () -> Swim.toward(Vec3.atBottomCenterOf(any))));
    }

    /** One way out: whether it applies, what it shrinks, and its actions in order. */
    private static final class Way implements Method {
        private final String id;
        private final Feasibility applicable;
        private final Set<Reason> reduces;
        private final Supplier<Action>[] steps;
        private int next;

        @SafeVarargs private Way(String id, Feasibility applicable, Set<Reason> reduces, Supplier<Action>... steps) {
            this.id = id;
            this.applicable = applicable;
            this.reduces = reduces;
            this.steps = steps;
        }

        @Override public String id() { return id; }
        @Override public Feasibility applicable() { return applicable; }
        @Override public Set<Reason> reduces() { return reduces; }
        @Override public Step next() { return next < steps.length ? new Step.Do(steps[next++].get()) : null; }
    }

    private Feasibility hat(PlacedHatEntity hat, boolean capsized) {
        if (hat == null || hat.isRemoved() || hat.capsized() != capsized) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_hat");
        double far = hat.distanceTo(body) - HAT_RANGE;
        return far > 0 ? Feasibility.of(Reason.TOO_FAR, far, "hat_far") : Feasibility.pass();
    }

    /** The nearest stand the pathfinder reaches through the water; for a climb only one within the body's climb rise. */
    private BlockPos nearest(List<BlockPos> stands, boolean climb) {
        double rise = BodyAbility.of(body).climbRise();
        int routes = 0;
        for (var pos : stands) {
            if (climb && pos.getY() - body.getY() > rise) continue;
            if (routes++ >= ROUTES) break;
            if (body.life().reachable(pos, true)) return pos;
        }
        return null;
    }

    /** Every dry stand of the search box, nearest first. */
    private List<BlockPos> stands() {
        var out = new ArrayList<BlockPos>();
        var at = body.blockPosition();
        for (var pos : BlockPos.betweenClosed(at.offset(-OUT, -DOWN, -OUT), at.offset(OUT, UP, OUT)))
            if (body.level().hasChunkAt(pos) && body.life().dryStand(pos)) out.add(pos.immutable());
        out.sort(Comparator.comparingDouble(pos -> body.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        return out;
    }

    /** The point of the stand's top face nearest the body: the edge it holds. */
    private Vec3 edge(BlockPos stand) {
        return new Vec3(Math.clamp(body.getX(), stand.getX(), stand.getX() + 1), stand.getY(), Math.clamp(body.getZ(), stand.getZ(), stand.getZ() + 1));
    }

    /** Where it stands once over: in from the edge by its half width, toward the stand's middle. */
    private Vec3 onto(BlockPos stand) {
        Vec3 edge = edge(stand), in = Vec3.atBottomCenterOf(stand).subtract(edge);
        double length = in.length();
        return length < 1E-6 ? edge : edge.add(in.scale(Math.min(length, body.getBbWidth() / 2 + .05) / length));
    }
}
