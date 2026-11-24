package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.GriefVow.Lost;
import io.github.longshou.mcyukkuri.social.Departure;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.PunishmentReaction;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * The course of one strong feeling after the moment that set it off (docs/FEATURES.md 情绪过渡). The trigger's own reaction is
 * the onset (the bereaved stare, the flight, the puff); then a peak, an easing and a fading stage, each visible in face, idle
 * pose, pace, speech, appetite and what the individual still feels like doing. Its length is a range from the strength of the
 * cause, shifted by the individual's values; kin who nuzzle it and a night's sleep carry it through sooner. One course at a
 * time: a stronger kind replaces a weaker one, and a fading course gives way to any new one.
 * <p>Shared entries: {@link #mourn} (a death or a loss of kin), {@link #wronged} (something taken by force: anger or sorrow by
 * character), {@link #frightened} (the course that starts with a real danger), {@link #begin} for the rest.
 */
public final class EmotionCourse {
    public static final int PARTING_PRIORITY = 8;

    public enum Kind { GRIEF, TERROR, RAGE, JOY, PARTING }
    public enum Stage {
        NONE, ONSET, PEAK, EASING, FADING;

        static Stage at(int elapsed, int length) {
            if (elapsed < ONSET_TICKS) return ONSET;
            float fraction = (elapsed - ONSET_TICKS) / (float)(length - ONSET_TICKS);
            return fraction < PEAK_END ? PEAK : fraction < EASING_END ? EASING : FADING;
        }

        Stage presentation() { return this == ONSET ? PEAK : this; }

        String face(Kind kind) {
            return switch (this) {
                case ONSET, PEAK -> switch (kind) {
                    case GRIEF -> "teary";
                    case TERROR -> "horrified";
                    case RAGE -> "angry";
                    case JOY -> "happy";
                    case PARTING -> "teary_smile";
                };
                case EASING -> switch (kind) {
                    case GRIEF -> "side_teary";
                    case TERROR -> "teary";
                    case RAGE -> "down_angry";
                    case JOY, PARTING -> "closed_smile";
                };
                case FADING -> switch (kind) {
                    case GRIEF -> "pursed";
                    case TERROR -> "confused_small";
                    case RAGE -> "hmph";
                    case JOY, PARTING -> null;
                };
                case NONE -> null;
            };
        }
    }
    /** The opening beat of the clock. The peak look can show as soon as the trigger reaction ends. */
    static final int ONSET_TICKS = 100;
    private static final float PEAK_END = .25f, EASING_END = .65f;
    // The first easing line can become a vow; the next line is already outside its window.
    private static final int VOW_WINDOW = 600;

    private final YukkuriEntity entity;
    private Kind kind;
    private float strength;
    private int length, elapsed;
    /** Whom or what it is about, and where it happened; both optional. */
    private UUID about;
    private BlockPos where;
    private final LineClock lineClock = new LineClock();
    private final RecallClock recallClock = new RecallClock();
    private boolean running;
    private String publishedActivity;
    private Stage publishedStage;
    private GriefFocus griefFocus;

    EmotionCourse(YukkuriEntity entity) { this.entity = entity; }

    // ---- reads -------------------------------------------------------------------------------------------------------------------
    public Kind kind() { return kind; }
    public Stage stage() { return kind == null ? Stage.NONE : Stage.at(elapsed, length); }
    public UUID about() { return about; }
    public BlockPos where() { return where; }
    public boolean mourning() { return kind == Kind.GRIEF && stage() != Stage.FADING && stage() != Stage.NONE; }
    /** Grief still at its onset or peak: too stricken to go and plead or to start anything of its own. */
    public boolean stricken() { return kind == Kind.GRIEF && (stage() == Stage.ONSET || stage() == Stage.PEAK); }
    public String describe() {
        if (kind == null) return "none";
        return kind.name().toLowerCase(Locale.ROOT) + " stage=" + stage().name().toLowerCase(Locale.ROOT) + " strength="
                + Math.round(strength * 100) / 100f + " elapsed=" + elapsed + "/" + length + " about=" + about;
    }

    /** Low spirits keep it from starting songs, games, group play and chatting: grief and fright until they ease, anger at its height. */
    public boolean subdued() {
        Stage s = stage();
        return kind != null && switch (kind) {
            case GRIEF, TERROR -> s == Stage.ONSET || s == Stage.PEAK || s == Stage.EASING;
            case RAGE -> s == Stage.ONSET || s == Stage.PEAK;
            case JOY, PARTING -> false;
        };
    }

    /** Nutrition points taken off the "wants a meal" line: grief and fright spoil the appetite, most at the peak. */
    public float appetiteLoss() {
        if (kind == null) return 0;
        Stage s = stage();
        return switch (kind) {
            case GRIEF -> s == Stage.ONSET || s == Stage.PEAK ? 35 : s == Stage.EASING ? 15 : 0;
            case TERROR -> s == Stage.ONSET || s == Stage.PEAK ? 25 : 0;
            default -> 0;
        };
    }

    /** Walk and hop pace factor (motor/BodyAbility temperament): a mourner drags itself along; the rest keep their pace. */
    public double pace() {
        if (kind != Kind.GRIEF) return 1;
        return switch (stage()) {
            case ONSET, PEAK -> .7;
            case EASING -> .82;
            case FADING -> .93;
            case NONE -> 1;
        };
    }

    /** The face it wears over ordinary behaviour, or null; meals keep their chewing faces, its own goal its presentation row. */
    public String face() {
        if (kind == null || running && goalPriority() != 0 || entity.life().mealTicks() > 0) return null;
        if (entity.life().isSleeping()) return mourning() && stage() != Stage.ONSET ? "tear_sleep" : null;
        return stageFace();
    }
    private String stageFace() { return stage().face(kind); }

    /** The pose it falls into when it has nothing else to do, or null. */
    public YukkuriAction idle() {
        Stage s = stage().presentation();
        if (kind == null || s == Stage.FADING) return null;
        boolean peak = s == Stage.PEAK;
        return switch (kind) {
            case GRIEF -> YukkuriAction.DROOP;
            case TERROR -> peak ? YukkuriAction.TREMBLE : YukkuriAction.NERVOUS;
            case RAGE -> YukkuriAction.SULK;
            case JOY -> peak ? YukkuriAction.SWAY : null;
            case PARTING -> null;
        };
    }

    /** The behaviour shown while its own goal runs. */
    public String activity() {
        Stage s = stage();
        if (!running || goalPriority() == 0 || s == Stage.ONSET && entity.hasReaction()) return null;
        boolean peak = s.presentation() == Stage.PEAK;
        return switch (kind) {
            case GRIEF -> peak ? Behaviors.MOURN : entity.hasReaction() ? null : Behaviors.MOURN_LISTLESS;
            case TERROR -> peak ? Behaviors.SHAKEN : Behaviors.SHAKEN_WARY;
            case RAGE -> Behaviors.FUME;
            case JOY -> null;
            case PARTING -> partedFromAbout() ? Behaviors.PARTING_GAZE : null;
        };
    }

    /** Whether the one this parting is about is truly gone: absent, far (>= 8 blocks), or out of sight. */
    private boolean partedFromAbout() { return partedFromAbout(8); }

    private boolean partedFromAbout(int distance) {
        if (about == null || !(entity.level() instanceof ServerLevel level)) return true;
        if (!(level.getEntity(about) instanceof YukkuriEntity other) || !other.isAlive()) return true;
        return entity.distanceToSqr(other) >= (double) distance * distance || !entity.hasLineOfSight(other);
    }

    /** Observes the selected presentation row after the arbiter publishes it. */
    public void observeActivity(String behavior) {
        Stage stage = stage();
        if (stage == publishedStage && Objects.equals(behavior, publishedActivity)) return;
        publishedStage = stage;
        publishedActivity = behavior;
        if (behavior == null) return;
        String phase = stage.name().toLowerCase(Locale.ROOT);
        GameplayLog.limited(entity, "emotion", "goal_" + phase + "_" + behavior, ONSET_TICKS,
                "course goal behavior kind={} stage={} behavior={}", () -> new Object[]{kind.name().toLowerCase(Locale.ROOT), phase, behavior});
    }

    /** Ordinary errands give way over the attached range. The length of that peak already grows with attachment. */
    public boolean prefersPartingGaze() {
        Stage stage = stage();
        return kind == Kind.PARTING && (stage == Stage.PEAK || stage == Stage.ONSET && !entity.hasReaction())
                && entity.life().personality().temperament().is(Gate.ATTACHED);
    }

    /** The selector tier for the current stage, or 0 when it owns no goal. */
    int goalPriority() {
        if (kind == null) return 0;
        Stage s = stage().presentation();
        return switch (kind) {
            case GRIEF, TERROR -> s == Stage.PEAK ? 3 : s == Stage.EASING ? 6 : 0;
            case RAGE -> s == Stage.PEAK ? 6 : 0;
            case PARTING -> s == Stage.PEAK ? PARTING_PRIORITY : 0;
            case JOY -> 0;
        };
    }

    // ---- entries -----------------------------------------------------------------------------------------------------------------
    /**
     * Grief for a lost one (death, a lost newborn, a found body, a keeper gone): how close it was decides the strength. Shared entry
     * for every death and loss path. {@code lost} and {@code where} may be null when unknown.
     */
    public boolean mourn(UUID lost, BlockPos at) { return begin(Kind.GRIEF, closeness(lost), lost, at); }

    /** Something taken or spoiled by another: a bold or quick-tempered one fumes, a meek or self-controlled one is hurt and sad. */
    public boolean wronged(UUID by, float power) {
        return wronged(by, power, entity.blockPosition());
    }

    public boolean wronged(UUID by, float power, BlockPos at) {
        boolean fumes = wrongedKind() == Kind.RAGE;
        return begin(wrongedKind(), fumes ? power : power * .5f, by, at);
    }

    /** The kind {@link #wronged} will begin for this temperament, so a caller's onset face matches the course that follows. */
    public Kind wrongedKind() {
        return Ousting.fumes(entity.life().personality().temperament()) ? Kind.RAGE : Kind.GRIEF;
    }

    /** The face a course of {@code kind} opens with (its onset and peak). */
    public static String onsetFace(Kind kind) { return Stage.ONSET.face(kind); }

    /** A real danger starts the course immediately; panic owns the presentation of the flight. */
    public void frightened(float power) {
        LivingEntity threat = entity.getLastHurtByMob();
        if (begin(Kind.TERROR, power, threat == null ? null : threat.getUUID(), entity.blockPosition())
                && (PunishmentReaction.isHuman(threat) || threat instanceof YukkuriEntity peer && peer.profile().dangerToYukkuri()))
            entity.life().personality().experienced(Trait.BOLDNESS, -1, "terrified");
    }

    /** Start a course of {@code next} at strength 0-1. A weaker kind does not replace a stronger one before it fades. */
    public boolean begin(Kind next, float power, UUID cause, BlockPos at) {
        if (entity.level().isClientSide || !entity.isAlive() || next == null || power <= 0 || entity.profile().dangerToYukkuri()) return false;
        Stage now = stage();
        if (kind != null && now != Stage.FADING && next.ordinal() > kind.ordinal()) return false;
        boolean renewal = next == kind && now != Stage.FADING;
        power = Math.min(1, power);
        if (renewal) power = Math.max(strength, power);
        var p = entity.life().personality();
        float span = length(next, power, p, entity.mind().unease(), hasDependants());
        if (next != Kind.PARTING) span *= .9f + .2f * entity.getRandom().nextFloat();
        int duration = Math.clamp(Math.round(span), 300, 24000);
        if (renewal) duration = Math.max(duration, length - elapsed);
        kind = next; strength = power; length = duration; elapsed = 0; about = cause; where = at;
        griefFocus = kind == Kind.GRIEF ? new GriefFocus(false) : null;
        lineClock.begin(next, renewal);
        recallClock.schedule(0);
        String feeling = kind.name().toLowerCase(Locale.ROOT);
        int suppressed = renewal ? GameplayLog.admit(entity, "emotion", "renew_" + feeling, ONSET_TICKS) : 0;
        if (suppressed < 0) return true;
        float loggedStrength = Math.round(power * 100) / 100f;
        if (renewal) GameplayLog.limitedEvent(entity, "emotion", suppressed,
                "course renewed kind={} strength={} length={} about={} at={}", feeling, loggedStrength, length, about, where);
        else GameplayLog.event(entity, "emotion", "course begun kind={} strength={} length={} about={} at={} attachment={} selfControl={} boldness={} unease={}",
                feeling, loggedStrength, length, about, where, p.attachment(), p.selfControl(), p.boldness(), Math.round(entity.mind().unease()));
        return true;
    }

    /**
     * Ticks of a whole course. Grief: 2-10 minutes by closeness, longer for the attached and the uneasy, shorter for the
     * self-controlled and for a parent with young to feed. Fright: 30 s-3 min, longer for the timid. Anger: 20 s-2 min, longer for
     * the quick-tempered. Joy: 20 s-2 min, longer for the friendly.
     */
    private static float length(Kind kind, float strength, YukkuriPersonality p, float unease, boolean dependants) {
        float attachment = p.attachment() / 100f, self = p.selfControl() / 100f, bold = p.boldness() / 100f, friendly = p.friendliness() / 100f;
        float worry = ramp(unease, 35, 80);
        return switch (kind) {
            case GRIEF -> lerp(2400, 12000, strength) * (.7f + .6f * attachment) * (1.3f - .6f * self) * (1 + .4f * worry) * (dependants ? .7f : 1);
            case TERROR -> lerp(600, 3600, strength) * (1.35f - .7f * bold) * (1.2f - .4f * self) * (1 + .4f * worry);
            case RAGE -> lerp(400, 2400, strength) * (1.4f - .8f * self) * (1.2f - .4f * friendly);
            case JOY -> lerp(400, 2400, strength) * (.7f + .6f * friendly) * (1 - .4f * worry);
            case PARTING -> lerp(600, 2400, strength) * (.7f + .6f * attachment);
        };
    }

    /** Mate and own young 1, parents .9; anyone else (siblings, foster kin, a keeper) by how fond of them it was and its attachment. */
    private float closeness(UUID lost) {
        if (lost == null) return .9f;
        float base = switch (lostRelation(lost, FamilyData.get((ServerLevel)entity.level()))) {
            case PARTNER, CHILD -> 1;
            case MOTHER, FATHER -> .9f;
            case OTHER -> 0;
        };
        int fondness = YukkuriWorldData.get((ServerLevel)entity.level()).affinity(entity.getUUID(), lost);
        if (base == 0) return .25f + .45f * ramp(fondness, 0, 60) + .2f * entity.life().personality().attachment() / 100f;
        return Math.clamp(base * (.85f + .3f * ramp(fondness, 0, 60)), .3f, 1);
    }

    private Lost lostRelation(UUID lost, FamilyData data) {
        var family = entity.family();
        if (lost.equals(family.partner())) return Lost.PARTNER;
        if (data.children(entity.getUUID()).contains(lost)) return Lost.CHILD;
        if (lost.equals(family.mother())) return Lost.MOTHER;
        if (lost.equals(family.father())) return Lost.FATHER;
        return Lost.OTHER;
    }

    private boolean hasDependants() {
        var data = FamilyData.get((ServerLevel)entity.level());
        for (var id : data.children(entity.getUUID()))
            if (entity.level() instanceof ServerLevel level && level.getEntity(id) instanceof YukkuriEntity child && child.isAlive()
                    && child.childhood().shouldStayWithParents()) return true;
        return false;
    }

    // ---- clock -------------------------------------------------------------------------------------------------------------------
    /** Every server tick from MindLife. Comfort from kin runs the course three times as fast, sleep twice. */
    void tick(boolean comforted) {
        if (kind == null) return;
        Stage before = stage();
        elapsed += comforted ? 3 : entity.life().isSleeping() ? 2 : 1;
        if (elapsed >= length) { finish(); return; }
        Stage now = stage();
        if (now != before) {
            GameplayLog.event(entity, "emotion", "course stage kind={} stage={} elapsed={}/{} comforted={}",
                    kind.name().toLowerCase(Locale.ROOT), now.name().toLowerCase(Locale.ROOT), elapsed, length, comforted);
            lineClock.stageChanged();
            if (now == Stage.FADING) recallClock.schedule((length - elapsed) / 2);
        }
        if (lineClock.due(kind, now)) {
            if (voice(now)) lineClock.scheduleNext(kind, now, interval(now));
            else lineClock.waitForCalm();
        }
        if (now == Stage.FADING && kind == Kind.GRIEF && recallClock.due() && !recall()) recallClock.schedule(60);
    }

    private int interval(Stage stage) {
        int base = switch (stage) { case PEAK -> 300; case EASING -> VOW_WINDOW; default -> 100000; };
        return base + entity.getRandom().nextInt(base * 2 / 3 + 1);
    }

    private int easingStart() { return ONSET_TICKS + (int)Math.ceil(PEAK_END * (length - ONSET_TICKS)); }

    private boolean calm() {
        return !entity.hasReaction() && !entity.life().isSleeping() && !entity.isPanicking() && entity.life().mealTicks() == 0
                && entity.level().getGameTime() >= entity.speechEnd() && !entity.body().dying();
    }

    /** A stage's own line: sobs at the peak of grief, a sigh of missing later; the shaking, the huff and the glow likewise. */
    private boolean voice(Stage stage) {
        if (stage != Stage.PEAK && (stage != Stage.EASING || kind == Kind.PARTING)) return true;
        if (kind == Kind.PARTING && !partedFromAbout(16)) return false;
        if (!calm()) return false;
        boolean peak = stage == Stage.PEAK;
        if (kind == Kind.GRIEF && !peak && elapsed - easingStart() < VOW_WINDOW && about != null) {
            var data = FamilyData.get((ServerLevel)entity.level());
            var person = data.person(about);
            // Social hurt and exile also use GRIEF. A vow addresses a confirmed death.
            if (person != null && person.status == FamilyData.Status.DEAD) {
                var lost = lostRelation(about, data);
                var t = entity.life().personality().temperament();
                String event = GriefVow.event(lost, t.is(Gate.SELFISH), hasDependants());
                if (event != null) return vow(event, lost, GriefVow.style(t.is(Gate.ATTACHED), t.is(Gate.STOIC), t.is(Gate.BOLD)));
            }
        }
        // Grief for a thing (a lost ornament) has no companion to sob for: only the face shows it.
        String event = switch (kind) {
            case GRIEF -> about == null ? "" : peak ? "mourn_sob" : "mourn_miss";
            case TERROR -> terrorWords(peak);
            case RAGE -> peak ? "fume" : "sulk_huff";
            case JOY -> peak ? "glow" : "glow_hum";
            case PARTING -> "parting_gaze";
        };
        entity.showReaction(event.equals("mourn_sob") ? "cry" : stageFace(), event, 60);
        return true;
    }

    /** "Has it gone?" asks after a threat; fear of one's own mold, burn or wound has none, so only the shaking is spoken. */
    private String terrorWords(boolean peak) {
        if (peak) return "shaken_after";
        return about == null ? "" : "shaken_check";
    }

    private boolean aboutDead() {
        if (about == null) return false;
        var person = FamilyData.get((ServerLevel)entity.level()).person(about);
        return person != null && person.status == FamilyData.Status.DEAD;
    }

    private boolean vow(String event, Lost lost, String style) {
        if (style.equals("proud")) entity.showReaction(YukkuriAction.PROUD, "satisfied", "", 80);
        else entity.showReaction("teary_smile", "", 80);
        boolean spoken = entity.speakCue(event, style);
        GameplayLog.event(entity, "emotion", "grief vow event={} style={} lost={} spoken={}", event, style, lost, spoken);
        return spoken;
    }

    /** Midway through a fading grief it remembers once: looks around, teary, and says so. */
    private boolean recall() {
        if (!calm()) return false;
        entity.showReaction("teary", about == null ? "" : "mourn_recall", 80);
        return true;
    }

    private void finish() {
        // "That little one will be fine" is said by a parent about its own child; a child or sibling parting stays quiet.
        boolean ownChild = kind == Kind.PARTING && about != null
                && FamilyData.get((ServerLevel)entity.level()).children(entity.getUUID()).contains(about);
        String closing = switch (kind) {
            // The closing vow carries a lost one's share forward: only a confirmed death earns it.
            case GRIEF -> aboutDead() ? "mourn_over" : null;
            case TERROR -> about == null ? null : "shaken_over";
            case PARTING -> ownChild && partedFromAbout(16) ? "parting_over" : null;
            default -> null;
        };
        String face = kind == Kind.GRIEF ? "teary_smile" : "satisfied";
        GameplayLog.event(entity, "emotion", "course ended kind={} length={} about={}", kind.name().toLowerCase(Locale.ROOT), length, about);
        kind = null; strength = 0; length = elapsed = 0; about = null; where = null;
        griefFocus = null;
        if (closing != null && calm()) entity.showReaction(face, closing, 70);
    }

    // ---- save --------------------------------------------------------------------------------------------------------------------
    static final class LineClock {
        private int ticks;
        private boolean joyPeakSpent;

        void begin(Kind kind, boolean renewal) {
            if (kind == Kind.JOY && renewal) return;
            ticks = ONSET_TICKS + 40;
            joyPeakSpent = false;
        }

        void stageChanged() { ticks = Math.min(ticks, 40); }

        boolean due(Kind kind, Stage stage) {
            return !(kind == Kind.JOY && stage == Stage.PEAK && joyPeakSpent) && --ticks <= 0;
        }

        void scheduleNext(Kind kind, Stage stage, int delay) {
            ticks = delay;
            if (kind == Kind.JOY && stage == Stage.PEAK) joyPeakSpent = true;
        }

        void waitForCalm() { ticks = 40; }

        void save(ObjIntConsumer<String> putInt) {
            putInt.accept("LineClock", ticks);
            if (joyPeakSpent) putInt.accept("JoyPeakLineSpent", 1);
        }

        void load(ToIntFunction<String> getInt, boolean hasLineClock) {
            ticks = hasLineClock ? getInt.applyAsInt("LineClock") : 200;
            joyPeakSpent = getInt.applyAsInt("JoyPeakLineSpent") != 0;
        }
    }

    static final class RecallClock {
        private int ticks;

        void schedule(int delay) { ticks = delay; }
        boolean due() { return ticks > 0 && --ticks == 0; }
        void save(ObjIntConsumer<String> putInt) { putInt.accept("RecallClock", ticks); }
        void load(ToIntFunction<String> getInt) { ticks = getInt.applyAsInt("RecallClock"); }
    }

    void save(CompoundTag mind) {
        if (kind == null) return;
        var tag = new CompoundTag();
        tag.putString("Kind", kind.name()); tag.putFloat("Strength", strength); tag.putInt("Length", length); tag.putInt("Elapsed", elapsed);
        lineClock.save(tag::putInt);
        recallClock.save(tag::putInt);
        if (griefFocus != null) tag.putBoolean("ApproachConsidered", griefFocus.peakSeen);
        if (about != null) tag.putUUID("About", about);
        if (where != null) tag.putLong("Where", where.asLong());
        mind.put("Feeling", tag);
    }
    void load(CompoundTag mind) {
        kind = null; strength = 0; length = elapsed = 0; about = null; where = null; running = false;
        publishedStage = null; publishedActivity = null;
        griefFocus = null;
        if (!mind.contains("Feeling")) return;
        var tag = mind.getCompound("Feeling");
        try { kind = Kind.valueOf(tag.getString("Kind")); } catch (IllegalArgumentException ex) { return; }
        strength = Math.clamp(tag.getFloat("Strength"), 0f, 1f);
        length = Math.clamp(tag.getInt("Length"), 300, 24000);
        elapsed = Math.clamp(tag.getInt("Elapsed"), 0, length);
        about = tag.hasUUID("About") ? tag.getUUID("About") : null;
        where = tag.contains("Where") ? BlockPos.of(tag.getLong("Where")) : null;
        if (kind == Kind.GRIEF) griefFocus = new GriefFocus(tag.contains("ApproachConsidered")
                ? tag.getBoolean("ApproachConsidered") : stage() != Stage.ONSET);
        lineClock.load(tag::getInt, tag.contains("LineClock"));
        recallClock.load(tag::getInt);
    }

    static float ramp(float x, float a, float b) { return Math.clamp((x - a) / (b - a), 0f, 1f); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ---- body --------------------------------------------------------------------------------------------------------------------
    /** One look/approach per course, shared across the peak and easing goals and their interruptions. */
    private static final class GriefFocus {
        private RemainsEntity remains;
        private Vec3 stand;
        private long nextSearch, walkUntil;
        private boolean peakSeen;
        private String result;

        private GriefFocus(boolean peakSeen) { this.peakSeen = peakSeen; }

        private Vec3 target(YukkuriEntity y, EmotionCourse f, long now) {
            if (now >= nextSearch) {
                remains = f.about == null ? null : RemainsEntity.find((ServerLevel)y.level(), y, f.about, 16);
                nextSearch = now + 40;
            }
            Vec3 point = remains == null || remains.isRemoved() ? place(f) : remains.position();
            return point != null && y.distanceToSqr(point) <= 16 * 16 ? point : null;
        }

        private void plan(YukkuriEntity y, Vec3 point, long now) {
            peakSeen = true;
            boolean attached = y.life().personality().temperament().is(Gate.ATTACHED);
            double distance = point == null ? -1 : y.position().distanceTo(point);
            var body = remains != null && !remains.isRemoved() ? remains : null;
            double stop = y.getBbWidth() / 2 + (body == null ? .5 : body.getBbWidth() / 2 + .3);
            if (attached && distance > stop && distance <= 6) {
                Predicate<Vec3> allowed = at -> Departure.allowsAddress(y, BlockPos.containing(at));
                stand = body == null ? SurfaceApproach.beside(y, point, stop, allowed)
                        : SurfaceApproach.beside(y, point, stop, body, allowed);
                if (stand != null) walkUntil = now + 200;
            }
            GameplayLog.event(y, "emotion", "mourn focus target={} distance={} approach={} attached={} yawError={}",
                    point == null ? "none" : body == null ? "place" : "remains", distance, stand != null, attached, yawError(y, point));
        }

        private void walk(YukkuriEntity y, Vec3 point, long now) {
            if (now >= walkUntil) result = "timeout";
            else if (point == null) result = "blocked";
            else {
                var outcome = SurfaceApproach.walkResult(y, stand, remains != null && !remains.isRemoved() ? remains : null, .7);
                if (outcome == SurfaceApproach.Result.ARRIVED) result = "arrived";
                if (outcome == SurfaceApproach.Result.BLOCKED) result = "blocked";
            }
            if (result == null) return;
            stand = null;
            SurfaceApproach.halt(y);
        }

        private void reportEnd(YukkuriEntity y, Vec3 point) {
            GameplayLog.event(y, "emotion", "mourn approach ended result={} distance={} yawError={}", result,
                    point == null ? -1 : y.position().distanceTo(point), yawError(y, point));
            result = null;
        }

        private void stop(YukkuriEntity y) {
            if (stand != null || result != null) {
                stand = null;
                GameplayLog.event(y, "emotion", "mourn approach interrupted walkResult={}", result);
                result = null;
            }
        }
    }

    private static Vec3 place(EmotionCourse f) { return f.where == null ? null : Vec3.atBottomCenterOf(f.where); }
    private static float yawError(YukkuriEntity y, Vec3 point) {
        return point == null ? 0 : Math.abs(Mth.wrapDegrees(SurfaceApproach.yaw(y.position(), point) - y.getYRot()));
    }
    private static void face(YukkuriEntity y, Vec3 point) {
        if (yawError(y, point) > 3) SurfaceApproach.face(y, SurfaceApproach.yaw(y.position(), point));
    }

    /**
     * The body side of the strong stages. The peak goal (priority 3, above errands) watches the place of a loss and sends a
     * frightened one home to tremble; the easing goal (priority 6, above roaming) sits listlessly and
     * strolls a few slow steps between sits. The parting goal watches the departing child below needs and duties.
     */
    public static final class CourseGoal extends Goal {
        private final YukkuriEntity y;
        private final int priority;
        private int sit, ticks;
        private BlockPos stroll;
        private GriefFocus watched;

        public CourseGoal(YukkuriEntity y, int priority) {
            this.y = y; this.priority = priority;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        private EmotionCourse f() { return y.mind().feeling(); }
        private boolean wanted() {
            // A parent carrying its young yields movement/look to getting them to safety first; the course still ticks and
            // shows its facial overlay while walking, then plays its tremble/stroll once the young are set down.
            return f().goalPriority() == priority && y.isAlive() && !y.inert() && !y.isPassenger() && !y.isPanicking()
                    && !y.isInWaterOrBubble() && !y.isOnFire() && !y.isCommandPlaying() && !y.operations().active()
                    && !y.life().hasUrgentNeed() && !y.life().isSleeping() && y.life().mealTicks() == 0 && !y.body().dying()
                    && !y.family().laboring() && !y.family().carry().carrying() && !y.mind().nonYukkuri() && !HomeClaim.retreating(y)
                    && (priority != PARTING_PRIORITY || !y.duty().claimed());
        }
        @Override public boolean canUse() { return wanted(); }
        @Override public boolean canContinueToUse() { return wanted(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            f().running = true; ticks = 0; stroll = null;
            sit = 60 + y.getRandom().nextInt(80);
            SurfaceApproach.halt(y);
            var f = f();
            watched = f.griefFocus;
            if (f.kind == Kind.TERROR && priority == 3) {
                var home = y.life().homeStand();
                if (home != null && home.distSqr(y.blockPosition()) > 4 && home.distSqr(y.blockPosition()) <= 32 * 32) stroll = home;
            }
        }
        @Override public void tick() {
            var f = f();
            ticks++;
            if (watched != f.griefFocus) {
                if (watched != null) watched.stop(y);
                watched = f.griefFocus;
                stroll = null;
                SurfaceApproach.halt(y);
            }
            if (stroll != null) {
                // Going somewhere: home to tremble in (fright), or a few aimless steps (easing). Slow unless frightened.
                double speed = f.kind == Kind.TERROR && priority == 3 ? 1.1 : .7;
                if (ticks % 20 == 1 && !y.life().navigate(stroll, speed, false)) stroll = null;
                if (stroll != null && (stroll.distSqr(y.blockPosition()) <= 2 || ticks > 200)) stroll = null;
                if (stroll == null) { SurfaceApproach.halt(y); ticks = 0; }
                return;
            }
            if (watched != null) {
                long now = y.level().getGameTime();
                Vec3 point = watched.target(y, f, now);
                if (watched.stand == null) face(y, point);
                if (f.stage() == Stage.PEAK && !watched.peakSeen) watched.plan(y, point, now);
                if (watched.stand != null) {
                    watched.walk(y, point, now);
                    if (watched.stand != null) return;
                    face(y, point);
                }
                if (watched.result != null && yawError(y, point) <= 3) watched.reportEnd(y, point);
            }
            if (f.kind == Kind.PARTING) {
                var level = (ServerLevel)y.level();
                if (f.about != null && level.getEntity(f.about) instanceof YukkuriEntity child && child.isAlive())
                    face(y, child.position());
                else face(y, place(f));
                return;
            }
            if (ticks == 1 && f.kind == Kind.RAGE && f.about != null && y.level() instanceof ServerLevel level
                    && level.getEntity(f.about) instanceof LivingEntity other
                    && other.distanceToSqr(y) <= 144) y.getLookControl().setLookAt(other, 30, 30);
            if (priority != 6 || ticks < sit) return;
            // Easing: after a sit, a few slow aimless steps, then another sit.
            sit = 100 + y.getRandom().nextInt(140);
            ticks = 0;
            if (f.kind == Kind.RAGE) return;
            // A frightened individual already inside or on its nest stays where it is safe instead of stepping back out over the rim.
            if (f.kind == Kind.TERROR && FamilyNest.occupiedBed(y) != null) return;
            // Never back into a home it was driven out of (settlement/MoldExile).
            var to = LandRandomPos.getPos(y, 4, 2);
            if (to != null && Departure.allowsAddress(y, BlockPos.containing(to))) stroll = BlockPos.containing(to);
        }
        @Override public void stop() {
            if (watched != null) watched.stop(y);
            watched = null;
            f().running = false; stroll = null;
            SurfaceApproach.halt(y);
        }
        @Override public String toString() { return "EmotionCourse.CourseGoal(" + priority + ")"; }
    }
}
