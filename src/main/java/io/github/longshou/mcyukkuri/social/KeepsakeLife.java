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
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.PlaceMouthItem;
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
    public enum Phase { IDLE, CARRY_OFF, TALK }

    private static final int SCAN_INTERVAL = 160;
    private static final int CARRY_LIMIT = 600;
    private static final int REFUSED_COOLDOWN = 2400;
    private static final int DONE_COOLDOWN = 600;
    private static final int NOTICE_PAUSE = 15;
    private static final int PICK_FACE_TICKS = 15;
    private static final int TALK_TICKS = 60;
    private static final double TALK_RANGE = 4.0;
    private static final double PEER_GUARD_RANGE = 6.0;
    private static final double RIM_DROP_GAP = 0.32;
    private static final double SET_DOWN_MAX_RIM_DIST = 1.2;

    private record Beat(String name, String declared, String line, String where, String detail) {}
    private record DropSpot(Vec3 stand, Vec3 dropPoint) {}

    private final YukkuriEntity entity;
    private Phase phase = Phase.IDLE;
    private ItemEntity target;
    private DropSpot dropSpot;
    private PlaceMouthItem placeAction;
    private BlockPos blockedDropAt;
    private int cooldown, talkCooldown, scanWait, elapsed, noticeTicks, carryTicks, talkTicks;
    private boolean picking, placing, carryLogged, dropRefused, talkAddressed;
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

    /** Shared beat logger for DailyLife doll nuzzle, LifeMeal held-out bite, and sleep-entry beats. */
    public void emitExternalBeat(String name, String declaredFace, String line, String where, boolean speak, String detail) {
        if (speak && !"none".equals(line)) entity.speakCue(line);
        pendingBeat = new Beat(name, declaredFace, line, where, detail);
    }

    /** Shared eligibility for doll comfort / talk / meal-offer (called from DailyLife and LifeMeal too). */
    public boolean comfortEligible(boolean allowDailyActive) {
        return entity.isAlive() && !entity.isRemoved() && !entity.inert()
                && !entity.life().isSleeping() && !entity.isPanicking()
                && !entity.isLeashed() && !entity.isPassenger()
                && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !entity.species().catchingBreath()
                && !WildEvents.reserved(entity) && !entity.isCommandPlaying()
                && !entity.operations().active() && (allowDailyActive || !entity.dailyLife().active())
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

    private boolean baseEligible(boolean continuing) {
        return comfortEligible(false) && (continuing || entity.onGround());
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
        if (talkCooldown > 0) talkCooldown--;
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
        if (tryStartTalk()) return true;
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

    private boolean tryStartTalk() {
        if (talkCooldown > 0 || entity.family().adult()) return false;
        // The turn to the doll is the first beat: a body whose motor another action holds would wait out the whole talk.
        if (entity.motor().current() != null) return false;
        if (!baseEligible(false) || entity.life().hasUrgentNeed()
                || entity.life().nutrition() < 30 || entity.life().mealTicks() > 0) return false;
        if (!Keepsakes.wantsComfort(entity)) return false;
        if (Keepsakes.hasAwakePeerNear(level(), entity, PEER_GUARD_RANGE)) return false;
        ItemEntity doll = Keepsakes.nearestVisibleDoll(level(), entity, TALK_RANGE, true);
        if (doll == null) return false;
        target = doll;
        phase = Phase.TALK;
        elapsed = 0;
        talkTicks = 0;
        talkAddressed = false;
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(doll, 30, 30);
        return true;
    }

    public boolean canContinue() {
        if (phase == Phase.TALK) {
            return talkTicks < TALK_TICKS && elapsed < TALK_TICKS + 40
                    && baseEligible(true) && !entity.life().hasUrgentNeed()
                    && entity.life().nutrition() >= 30 && entity.life().mealTicks() == 0
                    && target != null && target.isAlive() && !target.isRemoved() && !target.isInWater()
                    && Keepsakes.kinDoll(entity, target.getItem())
                    && entity.distanceToSqr(target) <= (TALK_RANGE + 0.5) * (TALK_RANGE + 0.5)
                    && !Keepsakes.hasAwakePeerNear(level(), entity, PEER_GUARD_RANGE);
        }
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
        if (phase == Phase.TALK) {
            tickTalk();
            return;
        }
        if (picking) {
            tickFetch();
        } else if (placing || !carried().isEmpty()) {
            tickCarryAndSetDown();
        } else {
            tickFetch();
        }
    }

    private void tickTalk() {
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(target, 30, 30);
        if (!talkAddressed) {
            float yaw = SurfaceApproach.yaw(entity.position(), target.position());
            if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) > Face.FACE_ARRIVED) {
                if (entity.motor().owner() != this || entity.motor().current() == null) {
                    entity.motor().run(this, new Face(yaw));
                }
                return;
            }
            talkAddressed = true;
            talkTicks = 0;
            entity.showReaction(YukkuriAction.SWAY, "happy", "", TALK_TICKS);
            emitBeat("address", "happy", "doll_talk", "ground", 0, "doll=" + target.getItem().getItem());
            return;
        }
        talkTicks++;
        if (talkTicks >= TALK_TICKS) {
            talkCooldown = talkRepeatGap();
            finish();
        }
    }

    /** How long a child waits before it speaks to a doll again; the same after a talk it never got to give. */
    private int talkRepeatGap() {
        return 600 + Math.round((100 - entity.life().personality().attachment()) * 6f);
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
                // The evidence is the item that actually left her mouth: its own spot decides the rim distance and the hollow.
                ItemEntity drop = placeAction.released();
                double rimDist = Keepsakes.rimDistance(drop.position(), bed.getBlockPos(), NestDoorway.shape(bed).reach(), bed.centre());
                boolean hollow = NestDoorway.at(entity, drop.position()) != null;
                String detail = String.format(Locale.ROOT, "item=%s at=%s bed=%s bedDist=%.2f hollow=%s",
                        drop.getUUID(), GameplayLog.at(drop.position()), bed.getBlockPos().toShortString(), rimDist, hollow);
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
        placeAction = new PlaceMouthItem(Purpose.KEEPSAKE, new KeepsakeSite(bed, dropSpot.dropPoint()));
        if (entity.motor().run(this, placeAction)) {
            placing = true;
        } else {
            placeAction = null;
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
            return new DropSpot(stand, dropPoint);
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
        }
        if (sleepTicks >= 1 && sleepTicks < 20) {
            ItemEntity doll = Keepsakes.nearestBeside(level(), entity, Keepsakes.BED_RADIUS);
            if (doll != null) {
                float yaw = SurfaceApproach.yaw(entity.position(), doll.position());
                Motor.face(entity, yaw);
                if (sleepTicks == 1) {
                    // Replace any lingering awake reaction (e.g. nuzzle_done) so the sleeping face is honestly "sleep".
                    entity.showReaction("sleep", "", 25);
                }
            }
            return;
        }
        if (sleepTicks == 20) {
            ItemEntity doll = Keepsakes.nearestBeside(level(), entity, Keepsakes.BED_RADIUS);
            if (doll != null) {
                // The doll's own spot says whether it lies in the nest hollow or on open ground beside her.
                String where = NestDoorway.at(entity, doll.position()) != null ? "bed" : "ground";
                double dist = entity.position().subtract(doll.position()).horizontalDistance();
                float targetYaw = SurfaceApproach.yaw(entity.position(), doll.position());
                float yawDiff = Math.abs(Mth.wrapDegrees(targetYaw - entity.yBodyRot));
                String detail = String.format(Locale.ROOT, "dollDist=%.2f yawDiff=%.1f", dist, yawDiff);
                emitExternalBeat("sleep", "sleep", "sleep", where, false, detail);
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
        if (phase == Phase.TALK && !talkAddressed) {
            // A talk that never reached the doll waits the same gap: re-entering as soon as the body is free again is a loop beside it.
            talkCooldown = talkRepeatGap();
            GameplayLog.limited(entity, "keepsake", "talk_given_up", 200,
                    "talk gave up unaddressed elapsed={} gap={}", elapsed, talkCooldown);
        }
        phase = Phase.IDLE;
        target = null;
        dropSpot = null;
        elapsed = 0;
        noticeTicks = 0;
        carryTicks = 0;
        talkTicks = 0;
        picking = false;
        placing = false;
        placeAction = null;
        carryLogged = false;
        dropRefused = false;
        talkAddressed = false;
        SurfaceApproach.stopWalking(entity);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Cooldown", cooldown);
        tag.putInt("TalkCooldown", talkCooldown);
        return tag;
    }

    public void load(CompoundTag tag) {
        finish();
        blockedDropAt = null;
        pendingBeat = null;
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 24000);
        talkCooldown = Math.clamp(tag.getInt("TalkCooldown"), 0, 24000);
    }

    /** The dry spot beside her own nest's rim: the mouth lets go anywhere but inside the nest hollow. */
    private final class KeepsakeSite implements PlaceMouthItem.Site {
        private final CareBlockEntity bed;
        private final Vec3 dropPoint;

        KeepsakeSite(CareBlockEntity bed, Vec3 dropPoint) {
            this.bed = bed;
            this.dropPoint = dropPoint;
        }

        @Override public Vec3 at() { return dropPoint; }

        @Override public Feasibility mouthAt(YukkuriEntity actor, Vec3 mouth) {
            return NestDoorway.at(actor, mouth) == null ? Feasibility.pass()
                    : Feasibility.of(Reason.NO_ROOM, 0, "mouth_inside_hollow");
        }

        @Override public String detail() { return "bed=" + bed.getBlockPos(); }
    }
}
