package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.Ousting;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one way a driven-out individual leaves: it stares at whoever drove it, pleads or protests by its own character (a pleader steps
 * up to the driver, which turns it away), turns and walks off with looks back the more attached it is, and is done once it stands
 * dry and beyond the range it was driven out of. The mould exile ({@code settlement/MoldExile}) and the overload severance
 * ({@code family/DrivenOut}) both run this; each decides, from its own facts, when the walk counts as a departure.
 */
public final class Departure {
    public enum Status { RUNNING, OUT, FAILED }

    /**
     * What one departure is from. {@code about} is whom its hurt is about, {@code power} how hard it hits; {@code repeat} marks a second
     * leave-taking (it neither pleads nor protests again); {@code approach} lets a pleader step up to the driver; {@code resistLine} is
     * what a protester says (none for a driven child); {@code onTurn} is told the answer when it turns to go.
     */
    public record Terms(String category, String label, Vec3 centre, int radius, YukkuriEntity driver, UUID about, float power,
                        boolean repeat, boolean byGuardian, boolean approach, String resistLine, Consumer<Answer> onTurn) {}

    /** Ticks it keeps walking beyond the range once a caller extends it; ticks a turned-away pleader still stands after a rebuff. */
    private static final int EXIT_CLEARANCE = 1, REBUFF_HOLD = 30, APPROACH_AFTER = 10, REBUFF_WAIT = 40;
    /** A pleader keeps closing in on its driver while it gains {@link #PROGRESS} blocks (nearer to her, or further along its route to her, which
     *  leads away from her first round a body or a corner) within {@link #APPROACH_STALL} ticks (a young one
     *  crawls about 0.4 blocks a second) and for {@link #APPROACH_CAP} ticks in all (60 s: the 16 blocks she watches it from); then it gives up and turns to go. */
    private static final int APPROACH_STALL = 60, APPROACH_CAP = 1200;
    /** Ticks its turning-away face is kept before a look back may replace it; blocks it must walk before looking back. */
    private static final int TURN_SHOW = 40, WALK_FACE = 40;
    private static final double LOOK_BACK_WALK = 2.0;
    /** Speech priority for the turning-away line so it is not muted by the plea that just ended. */
    private static final int SAY_PRIORITY = 4;
    /** A walk is judged by its progress, not a clock: a weak or grieving body crawls (a young one covers about 0.4 blocks a second), so it
     *  fails only after this long without gaining {@link #PROGRESS} blocks, or after {@link #WALK_CAP} ticks in all. */
    private static final int STALL = 800, WALK_CAP = 4000;
    private static final double PROGRESS = .25;
    private static final int[] EXIT_ANGLES = {0, 45, -45, 90, -90, 135, -135, 180};

    private final YukkuriEntity entity;
    private final BooleanSupplier myTurn;
    private Terms terms;
    private Vec3 destination, outward, turnPos, approachAt;
    private int ticks, nextExit, extra, answerTicks, lookBacks, lookBackTicks, atDriver, approachStall, stall, turnedAt = -TURN_SHOW;
    private double best, approachBest = Double.MAX_VALUE;
    private boolean resists, pleads, turned, rebuffed, walkNoted;
    private Answer answer;
    private String failure = "";

    /** {@code myTurn} says whether it may speak now (lines take turns); it is asked at the beats that speak. */
    public Departure(YukkuriEntity entity, BooleanSupplier myTurn) {
        this.entity = entity;
        this.myTurn = myTurn;
    }

    /** Decides the beats of a fresh departure from the individual's own character. */
    public void begin(Terms terms) {
        this.terms = terms;
        var t = entity.life().personality().temperament();
        ticks = nextExit = extra = lookBackTicks = atDriver = approachStall = stall = 0;
        best = 0;
        approachBest = Double.MAX_VALUE;
        approachAt = null;
        turned = rebuffed = walkNoted = false;
        turnedAt = -TURN_SHOW;
        failure = "";
        resists = !terms.repeat() && t.is(Gate.PROTESTS_EXILE);
        // Whether it begs is its own character: attached or fond of the one driving it, and not too proud or selfish to ask.
        pleads = !resists && terms.driver() != null && !t.is(Gate.HARSH) && (t.is(Gate.ATTACHED)
                || YukkuriWorldData.get((net.minecraft.server.level.ServerLevel) entity.level())
                        .affinity(entity.getUUID(), terms.driver().getUUID()) >= BondLevels.AFFINITY_CLOSE);
        answer = Ousting.answer(t);
        answerTicks = resists ? 50 + t.boldness() / 3
                : pleads ? 50 + t.attachment() / 2
                : !terms.repeat() && answer != Answer.SHRUG ? 40 + (100 - t.boldness()) / 3 : 0;
        lookBacks = answer == Answer.FAREWELL ? t.is(Gate.DEVOTED_KIN) ? 2 : 1 : 0;
        if (terms.repeat() && terms.driver() == null) {
            ticks = answerTicks + 1;
            lookBacks = 0;
        }
        aim();
    }

    /** A departure cut short goes on where it stopped: a plea and a turn already made are not repeated. */
    public void resume(Terms terms) {
        this.terms = terms;
        ticks = turned ? answerTicks : 0;
        turnedAt = -TURN_SHOW;
        nextExit = lookBackTicks = atDriver = approachStall = stall = 0;
        best = 0;
        approachBest = Double.MAX_VALUE;
        approachAt = null;
        destination = null;
        aim();
    }

    private void aim() {
        var centre = terms.centre();
        outward = entity.position().subtract(centre).multiply(1, 0, 1).normalize();
        if (outward.lengthSqr() == 0 && terms.driver() != null)
            outward = entity.position().subtract(terms.driver().position()).multiply(1, 0, 1).normalize();
        if (outward.lengthSqr() == 0) outward = new Vec3(0, 0, 1);
    }

    public boolean turned() { return turned; }
    public boolean pleads() { return pleads; }
    public boolean resists() { return resists; }
    public Answer answer() { return answer; }
    public int lookBacks() { return lookBacks; }
    public String failure() { return failure; }
    /** The pleader has stepped up to its driver and stands within her reach. */
    public boolean atDriver() { return atDriver > 0; }
    /** Whether it is still in the hold of its plea or protest, before it turns to go. */
    public boolean holding() { return ticks <= answerTicks && !turned; }

    /** The driver turned a pleader away: its plea is over after a short moment, and it turns to go. */
    public void rebuff() {
        rebuffed = true;
        answerTicks = Math.min(answerTicks, ticks + REBUFF_HOLD);
    }

    /** It is not out yet: walks on for {@code blocks} more beyond the range. */
    public void extend(int blocks) {
        extra += blocks;
        destination = null;
        nextExit = 0;
    }

    /** Whether it has been extended past the range it was driven out of (it keeps walking after the departure is settled). */
    public boolean extended() { return extra > 0; }

    /** The face it wears on the walk out, by how it took the order: the clip's own happy faces never show over a driven one. */
    public String walkFace() {
        if (!turned || lookBackTicks > 0 || ticks <= answerTicks) return "";
        return switch (answer) {
            case CURSE -> "angry";
            case FAREWELL -> "teary";
            case SHRUG -> "hmph";
        };
    }

    public String activity() {
        return ticks <= answerTicks ? resists ? Behaviors.MOLD_RESIST : Behaviors.MOLD_PLEAD
                : answer != Answer.FAREWELL ? null : lookBackTicks > 0 ? Behaviors.MOLD_LOOK_BACK : Behaviors.MOLD_LEAVE;
    }

    private int clearRange() { return terms.radius() + EXIT_CLEARANCE + extra; }

    public Status tick() {
        ticks++;
        var driver = terms.driver();
        // A begging or protesting one starts at once, to the face of the one driving it; one that neither begs nor protests just turns to go.
        if (extra == 0 && (ticks == 1 && answerTicks > 0 && !terms.repeat() || ticks == answerTicks + 1) && !myTurn.getAsBoolean()) {
            ticks--;
            return Status.RUNNING;
        }
        if (ticks <= answerTicks) {
            hold(driver);
            return Status.RUNNING;
        }
        if (ticks == answerTicks + 1 && !turned) turnAway();
        if (entity.reactionAction() == YukkuriAction.STAMP) return Status.RUNNING;
        if (lookBackTicks > 0) {
            SurfaceApproach.stopWalking(entity);
            SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), terms.centre()));
            lookBackTicks--;
            return Status.RUNNING;
        }
        // Looks back at about half way and near the edge of the range (attached ones), the goodbye at the last, once it has actually walked.
        double out = Math.sqrt(entity.position().subtract(terms.centre()).horizontalDistanceSqr()) / terms.radius();
        boolean walked = turnPos == null || entity.position().subtract(turnPos).horizontalDistanceSqr() >= LOOK_BACK_WALK * LOOK_BACK_WALK;
        if (lookBacks > 0 && walked && ticks - turnedAt >= TURN_SHOW && out >= (lookBacks == 2 ? .45 : .8)) {
            lookBacks--;
            lookBackTicks = 40 + entity.life().personality().attachment() / 4;
            SurfaceApproach.stopWalking(entity);
            String line = lookBacks == 0 ? "mold_leave" : "";
            entity.showReaction("teary", line, lookBackTicks);
            entity.beat("look_back", "teary", line);
            GameplayLog.event(entity, terms.category(), "{} looked back left={} out={}", terms.label(), lookBacks, Math.round(out * 100) / 100.0);
            return Status.RUNNING;
        }
        // The walk's face is a held reaction (above the running course and every activity face), renewed as it lapses.
        String face = walkFace();
        if (!face.isEmpty() && !entity.hasReaction() && entity.showReaction(face, "", WALK_FACE) && !walkNoted) {
            walkNoted = true;
            entity.beat("walk_out", face, "");
        }
        int clear = clearRange();
        if (entity.position().subtract(terms.centre()).horizontalDistanceSqr() >= clear * clear
                && entity.onGround() && SurfaceApproach.stands(entity, entity.position())) return Status.OUT;
        double reached = entity.position().subtract(terms.centre()).horizontalDistance();
        if (reached > best + PROGRESS) {
            best = reached;
            stall = 0;
        } else if (++stall >= STALL) return fail("stalled");
        if (ticks >= WALK_CAP) return fail("walk_limit");
        if (destination == null && !nextRetreat()) return fail("no_dry_exit");
        var outcome = SurfaceApproach.walkResult(entity, destination, null, 1);
        if (outcome == SurfaceApproach.Result.BLOCKED || outcome == SurfaceApproach.Result.ARRIVED) destination = null;
        return Status.RUNNING;
    }

    private Status fail(String reason) {
        failure = reason;
        return Status.FAILED;
    }

    private void hold(YukkuriEntity driver) {
        if (driver != null) entity.getLookControl().setLookAt(driver, 30, 30);
        if (ticks == 1 && !terms.repeat()) {
            String line = resists ? terms.resistLine() == null ? "" : terms.resistLine() : pleads ? "mold_plea" : "";
            String face = resists ? "angry" : "teary";
            entity.showReaction(face, line, Math.max(answerTicks, 1));
            entity.beat(resists ? "resist" : pleads ? "plead" : "stare", face, line);
        }
        if (pleads && terms.approach() && driver != null && !rebuffed && ticks >= APPROACH_AFTER) stepUp(driver);
    }

    /** A pleader goes up to the one driving it, as close as her mouth reaches, and stays there until she answers. */
    private void stepUp(YukkuriEntity driver) {
        if (ContactGeometry.inMouthReach(driver, entity)) {
            SurfaceApproach.stopWalking(entity);
            if (++atDriver == 1) GameplayLog.event(entity, terms.category(), "{} pleader at parent driver={} distance={}", terms.label(),
                    driver.getUUID(), Math.round(entity.distanceTo(driver) * 100) / 100.0);
            if (atDriver <= REBUFF_WAIT && ticks >= answerTicks) answerTicks = ticks + 1;
            return;
        }
        atDriver = 0;
        double distance = entity.distanceTo(driver);
        // Gaining ground is closing the gap or walking the route to her: the way round a body or the bank's corner leads away first.
        if (distance < approachBest - PROGRESS || approachAt == null || entity.position().distanceToSqr(approachAt) >= PROGRESS * PROGRESS) {
            approachBest = Math.min(approachBest, distance);
            approachAt = entity.position();
            approachStall = 0;
        } else if (++approachStall > APPROACH_STALL) {
            if (approachStall == APPROACH_STALL + 1) GameplayLog.event(entity, terms.category(), "{} pleader gave up short of parent driver={} distance={}",
                    terms.label(), driver.getUUID(), Math.round(distance * 100) / 100.0);
            return;
        }
        double spacing = (entity.getBbWidth() + driver.getBbWidth()) * .5 + .1;
        var stand = SurfaceApproach.beside(entity, driver, spacing, ContactGeometry.GRAB_HEIGHT, Double.POSITIVE_INFINITY);
        if (stand != null) {
            SurfaceApproach.walkToContact(entity, stand, driver, 1);
            if (ticks < APPROACH_CAP && ticks >= answerTicks) answerTicks = ticks + 1;
        }
    }

    /** It turns to go: the hurt of it, by character, and whatever the caller does with the answer (an onlooker's cry). */
    private void turnAway() {
        turned = true;
        turnedAt = ticks;
        turnPos = entity.position();
        SurfaceApproach.stopWalking(entity);
        entity.mind().felt(EmotionEvent.SOCIAL_HURT);
        String face = switch (answer) {
            case CURSE -> "angry";
            case FAREWELL -> "cry";
            case SHRUG -> "hmph";
        };
        String line = ReactionSpeech.ousted(answer, terms.byGuardian());
        boolean shown = switch (answer) {
            case CURSE -> entity.showReaction(YukkuriAction.STAMP, "angry", "", 60);
            case FAREWELL -> entity.showReaction("cry", "", 40);
            case SHRUG -> entity.showReaction("hmph", "", 40);
        };
        boolean spoken = !line.isEmpty() && shown && entity.speakCue(line, null, SAY_PRIORITY);
        entity.beat("turn_away", face, spoken ? line : "");
        GameplayLog.event(entity, terms.category(), "{} answer={} fumes={}", terms.label(), answer, answer == Answer.CURSE);
        terms.onTurn().accept(answer);
    }

    /** After it has truly left sight, the grief or anger course begins (never before, so it neither pins nor slows the walk out). */
    public void feltLoss() {
        if (terms != null) entity.mind().feeling().wronged(terms.about(), terms.power(), BlockPos.containing(terms.centre()));
    }

    /**
     * A finished departure, the one place it is written: the tie is severed both ways ({@link Bonds#sever}: she does not take it back, it
     * no longer expects her, it is a stray), and a tearful parent is left with the parting feeling, which watches the road it took.
     */
    public static void parted(YukkuriEntity parent, YukkuriEntity child, Verdict verdict, String reason) {
        parted((ServerLevel) parent.level(), parent.getUUID(), parent, child, verdict, reason);
    }

    /** As {@link #parted(YukkuriEntity, YukkuriEntity, Verdict, String)}, still cutting the bond when the parent is unloaded or dead. */
    public static void parted(ServerLevel level, UUID parentId, YukkuriEntity parent, YukkuriEntity child, Verdict verdict, String reason) {
        if (parentId != null) Bonds.sever(level, parentId, parent, child, reason);
        if (parent == null || verdict != Verdict.DRIVE_TEARFUL) return;
        parent.mind().feeling().begin(EmotionCourse.Kind.PARTING, .5f, child.getUUID(), child.blockPosition());
        parting(parent);
    }

    /** A home range this individual was driven out of and must not claim, nest in, or stroll back into. */
    public record BannedRange(GlobalPos centre, int radius) {
        public boolean allows(Level level, BlockPos pos) {
            if (!centre.dimension().equals(level.dimension())) return true;
            return Vec3.atBottomCenterOf(pos).subtract(Vec3.atBottomCenterOf(centre.pos())).horizontalDistanceSqr() > (double) radius * radius;
        }

        public void save(CompoundTag tag, String key) {
            var nbt = new CompoundTag();
            FamilyData.putPosition(nbt, "Centre", centre);
            nbt.putInt("Radius", radius);
            tag.put(key, nbt);
        }

        public static BannedRange load(CompoundTag tag, String key) {
            if (!tag.contains(key)) return null;
            var nbt = tag.getCompound(key);
            var pos = FamilyData.position(nbt, "Centre");
            return pos == null ? null : new BannedRange(pos, Math.max(1, nbt.getInt("Radius")));
        }
    }

    /** One choke point for every home, roof, nest, shelter, and mourn-stroll check: neither a mould exile nor a severed child settles back in its old range. */
    public static boolean allowsAddress(YukkuriEntity entity, BlockPos pos) {
        return entity.moldExile().allowsAddress(pos) && entity.drivenOut().allowsAddress(pos);
    }

    /** The one who stays shows the parting face at once (over whatever a passing reaction still wears) and the beat records what is shown. */
    public static void parting(YukkuriEntity stays) {
        String face = EmotionCourse.onsetFace(EmotionCourse.Kind.PARTING);
        stays.showReaction(face, "", 60);
        stays.beat("parting_begins", face, "");
    }

    private boolean nextRetreat() {
        while (nextExit < EXIT_ANGLES.length) {
            float turn = (float) Math.toRadians(EXIT_ANGLES[nextExit++]);
            var aim = terms.centre().add(outward.yRot(turn).scale(clearRange() + 1));
            if (!entity.level().hasChunkAt(BlockPos.containing(aim))) continue;
            var stand = SurfaceApproach.floor(entity, aim);
            int clear = clearRange();
            if (stand == null || stand.subtract(terms.centre()).horizontalDistanceSqr() < clear * clear
                    || !SurfaceApproach.hasDryBankBuffer(entity, stand, entity.getMaxFallDistance())
                    || !SurfaceApproach.canApproach(entity, stand)) continue;
            destination = stand;
            return true;
        }
        return false;
    }

}
