package io.github.longshou.mcyukkuri.pet;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** One line of an individual's exam history. type: 0 none,2 shop stock,3 simple,4 judgment,5 promotion,6 renewal.
 *  outcome: 0 pass/promote,1 keep,2 fail,3 demote,4 interrupted. scores: by Ability ordinal, -1 untested. */
public record ExamRecord(int type, long gameTime, int[] scores, BadgeGrade result, int outcome, UUID examiner) {
    public ExamRecord {
        int[] fixed = new int[4]; java.util.Arrays.fill(fixed, -1);
        if (scores != null) for (int i = 0; i < Math.min(4, scores.length); i++) fixed[i] = Math.clamp(scores[i], -1, 100);
        scores = fixed; if (result == null) result = BadgeGrade.NONE;
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Type", type); tag.putLong("Time", gameTime); tag.putIntArray("Scores", scores.clone());
        tag.putInt("Result", result.id()); tag.putInt("Outcome", outcome); if (examiner != null) tag.putUUID("Examiner", examiner); return tag;
    }
    public static ExamRecord load(CompoundTag tag) {
        return new ExamRecord(tag.getInt("Type"), tag.getLong("Time"), tag.getIntArray("Scores"), BadgeGrade.of(tag.getInt("Result")),
                tag.getInt("Outcome"), tag.hasUUID("Examiner") ? tag.getUUID("Examiner") : null);
    }
}
