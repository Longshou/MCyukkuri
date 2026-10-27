package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewPort;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewReach;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import io.github.longshou.mcyukkuri.subspecies.crew.Purpose;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The common finite carry session. CrewTransfer alone owns contact, curve, seat claims and the body's actual movement. */
public final class BraidCarry {
    public enum Intent { RESCUE, BOARD, LAND, CARRY_WITHIN, TAKE_DOWN_FROM_HAT }
    public enum Verdict { OK, NEEDS_REPOSITION, IMPOSSIBLE }
    public enum Outcome { OK, TRANSIENT, GEOMETRY, INCAPABLE, NO_PROGRESS }
    public record Preflight(Verdict verdict, Outcome outcome, String reason) {}

    public static final class Session {
        private final Intent intent;
        private final UUID carrier;
        private final int originHull, destinationHull;
        private final long started;
        private final EnumMap<Outcome, Integer> failures = new EnumMap<>(Outcome.class);
        private long retryAt, ticket;
        private String lastAttempt = "";
        private boolean exhausted;
        private boolean staged;
        private CrewTransfer.Request request;

        private Session(CrewTransfer.Request request) {
            this.request = request;
            intent = BraidCarry.intent(request);
            carrier = request.carrier() == null ? null : request.carrier().getUUID();
            originHull = request.from().boat(); destinationHull = request.to().boat();
            started = request.body().level().getGameTime();
            GameplayLog.limited(request.body(), "braid", "begin." + intent, 100,
                    "begin intent={} carrier={} body={} from={} to={}", intent, carrier, request.body().getUUID(), request.from().key(), request.to().key());
        }
        private boolean matches(CrewTransfer.Request next) {
            return intent == BraidCarry.intent(next) && Objects.equals(carrier, next.carrier() == null ? null : next.carrier().getUUID())
                    && originHull == next.from().boat() && destinationHull == next.to().boat()
                    && next.body().level().getGameTime() - started <= 1200;
        }
        public Intent intent() { return intent; }
        public Preflight preflight() {
            if (request.carrier() == null || !CrewReach.canLift(request.carrier(), request.body()))
                return new Preflight(Verdict.IMPOSSIBLE, Outcome.INCAPABLE, "lift_capacity");
            var result = CrewTransfer.previewLift(request);
            if (result.ok()) return new Preflight(Verdict.OK, Outcome.OK, "");
            Outcome outcome = classify(result.refusal(), result.reason());
            return new Preflight(outcome == Outcome.GEOMETRY ? Verdict.NEEDS_REPOSITION : Verdict.IMPOSSIBLE, outcome, result.reason());
        }
        /** Repeated calls renew the same request; a moving carrier/deck is handled by the ticket's APPROACH phase. */
        public CrewTransfer.Result tick(ServerLevel level, CrewTransfer.Request next) {
            request = next;
            long now = level.getGameTime();
            if (exhausted) return refused(CrewTransfer.Refusal.INCAPABLE, "carry_no_progress");
            String attempt = request.from().key() + ">" + request.to().key();
            if (now < retryAt && attempt.equals(lastAttempt)) return refused(CrewTransfer.Refusal.TRANSIENT, "carry_retry_wait");
            lastAttempt = attempt;
            var result = CrewTransfer.open(level, request);
            if (result.ok()) { ticket = result.ticket().id(); return result; }
            if (result.refusal() == CrewTransfer.Refusal.GEOMETRY && !staged
                    && (intent == Intent.LAND || intent == Intent.TAKE_DOWN_FROM_HAT)) {
                var stage = approachBody(level);
                if (stage != null) { ticket = stage.ticket().id(); staged = true; return stage; }
            }
            // Participant/seat contention is not an attempt to grab; it must not spend a physical failure budget.
            if (result.refusal() != CrewTransfer.Refusal.TRANSIENT) failed(classify(result.refusal(), result.reason()), result.reason(), now);
            return exhausted ? refused(CrewTransfer.Refusal.INCAPABLE, "carry_no_progress:" + result.reason()) : result;
        }

        /** One real intermediate seat, only when it improves the ultimate route and the following land lift is feasible. */
        private CrewTransfer.Result approachBody(ServerLevel level) {
            var body = request.body();
            var helper = request.carrier();
            if (!(body.getVehicle() instanceof HatBoatEntity hat) || helper == null || helper.getVehicle() != hat
                    || request.to().kind() != CrewTransfer.Kind.GROUND) return null;
            var placed = hat.deck().get(body.getUUID());
            if (placed == null || placed.slot() < 0) return null;
            var target = request.to().pos();
            double currentDistance = body.position().distanceTo(target);
            int tested = 0;
            for (int seat : hat.deck().freeSeats(hat.bodyOf(body), Purpose.SERVICE, hat.toLocal(target))) {
                if (seat == placed.slot()) continue;
                var at = hat.toWorld(hat.deckLayout().slot(seat).pos());
                if (at.distanceTo(target) >= currentDistance - .05) continue;
                if (++tested > 12) break;
                if (!CrewTransfer.canLandFromSeat(body, helper, hat, seat, target)) continue;
                var stage = new CrewTransfer.Request(CrewTransfer.Mode.LIFT, body, helper, request.from(),
                        CrewTransfer.Anchor.berth(hat, seat), Purpose.SERVICE, false, false, DeckLayout.NONE, "land_approach");
                var result = CrewTransfer.open(level, stage);
                if (result.ok()) {
                    GameplayLog.event(body, "braid", "approach intent={} carrier={} body={} reposition=body from={} to={} final={}",
                            intent, carrier, body.getUUID(), request.from().key(), stage.to().key(), request.to().key());
                    return result;
                }
            }
            return null;
        }
        private void failed(Outcome outcome, String reason, long now) {
            int attempts = failures.merge(outcome, 1, Integer::sum);
            retryAt = now + 20;
            exhausted = attempts >= 3 || outcome == Outcome.INCAPABLE;
            GameplayLog.limited(request.body(), "braid", "failed." + intent + "." + outcome, 100,
                    "failed intent={} carrier={} body={} from={} to={} outcome={} reason={} attempts={} exhausted={}",
                    intent, carrier, request.body().getUUID(), request.from().key(), request.to().key(), outcome, reason, attempts, exhausted);
        }
        private void ended(CrewTransfer transfer, CrewPort.Outcome outcome) {
            if (ticket != transfer.id()) return;
            if (!outcome.success()) failed(classify(outcome.refusal(), outcome.reason()), outcome.reason(), outcome.tick());
            else if (request.to().kind() == CrewTransfer.Kind.GROUND && transfer.to().kind() == CrewTransfer.Kind.BERTH) {
                GameplayLog.event(request.body(), "braid", "approach intent={} carrier={} body={} stage=body_repositioned at={} final={} ticket={}",
                        intent, carrier, request.body().getUUID(), transfer.to().key(), request.to().key(), ticket);
            }
            else {
                failures.clear(); exhausted = false;
                GameplayLog.event(request.body(), "braid", "done intent={} carrier={} body={} from={} to={} ticket={} outcome=OK",
                        intent, carrier, request.body().getUUID(), transfer.from().key(), transfer.to().key(), ticket);
                request.body().family().crew().braidSession(null);
            }
        }
    }

    public static Session begin(CrewTransfer.Request request) {
        var port = request.body().family().crew();
        var session = port.braidSession();
        if (session == null || !session.matches(request)) { session = new Session(request); port.braidSession(session); }
        return session;
    }
    public static void approach(YukkuriEntity body, YukkuriEntity carrier, Vec3 stand, float yaw, String reason) {
        var session = body.family().crew().braidSession();
        GameplayLog.limited(body, "braid", "approach." + reason, 100,
                "approach intent={} carrier={} body={} stand={} yawError={} reason={}", session == null ? "unknown" : session.intent(),
                carrier.getUUID(), body.getUUID(), stand, Mth.wrapDegrees(yaw - carrier.yBodyRot), reason);
    }
    public static void finished(YukkuriEntity body, YukkuriEntity carrier, CrewTransfer ticket, CrewPort.Outcome outcome) {
        var session = body.family().crew().braidSession();
        if (session != null) session.ended(ticket, outcome);
    }
    public static void stalled(YukkuriEntity body, YukkuriEntity carrier, Vec3 stand, float yaw, String phase) {
        GameplayLog.limited(body, "braid", "stall." + phase, 200,
                "stall carrier={} body={} phase={} stand={} yawError={}", carrier == null ? null : carrier.getUUID(), body.getUUID(), phase,
                stand, carrier == null ? 0 : Mth.wrapDegrees(yaw - carrier.yBodyRot));
    }
    private static Intent intent(CrewTransfer.Request request) {
        if (request.rescue()) return Intent.RESCUE;
        if (request.to().kind() == CrewTransfer.Kind.GROUND) {
            if (request.body().getVehicle() instanceof HatBoatEntity hat && hat.catches()) return Intent.TAKE_DOWN_FROM_HAT;
            return Intent.LAND;
        }
        return request.from().boat() >= 0 && request.from().boat() == request.to().boat() ? Intent.CARRY_WITHIN : Intent.BOARD;
    }
    private static Outcome classify(CrewTransfer.Refusal refusal, String reason) {
        if (reason.equals("same_anchor") || reason.contains("stalled")) return Outcome.NO_PROGRESS;
        if (refusal == null) return Outcome.OK;
        return switch (refusal) {
            case GEOMETRY -> Outcome.GEOMETRY;
            case INCAPABLE -> Outcome.INCAPABLE;
            case TRANSIENT, SLIP -> Outcome.TRANSIENT;
        };
    }
    private static CrewTransfer.Result refused(CrewTransfer.Refusal refusal, String reason) {
        return new CrewTransfer.Result(null, refusal, reason);
    }
    private BraidCarry() {}
}
