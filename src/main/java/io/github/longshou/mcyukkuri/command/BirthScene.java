package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Disposable museum choreography. Uses the production mesh, timing and hat geometry, never a family ledger. */
public final class BirthScene {
    public static final String HAT_TAG = "pm_birth_hat";
    private static final int APPROACH = 64, HAT_OFF = 32, START = APPROACH + HAT_OFF;
    private final YukkuriEntity mother, helper;
    private final List<YukkuriEntity> children;
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final String mode;
    private final FamilyLife.HatPose hatPose;
    private final FamilyLife.Stand helperStand;
    private final Vec3 helperStart;
    private final Vec3[] exits, seats, departures;
    private final int[] flights, departureFlights;
    private HatBoatEntity hat;
    private int tick, born;
    private boolean stopped;

    private record Snapshot(YukkuriEntity actor, Vec3 at, float yaw, boolean gravity) {
        CompoundTag save() {
            var tag = new CompoundTag();
            tag.putDouble("X", at.x); tag.putDouble("Y", at.y); tag.putDouble("Z", at.z);
            tag.putFloat("Yaw", yaw); tag.putBoolean("NoGravity", gravity); return tag;
        }
    }

    public static boolean eligible(YukkuriEntity actor) {
        return actor.isAlive() && actor.isNoAi() && actor.getTags().contains("pm_actor")
                && !actor.isPassenger() && actor.family().brood() == null;
    }

    public BirthScene(YukkuriEntity mother, YukkuriEntity helper, List<YukkuriEntity> children, String mode) {
        this.mother = mother; this.helper = helper; this.children = List.copyOf(children); this.mode = mode;
        exits = new Vec3[children.size()]; seats = new Vec3[children.size()]; departures = new Vec3[children.size()];
        flights = new int[children.size()]; departureFlights = new int[children.size()];
        var actors = new ArrayList<>(children); actors.add(mother); actors.add(helper);
        for (var actor : actors) {
            snapshots.add(new Snapshot(actor, actor.position(), actor.getYRot(), actor.isNoGravity()));
            actor.setBirthScene(this); actor.setNoGravity(true); actor.playAction("idle");
        }
        for (var actor : actors) actor.syncFamily();
        // Layout comes from the same authored braid endpoint as natural assisted births.
        var brood = new FamilyData.Brood(); brood.kind = FamilyData.BirthKind.LIVE;
        for (var child : children) { var c = new FamilyData.Child(); c.variant = child.getVariant(); c.subspeciesFlags = child.subspeciesFlags(); brood.children.add(c); }
        var normal = FamilyLife.catchPose(mother, brood, helper, HatVessel.Role.HELPER);
        if (mode.equals("boat")) {
            hatPose = new FamilyLife.HatPose(mother.position(), mother.yBodyRot, HatVessel.Mode.FLOAT, HatVessel.Role.SELF);
            var beside = mother.position().add(Vec3.directionFromRotation(0, mother.yBodyRot + 90).scale(2.5));
            helperStand = new FamilyLife.Stand(beside, yaw(beside, mother.position()));
        } else {
            hatPose = new FamilyLife.HatPose(normal.at(), normal.yaw(), mode.equals("bank") ? HatVessel.Mode.FLOAT : HatVessel.Mode.NEST, HatVessel.Role.HELPER);
            helperStand = FamilyLife.stand(hatPose, helper);
        }
        helperStart = helper.position();
        mother.speak("expecting");
    }

    public boolean isMother(YukkuriEntity actor) { return actor == mother; }
    public boolean valid() {
        if (stopped) return false;
        if (snapshots.stream().anyMatch(s -> !s.actor.isAlive() || s.actor.isRemoved() || !s.actor.isNoAi()
                || s.actor.level() != mother.level()
                || ((net.minecraft.server.level.ServerLevel)mother.level()).getEntity(s.actor.getUUID()) != s.actor)) {
            stop(); return false;
        }
        return true;
    }
    public CompoundTag restorePoint(YukkuriEntity actor) {
        return snapshots.stream().filter(s -> s.actor == actor).findFirst().orElseThrow().save();
    }
    public static void restore(YukkuriEntity actor, CompoundTag tag) {
        actor.setNoGravity(tag.getBoolean("NoGravity"));
        place(actor, new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")), tag.getFloat("Yaw"));
    }
    public boolean hidesHat(YukkuriEntity actor) {
        return hat != null && (mode.equals("boat") ? actor == mother : actor == helper);
    }
    public float childScale(YukkuriEntity actor) { return children.contains(actor) ? LiveBirthGeometry.BABY_SCALE : actor.family().scale(); }
    private int delivery() { return tick < START ? -1 : tick - START; }
    private int lastBirth() { return START + LiveBirthGeometry.birthTick(children.size() - 1); }
    public float belly() {
        if (born < children.size()) return LiveBirthGeometry.belly(1, born, children.size());
        return .22f * (1 - LiveBirthGeometry.smooth((tick - lastBirth()) / (float)LiveBirthGeometry.RECOVERY));
    }
    public int deliveryTicks() { return born < children.size() ? delivery() : -1; }
    public CompoundTag liveChildren() {
        var tag = new CompoundTag(); int types = 0; int[] morphs = new int[children.size()];
        for (int i = 0; i < children.size(); i++) { types |= children.get(i).getVariant().ordinal() << (4 * i); morphs[i] = children.get(i).subspeciesFlags(); }
        tag.putInt("Count", children.size()); tag.putInt("Born", (1 << born) - 1);
        tag.putInt("Types", types); tag.putIntArray("Morphs", morphs); return tag;
    }

    /** Called only by the mother's server tick; the rest of the cast shares this controller. */
    public void tick() {
        if (!valid()) return;
        tick++;
        if (tick <= APPROACH) {
            var q = LiveBirthGeometry.smooth(tick / (float)APPROACH);
            place(helper, helperStart.lerp(helperStand.at(), q), yaw(helperStart, helperStand.at()));
            pose(helper, "waddle", tick);
            pose(mother, mode.equals("boat") ? "boat_brood" : "brood", tick);
        } else if (tick < START) {
            place(helper, helperStand.at(), helperStand.yaw());
            pose(helper, mode.equals("boat") ? "midwife" : "hat_off" + suffix(), tick - APPROACH);
        }
        if ((mode.equals("boat") && tick == 1) || (!mode.equals("boat") && tick == START)) {
            if (!createHat()) { stop(); return; }
        }
        if (tick >= START) {
            if (tick == START) mother.speak("birth");
            int phase = Math.max(0, delivery());
            float animation = LiveBirthGeometry.animationTick(phase);
            if (born < children.size()) pose(mother, mode.equals("boat") ? "boat_labor" : "labor", animation);
            else pose(mother, "postpartum", Math.min(tick - lastBirth(), LiveBirthGeometry.RECOVERY - 1));
            if (hat != null) {
                String action = mode.equals("boat") ? "midwife" : "hat_hold" + suffix();
                pose(helper, action, mode.equals("boat") ? animation : LiveBirthGeometry.hatAnimationTick(phase));
            }
            if (born < children.size() && phase >= LiveBirthGeometry.birthTick(born)) {
                int i = born;
                // Capture the exit before the belly shrinks, matching the last rendered emergence frame.
                var outlet = LiveBirthGeometry.modelOutlet(mother, mother.livePregnancyProgress(), 0);
                double front = Math.max(-outlet.z / 16 * mother.growthScale() + .15, mother.getBbWidth() / mother.getScale() * .5 + .15);
                if (mode.equals("boat")) front = -outlet.z / 16 * mother.growthScale() + children.get(i).getBbWidth() * .5 + .02;
                exits[i] = FamilyGeometry.toWorld(mother, new Vec3(0, Math.max(.015, outlet.y / 16 * mother.growthScale() - children.get(i).getBbHeight() * .5), -front));
                flights[i] = HatVessel.solve(exits[i], seats[i], hat, children.get(i).getBbWidth() * .5, HatVessel.TMIN);
                born++;
            }
            for (int i = 0; i < born; i++) {
                int age = phase - LiveBirthGeometry.birthTick(i);
                Vec3 at = age < flights[i] ? HatVessel.arc(exits[i], seats[i], flights[i], age) : seats[i];
                place(children.get(i), at, mother.yBodyRot);
                pose(children.get(i), age < flights[i] ? "sleep" : age < flights[i] + 50 ? "first_wake" : "look_around", Math.max(0, age - flights[i]));
                if (age == flights[i]) { children.get(i).speak("newborn"); helper.speak("caregiver"); }
            }
        }
        // Let the full recovery play before the children leave the cradle; the helper then lifts the empty hat.
        int leaving = lastBirth() + LiveBirthGeometry.RECOVERY;
        if (!mode.equals("boat") && tick >= leaving) {
            var sideways = Vec3.directionFromRotation(0, mother.yBodyRot + 90);
            var forward = Vec3.directionFromRotation(0, mother.yBodyRot);
            for (int i = 0; i < born; i++) {
                int elapsed = tick - leaving - i * 32;
                if (elapsed < 0) continue;
                if (departures[i] == null) {
                    var end = hatPose.at().add(forward.scale(1.3)).add(sideways.scale((i - (children.size() - 1) * .5) * .4));
                    departures[i] = new Vec3(end.x, snapshots.get(children.size()).at.y, end.z);
                    departureFlights[i] = HatVessel.solve(seats[i], departures[i], hat, children.get(i).getBbWidth() * .5, HatVessel.TMIN);
                }
                // The helper nudges each dependent newborn over the brim; do not slide it through the mesh.
                if (elapsed < departureFlights[i]) pose(helper, "nuzzle", elapsed);
                var at = elapsed < departureFlights[i] ? HatVessel.arc(seats[i], departures[i], departureFlights[i], elapsed) : departures[i];
                place(children.get(i), at, mother.yBodyRot);
                pose(children.get(i), "first_wake", Math.min(elapsed, 49));
            }
            int on = leaving + children.size() * 32;
            if (tick == on && hat != null) { hat.discard(); hat = null; }
            if (tick >= on) pose(helper, "hat_on" + suffix(), Math.min(tick - on, 31));
        }
        // Hold the last tableau until the scene's scheduled stop restores the cast and idle hat boats together.
        // Returning a water exhibit early would leave its actors standing in water before that reset runs.
        tick = Math.min(tick, leaving + children.size() * 32 + 80);
    }

    private String suffix() { return HatVessel.aquatic(helper) ? "_aq" : ""; }
    private boolean createHat() {
        hat = ModEntities.HAT_BOAT.get().create(mother.level());
        if (hat == null) return false;
        var owner = mode.equals("boat") ? mother : helper;
        var tag = new CompoundTag();
        tag.putString("State", mode.equals("boat") ? "AFLOAT" : mode.equals("bank") ? "MOORED" : "CRADLE");
        tag.putFloat("HullScale", HatVessel.hatScale(owner)); tag.putUUID("OwnerYukkuri", owner.getUUID());
        tag.putString("Role", mode.equals("boat") ? "SELF" : "HELPER");
        hat.load(tag); hat.addTag(HAT_TAG); hat.setPos(hatPose.at()); hat.setYRot(hatPose.yaw()); hat.setNoGravity(true);
        if (!mother.level().addFreshEntity(hat)) { hat = null; return false; }
        if (mode.equals("boat")) {
            if (!mother.startRiding(hat, true)) return false;
            place(mother, hat.toWorld(hat.ownerSeat(mother)), mother.yBodyRot);
        }
        double[] radii = children.stream().mapToDouble(c -> HatVessel.radius(c.getBbWidth()) / hat.unit()).toArray();
        var packed = HatVessel.pack(hat.mode(), radii, mode.equals("boat") ? "day" : "catch");
        if (packed.size() != children.size()) return false;
        for (int i = 0; i < seats.length; i++) seats[i] = hat.toWorld(packed.get(i));
        return true;
    }

    public void stop() {
        if (stopped) return; stopped = true;
        for (var snapshot : snapshots) if (snapshot.actor.getVehicle() == hat) snapshot.actor.stopRiding();
        if (hat != null) { hat.discard(); hat = null; }
        for (var snapshot : snapshots) {
            var actor = snapshot.actor; actor.setBirthScene(null); restore(actor, snapshot.save());
            actor.playAction("idle"); actor.syncFamily();
        }
    }
    private static float yaw(Vec3 from, Vec3 to) { var d = to.subtract(from); return (float)Math.toDegrees(Math.atan2(-d.x, d.z)); }
    private static void place(YukkuriEntity actor, Vec3 at, float yaw) {
        actor.setPos(at); actor.setDeltaMovement(Vec3.ZERO); actor.setYRot(yaw); actor.yBodyRot = yaw; actor.setYHeadRot(yaw);
    }
    private static void pose(YukkuriEntity actor, String action, float tick) { actor.poseBirthScene(action, (long)tick); }
}
