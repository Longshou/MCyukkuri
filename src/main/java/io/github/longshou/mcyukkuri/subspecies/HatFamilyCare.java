package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** A parent stops rowing to tend real children. Interrupted servings return to cargo or the world. */
public final class HatFamilyCare {
    private enum Kind { NONE, FEED, FIRST_TOILET, NUZZLE, LULLABY }
    private final YukkuriEntity parent;
    private Kind kind = Kind.NONE;
    private UUID child;
    private ItemStack serving = ItemStack.EMPTY;
    private int ticks, deliveryTicks, mealWait, affectionWait, songWait, meals, comforts, songs;
    private int finishTicks, toiletTicks;
    private final java.util.Map<UUID,Long> blocked=new java.util.HashMap<>();
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
    /** Client-sync source: uninterrupted real contact in the active feed or first-toilet care. */
    public int contactProgress() { return kind == Kind.FEED || kind == Kind.FIRST_TOILET ? deliveryTicks : 0; }
    private YukkuriEntity child() {
        return child != null && level().getEntity(child) instanceof YukkuriEntity y ? y : null;
    }
    public String activity() {
        if(parent.getVehicle() instanceof HatBoatEntity boat&&boat.carryingInHat(parent))return "boat_carry_young";
        if(parent.getVehicle() instanceof HatBoatEntity boat&&boat.walkingInHat(parent))return "hat_walk";
        if(finishTicks>0)return "care_finish";
        return switch (kind) { case FEED -> {
            var young=child();yield young!=null&&young.family().needsMash()&&ticks<40?"family_chew":"boat_feed_young";
        } case FIRST_TOILET -> deliveryTicks > 0 ? "first_toilet_assist" : "first_toilet_seek";
            case NUZZLE -> "boat_nuzzle";
            case LULLABY -> "boat_lullaby"; default -> "idle"; };
    }
    public String response(YukkuriEntity young) {
        if(young.getVehicle() instanceof HatBoatEntity boat&&boat.carryingInHat(young))return "boat_be_carried";
        if(young.getVehicle() instanceof HatBoatEntity boat&&boat.walkingInHat(young))return "hat_walk";
        if (kind == Kind.LULLABY && parent.getVehicle() == young.getVehicle()) return "boat_listen";
        if (!young.getUUID().equals(child)) return "idle";
        if (finishTicks > 0) return kind == Kind.FEED && young.life().mealTicks() > 0 ? "boat_eat" : "boat_receive_care";
        return switch (kind) {
            case FEED -> "boat_beg";
            case FIRST_TOILET -> deliveryTicks>=ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? "boat_toilet" : "first_toilet_wait";
            case NUZZLE -> "boat_receive_care";
            default -> "idle";
        };
    }
    public void clock() {
        if (mealWait > 0) mealWait--;
        if (affectionWait > 0) affectionWait--;
        if (songWait > 0) songWait--;
        if (!level().isNight()) bedtimeSung = false;
        blocked.entrySet().removeIf(entry -> entry.getValue() <= level().getGameTime());
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
                && parent.life().mealTicks() == 0 && parent.life().nutrition() >= 30 && parent.life().energy() >= 25;
    }
    /** Feeding has priority over affection. Bedtime singing begins only after reaching quiet water. */
    public boolean tend(HatBoatEntity boat, boolean bedtime) {
        if (!available(boat)) { cancel(); return false; }
        if (boat.transportingInHat(parent)) return active();
        var children = children(boat);
        if (!active()) {
            var hungry = children.stream().filter(y -> y.life().nutrition() < 60 && y.life().canReceiveCareFood() && !y.life().isSleeping()&&blocked.getOrDefault(y.getUUID(),0L)<=level().getGameTime())
                    .min(Comparator.comparingDouble(y -> y.life().nutrition())).orElse(null);
            var toilet = children.stream().filter(y -> y.childhood().needsAssistedFirstToilet()&&y.life().nutrition()>=30
                    &&blocked.getOrDefault(y.getUUID(),0L)<=level().getGameTime()).max(Comparator.comparingDouble(y->y.life().bladder())).orElse(null);
            if (toilet != null && (hungry==null||hungry.life().nutrition()>=30)) begin(Kind.FIRST_TOILET, toilet);
            if (!active() && hungry != null && (mealWait == 0 || hungry.life().nutrition() < 25)) {
                serving = boat.cargo().takeCareMeal(parent,hungry);
                if (!serving.isEmpty()) begin(Kind.FEED, hungry);
            }
            if (!active() && bedtime && !bedtimeSung && songWait == 0 && !children.isEmpty()
                    && children.stream().anyMatch(y -> !y.life().isSleeping())
                    && children.stream().noneMatch(y -> y.life().nutrition() < 30 || y.life().mealTicks() > 0))
                begin(Kind.LULLABY, null);
            if (!active() && !bedtime && !level().isNight() && affectionWait == 0 && parent.life().nutrition() >= 35 && parent.life().energy() >= 25) {
                var lonely = children.stream().filter(y -> y.life().mealTicks() == 0 && !y.life().isSleeping()
                        && y.life().nutrition() >= 35 && y.dailyLife().affectionNeed() >= 35 && parent.distanceToSqr(y) <= 4&&blocked.getOrDefault(y.getUUID(),0L)<=level().getGameTime())
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
        if(kind!=Kind.FEED&&children.stream().anyMatch(y->y.life().nutrition()<25&&y.life().canReceiveCareFood())){cancel();return false;}
        if (finishTicks > 0) {
            finishTicks++;
            // A feeding pair stays in contact for the child's actual swallowing, including slower meals.
            if (kind == Kind.FEED && young != null && young.life().mealTicks() > 0) {
                if (finishTicks >= 160) fail();
                return true;
            }
            if (finishTicks >= 24) cancel();
            return true;
        }
        if (young != null) {
            parent.getLookControl().setLookAt(young, 20, 20);
            // Rear care and the subsequent rim release set the child's orientation deliberately:
            // a generic look-at here would turn its back away from the parent or its rear back over the hat.
            if (kind != Kind.FIRST_TOILET) young.getLookControl().setLookAt(parent, 20, 20);
        }
        if (kind == Kind.FEED) {
            if (young.family().needsMash() && !InfantFood.isMash(serving)) {
                if (ticks < 40) return true;
                serving=InfantFood.mash(serving,parent.getUUID());
            }
            if (!boat.prepareCareSeat(young, parent)) { fail(); return false; }
            boolean prepared=!young.family().needsMash()||InfantFood.isMash(serving);
            if (prepared && boat.careSeatReady(young)) FamilyFeedingContact.faceEachOther(parent, young);
            if (prepared && boat.careSeatReady(young) && FamilyFeedingContact.mouthsMeet(parent,young)) deliveryTicks++; else deliveryTicks=0;
            if (deliveryTicks >= 20) {
                boolean served=young.family().needsMash() ? young.life().beginBraidMeal(parent,serving) : young.life().beginMeal(serving,null,true);
                if (!served) { cancel(); return false; }
                serving = ItemStack.EMPTY; meals++; mealWait = 80;
                YukkuriWorldData.get(level()).relate(young.getUUID(), parent.getUUID(), 2);
                young.life().personality().environment().record(parent.getUUID(), "shared_food", 4);
                parent.speak("share");
                finishTicks=1; return true;
            }
        }
        if (kind == Kind.FIRST_TOILET) {
            if (!boat.prepareRearCareSeat(young, parent)) { fail(); return false; }
            if (deliveryTicks < ChildhoodLife.FIRST_TOILET_CONTACT_TICKS) {
                if (boat.careSeatReady(young)) faceRearCare(parent, young);
                if (boat.careSeatReady(young) && ChildhoodLife.toiletContact(parent, young)) deliveryTicks++; else deliveryTicks=0;
                if (ticks >= 360) { fail(); return false; }
                return true;
            }
            if (!boat.prepareToiletSeat(young, parent)) { fail(); return false; }
            if (!boat.toiletSeatReady(young)) { if (ticks >= 640) fail(); return active(); }
            boat.faceToiletOutward(young);
            parent.getLookControl().setLookAt(young,20,20);
            if(++toiletTicks<40)return true;
            if (!young.childhood().completeAssistedFirstToilet(parent)) { cancel(); return false; }
            parent.speak("caregiver");
            finishTicks=1; return true;
        }
        if(kind==Kind.NUZZLE){
            if(!boat.prepareCareSeat(young,parent)){fail();return false;}
            if(boat.careSeatReady(young))FamilyFeedingContact.faceEachOther(parent,young);
            if(boat.careSeatReady(young)&&FamilyFeedingContact.mouthsMeet(parent,young))deliveryTicks++;else deliveryTicks=0;
        }
        if (kind == Kind.NUZZLE && deliveryTicks == 48) {
            parent.dailyLife().comfort(15); young.dailyLife().comfort(35); comforts++; affectionWait = 600;
            var relations = YukkuriWorldData.get(level());
            relations.relate(parent.getUUID(), young.getUUID(), 2); relations.relate(young.getUUID(), parent.getUUID(), 2);
            young.life().personality().environment().record(parent.getUUID(), "nuzzle", 3);
            parent.life().personality().environment().record(young.getUUID(), "nuzzle", 3);
            finishTicks=1;
        }
        if (kind == Kind.LULLABY) {
            if (ticks % 20 == 1) level().sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE,
                    parent.getX(), parent.getEyeY()+.2, parent.getZ(), 1, .1, .03, .1, 0);
            if (ticks == 80) {
                for (var listener : children) { listener.dailyLife().comfort(10); listener.dailyLife().entertained(20); }
                parent.dailyLife().entertained(15); songs++; songWait = 1200; bedtimeSung = true;
            }
        }
        if (ticks >= (kind == Kind.LULLABY ? 80 : kind == Kind.FIRST_TOILET ? 640 : 480)) {
            if (kind == Kind.LULLABY) cancel(); else fail();
        }
        return true;
    }
    private void begin(Kind next, YukkuriEntity young) {
        kind = next; child = young == null ? null : young.getUUID(); ticks = deliveryTicks = finishTicks = toiletTicks = 0;
        parent.life().wakeInHat();
        if (young != null) young.life().wakeInHat();
        if (next == Kind.LULLABY && parent.getVehicle() instanceof HatBoatEntity boat)
            for (var listener : children(boat)) if (!listener.life().isSleeping()) listener.life().wakeInHat();
        parent.setPersistenceRequired();
        parent.speak(next == Kind.LULLABY ? "song" : next == Kind.NUZZLE ? "nuzzle" : "caregiver");
    }
    public void cancel() {
        if (child != null && parent.getVehicle() instanceof HatBoatEntity boat) boat.releaseCareSeat(child);
        kind = Kind.NONE; child = null; ticks = deliveryTicks = finishTicks = toiletTicks = 0; refund();
    }
    private void fail(){if(child!=null)blocked.put(child,level().getGameTime()+100);cancel();}
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
        if (child != null && parent.getVehicle() instanceof HatBoatEntity boat) boat.releaseCareSeat(child);
        blocked.clear();
        kind = Kind.NONE; child = null; ticks = deliveryTicks = finishTicks = toiletTicks = 0;
        mealWait = Math.clamp(tag.getInt("MealWait"), 0, 80); affectionWait = Math.clamp(tag.getInt("AffectionWait"), 0, 600);
        songWait = Math.clamp(tag.getInt("SongWait"), 0, 1200); bedtimeSung = tag.getBoolean("BedtimeSung");
        meals = Math.max(0, tag.getInt("Meals")); comforts = Math.max(0, tag.getInt("Comforts")); songs = Math.max(0, tag.getInt("Songs"));
        serving = ItemStack.parseOptional(parent.registryAccess(), tag.getCompound("Serving"));
        if (!serving.isEmpty()) serving.setCount(1);
    }

    /** Child faces away from the captain so the parent is physically behind it for first-toilet care. */
    private static void faceRearCare(YukkuriEntity parent, YukkuriEntity child) {
        var delta = child.position().subtract(parent.position());
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(parent,yaw);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(child,yaw);
    }
}
