package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The shared meeting of two bodies (spec 14 WP3 step 1): each participant gets an intent that walks to its own stand
 * ({@link GoTo}, around the other body) and then turns to the partner, and both intents carry the <em>same absolute
 * deadline</em>. Satisfied when both bodies stand at their stands facing each other ({@link Face#FACE_ARRIVED}), which is
 * the only fact the ceremony reads; the domain's {@link SiteSelector} decides where the two stands are.
 *
 * <p>The pair is a value: build it once when the ceremony reaches the rendezvous beat, hand {@link #forA()} /
 * {@link #forB()} to the two role goals, and let the choreography's own bounded {@code Await} watch {@link #satisfied()}.
 * The two intents never negotiate with each other and nothing is stored on either body.
 */
public final class Rendezvous {
    /** The facing tolerance, shared with the {@link Face} action so "arrived facing" and "faces the partner" agree. */
    public static final double FACING_DEGREES = Face.FACE_ARRIVED;

    /** Where the two bodies stand; each faces the other's stand. */
    public record Site(Vec3 a, Vec3 b) {
        public Site {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            if (a.distanceToSqr(b) < 1E-6) throw new IllegalArgumentException("A rendezvous needs two distinct stands");
        }
    }

    /** The domain decides the two stands from the two bodies; empty when this pair cannot meet here. */
    @FunctionalInterface
    public interface SiteSelector {
        Optional<Site> select(YukkuriEntity a, YukkuriEntity b);
    }

    private final YukkuriEntity a;
    private final YukkuriEntity b;
    private final Site site;
    private final long deadline;

    private Rendezvous(YukkuriEntity a, YukkuriEntity b, Site site, long deadline) {
        this.a = a;
        this.b = b;
        this.site = site;
        this.deadline = deadline;
    }

    /** Selects the site and returns a pair whose one deadline is {@code ticks} from now; empty when no site fits. */
    public static Optional<Rendezvous> of(YukkuriEntity a, YukkuriEntity b, SiteSelector selector, int ticks) {
        return of(a, b, selector, a == null ? 0 : a.level().getGameTime() + Math.max(1, ticks));
    }

    /** As {@link #of(YukkuriEntity, YukkuriEntity, SiteSelector, int)} with an absolute deadline (one per ceremony). */
    public static Optional<Rendezvous> of(YukkuriEntity a, YukkuriEntity b, SiteSelector selector, long deadline) {
        if (a == null || b == null || a == b || selector == null) return Optional.empty();
        if (deadline == Long.MAX_VALUE) throw new IllegalArgumentException("A rendezvous needs a finite deadline");
        Optional<Site> chosen = selector.select(a, b);
        if (chosen == null || chosen.isEmpty()) return Optional.empty();
        return Optional.of(new Rendezvous(a, b, chosen.get(), deadline));
    }

    /** The intent for the first body ({@code Site.a()}). */
    public Intent forA() { return new Side(a, b, site.a()); }

    /** The intent for the second body ({@code Site.b()}). */
    public Intent forB() { return new Side(b, a, site.b()); }

    public Site site() { return site; }

    /** The single absolute deadline both intents carry. */
    public long deadline() { return deadline; }

    /** Both bodies stand at their stands and face each other; the only success fact of the method. */
    public boolean satisfied() {
        return atStand(a, site.a()) && atStand(b, site.b()) && facing(a, b) && facing(b, a);
    }

    /** {@code a=(x,y,z) b=(x,y,z) deadline=+n} for logs and reports. */
    public String detail() {
        long now = a.level().getGameTime();
        return String.format(Locale.ROOT, "a=%s b=%s deadline=+%d", GameplayLog.at(site.a()), GameplayLog.at(site.b()),
                deadline - now);
    }

    /** Whether {@code self} stands at its stand with room to spare for the other's own error. */
    private static boolean atStand(YukkuriEntity body, Vec3 stand) {
        return body.isAlive() && !body.isRemoved() && SurfaceApproach.arrived(body, stand);
    }

    /** Whether {@code self} is turned toward {@code other} within {@link #FACING_DEGREES}. */
    public static boolean facing(YukkuriEntity self, YukkuriEntity other) {
        if (!self.isAlive() || !other.isAlive()) return false;
        float want = SurfaceApproach.yaw(self.position(), other.position());
        return Math.abs(Mth.wrapDegrees(want - self.getYRot())) <= FACING_DEGREES;
    }

    /** One participant's intent: its own stand, then its partner, on the pair's one deadline. */
    private final class Side implements Intent {
        private final YukkuriEntity self;
        private final YukkuriEntity other;
        private final Vec3 stand;

        private Side(YukkuriEntity self, YukkuriEntity other, Vec3 stand) {
            this.self = self;
            this.other = other;
            this.stand = stand;
        }

        @Override public String id() { return "rendezvous"; }

        @Override public boolean satisfied() { return Rendezvous.this.satisfied(); }

        @Override public long deadline() { return deadline; }

        @Override public List<Method> methods() { return List.of(new GoThenFace()); }

        /** Walk to my stand, turn to the partner, wait (bounded) until both are in place; repeat if the partner shifted. */
        private final class GoThenFace implements Method {
            private int phase;

            @Override public String id() { return "go_then_face"; }

            @Override public Feasibility applicable() {
                if (partnersGone()) return Feasibility.of(Reason.TARGET_GONE, 0, "partner_gone");
                return SurfaceApproach.canApproach(self, stand)
                        ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
            }

            @Override public Set<Reason> reduces() { return Set.of(Reason.NO_PATH, Reason.TOO_FAR); }

            @Override public Step next() {
                int step = Math.floorMod(phase++, 3);
                if (step == 0) {
                    if (partnersGone()) return null;
                    return new Step.Sub(new GoTo(self, stand, deadline).around(other));
                }
                if (step == 1) return new Step.Do(new Face(SurfaceApproach.yaw(self.position(), other.position())));
                return new Step.Wait(this::partnerGoneOrSatisfied, remaining(), "partner_at_stand");
            }

            private boolean partnersGone() {
                return !a.isAlive() || !b.isAlive() || a.isRemoved() || b.isRemoved();
            }

            private boolean partnerGoneOrSatisfied() {
                return satisfied() || partnersGone();
            }

            /** The ticks left of the pair's one deadline; the wait never outlives it. */
            private int remaining() {
                long left = deadline - self.level().getGameTime();
                return (int) Math.max(1, Math.min(Integer.MAX_VALUE, left));
            }
        }
    }
}
