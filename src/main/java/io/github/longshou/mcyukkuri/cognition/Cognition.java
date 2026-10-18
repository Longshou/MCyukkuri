package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/** Independent capability. Personality, sensory access and learned waiting remain separate. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class Cognition {
    public static final String KEY = "MCYukkuriCognition";
    public enum TeachingResult { APPLIED, DUPLICATE, INELIGIBLE }
    public record Snapshot(int innate, int learned, int ability, String origin, UUID mother, UUID father,
                           int motherInnate, int fatherInnate) {}
    private Cognition() {}
    private static CognitionData.Entry entry(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return CognitionData.Entry.load(e.getPersistentData().getCompound(KEY));
        var data = CognitionData.get(level);
        var value = data.entries.get(e.getUUID());
        if (value == null) {
            value = CognitionData.Entry.load(e.getPersistentData().getCompound(KEY));
            // Existing waiting skill is authoritative, but migration grants no retroactive cognition.
            value.playerLessons = Math.max(value.playerLessons, e.life().personality().lessons());
            data.entries.put(e.getUUID(), value); data.setDirty();
        }
        mirror(e, value); return value;
    }
    private static void mirror(YukkuriEntity e, CognitionData.Entry value) { e.getPersistentData().put(KEY, value.save()); }
    private static void changed(YukkuriEntity e, CognitionData.Entry value) {
        CognitionData.get((ServerLevel)e.level()).setDirty(); mirror(e, value);
    }
    public static Snapshot get(YukkuriEntity e) {
        var v = entry(e); return new Snapshot(v.innate, v.learned, v.innate + v.learned, v.origin,
                v.mother, v.father, v.motherInnate, v.fatherInnate);
    }
    @SubscribeEvent public static void joining(EntityJoinLevelEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel && event.getEntity() instanceof YukkuriEntity e) entry(e);
    }
    @SubscribeEvent public static void spawning(FinalizeSpawnEvent event) {
        if (event.getEntity() instanceof YukkuriEntity e && e.level() instanceof ServerLevel
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) initializeNatural(e);
    }
    /** MC distribution, fixed by identity; called only by an actual natural spawn. */
    public static void initializeNatural(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level) || e.getPersistentData().contains(KEY, Tag.TAG_COMPOUND)
                || CognitionData.get(level).entries.containsKey(e.getUUID())) return;
        var v = entry(e); v.innate = (int) Math.round((50 + random(e.getUUID()).nextInt(151)) * io.github.longshou.mcyukkuri.rare.MyonLife.cognitionScale(e)); v.origin = "mc_natural_v1"; changed(e, v);
    }
    private static Random random(UUID id) { return new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ 0x434F474E4954494FL); }
    /** Only the successful family birth path may call this, after real ancestry and entity addition. */
    public static boolean born(YukkuriEntity child, UUID mother, UUID father) {
        if (!(child.level() instanceof ServerLevel level) || mother == null || child.isRemoved()
                || level.getEntity(child.getUUID()) != child) return false;
        var person = FamilyData.get(level).person(child.getUUID());
        if (person == null || !mother.equals(person.mother) || !Objects.equals(father, person.father)) return false;
        var v = entry(child); if (v.origin.equals("mc_birth_v1")) return false;
        if (!v.origin.equals("legacy_default") || v.learned != 0 || !v.classrooms.isEmpty()) return false;
        var data = CognitionData.get(level);
        v.mother = mother; v.father = father;
        var m = data.entries.get(mother); var f = father == null ? null : data.entries.get(father);
        v.motherInnate = m == null ? -1 : m.innate; v.fatherInnate = f == null ? -1 : f.innate;
        int count = (m == null ? 0 : 1) + (f == null ? 0 : 1);
        int base = count == 0 ? 100 : ((m == null ? 0 : m.innate) + (f == null ? 0 : f.innate)) / count;
        v.innate = Math.clamp(base + random(child.getUUID()).nextInt(41) - 20, 0, 1000);
        v.origin = "mc_birth_v1"; changed(child, v); return true;
    }
    public static boolean canTeach(YukkuriEntity teacher, YukkuriEntity student) {
        return teacher != student && teacher.level() instanceof ServerLevel && teacher.level() == student.level()
                && teacher.family().adult() && !student.family().adult() && student.childhood().canLearn()
                && teacher.life().personality().trait(Trait.PATIENCE) >= 55
                && WildEvents.safe(teacher) && WildEvents.safe(student)
                && !teacher.life().hasUrgentNeed() && !student.life().hasUrgentNeed()
                && teacher.life().mealTicks() == 0 && student.life().mealTicks() == 0
                && teacher.distanceToSqr(student) <= 16 && teacher.hasLineOfSight(student) && student.hasLineOfSight(teacher)
                && WildEvents.friendly(teacher, student) && get(teacher).ability() - get(student).ability() >= 50;
    }
    public static boolean learningSucceeds(YukkuriEntity student, double roll) {
        return validRoll(roll) && roll < .3 + get(student).ability() / 500.0;
    }
    public static boolean forestFindSucceeds(YukkuriEntity entity, double roll) {
        return validRoll(roll) && roll < Math.clamp(.35 + get(entity).ability() / 600.0, 0, 1);
    }
    private static boolean validRoll(double roll) { return Double.isFinite(roll) && roll >= 0 && roll < 1; }
    /** Event authority supplies real demonstration/imitation evidence; dialogue alone cannot pay. */
    public static TeachingResult commitTeaching(YukkuriEntity teacher, YukkuriEntity student, UUID classroom) {
        if (!(student.level() instanceof ServerLevel level) || classroom == null) return TeachingResult.INELIGIBLE;
        var value = entry(student); if (value.classrooms.contains(classroom)) return TeachingResult.DUPLICATE;
        var scene = WildEventData.get(level).scene(classroom);
        if (!canTeach(teacher, student) || scene == null || !scene.active() || scene.kind != WildEventData.Kind.CARD
                || !scene.card.equals("teach") || !scene.members.equals(java.util.List.of(teacher.getUUID(), student.getUUID()))
                || scene.sourceCount != 1 || scene.responses != 3) return TeachingResult.INELIGIBLE;
        value.classrooms.add(classroom); value.learned++; changed(student, value); return TeachingResult.APPLIED;
    }
    /** Called only after the existing Social waiting lesson commits. No new copy of the waiting skill. */
    public static TeachingResult playerLessonCompleted(YukkuriEntity student, Player teacher) {
        if (!(student.level() instanceof ServerLevel) || teacher.level() != student.level() || !teacher.isAlive()
                || !student.isAlive() || student.isRemoved() || !student.childhood().canLearn() || student.isPanicking() || student.life().hasUrgentNeed()
                || student.life().isSleeping() || student.life().mealTicks() > 0 || student.isCommandPlaying()
                || student.distanceToSqr(teacher) > 16 || !student.hasLineOfSight(teacher)) return TeachingResult.INELIGIBLE;
        var value = entry(student); int completed = student.life().personality().lessons();
        if (completed <= value.playerLessons) return TeachingResult.DUPLICATE;
        value.playerLessons = completed; value.learned = Math.min(1000 - value.innate, value.learned + 1);
        changed(student, value); return TeachingResult.APPLIED;
    }
}
