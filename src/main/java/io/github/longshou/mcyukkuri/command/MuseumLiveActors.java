package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/** Exhibit control only. Once released, inventory, ancestry and homes belong to ordinary life. */
public final class MuseumLiveActors {
    private static final String SNAPSHOT = "MuseumLiveRestore";

    public static boolean prepare(YukkuriEntity actor, String need) {
        if (!(actor.level() instanceof ServerLevel level) || level.getEntity(actor.getUUID()) != actor
                || !actor.getTags().contains("pm_actor") || !actor.isAlive()
                || !java.util.Set.of("observe", "meal", "toilet", "sleep", "house", "gather").contains(need)) return false;
        if (actor.getPersistentData().contains(SNAPSHOT)) {
            // Repeated button presses may not reseed hunger, excretion or sleep in one live run.
            var session = actor.getPersistentData().getCompound(SNAPSHOT);
            return actor.getTags().contains("pm_live") && session.getString("Need").equals(need);
        }
        if (unsafeTransition(actor)) return false;
        MuseumWalkGoal.cancel(actor);
        var before = new CompoundTag();
        before.putInt("ControlVersion", 1); before.putUUID("Actor", actor.getUUID());
        before.putBoolean("NoAI", actor.isNoAi()); before.putString("Need", need);
        // Never store an entity/inventory snapshot that could later duplicate an emitted item or child.
        actor.getPersistentData().put(SNAPSHOT, before);
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

    /** False leaves the live session intact; specialized scene stops must succeed first. */
    public static boolean restore(YukkuriEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || level.getEntity(actor.getUUID()) != actor
                || !actor.getTags().contains("pm_actor") || !actor.isAlive()) return false;
        var data = actor.getPersistentData();
        if (unsafeTransition(actor) || data.contains("MuseumIndependenceSession") || data.contains("MuseumAquaticOwner")
                || actor.getTags().contains("pm_museum_aquatic")) return false;
        if (!data.contains(SNAPSHOT)) return !actor.getTags().contains("pm_live");
        if (!data.contains(SNAPSHOT, Tag.TAG_COMPOUND)) return false;
        var before = data.getCompound(SNAPSHOT);
        if (before.contains("ControlVersion")
                && (!before.hasUUID("Actor") || !before.getUUID("Actor").equals(actor.getUUID()))) return false;
        if (!before.contains("NoAI", Tag.TAG_BYTE)) return false;
        // Legacy full-entity snapshots are deliberately read only for their control bit. Loading
        // their Life/Family/Accessory data would undo real births, replenish food and copy hats.
        MuseumWalkGoal.cancel(actor);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.halt(actor);
        actor.setNoAi(before.getBoolean("NoAI"));
        actor.removeTag("pm_live");
        data.remove(SNAPSHOT);
        FamilyData.get(level).register(actor);
        return true;
    }

    private static boolean unsafeTransition(YukkuriEntity actor) {
        return actor.birthScene() != null || actor.isPassenger() || actor.isVehicle()
                || actor.family().awaitingLanding() || actor.family().flying()
                || actor.family().laboring() || actor.family().braidRescueActive()
                || actor.family().cradlePickup().active() || actor.family().cradleHeld();
    }

    /** Creates a fixture relationship, not a feeding/toilet completion. Preserve the chosen age. */
    public static boolean parent(YukkuriEntity parent, YukkuriEntity child, boolean firstToilet) {
        if (parent == child || !parent.getTags().contains("pm_live") || !child.getTags().contains("pm_live")
                || !parent.getTags().contains("pm_actor") || !child.getTags().contains("pm_actor")
                || !(parent.level() instanceof ServerLevel) || parent.level() != child.level()
                || !parent.isAlive() || !child.isAlive() || !parent.family().adult() || child.family().adult()
                || unsafeTransition(parent) || unsafeTransition(child)) return false;
        // Keep the real ancestry on repeat. An explicit first-toilet button may prepare a baby
        // for another care trial, but must not interrupt ongoing care or affect other life stages.
        if (parent.family().caresFor(child)) {
            if (!firstToilet) return true;
            if (child.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                    || child.childhood().active() || parent.childhood().active()
                    || child.family().active() || parent.family().active()) return false;
            child.childhood().newborn();
            return true;
        }
        if (child.family().mother() != null || child.family().father() != null || child.family().birthId() != null) return false;
        if (firstToilet && (child.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                || child.childhood().active() || parent.childhood().active()
                || child.family().active() || parent.family().active())) return false;
        var growth = child.family().save();
        child.family().generatedChild(parent, null);
        var linked = child.family().save();
        for (String key : java.util.List.of("Stage", "Age", "Nourishment", "Maturity", "Lifespan", "Scale", "AdultScale"))
            if (growth.contains(key)) linked.put(key, growth.get(key).copy());
        child.family().load(linked);
        if (firstToilet) child.childhood().newborn();
        FamilyData.get((ServerLevel)child.level()).register(child);
        return true;
    }

    private MuseumLiveActors() {}
}
