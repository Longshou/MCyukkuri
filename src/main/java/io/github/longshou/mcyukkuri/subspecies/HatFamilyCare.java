package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** A parent stops rowing to tend real children. Interrupted servings return to cargo or the world. */
public final class HatFamilyCare {
    private enum Kind { NONE, FEED, NUZZLE, LULLABY }
    private final YukkuriEntity parent;
    private Kind kind = Kind.NONE;
    private UUID child;
    private ItemStack serving = ItemStack.EMPTY;
    private int ticks, mealWait, affectionWait, songWait, meals, comforts, songs;
    private boolean bedtimeSung;

    public HatFamilyCare(YukkuriEntity parent) { this.parent = parent; }
    public ItemStack serving() { return serving; }
    public int meals() { return meals; }
    public int comforts() { return comforts; }
    public int songs() { return songs; }
    public boolean active() { return kind != Kind.NONE; }
    public boolean bedtimeReady() { return bedtimeSung; }
    private ServerLevel level() { return (ServerLevel) parent.level(); }
    public int childId() { var y = child(); return y == null ? -1 : y.getId(); }
    private YukkuriEntity child() {
        return child != null && level().getEntity(child) instanceof YukkuriEntity y ? y : null;
    }
    public String activity() {
        return switch (kind) { case FEED -> "boat_feed_young"; case NUZZLE -> "boat_nuzzle";
            case LULLABY -> "boat_lullaby"; default -> "idle"; };
    }
    public String response(YukkuriEntity young) {
        if (kind == Kind.LULLABY && parent.getVehicle() == young.getVehicle()) return "boat_listen";
        if (!young.getUUID().equals(child)) return "idle";
        return kind == Kind.FEED ? "boat_beg" : kind == Kind.NUZZLE ? "boat_receive_care" : "idle";
    }
    public void clock() {
        if (mealWait > 0) mealWait--;
        if (affectionWait > 0) affectionWait--;
        if (songWait > 0) songWait--;
        if (!level().isNight()) bedtimeSung = false;
        if (active() && (!(parent.getVehicle() instanceof HatBoatEntity boat) || !available(boat))) cancel();
        // A saved serving is recovered once; a partially completed interaction never grants its rewards on load.
        if (!active() && !serving.isEmpty()) refund();
    }
    private List<YukkuriEntity> children(HatBoatEntity boat) {
        return boat.getPassengers().stream().filter(p -> p instanceof YukkuriEntity).map(p -> (YukkuriEntity)p)
                .filter(y -> boat.young(y) && y.isAlive() && !y.isNoAi() && !y.isCommandPlaying()
                        && !y.operations().active() && !y.isPanicking() && boat.protects(y)
                        && parent.life().canRecognize(y) && y.life().canRecognize(parent)
                        && parent.hasLineOfSight(y)).toList();
    }
    private boolean available(HatBoatEntity boat) {
        return parent.isAlive() && !parent.isNoAi() && !parent.isCommandPlaying() && !parent.operations().active()
                && parent.getVehicle() == boat && parent.getUUID().equals(boat.ownerYukkuri()) && boat.protects(parent)
                && boat.isAfloat() && !boat.towed() && boat.flooding() == 0 && !boat.hasIncoming()
                && !parent.isPanicking() && !parent.family().laboring() && !parent.life().isSleeping()
                && parent.life().mealTicks() == 0;
    }
    /** Feeding has priority over affection. Bedtime singing begins only after reaching quiet water. */
    public boolean tend(HatBoatEntity boat, boolean bedtime) {
        if (!available(boat)) { cancel(); return false; }
        var children = children(boat);
        if (!active()) {
            var hungry = children.stream().filter(y -> y.life().nutrition() < 60 && y.life().canEat() && !y.life().isSleeping())
                    .min(Comparator.comparingDouble(y -> y.life().nutrition())).orElse(null);
            if (hungry != null && (mealWait == 0 || hungry.life().nutrition() < 25)) {
                serving = boat.cargo().takeMeal(hungry);
                if (!serving.isEmpty()) begin(Kind.FEED, hungry);
            }
            if (!active() && bedtime && !bedtimeSung && songWait == 0 && !children.isEmpty()
                    && children.stream().anyMatch(y -> !y.life().isSleeping())
                    && children.stream().noneMatch(y -> y.life().nutrition() < 30 || y.life().mealTicks() > 0))
                begin(Kind.LULLABY, null);
            if (!active() && !bedtime && !level().isNight() && affectionWait == 0 && parent.life().nutrition() >= 35 && parent.life().energy() >= 25) {
                var lonely = children.stream().filter(y -> y.life().mealTicks() == 0 && !y.life().isSleeping()
                        && y.life().nutrition() >= 35 && y.dailyLife().affectionNeed() >= 35 && parent.distanceToSqr(y) <= 4)
                        .max(Comparator.comparingInt(y -> y.dailyLife().affectionNeed())).orElse(null);
                if (lonely != null) begin(Kind.NUZZLE, lonely);
            }
            if (!active()) return false;
        }
        var young = child();
        if (kind != Kind.LULLABY && (young == null || !children.contains(young) || parent.distanceToSqr(young) > 4
                || young.life().isSleeping() || kind == Kind.NUZZLE && young.life().mealTicks() > 0)) {
            cancel(); return false;
        }
        if (kind == Kind.LULLABY && (!bedtime || children.isEmpty() || children.stream().anyMatch(y -> y.life().nutrition() < 30))) {
            cancel(); return false;
        }
        boat.stopPaddling(); ticks++;
        if (young != null) {
            parent.getLookControl().setLookAt(young, 20, 20);
            young.getLookControl().setLookAt(parent, 20, 20);
        }
        if (kind == Kind.FEED && ticks == 24) {
            if (!young.life().beginMeal(serving.copy(), null, true)) { cancel(); return false; }
            serving = ItemStack.EMPTY; meals++; mealWait = 80;
            YukkuriWorldData.get(level()).relate(young.getUUID(), parent.getUUID(), 2);
            young.life().personality().environment().record(parent.getUUID(), "shared_food", 4);
            parent.speak("share");
        }
        if (kind == Kind.NUZZLE && ticks == 48) {
            parent.dailyLife().comfort(15); young.dailyLife().comfort(35); comforts++; affectionWait = 600;
            var relations = YukkuriWorldData.get(level());
            relations.relate(parent.getUUID(), young.getUUID(), 2); relations.relate(young.getUUID(), parent.getUUID(), 2);
            young.life().personality().environment().record(parent.getUUID(), "nuzzle", 3);
            parent.life().personality().environment().record(young.getUUID(), "nuzzle", 3);
        }
        if (kind == Kind.LULLABY) {
            if (ticks % 20 == 1) level().sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE,
                    parent.getX(), parent.getEyeY()+.2, parent.getZ(), 1, .1, .03, .1, 0);
            if (ticks == 80) {
                for (var listener : children) { listener.dailyLife().comfort(10); listener.dailyLife().entertained(20); }
                parent.dailyLife().entertained(15); songs++; songWait = 1200; bedtimeSung = true;
            }
        }
        if (ticks >= (kind == Kind.LULLABY ? 80 : 48)) cancel();
        return true;
    }
    private void begin(Kind next, YukkuriEntity young) {
        kind = next; child = young == null ? null : young.getUUID(); ticks = 0;
        parent.life().wakeInHat();
        if (young != null) young.life().wakeInHat();
        if (next == Kind.LULLABY && parent.getVehicle() instanceof HatBoatEntity boat)
            for (var listener : children(boat)) if (!listener.life().isSleeping()) listener.life().wakeInHat();
        parent.setPersistenceRequired();
        parent.speak(next == Kind.LULLABY ? "song" : next == Kind.NUZZLE ? "nuzzle" : "caregiver");
    }
    public void cancel() { kind = Kind.NONE; child = null; ticks = 0; refund(); }
    private void refund() {
        if (serving.isEmpty()) return;
        var returned = serving; serving = ItemStack.EMPTY;
        if (parent.getVehicle() instanceof HatBoatEntity boat && boat.protects(parent)) boat.cargo().store(returned, returned.getCount(), false);
        if (!returned.isEmpty()) parent.spawnAtLocation(returned);
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("MealWait", mealWait); tag.putInt("AffectionWait", affectionWait); tag.putInt("SongWait", songWait);
        tag.putInt("Meals", meals); tag.putInt("Comforts", comforts); tag.putInt("Songs", songs); tag.putBoolean("BedtimeSung", bedtimeSung);
        if (!serving.isEmpty()) tag.put("Serving", serving.save(parent.registryAccess()));
        return tag;
    }
    public void load(CompoundTag tag) {
        kind = Kind.NONE; child = null; ticks = 0;
        mealWait = Math.clamp(tag.getInt("MealWait"), 0, 80); affectionWait = Math.clamp(tag.getInt("AffectionWait"), 0, 600);
        songWait = Math.clamp(tag.getInt("SongWait"), 0, 1200); bedtimeSung = tag.getBoolean("BedtimeSung");
        meals = Math.max(0, tag.getInt("Meals")); comforts = Math.max(0, tag.getInt("Comforts")); songs = Math.max(0, tag.getInt("Songs"));
        serving = ItemStack.parseOptional(parent.registryAccess(), tag.getCompound("Serving"));
        if (!serving.isEmpty()) serving.setCount(1);
    }
}
