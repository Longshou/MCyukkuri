package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/**
 * What an add-on gives every body without the base naming it (a domain Goal): its goals with their selector
 * priority, and where a body that joins the world by nature first is (a natural spawn, a generated settlement member).
 * Registered once from the registration entry. A goal factory runs inside the entity's constructor: it may keep the body and
 * bind the goal to the presentation, but must not read the variant or a component yet.
 */
public final class DomainHooks {
    private record GoalFactory(int priority, BiFunction<YukkuriEntity, PresentationGoals, Goal> create) {}

    private static final List<GoalFactory> GOALS = new CopyOnWriteArrayList<>();
    private static final List<BiConsumer<YukkuriEntity, String>> SPAWNS = new CopyOnWriteArrayList<>();

    private DomainHooks() {}

    public static void goal(int priority, BiFunction<YukkuriEntity, PresentationGoals, Goal> create) { GOALS.add(new GoalFactory(priority, create)); }

    public static void spawn(BiConsumer<YukkuriEntity, String> place) { SPAWNS.add(place); }

    static void addGoals(YukkuriEntity owner, GoalSelector selector, PresentationGoals presentation) {
        for (var goal : GOALS) selector.addGoal(goal.priority(), goal.create().apply(owner, presentation));
    }

    /** A body joined the world by nature ({@code cause}: natural, chunk_generation, settlement): an add-on may seat it at home. */
    public static void spawned(YukkuriEntity body, String cause) { for (var place : SPAWNS) place.accept(body, cause); }
}
