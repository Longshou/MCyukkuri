package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.cognition.SkillMath.Attempt;
import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/** One counting example the pet can actually see. */
public final class PetStudy {
    public record Exercise(Component example, int count, String source) {}
    public record Answer(boolean success, UUID caption, int ticks) {}

    public static Exercise counting(YukkuriEntity pet) {
        var area = pet.getBoundingBox().inflate(5);
        int young = pet.level().getEntitiesOfClass(YukkuriEntity.class, area,
                y -> y != pet && y.isAlive() && !y.family().adult() && pet.hasLineOfSight(y) && Bonds.parentOf(pet, y)).size();
        if (young > 0 || pet.pet().lessons().hasCountedYoung()) return new Exercise(number(young, pet.skills().cap()), young, "young");
        return foodCount(pet);
    }

    public static Exercise foodCount(YukkuriEntity pet) {
        int food = visibleFood(pet);
        return food == 0 ? null : new Exercise(number(food, pet.skills().cap()), food, "food");
    }

    public static int visibleFood(YukkuriEntity pet) {
        return visibleFoodStacks(pet).stream().mapToInt(item -> item.getItem().getCount()).sum();
    }

    public static List<ItemEntity> visibleFoodStacks(YukkuriEntity pet) {
        return pet.level().getEntitiesOfClass(ItemEntity.class, pet.getBoundingBox().inflate(5),
                item -> item.isAlive() && pet.life().acceptsFood(item.getItem()) && pet.hasLineOfSight(item));
    }

    public static LivingEntity visitor(YukkuriEntity pet) {
        return pet.level().getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(6), person ->
                (person instanceof Player player && !player.isSpectator() || person instanceof HumanNpc)
                        && person.isAlive() && !pet.pet().ownedBy(person.getUUID()) && pet.hasLineOfSight(person))
                .stream().min(Comparator.comparingDouble(pet::distanceToSqr)).orElse(null);
    }

    private static Component number(int count, int cap) {
        return count > cap ? Component.translatable("pet.mcyukkuri.practice.many") : Component.literal(Integer.toString(count));
    }

    public static Answer answer(YukkuriEntity pet, Exercise exercise) {
        boolean detailed = pet.skills().grade(Ability.COUNTING) == 3
                && pet.getRandom().nextDouble() < PetConversation.explanation(pet);
        return answer(pet, exercise, detailed);
    }

    public static Answer answer(YukkuriEntity pet, Exercise exercise, boolean detailed) {
        boolean success = pet.skills().attempt(Ability.COUNTING) == Attempt.DONE;
        int count = exercise.count();
        if (success) pet.skills().countSucceeded(count);
        int cap = pet.skills().speechCap();
        Component answer = number(count, cap);
        boolean missing = success && exercise.source().equals("young") && pet.pet().lessons().noticesMissingYoung(count);
        if (missing) answer = Component.translatable("pet.mcyukkuri.practice.missing", answer);
        if (!success) {
            answer = count > 1 ? number(count - 1, cap) : Component.translatable("pet.mcyukkuri.practice.unsure");
        }
        if (detailed && success && !missing) answer = PetConversation.fullStudyReply(answer);
        int length = ClipMarkers.of("think").length();
        UUID caption = Staging.card((ServerLevel)pet.level(), pet.position().add(0, pet.getBbHeight() + .65, 0), answer, length + 30, 1.2f);
        String base = exercise.source().equals("young") ? SpeechCount.CHILDREN : SpeechCount.FOOD;
        String event = success ? count == 0 ? "exam_answer" : SpeechCount.event(base, count, cap) : "exam_unsure";
        pet.showReaction(missing ? "teary" : success ? "happy" : "confused", event, length + 30);
        GameplayLog.event(pet, "lesson", "study answered skill={} source={} count={} success={} missing={}",
                Ability.COUNTING, exercise.source(), count, success, missing);
        if (success && caption != null && pet.pet().isPet()) pet.life().personality().experienced(Trait.INTELLIGENCE, 1);
        pet.pet().lessons().performed(Ability.COUNTING, success);
        return new Answer(success, caption, length + 30);
    }

    private PetStudy() {}
}
