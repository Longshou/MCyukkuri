package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One readable example or a collection the pet can actually see. */
public final class PetStudy {
    private static final int CLEAR_COUNT = 4;

    public record Exercise(PetSkill skill, Component example, int count, String source) {
        public boolean sameExample(Exercise other) {
            return skill == other.skill && count == other.count && source.equals(other.source) && example.equals(other.example);
        }
    }

    public static Exercise find(YukkuriEntity pet, Player owner, PetSkill skill) {
        return skill == PetSkill.READING ? reading(pet, owner) : counting(pet);
    }

    private static Exercise reading(YukkuriEntity pet, Player owner) {
        if (owner != null && pet.distanceToSqr(owner) <= 16 && pet.hasLineOfSight(owner)) {
            var held = owner.getMainHandItem();
            var written = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null && !written.pages().isEmpty())
                return text(written.pages().getFirst().get(true).getString(), "book");
            var writable = held.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writable != null && !writable.pages().isEmpty())
                return text(writable.pages().getFirst().get(true), "book");
        }
        var at = pet.blockPosition();
        Exercise closest = null;
        double best = 16;
        for (var pos : BlockPos.betweenClosed(at.offset(-4, -1, -4), at.offset(4, 2, 4))) {
            var point = Vec3.atCenterOf(pos);
            double distance = pet.distanceToSqr(point);
            if (distance > best || !pet.level().hasChunkAt(pos)
                    || !(pet.level().getBlockEntity(pos) instanceof SignBlockEntity sign)) continue;
            var hit = pet.level().clip(new ClipContext(pet.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pet));
            if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(pos)) continue;
            for (int row = 0; row < 4; row++) {
                var candidate = text(sign.getFrontText().getMessage(row, true).getString(), pos.toShortString());
                if (candidate == null) continue;
                closest = candidate;
                best = distance;
                break;
            }
        }
        return closest;
    }

    private static Exercise text(String text, String source) {
        String line = text.lines().findFirst().orElse("").strip();
        if (line.isEmpty()) return null;
        int length = Math.min(40, line.codePointCount(0, line.length()));
        return new Exercise(PetSkill.READING, Component.literal(line.substring(0, line.offsetByCodePoints(0, length))), 0, source);
    }

    private static Exercise counting(YukkuriEntity pet) {
        var area = pet.getBoundingBox().inflate(5);
        int young = pet.level().getEntitiesOfClass(YukkuriEntity.class, area,
                y -> y != pet && y.isAlive() && !y.family().adult() && pet.hasLineOfSight(y) && Bonds.parentOf(pet, y)).size();
        if (young > 0 || pet.pet().lessons().hasCountedYoung()) return new Exercise(PetSkill.ARITHMETIC, number(young), young, "young");
        int food = pet.level().getEntitiesOfClass(ItemEntity.class, area,
                item -> item.isAlive() && pet.life().acceptsFood(item.getItem()) && pet.hasLineOfSight(item))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
        return food == 0 ? null : new Exercise(PetSkill.ARITHMETIC, number(food), food, "food");
    }

    public static LivingEntity visitor(YukkuriEntity pet) {
        return pet.level().getEntitiesOfClass(LivingEntity.class, pet.getBoundingBox().inflate(6), person ->
                (person instanceof Player player && !player.isSpectator() || person instanceof HumanNpc)
                        && person.isAlive() && !pet.pet().ownedBy(person.getUUID()) && pet.hasLineOfSight(person))
                .stream().min(Comparator.comparingDouble(pet::distanceToSqr)).orElse(null);
    }

    private static Component number(int count) {
        return count > CLEAR_COUNT ? Component.translatable("pet.mcyukkuri.practice.many") : Component.literal(Integer.toString(count));
    }

    public static void answer(YukkuriEntity pet, Exercise exercise) {
        boolean success = pet.pet().lessons().attempt(exercise.skill());
        Component answer = exercise.example();
        boolean missing = success && exercise.source().equals("young") && pet.pet().lessons().noticesMissingYoung(exercise.count());
        if (missing) answer = Component.translatable("pet.mcyukkuri.practice.missing", answer);
        if (!success) {
            if (exercise.count() > 1) answer = number(exercise.count() - 1);
            else if (exercise.skill() == PetSkill.READING && pet.pet().lessons().level(PetSkill.READING) > 0) {
                String text = exercise.example().getString();
                answer = Component.literal(text.substring(0, text.offsetByCodePoints(0, 1)) + "…");
            } else answer = Component.translatable("pet.mcyukkuri.practice.unsure");
        }
        int length = ClipMarkers.of("think").length();
        Staging.card((ServerLevel)pet.level(), pet.position().add(0, pet.getBbHeight() + .65, 0), answer, length + 30, 1.2f);
        String event = success ? countLine(exercise) : "exam_unsure";
        pet.showReaction(missing ? "teary" : success ? "happy" : "confused", event, length + 30);
        GameplayLog.event(pet, "lesson", "study answered skill={} source={} count={} success={} missing={}",
                exercise.skill(), exercise.source(), exercise.count(), success, missing);
        pet.pet().lessons().performed(exercise.skill(), success);
    }

    private static String countLine(Exercise exercise) {
        int count = exercise.count();
        if (count > 0)
            return "count_" + (exercise.source().equals("young") ? "children_" : "food_")
                    + (count > CLEAR_COUNT ? "many" : count);
        return "exam_answer";
    }

    private PetStudy() {}
}
