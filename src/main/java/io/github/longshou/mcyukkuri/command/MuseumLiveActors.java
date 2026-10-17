package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Disposable exhibit initial conditions. Once released, all results belong to ordinary life goals. */
public final class MuseumLiveActors {
    private static final String SNAPSHOT = "MuseumLiveRestore";

    public static boolean prepare(YukkuriEntity actor, String need) {
        if (!actor.getTags().contains("pm_actor") || !actor.isAlive()
                || !java.util.Set.of("observe", "meal", "toilet", "sleep", "house", "gather").contains(need)) return false;
        if (!actor.getPersistentData().contains(SNAPSHOT)) {
            var before = new CompoundTag(); actor.saveWithoutId(before);
            actor.getPersistentData().put(SNAPSHOT, before);
        }
        MuseumWalkGoal.cancel(actor);
        actor.playAction("idle");
        var life = actor.life().save();
        if (need.equals("meal")) life.putFloat("Nutrition", 35);
        if (need.equals("toilet")) { life.putFloat("Bladder", 95); life.putFloat("Dryness", 0); }
        if (need.equals("sleep")) life.putFloat("Energy", 15);
        actor.life().load(life);
        actor.setNoAi(false);
        actor.addTag("pm_live");
        return true;
    }

    public static void restore(YukkuriEntity actor) {
        if (!actor.getPersistentData().contains(SNAPSHOT)) return;
        var before = actor.getPersistentData().getCompound(SNAPSHOT).copy();
        actor.getPersistentData().remove(SNAPSHOT);
        actor.habitat().release();
        actor.independence().abandon();
        actor.house().dropCarrying();
        actor.settlement().duties().dropCarrying();
        actor.stopRiding();
        actor.load(before);
        actor.removeTag("pm_live");
        FamilyData.get((ServerLevel)actor.level()).register(actor);
    }

    /** Creates a fixture relationship, not a feeding/toilet completion. Preserve the chosen age. */
    public static boolean parent(YukkuriEntity parent, YukkuriEntity child, boolean firstToilet) {
        if (parent == child || !parent.getTags().contains("pm_live") || !child.getTags().contains("pm_live")
                || parent.level() != child.level() || !parent.family().adult() || child.family().adult()) return false;
        var growth = child.family().save();
        child.family().generatedChild(parent, null);
        var linked = child.family().save();
        for (String key : java.util.List.of("Stage", "Age", "Nourishment", "Maturity", "Scale", "AdultScale"))
            if (growth.contains(key)) linked.put(key, growth.get(key).copy());
        child.family().load(linked);
        if (firstToilet) child.childhood().newborn();
        FamilyData.get((ServerLevel)child.level()).register(child);
        return true;
    }

    private MuseumLiveActors() {}
}
