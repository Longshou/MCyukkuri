package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * A hungry body shut in by water and a ledge higher than it hops (a sand spit under a full-block bank) gets out the one way its
 * body allows: it walks to the foot of the ledge and strains over it, a hop and a hook of the braid ({@link Climb}, paid in
 * energy, bodies whose size and fitness reach it only; {@link BodyAbility#scrambleRise}). Whether it is shut in and where the
 * ledge is comes from the pure {@link Stranding}; the want is hunger (the line it wakes at, {@link HungerDrive#wakeLevel}), so a
 * fed body rests on its spit and a bold hungry one goes sooner. No exit: nothing happens, the water and the ledge stay real.
 */
public final class LedgeScramble implements Intent {
    /** Ticks between looks at the ground a hungry body stands on; a run's deadline; the pause after a run that did not get out. */
    private static final int LOOK = 100, DEADLINE = 400, PAUSE = 600;
    /** How far a drop still counts as ground it walks down (blocks): inside the vanilla safe fall. */
    private static final double DROP = 2;
    private static final Set<Reason> REDUCES = Set.of(Reason.NO_CONTACT, Reason.TOO_FAR, Reason.NO_PROGRESS, Reason.SLIPPED);

    /** The ledge a stranded body strains over: where it stands at the foot, the lip it grips, the stand on top. */
    public record Plan(Vec3 front, Vec3 edge, Vec3 land, double rise) {}

    private final YukkuriEntity body;
    private final Plan plan;
    private final long started;

    private LedgeScramble(YukkuriEntity body, Plan plan) {
        this.body = body;
        this.plan = plan;
        this.started = body.level().getGameTime();
    }

    /** The plan for a body shut in with a ledge it can strain over, or null: open ground, or no ledge within its scramble rise. */
    public static Plan plan(YukkuriEntity body) {
        var ability = BodyAbility.of(body);
        Stranding.Terrain terrain = (x, z, lo, hi) -> {
            double best = Double.NaN;
            // one probe sees half a block either way; the lower probe is only needed for a band wider than a block
            for (double at : new double[]{hi - .5, lo + .5}) {
                var stand = SurfaceApproach.floor(body, new Vec3(x + .5, at, z + .5));
                if (stand != null && stand.y >= lo && stand.y <= hi && (Double.isNaN(best) || stand.y > best)) best = stand.y;
            }
            return best;
        };
        var start = new Stranding.Cell(body.getBlockX(), body.getBlockZ(), body.getY());
        var verdict = Stranding.assess(terrain, start, Math.max(Math.max(ability.stepUp(), ability.hopHeight()), body.maxUpStep()), ability.scrambleRise(), DROP);
        if (!verdict.stranded()) return null;
        var exit = verdict.exit();
        if (exit == null) {
            GameplayLog.limited(body, "move", "stranded_no_ledge", 1200,
                    "stranded without a ledge it can strain over size={} scrambleRise={}", () -> new Object[]{
                        verdict.size(), String.format(Locale.ROOT, "%.2f", ability.scrambleRise())});
            return null;
        }
        // stand against the wall: the body's half width off the face, in the middle of the foot cell
        double off = .5 - body.getBbWidth() / 2 - .04;
        var foot = new Vec3(exit.from().x() + .5 + exit.dx() * off, exit.from().y(), exit.from().z() + .5 + exit.dz() * off);
        var front = SurfaceApproach.floor(body, foot);
        var land = new Vec3(exit.to().x() + .5, exit.to().y(), exit.to().z() + .5);
        var edge = new Vec3(exit.to().x() + .5 - exit.dx() * .5, exit.to().y(), exit.to().z() + .5 - exit.dz() * .5);
        return front == null ? null : new Plan(front, edge, land, exit.rise());
    }

    @Override public String id() { return "ledge_scramble"; }

    @Override public boolean satisfied() {
        return body.onGround() && Math.abs(body.getX() - plan.land().x) < .5 && Math.abs(body.getZ() - plan.land().z) < .5
                && Math.abs(body.getY() - plan.land().y) < .3 && SurfaceApproach.stands(body, body.position());
    }

    @Override public long deadline() { return started + DEADLINE; }

    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.event(body, "move", "scramble {} at={} land={}", result.name().toLowerCase(Locale.ROOT),
                GameplayLog.at(body.position()), GameplayLog.at(plan.land()));
    }

    @Override public List<Method> methods() {
        return List.of(new Method() {
            private int next;
            @Override public String id() { return "scramble"; }
            @Override public Feasibility applicable() {
                return plan.rise() <= BodyAbility.of(body).scrambleRise() ? Feasibility.pass()
                        : Feasibility.of(Reason.TOO_HIGH, plan.rise() - BodyAbility.of(body).scrambleRise(), "ledge_too_high");
            }
            @Override public Set<Reason> reduces() { return REDUCES; }
            @Override public Step next() {
                return switch (next++) {
                    case 0 -> new Step.Do(new Walk(plan.front()));
                    case 1 -> new Step.Do(Climb.onto(plan.edge(), plan.land()));
                    default -> null;
                };
            }
        });
    }

    /** The goal: looks at its ground every few seconds while hungry on land, runs one scramble, rests before looking again. */
    public static final class ScrambleGoal extends Goal {
        private final YukkuriEntity owner;
        private IntentRunner runner;
        private Plan pending;
        private long next;

        public ScrambleGoal(YukkuriEntity owner) {
            this.owner = owner;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
        }

        @Override public boolean canUse() {
            long now = owner.level().getGameTime();
            if (now < next) return false;
            next = now + LOOK;
            if (!owner.onGround() || owner.isPassenger() || owner.isInWaterOrBubble() || owner.life().isSleeping()
                    || owner.house().active() || owner.life().nutrition() >= HungerDrive.wakeLevel(owner.life().personality().boldness()))
                return false;
            pending = plan(owner);
            return pending != null;
        }

        @Override public boolean canContinueToUse() { return runner != null && runner.active(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void start() {
            GameplayLog.event(owner, "move", "stranded ledge rise={} nutrition={} front={} land={}",
                    String.format(Locale.ROOT, "%.2f", pending.rise()), String.format(Locale.ROOT, "%.1f", owner.life().nutrition()),
                    GameplayLog.at(pending.front()), GameplayLog.at(pending.land()));
            runner = new IntentRunner(owner, new LedgeScramble(owner, pending), "ledge_scramble");
            runner.start("hunger");
        }

        @Override public void tick() { runner.tick(); }

        @Override public void stop() {
            if (runner != null) runner.stop("scramble_stop");
            // out or not, a body looks again after a pause: a failed run is no reason to try the same ledge at once
            next = owner.level().getGameTime() + PAUSE;
            runner = null;
        }
    }
}
