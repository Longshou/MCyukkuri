package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumMap;
import java.util.EnumSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** The server's skill practice and development record for one body. */
public final class Skills {
    public static final int PROGRESS = 8;
    private static final int MAX_POINTS = 9;
    private static final Ability[] ABILITIES = Ability.values();

    private final YukkuriEntity entity;
    private final EnumMap<Ability, Integer> points = new EnumMap<>(Ability.class);
    private final EnumSet<Ability> assessed = EnumSet.noneOf(Ability.class);
    private final EnumMap<Ability, Long> watchedDay = new EnumMap<>(Ability.class);
    private int cap;
    private Ability lastLearned;
    private long lastLearnedDay;
    private Ability progressing;

    public Skills(YukkuriEntity entity) { this.entity = entity; }

    public int points(Ability ability) { return points.getOrDefault(ability, 0); }
    public int grade(Ability ability) { return SkillMath.grade(points(ability), ability.graded); }
    public boolean knows(Ability ability) { return grade(ability) >= 1; }

    public int domesticMask() {
        int mask = 0;
        for (Ability ability : ABILITIES) {
            if (ability.domestic && grade(ability) >= 2) mask |= 1 << ability.ordinal();
        }
        return mask;
    }

    public int cap() {
        if (cap == 0) cap = entity.getVariant() == YukkuriVariant.PATCHOULI
                ? SpeechCount.PATCHOULI_CAP : SkillMath.capBase(entity.life().personality().intelligence());
        return cap;
    }

    public int speechCap() { return entity.family().youngVoice() ? Math.min(cap(), SpeechCount.BABY_CAP) : cap(); }

    public void countSucceeded(int counted) {
        int before = cap();
        if (counted != before + 1) return;
        int ceiling = entity.getVariant() == YukkuriVariant.PATCHOULI
                ? SpeechCount.PATCHOULI_CAP : SkillMath.capCeiling(entity.life().personality().intelligence());
        if (before >= ceiling) return;
        cap = counted;
        GameplayLog.event(entity, "skill", "counting cap={} -> {}", before, cap);
    }

    public void practise(Ability ability, int gain, String source) {
        assert gain > 0;
        int before = points(ability);
        int after = Math.min(MAX_POINTS, before + gain);
        if (after == before) return;
        int oldGrade = SkillMath.grade(before, ability.graded);
        int newGrade = SkillMath.grade(after, ability.graded);
        points.put(ability, after);
        GameplayLog.limited(entity, "skill", ability.key() + "." + source, 100,
                "practice ability={} source={} points={} -> {} grade={} -> {}", ability, source, before, after, oldGrade, newGrade);
        if (newGrade > oldGrade) {
            progressing = null;
            lastLearned = ability;
            lastLearnedDay = day();
            GameplayLog.event(entity, "skill", "learned ability={} grade={} source={}", ability, newGrade, source);
            gotIt(ability, newGrade);
        } else progressing = ability;
    }

    private void gotIt(Ability ability, int grade) {
        if (entity.onGround() && entity.life().mealTicks() == 0 && !entity.life().isSleeping()) {
            entity.showReaction(YukkuriAction.JOY_JUMP, "sparkle", "lesson_learned", 60);
        } else entity.showReaction("sparkle", "lesson_learned", 60);
        var level = (ServerLevel) entity.level();
        Staging.sparkle(level, entity.position().add(0, entity.getBbHeight() * .5, 0));
        var owner = level.getPlayerByUUID(entity.pet().owner());
        if (owner != null && entity.distanceToSqr(owner) <= 32 * 32) {
            owner.displayClientMessage(Component.translatable("pet.mcyukkuri.skill.learned", entity.getDisplayName(),
                    Component.translatable(ability.translationKey()), gradeWord(ability, grade)), true);
        }
    }

    private long day() { return Math.floorDiv(entity.level().getDayTime(), 24000L); }

    public boolean watched(Ability ability) {
        long day = day();
        if (Long.valueOf(day).equals(watchedDay.put(ability, day))) return false;
        practise(ability, 1, "watch");
        return true;
    }

    public boolean assessed(Ability ability) { return assessed.contains(ability); }

    public void discover(Ability ability, boolean passes, String why) {
        if (!assessed.add(ability)) return;
        GameplayLog.event(entity, "skill", "discovery ability={} rule={} passes={}", ability, why, passes);
        if (passes && !knows(ability)) practise(ability, SkillMath.pointsForGrade(1, ability.graded) - points(ability), "discover");
    }

    public double will() {
        var pet = entity.pet();
        // House rules supply learned obedience when they have their own source.
        return SkillMath.will(entity.life().personality().temperament().patient(), entity.mind().stressView(),
                pet.isPet() ? pet.neglect() : 0, .5);
    }

    public SkillMath.Attempt attempt(Ability ability) {
        int grade = grade(ability);
        double will = will();
        var random = entity.getRandom();
        var result = SkillMath.attempt(grade, will, random.nextDouble(), random.nextDouble());
        GameplayLog.limited(entity, "skill", ability.key() + ".attempt", 100,
                "attempt ability={} grade={} can={} will={} result={}", ability, grade, SkillMath.can(grade), will, result);
        return result;
    }

    public CompoundTag readout() {
        var tag = new CompoundTag();
        byte[] grades = new byte[ABILITIES.length];
        for (Ability ability : ABILITIES) grades[ability.ordinal()] = (byte) (grade(ability) | (ability == progressing ? PROGRESS : 0));
        tag.putByteArray("g", grades);
        tag.putInt("cap", cap());
        return tag;
    }

    private static Component gradeWord(Ability ability, int grade) {
        return Component.translatable("pet.mcyukkuri.skill.grade." + (!ability.graded && grade > 0 ? "known" : grade));
    }

    public Component observation() {
        var page = Component.translatable("notebook.mcyukkuri.skills");
        for (Ability ability : ABILITIES) {
            Component value = ability == Ability.COUNTING ? Component.translatable("notebook.mcyukkuri.skills.cap", cap())
                    : gradeWord(ability, grade(ability));
            page.append("\n").append(Component.translatable("notebook.mcyukkuri.skills.entry",
                    Component.translatable(ability.translationKey()), value));
        }
        if (lastLearned != null) page.append("\n\n").append(Component.translatable("notebook.mcyukkuri.skills.last",
                lastLearnedDay + 1, Component.translatable(lastLearned.translationKey())));
        return page;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var savedPoints = new CompoundTag();
        points.forEach((ability, amount) -> savedPoints.putInt(ability.key(), amount));
        tag.put("Points", savedPoints);
        tag.putInt("Cap", cap());
        var savedAssessed = new ListTag();
        assessed.forEach(ability -> savedAssessed.add(StringTag.valueOf(ability.key())));
        tag.put("Assessed", savedAssessed);
        var savedWatched = new CompoundTag();
        watchedDay.forEach((ability, day) -> savedWatched.putLong(ability.key(), day));
        tag.put("Watched", savedWatched);
        if (lastLearned != null) {
            tag.putString("Last", lastLearned.key());
            tag.putLong("LastDay", lastLearnedDay);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        points.clear();
        assessed.clear();
        watchedDay.clear();
        lastLearned = progressing = null;
        cap = Math.clamp(tag.getInt("Cap"), 0, SpeechCount.PATCHOULI_CAP);
        var savedPoints = tag.getCompound("Points");
        var savedAssessed = tag.getList("Assessed", Tag.TAG_STRING);
        var savedWatched = tag.getCompound("Watched");
        String last = tag.getString("Last");
        for (Ability ability : ABILITIES) {
            String key = ability.key();
            if (savedPoints.contains(key, Tag.TAG_ANY_NUMERIC)) points.put(ability, Math.clamp(savedPoints.getInt(key), 0, MAX_POINTS));
            if (savedAssessed.contains(StringTag.valueOf(key))) assessed.add(ability);
            if (savedWatched.contains(key, Tag.TAG_ANY_NUMERIC)) watchedDay.put(ability, savedWatched.getLong(key));
            if (key.equals(last)) lastLearned = ability;
        }
        lastLearnedDay = tag.getLong("LastDay");
    }
}
