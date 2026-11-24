package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.perception.Sight;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.play.PlayWant;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * General keepsake behaviour (carry to nest, and future comfort/talk/snatch phases in the same Goal).
 * Nothing about the keepsake or ownership is persisted: phases are derived from real world and mouth state.
 */
public final class KeepsakeLife implements MotorOwner {
    public enum Phase { IDLE, CARRY_OFF }

    private static final int SCAN_INTERVAL = 160;
    private static final int CARRY_LIMIT = 600;
    private static final int REFUSED_COOLDOWN = 2400;
    private static final int DONE_COOLDOWN = 600;
    private static final int NOTICE_PAUSE = 15;
    private static final int PICK_FACE_TICKS = 15;
    private static final double RIM_DROP_GAP = 0.32;
    private static final double SET_DOWN_MAX_RIM_DIST = 1.2;

    private record Beat(String name, String declared, String line, String where, String detail) {}
    private record DropSpot(Vec3 stand, Vec3 dropPoint, double rimDist) {}

    private final YukkuriEntity entity;
    private Phase phase = Phase.IDLE;
    private ItemEntity target;
    private DropSpot dropSpot;
    private BlockPos blockedDropAt;
    private int cooldown, scanWait, elapsed, noticeTicks, carryTicks;
    private boolean picking, placing, carryLogged, dropRefused;
    private Beat pendingBeat;

    public KeepsakeLife(YukkuriEntity entity) {
        this.entity = entity;
    }

    @Override public String ownerId() { return "keepsake"; }
    @Override public boolean active() { return phase != Phase.IDLE || !carried().isEmpty(); }

    public String activity() {
        if (placing) return Behaviors.KEEPSAKE_SET;
        if (!carried().isEmpty()) return Behaviors.KEEPSAKE_CARRY;
        return phase != Phase.IDLE ? Behaviors.IDLE : null;
    }

    public String activityFace() {
        if (!carried().isEmpty() && !placing) return carryFace();
        return "";
    }

    private String carryFace() {
        return entity.life().personality().temperament().timid() >= Temperament.TOP35 ? "flushed_smile" : "closed_smile";
    }

    private MouthOccupancy mouth() { return entity.component(YukkuriComponents.MOUTH); }
    private ItemStack carried() { return mouth().stack(Purpose.KEEPSAKE); }
    private ServerLevel level() { return (ServerLevel) entity.level(); }

    /** Logs each keepsake beat on the exact tick with the actual face published by {@code PresentationArbiter}. */
    public void observeFace(String shown) {
        if (pendingBeat == null) return;
        var beat = pendingBeat;
        pendingBeat = null;
        int dolls = Keepsakes.countNearbyDolls(level(), entity.position(), 32);
        if (beat.detail().isEmpty()) {
            GameplayLog.event(entity, "keepsake", "beat={} face={} declared={} line={} where={} dolls={}",
                    beat.name(), shown, beat.declared(), beat.line(), beat.where(), dolls);
        } else {
            GameplayLog.event(entity, "keepsake", "beat={} face={} declared={} line={} where={} dolls={} {}",
                    beat.name(), shown, beat.declared(), beat.line(), beat.where(), dolls, beat.detail());
        }
    }

    private void emitBeat(String name, String declaredFace, String line, String where, int holdTicks, String detail) {
        if (holdTicks > 0) entity.showReaction(declaredFace, "", holdTicks);
        if (!"none".equals(line)) entity.speakCue(line);
        pendingBeat = new Beat(name, declaredFace, line, where, detail);
    }

    private boolean baseEligible(boolean continuing) {
        return entity.isAlive() && !entity.isRemoved() && !entity.inert()
                && !entity.life().isSleeping() && !entity.isPanicking()
                && !entity.isLeashed() && !entity.isPassenger()
                && (continuing || entity.onGround())
                && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !WildEvents.reserved(entity) && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.dailyLife().active()
                && !entity.playLife().active() && !entity.family().active()
                && !entity.house().active() && !entity.groupInteraction().active()
                && !entity.social().active() && !entity.habitat().active()
                && !entity.settlement().active() && !entity.settlement().duties().active()
                && !entity.forageTrip().outing() && !entity.independence().active()
                && !entity.predator().active() && !entity.pet().busy()
                && !entity.waterDistress().active()
                && entity.status().identity() != Identity.STOCK
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && !ExamSession.running(entity);
    }

    private boolean carryEligible(boolean continuing) {
        return baseEligible(continuing)
                && entity.family().stage() != FamilyLife.Stage.BABY
                && entity.body().condition().readyForPlay()
                && entity.childhood().canExercise()
                && !entity.life().hasUrgentNeed()
                && entity.life().nutrition() >= 50
                && entity.life().mealTicks() == 0;
    }

    private CareBlockEntity ownBed() {
        BlockPos home = entity.life().homeStand();
        return home == null ? null : NestDoorway.bed(entity, home);
    }

    public void clock() {
        if (entity.level().isClientSide || entity.inert() || !entity.isAlive()) return;
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        // A body that kept a keepsake over water puts it down once it reaches dry ground outside the blocked cell.
        if (!carried().isEmpty() && phase == Phase.IDLE && !entity.blockPosition().equals(blockedDropAt)
                && entity.onGround() && !entity.isInWaterOrBubble()) {
            if (!carryEligible(true)) releaseOnSpot(false);
        }
    }

    public boolean canUse() {
        if (!carried().isEmpty()) {
            if (entity.blockPosition().equals(blockedDropAt)) return false;
            if (!carryEligible(true)) {
                releaseOnSpot(false);
                return false;
            }
            if (ownBed() == null) {
                releaseOnSpot(false);
                return false;
            }
            phase = Phase.CARRY_OFF;
            return true;
        }
        if (phase != Phase.IDLE) return canContinue();
        if (cooldown > 0 || scanWait > 0 || !carryEligible(false) || !mouth().freeForExternalWork()) return false;
        if (!PlayWant.wants(entity) || !TravelRange.nearHomeForLeisure(entity)) return false;
        CareBlockEntity bed = ownBed();
        if (bed == null || Keepsakes.hasNearBed(level(), entity, bed.centre())) return false;
        scanWait = SCAN_INTERVAL;

        var personality = entity.life().personality();
        double attach = SocialDisposition.ramp(personality.attachment(), 25, 70);
        if (attach <= 0) return false;
        double boredomPressure = SocialDisposition.ramp(entity.dailyLife().boredom(), PlayWant.boredomFloor(entity), 60);
        double curiosity = personality.temperament().curious();
        double chance = Math.min(1.0, attach * (.50 + .40 * boredomPressure + .25 * curiosity));
        double sightRange = Sight.range(personality.intelligence(), false);
        double maxCarry = 8.0 + personality.attachment() / 5.0 + entity.dailyLife().boredom() / 10.0;

        ItemEntity best = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(sightRange, 3, sightRange),
                item -> item.isAlive() && !item.hasPickUpDelay()
                        && Keepsakes.fond(entity, item.getItem())
                        && !item.isInWater() && entity.life().dryStand(item.blockPosition())
                        && Senses.sees(entity, item)
                        && !Keepsakes.isWithSomeone(level(), item, entity)
                        && item.position().distanceTo(bed.centre()) <= maxCarry
                        && (ContactGeometry.inMouthReach(entity, item)
                            || SurfaceApproach.canApproach(entity, ItemContact.approach(entity, item.position()))))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if (best == null) return false;
        if (entity.getRandom().nextDouble() >= chance) return false;

        DropSpot spot = findDropSpot(bed);
        if (spot == null) {
            cooldown = REFUSED_COOLDOWN;
            return false;
        }

        target = best;
        dropSpot = spot;
        phase = Phase.CARRY_OFF;
        elapsed = 0;
        noticeTicks = NOTICE_PAUSE;
        carryTicks = 0;
        picking = false;
        placing = false;
        carryLogged = false;
        dropRefused = false;
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(best, 30, 30);
        emitBeat("notice", "sparkle", "toy", "ground", NOTICE_PAUSE, "");
        return true;
    }

    public boolean canContinue() {
        if (phase != Phase.CARRY_OFF) return false;
        if (elapsed >= CARRY_LIMIT || !carryEligible(true)) return false;
        if (placing || picking) return true;
        if (!carried().isEmpty()) {
            return !entity.blockPosition().equals(blockedDropAt) && ownBed() != null;
        }
        return target != null && target.isAlive() && !target.isInWater() && Keepsakes.fond(entity, target.getItem());
    }

    public void tickGoal() {
        if (!canContinue()) {
            stop();
            return;
        }
        elapsed++;
        if (picking) {
            tickFetch();
        } else if (placing || !carried().isEmpty()) {
            tickCarryAndSetDown();
        } else {
            tickFetch();
        }
    }

    private void tickFetch() {
        if (picking) {
            if (entity.motor().owner() == this && entity.motor().current() != null) return;
            picking = false;
            if (!carried().isEmpty()) {
                target = null;
                elapsed = 0;
                carryTicks = 0;
                carryLogged = false;
                emitBeat("pick_up", "happy", "mine", "mouth", PICK_FACE_TICKS, "");
                return;
            }
            if (entity.motor().last() instanceof Outcome outcome && outcome.reason() == Reason.NO_CONTACT
                    && target != null && target.isAlive()) {
                return; // item shifted slightly; approach again within elapsed budget
            }
            cooldown = REFUSED_COOLDOWN;
            stop();
            return;
        }
        if (target == null || !target.isAlive()) { stop(); return; }
        entity.getLookControl().setLookAt(target, 30, 30);
        if (noticeTicks > 0) {
            noticeTicks--;
            SurfaceApproach.halt(entity);
            return;
        }
        if (ContactGeometry.inMouthReach(entity, target)) {
            if (!mouth().freeForExternalWork() || target.hasPickUpDelay()) { stop(); return; }
            SurfaceApproach.stopWalking(entity);
            float yaw = SurfaceApproach.yaw(entity.position(), target.position());
            if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) > Face.FACE_ARRIVED) {
                if (entity.motor().owner() != this || entity.motor().current() == null) {
                    entity.motor().run(this, new Face(yaw));
                }
                return;
            }
            boolean started = entity.motor().run(this, new Pick(target, one -> {
                if (!mouth().canHold(Purpose.KEEPSAKE)) return false;
                mouth().set(Purpose.KEEPSAKE, one);
                return true;
            }));
            if (started) {
                picking = true;
            } else {
                cooldown = REFUSED_COOLDOWN;
                stop();
            }
            return;
        }
        Vec3 approach = ItemContact.approach(entity, target.position());
        var result = SurfaceApproach.walkResult(entity, approach, null, 1.0);
        if (result == SurfaceApproach.Result.BLOCKED) {
            cooldown = REFUSED_COOLDOWN;
            stop();
        }
    }

    private void tickCarryAndSetDown() {
        CareBlockEntity bed = ownBed();
        if (bed == null) { stop(); return; }
        carryTicks++;
        if (!carryLogged && carryTicks > PICK_FACE_TICKS) {
            carryLogged = true;
            emitBeat("carry", carryFace(), "none", "mouth", 0, "");
        }
        if (dropSpot == null) {
            dropSpot = findDropSpot(bed);
            if (dropSpot == null) {
                cooldown = REFUSED_COOLDOWN;
                stop();
                return;
            }
        }
        if (placing) {
            if (entity.motor().owner() == this && entity.motor().current() != null) return;
            placing = false;
            if (carried().isEmpty()) {
                if (!carryLogged) {
                    carryLogged = true;
                    emitBeat("carry", carryFace(), "none", "mouth", 0, "");
                }
                String face = entity.life().personality().attachment() >= 60 ? "satisfied" : "closed_smile";
                String line = entity.family().stage() == FamilyLife.Stage.BABY ? "toy" : "thing_san";
                double rimDist = dropSpot.rimDist();
                String detail = String.format(Locale.ROOT, "bedDist=%.2f insideHollow=false", rimDist);
                emitBeat("set_down", face, line, "bed", 60, detail);
                cooldown = DONE_COOLDOWN;
                finish();
                return;
            }
            dropRefused = true;
            cooldown = REFUSED_COOLDOWN;
            stop();
            return;
        }

        double dist = dropSpot.stand().subtract(entity.position()).horizontalDistance();
        if (dist > 0.35) {
            var result = SurfaceApproach.walkResult(entity, dropSpot.stand(), null, 1.0);
            if (result == SurfaceApproach.Result.BLOCKED) {
                // Try another rim side before giving up.
                dropSpot = findDropSpot(bed);
                if (dropSpot == null) {
                    cooldown = REFUSED_COOLDOWN;
                    stop();
                }
            }
            return;
        }

        SurfaceApproach.stopWalking(entity);
        float yaw = SurfaceApproach.yaw(entity.position(), dropSpot.dropPoint());
        if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) > Face.FACE_ARRIVED) {
            if (entity.motor().owner() != this || entity.motor().current() == null) {
                entity.motor().run(this, new Face(yaw));
            }
            return;
        }
        if (!carryLogged) {
            carryLogged = true;
            emitBeat("carry", carryFace(), "none", "mouth", 0, "");
            return;
        }
        if (entity.motor().run(this, new PlaceKeepsakeAction(bed, dropSpot.dropPoint()))) {
            placing = true;
        } else {
            cooldown = REFUSED_COOLDOWN;
            stop();
        }
    }

    private DropSpot findDropSpot(CareBlockEntity bed) {
        var shape = NestDoorway.shape(bed);
        Vec3 centre = bed.centre();
        double rimReach = shape.reach();
        double mouthOff = Math.max(0.18, MouthPlacement.position(entity).subtract(entity.position()).horizontalDistance());
        Direction front = shape.facing();
        Direction[] order = { front.getOpposite(), front.getClockWise(), front.getCounterClockWise(), front };
        for (Direction dir : order) {
            Vec3 normal = new Vec3(dir.getStepX(), 0, dir.getStepZ());
            Vec3 dropPoint = centre.add(normal.scale(rimReach + RIM_DROP_GAP));
            if (NestDoorway.at(entity, dropPoint) != null) continue;
            if (BlockPos.containing(dropPoint).equals(bed.getBlockPos())) continue;
            if (!ItemContact.hasReleaseSupport(entity, dropPoint.add(0, 0.25, 0))) continue;
            double rimDist = Keepsakes.rimDistance(dropPoint, bed.getBlockPos(), rimReach, centre);
            if (rimDist > SET_DOWN_MAX_RIM_DIST) continue;
            Vec3 rawStand = dropPoint.add(normal.scale(mouthOff));
            Vec3 stand = SurfaceApproach.restingFloor(entity, rawStand);
            if (stand == null || NestDoorway.at(entity, stand) != null || NestDoorway.rimAt(entity, stand) != null) continue;
            if (!SurfaceApproach.canApproach(entity, stand)) continue;
            return new DropSpot(stand, dropPoint, rimDist);
        }
        return null;
    }

    /** Called by sleep entry (life sleep or fatigue sleep): put down any held doll first, turn to face a nearby doll, and ease unease while sleeping beside it. */
    public void releaseForSleep() {
        if (active()) stop();
        if (!carried().isEmpty()) releaseOnSpot(false);
    }

    public void onSleepTick(int sleepTicks, boolean waking) {
        if (sleepTicks == 1) {
            releaseForSleep();
            ItemEntity doll = Keepsakes.nearestBeside(level(), entity, Keepsakes.BED_RADIUS);
            if (doll != null) {
                float yaw = SurfaceApproach.yaw(entity.position(), doll.position());
                Motor.face(entity, yaw);
            }
            return;
        }
        if (sleepTicks == 20) {
            ItemEntity doll = Keepsakes.nearestBeside(level(), entity, Keepsakes.BED_RADIUS);
            if (doll != null) {
                emitBeat("sleep", "sleep", "sleep", "bed", 0, "");
            }
            return;
        }
        if (sleepTicks > 20 && !waking && sleepTicks % 600 == 0) {
            if (Keepsakes.nearestBeside(level(), entity, Keepsakes.BED_RADIUS) != null) {
                entity.mind().add(-1, "keepsake_sleep");
            }
        }
    }

    private void releaseOnSpot(boolean routeRefused) {
        if (carried().isEmpty() || !entity.isAlive() || entity.level().isClientSide) return;
        if (entity.blockPosition().equals(blockedDropAt)) return;
        if (mouth().releaseItems(Purpose.KEEPSAKE, 20)) {
            blockedDropAt = null;
            if (routeRefused) cooldown = REFUSED_COOLDOWN;
            else scanWait = Math.max(scanWait, 40);
        } else {
            blockedDropAt = entity.blockPosition();
        }
    }

    public void stop() {
        boolean refused = dropRefused || elapsed >= CARRY_LIMIT;
        if (!carried().isEmpty() && entity.isAlive() && !entity.level().isClientSide) {
            releaseOnSpot(refused);
        }
        if (entity.motor().owner() == this) entity.motor().cancel(this, "keepsake_stop");
        finish();
    }

    private void finish() {
        phase = Phase.IDLE;
        target = null;
        dropSpot = null;
        elapsed = 0;
        noticeTicks = 0;
        carryTicks = 0;
        picking = false;
        placing = false;
        carryLogged = false;
        dropRefused = false;
        SurfaceApproach.stopWalking(entity);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Cooldown", cooldown);
        return tag;
    }

    public void load(CompoundTag tag) {
        finish();
        blockedDropAt = null;
        pendingBeat = null;
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 24000);
    }

    private final class PlaceKeepsakeAction implements Action {
        private static final String CLIP = YukkuriAction.MOUTH_ITEM_PLACE.id();
        private final CareBlockEntity bed;
        private final Vec3 dropPoint;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = marks.length();
        private final int releaseTick = marks.ticks("release", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean placed;

        PlaceKeepsakeAction(CareBlockEntity bed, Vec3 dropPoint) {
            this.bed = bed;
            this.dropPoint = dropPoint;
        }

        @Override public String id() { return CLIP; }

        @Override public Feasibility check(YukkuriEntity actor) {
            if (!actor.onGround() || actor.isPassenger() || actor.isInWaterOrBubble() || actor.isOnFire())
                return Feasibility.of(Reason.NO_SUPPORT, 0, "ground_required");
            if (carried().isEmpty())
                return Feasibility.of(Reason.NO_CONTACT, 0, "mouth_empty");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity actor) {
            actor.getNavigation().stop();
            startTick = actor.level().getGameTime();
            ticks = 0;
            placed = false;
        }

        @Override public Progress tick(YukkuriEntity actor) {
            if (!actor.onGround() || actor.isPassenger() || actor.isInWaterOrBubble() || actor.isOnFire())
                return Outcome.of(Reason.NO_SUPPORT, 0, "ground_required");
            if (actor.position().subtract(dropPoint).horizontalDistanceSqr() > 0.01) {
                Motor.face(actor, SurfaceApproach.yaw(actor.position(), dropPoint));
            }
            Motor.velocity(actor, new Vec3(0, actor.getDeltaMovement().y, 0), CLIP);
            if (!placed && ticks >= releaseTick) {
                if (mouth().purpose() != Purpose.KEEPSAKE || mouth().items(Purpose.KEEPSAKE).isEmpty())
                    return Outcome.of(Reason.NO_CONTACT, 0, "mouth_empty");
                Vec3 mouthPos = MouthPlacement.position(actor);
                if (NestDoorway.at(actor, mouthPos) != null)
                    return Outcome.of(Reason.NO_ROOM, 0, "mouth_inside_hollow");
                ItemStack one = mouth().stack(Purpose.KEEPSAKE).copyWithCount(1);
                ItemEntity drop = ItemContact.releaseSupported(actor, one, 20);
                if (drop == null) return Outcome.of(Reason.NO_ROOM, 0, "drop_refused");
                mouth().takeOne(Purpose.KEEPSAKE);
                placed = true;
            }
            if (++ticks >= length) return new Outcome.Done("bed=" + bed.getBlockPos());
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "bed=" + bed.getBlockPos() + " clipTicks=" + length; }
    }
}
