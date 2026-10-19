package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * "Cannot take it easy" (ゆっくりできない): the one unease value every individual carries, wild, stray, settler, house
 * yukkuri and pet alike (docs/EMOTION_CHAIN.md).
 *
 * <p>Lecture basis (komicolle 40720 No.11518890): whenever a yukkuri feels it cannot take it easy its filling turns
 * sweeter; when that goes too far it goes mad, can only scream "yukkuri", and its filling becomes foul. No.11518802
 * lists this sickness (非油症, non-yukkuri syndrome) as one of the few things that kill without touching the core.
 *
 * <p>The value is authoritative here; {@code PetLife.stress} is now a view of it (status key {@code mood.unease}).
 * Pressures and reliefs come from two places: a slow sampler of real conditions (hunger, wounds, missing accessory,
 * fear, cold, rain, crowding, corpses, waste, home) and explicit {@link EmotionEvent} causes raised by gameplay
 * (bereavement, fright, being driven out, treatment, a returned accessory...).
 */
public final class MindLife {
    public enum Stage { CALM, UNEASY, SWEETENED, NON_YUKKURI }
    public enum Course { NONE, FRENZY, WITHERING }

    /** Unease thresholds. Ordinary life with a home and food settles near 0; see the balance table in the doc. */
    public static final float UNEASY = 35, SWEETENED = 60, BREAKING = 95, RECOVERED = 70;
    /** Ticks at the top before the mind breaks, the recoverable frenzy, and the whole course to death. */
    public static final int ONSET_TICKS = 1200, FRENZY_TICKS = 24000, FATAL_TICKS = 72000;
    private static final int SAMPLE = 100;
    private static final float PER_SAMPLE = SAMPLE / 1200f; // rates below are "per minute" (1200 ticks)

    private final YukkuriEntity entity;
    private float unease, sweetness;
    private int breakingTicks, courseTicks, namings, shoutClock, fitClock, moanClock, witherClock;
    private Course course = Course.NONE;
    private String heldAction = "";
    private int heldTicks;
    private Stage reported = Stage.CALM;
    private boolean migrated;
    /** Last contribution per cause, rounded per minute, for status and traces. Transient. */
    private final Map<String, Float> causes = new LinkedHashMap<>();
    private final Map<String, Long> eventCooldown = new HashMap<>();

    public MindLife(YukkuriEntity entity) { this.entity = entity; }

    // ---- reads ----------------------------------------------------------------------------------------------------
    public float unease() { return unease; }
    /** Rounded view used by the pet systems (former {@code PetLife.stress}). */
    public int stressView() { return Math.round(unease); }
    /** Accumulated sweetening of the filling, 0-100: rises while unease is high, fades slowly when calm. */
    public float sweetness() { return sweetness; }
    public Course course() { return course; }
    public int courseTicks() { return courseTicks; }
    public int namings() { return namings; }
    private String givenName = "";
    /** Own sound-alike name (zh key into {@link GivenNames}); empty until drawn. */
    public String givenName() { return givenName; }
    /** Draw the own name once (first server tick); no-op when already set. */
    public void ensureGivenName() { if (givenName.isEmpty()) givenName = GivenNames.draw(entity); }
    public String givenLabel() {
        var e = GivenNames.find(entity.getVariant().dialogueSpecies(), givenName);
        return e == null ? "" : e.zh() + "/" + e.ja() + "/" + e.en();
    }
    public boolean nonYukkuri() { return course != Course.NONE; }
    public Stage stage() {
        if (course != Course.NONE) return Stage.NON_YUKKURI;
        return unease >= SWEETENED ? Stage.SWEETENED : unease >= UNEASY ? Stage.UNEASY : Stage.CALM;
    }
    public String stageId() {
        return course == Course.NONE ? stage().name().toLowerCase(Locale.ROOT) : "non_yukkuri_" + course.name().toLowerCase(Locale.ROOT);
    }
    public String causesText() {
        if (causes.isEmpty()) return "none";
        var text = new StringBuilder();
        causes.forEach((k, v) -> { if (Math.abs(v) >= .05f) text.append(text.length() == 0 ? "" : " ").append(k).append(v > 0 ? "+" : "").append(Math.round(v * 10) / 10f); });
        return text.length() == 0 ? "none" : text.toString();
    }
    /** Filling portions carry this sweetness (lecture: the filling turns sweet; foul once the mind has broken). */
    public int fillingSweetness() { return nonYukkuri() ? 5 : Math.round(60 + .4f * sweetness); }
    public boolean foulFilling() { return nonYukkuri(); }
    /** Subtracted from a predator's prey distance score: sweetened prey smells better, a broken one is shunned. */
    public double preyAppeal() { return nonYukkuri() ? -80 : sweetness * .6; }

    // ---- writes ---------------------------------------------------------------------------------------------------
    /** Direct change (pet systems, commands, naming). Relief works at half strength once the mind has broken. */
    public void add(float delta, String cause) {
        if (entity.level().isClientSide) return;
        if (delta < 0 && nonYukkuri()) delta *= .5f;
        unease = Math.clamp(unease + delta, 0f, 100f);
        if (cause != null && Math.abs(delta) >= 1) note(cause + (delta > 0 ? " +" : " ") + Math.round(delta * 10) / 10f);
    }
    public boolean set(float value) { unease = Math.clamp(value, 0f, 100f); if (unease < BREAKING) breakingTicks = 0; return true; }
    public boolean setSweetness(float value) { sweetness = Math.clamp(value, 0f, 100f); return true; }
    /** Testing door: start the sickness now (frenzy) or jump to the withering course. */
    public boolean force(String what) {
        switch (what) {
            case "frenzy" -> { unease = 100; begin(); }
            case "withering" -> { unease = 100; begin(); courseTicks = FRENZY_TICKS; course = Course.WITHERING; }
            case "recover" -> { course = Course.NONE; courseTicks = 0; breakingTicks = 0; unease = Math.min(unease, RECOVERED); }
            default -> { return false; }
        }
        return true;
    }

    /** A gameplay cause; dialogue playback never supplies or decodes this input. */
    public void felt(EmotionEvent event) {
        if (event == null || entity.level().isClientSide) return;
        long now = entity.level().getGameTime();
        Long until = eventCooldown.get(event.bucket);
        if (until != null && until > now) return;
        if (event.cooldown > 0) eventCooldown.put(event.bucket, now + event.cooldown);
        add(event.delta, event.bucket);
    }

    /** Called by YukkuriLife at the step a meal is swallowed. {@code petSweet}: Sweets.eaten already eased a pet. */
    public void ate(ItemStack swallowed, boolean petSweet) {
        if (entity.level().isClientSide) return;
        var tag = FillingResource.data(swallowed);
        if (FillingResource.isFilling(swallowed) && "foul".equals(tag.getString("Taste"))) { add(12, "foul_filling"); return; }
        if (FillingResource.isFilling(swallowed) && "stale".equals(tag.getString("Taste"))) { add(6, "stale_filling"); return; }
        if (Consumables.kind(swallowed) == Consumables.Kind.SPICY) { add(6, "spicy"); return; }
        if (Sweets.is(swallowed)) { if (!petSweet) add(-8, "sweets"); return; }
        add(-3, "meal");
    }

    /** The delayed swallow of a non-item bite from a living body or remains. */
    public void ate(BodyFood.Bite swallowed) {
        if (entity.level().isClientSide || swallowed == null || swallowed.isEmpty()) return;
        if ("foul".equals(swallowed.taste()) || swallowed.odor() >= 90) { add(12, "foul_filling"); return; }
        if ("stale".equals(swallowed.taste()) || swallowed.odor() >= 60) { add(6, "stale_filling"); return; }
        add(-3, "meal");
    }

    /** Speech while broken: every line becomes the scream. New event {@code non_yukkuri} (speech agent), else the "yukkuri... yukkuri!" line. */
    public String voice(String event) {
        if (!nonYukkuri() || event == null || event.isEmpty() || event.equals("non_yukkuri")) return event;
        return pick("non_yukkuri", "afraid");
    }
    /** The resting face when nothing else is shown. */
    public String face(String fallback) {
        if (course == Course.FRENZY) return "dazed";
        if (course == Course.WITHERING) return "blank_cry";
        if (unease >= SWEETENED && "neutral".equals(fallback)) return "teary";
        return fallback;
    }
    public boolean active() { return nonYukkuri() && entity.isAlive(); }
    public String activity() { return course == Course.WITHERING ? Behaviors.NON_YUKKURI_WITHER : Behaviors.NON_YUKKURI; }

    /** First available catalogued event for this voice, else the fallback (new events wait for the speech agent's lines). */
    public String pick(String wanted, String fallback) {
        String species = entity.getVariant().dialogueSpecies();
        return NativeDialogue.select(species, entity.speechTier(), entity.myon().quirk(), wanted, entity.getRandom()) != null ? wanted : fallback;
    }

    /** Most body clips loop and a played action is held until replaced: play one for {@code ticks}, then hand back to idle. */
    public boolean perform(String action, int ticks) {
        if (!entity.playAction(action)) return false;
        heldAction = action; heldTicks = ticks; return true;
    }
    private void releaseAction() {
        if (heldTicks > 0 && --heldTicks == 0 && entity.isCommandPlaying() && entity.getAnimationId().equals(heldAction)) entity.playAction("idle");
    }

    // ---- clock ----------------------------------------------------------------------------------------------------
    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi()) return;
        releaseAction();
        if (nonYukkuri()) courseTick();
        else if (unease >= BREAKING) { if ((breakingTicks += 1) >= ONSET_TICKS) begin(); }
        else breakingTicks = 0;
        if (entity.tickCount % SAMPLE == 37) sample();
        if (entity.tickCount % 24000 == 23999) namings = Math.max(0, namings - 1); // one naming forgiven per game day
        Stage now = stage();
        if (now != reported) {
            MCYukkuri.LOGGER.info("[mind] {} #{} stage {} -> {} (unease {}, sweetness {})", entity.getVariant().assetId(), entity.getId(),
                    reported.name().toLowerCase(Locale.ROOT), stageId(), Math.round(unease), Math.round(sweetness));
            if (now == Stage.SWEETENED && reported.ordinal() < now.ordinal() && calm()) {
                entity.showReaction("teary", pick("uneasy", "mood_bad"), 80);
                if (entity.life().activity().equals(Behaviors.IDLE)) perform("droop", 80);
            }
            reported = now;
        }
    }

    private void sample() {
        causes.clear();
        var life = entity.life();
        var identity = YukkuriStatus.identity(entity);
        if (life.nutrition() < 10) put("starving", 3); else if (life.nutrition() < 25) put("hungry", 1.5f);
        int hurt = entity.body().condition().painSeverity();
        if (hurt > 0) put("injury", Math.min(3, .5f * hurt));
        if (entity.body().healthRatio() <= .6f) put("health_loss", 1.5f);
        if (entity.body().condition().hasSicknessPressure()) put("sickness", .5f);
        // Missing, broken, lost or merely torn accessory: the rate and its name come from headwear.Headwear (package 6).
        float hat = entity.headwear().uneasePerMinute();
        if (hat > 0) put(entity.headwear().uneaseCause(), hat);
        if (entity.isPanicking()) put("panic", 2);
        var threat = entity.status().reactions().threat().stage();
        if (threat != Reactions.Threat.Stage.NONE) put("threat", 1.5f);
        if (life.temperature().cold()) put("cold", 1);
        if (life.wetness() >= 60) put("soaked", 1);
        if (identity == Identity.STRAY) put("homeless", .3f);
        if (entity.settlement().groupId() != null && entity.settlement().society().dejected()) put("shunned", 1);
        surroundings(identity);
        // reliefs
        put("ease", -1);
        if (life.isSleeping()) put("sleep", -1);
        if (atHome(identity)) put("home", -1);
        if (entity.dailyLife().nuzzlePartnerId() >= 0 && entity.dailyLife().inContact() || entity.woundComfort().receiving() && entity.woundComfort().inContact()
                || entity.contactPose().equals("RUB")) put("comfort", -6);
        float rate = 0;
        for (var e : causes.entrySet()) rate += e.getValue() < 0 && nonYukkuri() ? e.getValue() * .5f : e.getValue();
        unease = Math.clamp(unease + rate * PER_SAMPLE, 0f, 100f);
        // Lecture: the filling turns sweet while it cannot take it easy. Accumulates above 50, fades below 35.
        if (unease >= BondLevels.UNEASE_CARE_LIMIT) sweetness = Math.min(100, sweetness + (unease - 40) / 20f * PER_SAMPLE);
        else if (unease < UNEASY) sweetness = Math.max(0, sweetness - PER_SAMPLE);
        if (!nonYukkuri() && unease >= SWEETENED && --moanClock <= 0 && calm()) {
            moanClock = 18 + entity.getRandom().nextInt(18); // every 1.5-3 minutes
            entity.showReaction("teary", pick("uneasy", "mood_bad"), 80);
        } else if (!nonYukkuri() && unease >= UNEASY && unease < SWEETENED && --moanClock <= 0 && calm()) {
            moanClock = 36 + entity.getRandom().nextInt(36);
            if (entity.getRandom().nextInt(3) == 0) entity.showReaction("neutral", pick("uneasy", "mood_bad"), 60);
        }
    }

    private void put(String cause, float perMinute) { causes.merge(cause, perMinute, Float::sum); }

    /** One look around: strangers pressing close, corpses (永遠不能油庫里) and waste (超級不能油庫里) nearby. */
    private void surroundings(Identity identity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        int strangers = 0; boolean corpse = false, waste = false;
        for (Entity other : level.getEntities(entity, entity.getBoundingBox().inflate(6))) {
            double d = other.distanceToSqr(entity);
            if (other instanceof YukkuriEntity y) {
                var group = entity.settlement().groupId();
                if (d <= 6.25 && y.isAlive() && !Bonds.recordedMate(entity, y) && (group == null || !group.equals(y.settlement().groupId()))
                        && !Bonds.biological(entity, y)) strangers++;
            } else if (other instanceof RemainsEntity && d <= 36 && entity.hasLineOfSight(other)) corpse = true;
            else if (other instanceof ItemEntity item && d <= 9 && item.getItem().is(ModItems.WASTE.get())) waste = true;
        }
        // Kept and house individuals live in pens and colonies by design; their keepers manage the pressure.
        if (identity != Identity.PET && identity != Identity.OWNED && identity != Identity.HOUSE) {
            if (strangers >= 10) put("crowded", 2); else if (strangers >= 6) put("crowded", 1);
        }
        if (corpse) put("corpse", 1);
        // The smell of stale and rotten remains carries farther than sight and needs no line of sight (body/RemainsRot).
        float stench = RemainsRot.pressureAt(level, entity.position());
        if (stench > 0) put("stench", stench);
        if (waste) put("waste", .5f);
    }

    private boolean atHome(Identity identity) {
        var shelter = entity.habitat().shelter();
        if (shelter != null && shelter.contains(entity.blockPosition())) return true;
        var stand = entity.life().homeStand();
        if (stand != null && entity.blockPosition().distSqr(stand) <= 9) return true;
        if (identity == Identity.HOUSE && entity.house().homeStand() != null && entity.blockPosition().distSqr(entity.house().homeStand()) <= 16) return true;
        if (identity == Identity.PET) {
            var owner = entity.level().getPlayerByUUID(entity.pet().owner());
            return owner != null && owner.distanceToSqr(entity) <= 256;
        }
        return false;
    }

    private boolean calm() { return entity.pet().calm() && !entity.status().reactions().active(); }

    // ---- non-yukkuri syndrome --------------------------------------------------------------------------------------
    private void begin() {
        course = Course.FRENZY; courseTicks = 0; breakingTicks = 0; shoutClock = 0; fitClock = 40; witherClock = 0;
        MCYukkuri.LOGGER.info("[mind] {} #{} non-yukkuri onset (unease {}, sweetness {})", entity.getVariant().assetId(), entity.getId(), Math.round(unease), Math.round(sweetness));
        entity.showReaction("dazed", pick("non_yukkuri", "afraid"), 80);
        hearing();
    }

    private void courseTick() {
        courseTicks++;
        if (course == Course.FRENZY) {
            if (unease < RECOVERED) {
                MCYukkuri.LOGGER.info("[mind] {} #{} recovered from non-yukkuri frenzy after {} ticks (unease {})", entity.getVariant().assetId(), entity.getId(), courseTicks, Math.round(unease));
                course = Course.NONE; courseTicks = 0; breakingTicks = 0;
                entity.showReaction("teary", pick("uneasy", "mood_bad"), 100);
                return;
            }
            if (courseTicks >= FRENZY_TICKS) {
                course = Course.WITHERING;
                MCYukkuri.LOGGER.info("[mind] {} #{} non-yukkuri withering (no way back)", entity.getVariant().assetId(), entity.getId());
            }
        }
        if (--shoutClock <= 0) {
            shoutClock = course == Course.FRENZY ? 60 + entity.getRandom().nextInt(60) : 200 + entity.getRandom().nextInt(200);
            entity.showReaction(course == Course.FRENZY ? "dazed" : "blank_cry", pick("non_yukkuri", "afraid"), 50);
            hearing();
        }
        if (course == Course.WITHERING && ++witherClock >= 2400) {
            witherClock = 0;
            entity.hurtInternal(entity.damageSources().generic(), Math.min(.2f, Math.max(0, entity.getHealth() - 1)));
        }
        if (courseTicks >= FATAL_TICKS) {
            MCYukkuri.LOGGER.info("[mind] {} #{} died of non-yukkuri syndrome", entity.getVariant().assetId(), entity.getId());
            // Lecture 11518802: the sickness is one of the few deaths that do not need the core to be struck; it reaches the core itself.
            entity.body().damageCore("non_yukkuri");
        }
    }

    /** The scream unsettles everyone who hears it. */
    private void hearing() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        for (var other : level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8), y -> y != entity && y.isAlive() && !y.mind().nonYukkuri()))
            other.mind().heard();
    }
    void heard() {
        long now = entity.level().getGameTime();
        Long until = eventCooldown.get("heard_non_yukkuri");
        if (until != null && until > now) return;
        eventCooldown.put("heard_non_yukkuri", now + 200);
        add(1.5f, null);
    }

    private void note(String text) { StatusEvents.note(entity, "mind " + text + " -> unease " + Math.round(unease)); }

    /** Naming bookkeeping lives here so it is saved with the rest; the reaction itself is {@link Naming}. */
    int named() { return namings = Math.min(99, namings + 1); }

    // ---- save ------------------------------------------------------------------------------------------------------
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putFloat("Unease", unease); tag.putFloat("Sweetness", sweetness);
        tag.putString("Course", course.name()); tag.putInt("CourseTicks", courseTicks); tag.putInt("BreakingTicks", breakingTicks);
        tag.putInt("Namings", namings); tag.putString("GivenName", givenName); tag.putBoolean("Migrated", true);
        return tag;
    }
    /** {@code pet}: the old PetLife tag, whose Stress seeds unease once for saves written before the mind existed. */
    public void load(CompoundTag tag, CompoundTag pet) {
        if (tag.getBoolean("Migrated")) {
            unease = Math.clamp(tag.getFloat("Unease"), 0f, 100f); sweetness = Math.clamp(tag.getFloat("Sweetness"), 0f, 100f);
            try { course = Course.valueOf(tag.getString("Course")); } catch (IllegalArgumentException ex) { course = Course.NONE; }
            courseTicks = Math.max(0, tag.getInt("CourseTicks")); breakingTicks = Math.max(0, tag.getInt("BreakingTicks"));
            namings = Math.clamp(tag.getInt("Namings"), 0, 99); givenName = tag.getString("GivenName");
        } else {
            unease = Math.clamp(pet.getInt("Stress"), 0, 100); sweetness = 0; course = Course.NONE; courseTicks = breakingTicks = namings = 0;
        }
        reported = stage(); causes.clear(); eventCooldown.clear();
    }

    /** While broken it hops about screaming and keeps no ordinary life (priority 1, under water and operations); only urgent needs get through. */
    public static final class FrenzyGoal extends Goal {
        private final YukkuriEntity y;
        public FrenzyGoal(YukkuriEntity y) { this.y = y; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
        private boolean allowed() {
            var m = y.mind();
            return m.active() && !y.isPassenger() && !y.isInWaterOrBubble() && !y.operations().active() && !y.pet().busy()
                    && !y.isCommandPlaying() && y.life().mealTicks() == 0 && !y.family().laboring() && !y.isLeashed()
                    // Only bare survival breaks through: starving, exhausted or bursting it still eats, sleeps, goes (else it would starve in half a day).
                    && !y.life().hasUrgentNeed()
                    // Kin who come to nuzzle or lick it get through: that comfort is the way back from the frenzy.
                    && !y.woundComfort().receiving() && y.dailyLife().nuzzlePartnerId() < 0 && !y.contactPose().equals("RUB");
        }
        @Override public boolean canUse() { return allowed(); }
        @Override public boolean canContinueToUse() { return allowed(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { y.life().interrupt(); y.social().stop(); y.playLife().stop(); }
        @Override public void tick() {
            var m = y.mind();
            if (--m.fitClock > 0) return;
            if (m.course == Course.WITHERING) {
                y.getNavigation().stop();
                m.fitClock = 160 + y.getRandom().nextInt(120);
                m.perform(y.getRandom().nextBoolean() ? "tremble" : "droop", 100);
                return;
            }
            m.fitClock = 30 + y.getRandom().nextInt(40);
            int roll = y.getRandom().nextInt(4);
            if (roll == 0) m.perform("struggle", 40);
            else if (roll == 1) m.perform("hop", 32);
            else {
                var to = DefaultRandomPos.getPos(y, 5, 2);
                if (to != null) y.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
            }
        }
        @Override public void stop() { y.getNavigation().stop(); }
    }
}
