package io.github.longshou.mcyukkuri.pet;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** One line of an individual's examination history. It records the service and badge outcome, never derived subject scores. */
public record ExamRecord(int type, long gameTime, BadgeGrade result, int outcome, UUID examiner) {
    public ExamRecord { if (result == null) result = BadgeGrade.NONE; }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Type", type); tag.putLong("Time", gameTime);
        tag.putInt("Result", result.id()); tag.putInt("Outcome", outcome); if (examiner != null) tag.putUUID("Examiner", examiner); return tag;
    }
    public static ExamRecord load(CompoundTag tag) {
        return new ExamRecord(tag.getInt("Type"), tag.getLong("Time"), BadgeGrade.of(tag.getInt("Result")),
                tag.getInt("Outcome"), tag.hasUUID("Examiner") ? tag.getUUID("Examiner") : null);
    }
}
