package io.github.longshou.mcyukkuri.pet;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** One line of an individual's examination history. It records the service and badge outcome, never derived subject scores. */
public record ExamRecord(int type, long gameTime, BadgeGrade result, int outcome, UUID examiner,
                         BadgeGrade target, String questions, int next, int passed, String evidence, long attempt) {
    public ExamRecord { if (result == null) result = BadgeGrade.NONE; }
    public ExamRecord(int type, long gameTime, BadgeGrade result, int outcome, UUID examiner) {
        this(type, gameTime, result, outcome, examiner, BadgeGrade.NONE, "", 0, 0, "", -1);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Type", type); tag.putLong("Time", gameTime);
        tag.putInt("Result", result.id()); tag.putInt("Outcome", outcome); if (examiner != null) tag.putUUID("Examiner", examiner);
        if (attempt >= 0) {
            tag.putLong("Attempt", attempt);
            tag.putInt("Target", target.id());
            tag.putString("Questions", questions);
            tag.putInt("Next", next);
            tag.putInt("Passed", passed);
            tag.putString("Evidence", evidence);
        }
        return tag;
    }
    public static ExamRecord load(CompoundTag tag) {
        return new ExamRecord(tag.getInt("Type"), tag.getLong("Time"), BadgeGrade.of(tag.getInt("Result")),
                tag.getInt("Outcome"), tag.hasUUID("Examiner") ? tag.getUUID("Examiner") : null,
                BadgeGrade.of(tag.getInt("Target")), tag.getString("Questions"), tag.getInt("Next"), tag.getInt("Passed"),
                tag.getString("Evidence"), tag.contains("Attempt") ? tag.getLong("Attempt") : -1);
    }
}
