package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
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
     * leave-taking (it neither pleads nor protests again); {@code severance} is the overload severance's own kind (a parent driving her child
     * out), which adds its beats and keeps an aloof child's sulk; {@code approach} lets a pleader step up to the driver; {@code resistLine}
     * is what a protester says (none for a driven child); {@code onTurn} is told the answer when it turns to go.
     */
    public record Terms(String category, String label, Vec3 centre, int radius, YukkuriEntity driver, UUID about, float power,
                        boolean repeat, boolean byGuardian, boolean severance, boolean approach, String resistLine, Consumer<Answer> onTurn) {}

    /** Ticks it keeps walking beyond the range once a caller extends it; ticks a turned-away pleader still stands after a rebuff. */
    private static final int EXIT_CLEARANCE = 1, REBUFF_HOLD = 30, APPROACH_AFTER = 10, REBUFF_WAIT = 40;
    /** A pleader keeps closing in on its driver while it gains {@link #PROGRESS} blocks (nearer to her, or further along its route to her, which
     *  leads away from her first round a body or a corner) within {@link #APPROACH_STALL} ticks (a young one
     *  crawls about 0.4 blocks a second) and for {@link #APPROACH_CAP} ticks in all (60 s: the 16 blocks she watches it from); then it gives up and turns to go. */
    private static final int APPROACH_STALL = 60, APPROACH_CAP = 1200;
    /** The temper a shove adds (a pleader with boldness 45 and self-control 70 sits at .23 and flares at .5; one with boldness 30 never does). */
    private static final double SHOVE_ANGER = .3;
    /** Ticks its turning-away face is kept before a look back or mid-walk mutter may replace it; blocks it must walk before either. */
    private static final int TURN_SHOW = 40, SHOCK_HOLD = 45, FLICK_PAUSE = 24;
    /** The cold sulker's hmph is renewed in this long a hold across the walk, so it never drops to a plain face before its mutter. */
    private static final int SULK_FACE_HOLD = 60;
    private static final double LOOK_BACK_WALK = 2.0;
    /** Speech priority for severance/departure beats so a line is not muted by the beat that just ended. */
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
    private int ticks, nextExit, extra, shockTicks, answerTicks, lookBacks, lookBackTicks, atDriver, approachStall, stall, turnedAt = -TURN_SHOW;
    private double best, approachBest = Double.MAX_VALUE;
    private boolean resists, pleads, turned, rebuffed, listened, muttered;
    private Answer answer;
    private String failure = "";

    /** Lines take turns among neighbours: a beat that speaks waits while anyone within {@code radius} is mid-line, at most {@code cap} asks. */
    public static final class Turn implements BooleanSupplier {
        private final YukkuriEntity entity;
        private final int radius, cap;
        private int waited;

        public Turn(YukkuriEntity entity, int radius, int cap) {
            this.entity = entity;
            this.radius = radius;
            this.cap = cap;
        }

        @Override public boolean getAsBoolean() {
            long now = entity.level().getGameTime();
            if (waited < cap && !entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(radius),
                    y -> y != entity && y.isAlive() && y.speechEnd() > now).isEmpty()) {
                waited++;
                return false;
            }
            waited = 0;
            return true;
        }
    }

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
        turned = rebuffed = listened = muttered = false;
        turnedAt = -TURN_SHOW;
        failure = "";
        resists = !terms.repeat() && t.is(Gate.PROTESTS_EXILE);
        // Whether it begs is its own character: attached or fond of the one driving it, and not too proud or selfish to ask.
        pleads = !resists && terms.driver() != null && !t.is(Gate.HARSH) && (t.is(Gate.ATTACHED)
                || YukkuriWorldData.get((net.minecraft.server.level.ServerLevel) entity.level())
                        .affinity(entity.getUUID(), terms.driver().getUUID()) >= BondLevels.AFFINITY_CLOSE);
        answer = Ousting.answer(t);
        boolean severedFresh = terms.severance() && !terms.repeat();
        shockTicks = severedFresh && !resists && answer != Answer.SHRUG ? SHOCK_HOLD : 0;
        answerTicks = resists ? 50 + t.boldness() / 3
                : pleads ? shockTicks + 50 + t.attachment() / 2
                : severedFresh && answer == Answer.SHRUG ? 45 + (100 - t.attachment()) / 3
                : !terms.repeat() && answer != Answer.SHRUG ? shockTicks + 40 + (100 - t.boldness()) / 3 : 0;
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
        listened = false;
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

    /** The driver turned a pleader away: its plea is over after a short moment, and it turns to go. A shove is a hurt of its own: how
     *  it answers then is settled again with {@link #SHOVE_ANGER} added to its temper (a bold or quick-tempered pleader flares up, a meek
     *  or self-controlled one still cries or shrugs). */
    public void rebuff(boolean shoved) {
        rebuffed = true;
        answerTicks = Math.min(answerTicks, ticks + REBUFF_HOLD);
        if (!shoved) return;
        answer = Ousting.answer(entity.life().personality().temperament(), SHOVE_ANGER);
        if (answer != Answer.FAREWELL) lookBacks = 0;
    }

    /** It is not out yet: walks on for {@code blocks} more beyond the range. */
    public void extend(int blocks) {
        extra += blocks;
        destination = null;
        nextExit = 0;
    }

    /** Whether it has been extended past the range it was driven out of (it keeps walking after the departure is settled). */
    public boolean extended() { return extra > 0; }

    public String activity() {
        if (ticks <= answerTicks) {
            if (resists) return Behaviors.MOLD_RESIST;
            if (shockTicks > 0 && ticks <= shockTicks) return null;
            if (answer == Answer.SHRUG) return null;
            return pleads || !terms.severance() ? Behaviors.MOLD_PLEAD : null;
        }
        return answer != Answer.FAREWELL ? null : lookBackTicks > 0 ? Behaviors.MOLD_LOOK_BACK : Behaviors.MOLD_LEAVE;
    }

    private int clearRange() { return terms.radius() + EXIT_CLEARANCE + extra; }

    public Status tick() {
        ticks++;
        var driver = terms.driver();
        // A begging, shocked, scoffing or protesting one starts at once, to the face of the one driving it; non-guardian shrugs just turn to go.
        // Each beat that speaks waits for its own last line (and the gap after it) and for its neighbours' turn.
        boolean speakingBeat = extra == 0 && (ticks == 1 && answerTicks > 0 && !terms.repeat()
                || shockTicks > 0 && ticks == shockTicks + 1 && !terms.repeat()
                || ticks == answerTicks + 1);
        if (speakingBeat && !(entity.quiet() && myTurn.getAsBoolean())) {
            if (driver != null && ticks <= answerTicks) entity.getLookControl().setLookAt(driver, 30, 30);
            if (!listened && ticks == 1 && terms.severance() && !terms.repeat()) {
                listened = true;
                String face = SeveranceReaction.childListenFace(entity.life().personality().temperament(), resists, answer);
                entity.forceReaction(face, "", FaceTier.Tier.MEDIUM);
            }
            ticks--;
            return Status.RUNNING;
        }
        if (ticks <= answerTicks) {
            hold(driver);
            return Status.RUNNING;
        }
        if (ticks == answerTicks + 1 && !turned) turnAway();
        if (entity.reactionAction() == YukkuriAction.STAMP
                || entity.reactionAction() == YukkuriAction.MOOD_FLICK && ticks - turnedAt < FLICK_PAUSE) return Status.RUNNING;
        // A severed aloof child keeps its sulk the whole walk: the turn's hold is topped up as it lapses, so the cold face cannot drop to a
        // plain one between the turn and the mid-walk mutter (a running clip or another beat's hold refuses the top-up).
        if (terms.severance() && !terms.repeat() && answer == Answer.SHRUG && turned && entity.reactionAction() == null)
            entity.holdReaction("hmph", "", SULK_FACE_HOLD);
        if (lookBackTicks > 0) {
            SurfaceApproach.stopWalking(entity);
            SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), terms.centre()));
            lookBackTicks--;
            return Status.RUNNING;
        }
        // Looks back at about half way and near the edge of the range (attached ones), the goodbye at the last, once it has actually walked.
        double out = Math.sqrt(entity.position().subtract(terms.centre()).horizontalDistanceSqr()) / terms.radius();
        boolean walked = turnPos == null || entity.position().subtract(turnPos).horizontalDistanceSqr() >= LOOK_BACK_WALK * LOOK_BACK_WALK;
        if (lookBacks > 0 && walked && ticks - turnedAt >= TURN_SHOW && out >= (lookBacks == 2 ? .45 : .8) && entity.quiet()) {
            lookBacks--;
            lookBackTicks = 40 + entity.life().personality().attachment() / 4;
            SurfaceApproach.stopWalking(entity);
            String line = lookBacks == 0 ? (terms.severance() ? "sever_look_back" : "mold_leave") : "";
            String style = terms.severance() ? SeveranceReaction.speechStyle(entity.life().personality().temperament()) : null;
            // The look back keeps the walk's tearful face (a long hold: the turn's own face must not drop to the default mid-walk).
            entity.forceReaction("teary", "", FaceTier.Tier.LONG);
            boolean spoken = !line.isEmpty() && entity.speakCue(line, style, SAY_PRIORITY);
            entity.beat("look_back", "teary", spoken ? line : "");
            GameplayLog.event(entity, terms.category(), "{} looked back left={} out={}", terms.label(), lookBacks, Math.round(out * 100) / 100.0);
            return Status.RUNNING;
        }
        // An aloof severed child never looks back, but mutters once mid-walk so its sulk carries through the whole departure without tears.
        if (terms.severance() && !terms.repeat() && answer == Answer.SHRUG && !muttered && walked && ticks - turnedAt >= TURN_SHOW
                && out >= .55 && entity.quiet()) {
            muttered = true;
            String style = SeveranceReaction.speechStyle(entity.life().personality().temperament());
            entity.forceReaction("hmph", "", FaceTier.Tier.LONG);
            boolean spoken = entity.speakCue("sever_cold_mutter", style, SAY_PRIORITY);
            entity.beat("sulk_mutter", "hmph", spoken ? "sever_cold_mutter" : "");
            GameplayLog.event(entity, terms.category(), "{} sulk muttered out={}", terms.label(), Math.round(out * 100) / 100.0);
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
        var t = entity.life().personality().temperament();
        if (terms.severance() && !terms.repeat()) {
            if (ticks == 1) {
                var open = SeveranceReaction.childOpening(t, resists, pleads, answer);
                String line = resists && terms.resistLine() != null ? terms.resistLine() : open.line();
                FaceTier.Tier tier = answer == Answer.SHRUG ? FaceTier.Tier.LONG : FaceTier.Tier.MEDIUM;
                playBeat(open.beat(), open.clip(), open.face(), line, tier, SeveranceReaction.speechStyle(t));
            } else if (shockTicks > 0 && ticks == shockTicks + 1) {
                var follow = SeveranceReaction.childFollow(t, pleads);
                playBeat(follow.beat(), follow.clip(), follow.face(), follow.line(), FaceTier.Tier.MEDIUM, SeveranceReaction.speechStyle(t));
            }
        } else if (ticks == 1 && !terms.repeat()) {
            String line = resists ? terms.resistLine() == null ? "" : terms.resistLine() : pleads ? "mold_plea" : "";
            String face = resists ? "angry" : "teary";
            // One medium beat; a line longer than the beat keeps the face to its end, and the turn waits for the line (see tick).
            if (entity.showReaction(face, line, FaceTier.Tier.MEDIUM)) entity.beat(resists ? "resist" : pleads ? "plead" : "stare", face, line);
        }
        if (pleads && terms.approach() && driver != null && !rebuffed && ticks >= shockTicks + APPROACH_AFTER) stepUp(driver);
    }

    private void playBeat(String beat, YukkuriAction clip, String face, String line, FaceTier.Tier tier, String style) {
        if (clip != null) entity.forceReaction(clip, face, "", tier);
        else entity.forceReaction(face, "", tier);
        boolean spoken = !line.isEmpty() && entity.speakCue(line, style, SAY_PRIORITY);
        entity.beat(beat, face, spoken ? line : "");
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
        String style = terms.severance() ? SeveranceReaction.speechStyle(entity.life().personality().temperament()) : null;
        // The turn's face is one forced hold for the whole walk out (calming after it), not a renewal loop: the performance's
        // own beat is not refused by a reaction of another writer.
        switch (answer) {
            case CURSE -> entity.forceReaction(YukkuriAction.STAMP, "angry", "", FaceTier.Tier.LONG);
            case FAREWELL -> entity.forceReaction("cry", "", FaceTier.Tier.LONG);
            case SHRUG -> {
                if (terms.severance()) entity.forceReaction(YukkuriAction.MOOD_FLICK, "hmph", "", FaceTier.Tier.LONG);
                else entity.forceReaction("hmph", "", FaceTier.Tier.MEDIUM);
            }
        }
        boolean spoken = !line.isEmpty() && entity.speakCue(line, style, SAY_PRIORITY);
        entity.beat("turn_away", face, spoken ? line : "");
        // The walk out starts on this one hold (no renewal loop): log the beat the scene checks for its face.
        entity.beat("walk_out", face, "");
        GameplayLog.event(entity, terms.category(), "{} answer={} fumes={}", terms.label(), answer, answer == Answer.CURSE);
        terms.onTurn().accept(answer);
    }

    /** After it has truly left sight, the grief or anger course begins (never before, so it neither pins nor slows the walk out).
     *  In the overload severance an aloof sulker ({@link Answer#SHRUG}) keeps a cool hmph hold and never breaks into tears, and one that
     *  cursed keeps its anger; a mould exile takes the old course whatever its answer. */
    public void feltLoss() {
        if (terms == null) return;
        var at = BlockPos.containing(terms.centre());
        if (terms.severance() && answer == Answer.SHRUG) {
            entity.forceReaction("hmph", "", FaceTier.Tier.LONG);
            return;
        }
        if (terms.severance() && answer == Answer.CURSE) {
            entity.mind().feeling().begin(EmotionCourse.Kind.RAGE, terms.power(), terms.about(), at);
            return;
        }
        entity.mind().feeling().wronged(terms.about(), terms.power(), at);
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
        partingCourse(parent, child);
        parting(parent);
    }

    /** The tearful parent's parting feeling: begun when she gives the order (her face never falls to the default during the watch)
     *  and renewed when the child is truly gone. */
    public static void partingCourse(YukkuriEntity parent, YukkuriEntity child) {
        parent.mind().feeling().begin(EmotionCourse.Kind.PARTING, .5f, child.getUUID(), child.blockPosition());
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
        stays.forceReaction(face, "", FaceTier.Tier.MEDIUM);
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
