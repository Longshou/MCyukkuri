package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Server-owned newborn care and learned early movement.
 *
 * <p>The normal family age/nourishment clock remains the source of growth. This class records the
 * things that elapsed time alone must not grant: an actually settled birth, the one assisted first
 * toilet, grounded crawling and (except for shell forms) a real short jump. The late-JUVENILE
 * independence threshold is an intentional project-balance value; it is not a fourth saved stage.</p>
 */
public final class ChildhoodLife {
    /** Late juvenile balance gate: 3/4 of the individual's existing maturity clock. */
    public static final int INDEPENDENCE_AGE_NUMERATOR = 3;
    public static final int INDEPENDENCE_AGE_DENOMINATOR = 4;
    /** Late juvenile balance gate: 9/16 of the same nourishment clock. */
    public static final int INDEPENDENCE_NOURISHMENT_NUMERATOR = 9;
    public static final int INDEPENDENCE_NOURISHMENT_DENOMINATOR = 16;

    /** A parent must remain at the child for this many ticks before the first toilet completes. */
    public static final int FIRST_TOILET_CONTACT_TICKS = 20;
    /** Ground distance, rather than a wait, required to learn basic crawling. */
    public static final double CRAWL_DISTANCE_REQUIRED = .75;

    private enum Task { NONE, FIRST_TOILET, WAIT_CARE, DEMONSTRATE, CRAWL, JUMP }

    private final YukkuriEntity entity;
    private Task task = Task.NONE;
    private UUID careTarget;
    private BlockPos practiceTarget;
    private Vec3 lastPosition;
    private Vec3 lastHatPosition;
    private UUID practiceBoat;
    private boolean hatPractice;
    private double crawlDistance;
    private int careTicks, taskTicks;
    private boolean newborn, landed, firstToiletDone = true, crawled, jumped;
    private boolean jumpLaunched, jumpAirborne, demonstrationSeen;
    private double launchY;

    public ChildhoodLife(YukkuriEntity entity) { this.entity = entity; }

    /** Called only when a new birth has created this entity; existing saves use {@link #load}. */
    public void newborn() {
        if (entity.getVariant().isHouse() || entity.getVariant().isPredator()) {
            newborn = false; landed = firstToiletDone = crawled = jumped = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
            return;
        }
        newborn = true;
        landed = false;
        firstToiletDone = false;
        crawled = false;
        jumped = false;
        jumpLaunched = jumpAirborne = demonstrationSeen = false;
        crawlDistance = 0;
        careTicks = taskTicks = 0;
        careTarget = null;
        practiceTarget = null;
        lastPosition = null;
        entity.setPersistenceRequired();
    }

    /** Updates settled-birth and actual movement evidence after the regular entity physics tick. */
    public void tick() {
        if (entity.level().isClientSide || !newborn) return;
        if (hatPractice && (!(entity.getVehicle() instanceof HatBoatEntity boat)
                || !boat.getUUID().equals(practiceBoat) || !boat.protects(entity) || !safePracticeState())) stopHatPractice();
        if (!landed && settled()) {
            landed = true;
            entity.setPersistenceRequired();
        }
        if (!landed) return;
        observeMovement();
        if (entity.tickCount % 120 == 0 && childAvailable(entity)) {
            if (needsAssistedFirstToilet()) entity.showReaction("teary", "need_toilet", 50);
            else if (entity.family().needsMash() && entity.life().nutrition() < 45) entity.showReaction("teary", "hungry", 60);
        }
    }

    private boolean settled() {
        if (entity.family().awaitingLanding()) return false;
        // A protected hat is the aquatic family's home, including newborn care and practice.
        return entity.onGround() || entity.isPassenger() || entity.getVehicle() instanceof HatBoatEntity;
    }

    private void observeMovement() {
        if (hatPractice) { observeHatMovement(); return; }
        Vec3 here = entity.position();
        if (task == Task.CRAWL && lastPosition != null && !entity.isPassenger() && entity.onGround() && safePracticeState()) {
            double moved = here.subtract(lastPosition).horizontalDistance();
            // A teleport, a boat ride or a stationary timer is not crawling practice.
            if (moved > 1.0e-6 && moved <= .20) {
                crawlDistance = Math.min(CRAWL_DISTANCE_REQUIRED, crawlDistance + moved);
                if (crawlDistance >= CRAWL_DISTANCE_REQUIRED) crawled = true;
            }
        }
        if (task == Task.JUMP && jumpLaunched && safePracticeState() && !entity.isPassenger()) {
            if (!entity.onGround() && entity.getY() > launchY + .02) jumpAirborne = true;
            if (jumpAirborne && entity.onGround() && Math.abs(entity.getY() - launchY) < .5) {
                jumped = true;
                entity.setPersistenceRequired();
            }
        }
        lastPosition = here;
    }

    private void observeHatMovement() {
        if (!(entity.getVehicle() instanceof HatBoatEntity boat) || !boat.getUUID().equals(practiceBoat)) return;
        Vec3 here = boat.toLocal(entity.position());
        if (lastHatPosition != null && task == Task.CRAWL && safePracticeState()) {
            double moved = here.subtract(lastHatPosition).horizontalDistance() * boat.unit();
            if (moved > .001 && moved <= .20) {
                crawlDistance = Math.min(CRAWL_DISTANCE_REQUIRED, crawlDistance + moved);
                if (crawlDistance >= CRAWL_DISTANCE_REQUIRED) crawled = true;
            }
        }
        if (task == Task.JUMP && jumpLaunched && safePracticeState()) {
            double above = (here.y - launchY) * boat.unit();
            if (above > .02) jumpAirborne = true;
            if (jumpAirborne && !boat.practisingMove(entity) && Math.abs(above) < .04) jumped = true;
        }
        lastHatPosition = here;
    }

    /** The aquatic goal calls this while the child is dry and supported; boat drift grants no skill. */
    public boolean practiseInHat(HatBoatEntity boat) {
        if (hatPractice) observeHatMovement();
        if (!newborn || !landed || !firstToiletDone || movementReady() || !boat.protects(entity)
                || !safePracticeState() || boat.flooding() > 0 || boat.towed()) {
            stopHatPractice(); return false;
        }
        var parent = boat.getControllingPassenger() instanceof YukkuriEntity y ? y : null;
        if (parent == null || parent.aquaticLife().care().active() || parent.family().laboring()
                || parent.life().isSleeping() || parent.isPanicking()) { stopHatPractice(); return false; }
        if (hatPractice) {
            if (++taskTicks > 240 || !boat.getUUID().equals(practiceBoat)) { stopHatPractice(); return false; }
            if (!boat.practisingMove(entity) && (task != Task.JUMP || jumped) && boat.completePracticeMove(entity)) {
                // Movement evidence was observed from the actual rider, before retiring the service berth.
                hatPractice = false; practiceBoat = null; lastHatPosition = null;
                task = Task.NONE; jumpLaunched = jumpAirborne = false; taskTicks = 0;
            }
            return true;
        }
        boolean hop = crawled && !shellForm();
        if (boat.changingSeat(entity) || !boat.beginPracticeMove(entity, hop)) return false;
        hatPractice = true; practiceBoat = boat.getUUID(); task = hop ? Task.JUMP : Task.CRAWL; taskTicks = 0;
        lastHatPosition = boat.toLocal(entity.position()); launchY = lastHatPosition.y;
        jumpLaunched = hop; jumpAirborne = false;
        if (hop) entity.life().spendEnergy(.25f);
        return true;
    }

    public void stopHatPractice() {
        if (!hatPractice) return;
        if (entity.getVehicle() instanceof HatBoatEntity boat && boat.getUUID().equals(practiceBoat)) boat.cancelPracticeMove(entity);
        hatPractice = false; practiceBoat = null; lastHatPosition = null;
        task = Task.NONE; taskTicks = 0; jumpLaunched = jumpAirborne = false;
    }

    /** True only for a newborn introduced through the current birth path. */
    public boolean isNewborn() { return newborn; }
    public boolean landed() { return landed; }
    public boolean firstToiletDone() { return firstToiletDone; }
    public boolean active() { return task != Task.NONE; }
    /** Current parent being sought or providing care; null outside a live care action. */
    public UUID careTargetId() { return careTarget; }
    /** Consecutive verified first-toilet contact ticks, for presentation only. */
    public int careProgress() { return task == Task.FIRST_TOILET ? careTicks : 0; }

    /** The child asks for its assisted first toilet only after an actual stored urge appears. */
    public boolean needsAssistedFirstToilet() {
        return newborn && landed && !firstToiletDone && entity.life().bladder() >= 10;
    }

    /** A first toilet or unfinished ground movement keeps a current newborn in the care phase. */
    public boolean needsCare() {
        return newborn && (!landed || !firstToiletDone || !movementReady());
    }

    /** Solid food remains governed by FamilyLife's existing dual age/nourishment gate. */
    public boolean canEatSolid() {
        return (!newborn || landed) && entity.family().canEatIndependently();
    }

    /** Shell forms graduate by grounded crawling; all other newborns also need an actual short jump. */
    public boolean canJump() {
        if (!newborn) return true;
        return landed && !shellForm() && jumped;
    }

    /** Gymnastics and play must follow the assisted first toilet and learned movement. */
    public boolean canExercise() {
        return !newborn || firstToiletDone && movementReady();
    }

    /** Wandering and lessons are deferred until the same safe early movement boundary. */
    public boolean canLearn() { return canExercise(); }

    /**
     * A JUVENILE becomes a capable subadult without changing the persisted stage enum. Adults stay
     * eligible through this method so the existing IndependenceLife flow remains compatible.
     */
    public boolean canLiveIndependently() {
        if (entity.family().adult()) return !newborn || canEatSolid() && firstToiletDone && movementReady();
        long maturity = Math.max(1, entity.family().maturityAge());
        return entity.family().stage() == FamilyLife.Stage.JUVENILE
                && entity.family().age() >= maturity * INDEPENDENCE_AGE_NUMERATOR / INDEPENDENCE_AGE_DENOMINATOR
                && entity.family().nourishment() >= maturity * INDEPENDENCE_NOURISHMENT_NUMERATOR / INDEPENDENCE_NOURISHMENT_DENOMINATOR
                && canEatSolid() && firstToiletDone && movementReady();
    }

    /** Family-follow and the shared childhood bed apply until the child has this independent capability. */
    public boolean shouldStayWithParents() { return !entity.family().adult() && !canLiveIndependently()
            && !entity.independence().independent() && !entity.aquaticLife().firstVoyage().independent(); }

    /** Only a real current action surfaces as activity; identity/state never masks other systems. */
    public String activity() {
        return switch (task) {
            case FIRST_TOILET -> careTicks > 0 ? "first_toilet_assist" : "first_toilet_seek";
            case WAIT_CARE -> "first_toilet_wait";
            case DEMONSTRATE -> "learn_jump";
            case CRAWL -> "newborn_crawl";
            case JUMP -> "learn_jump";
            default -> "idle";
        };
    }

    public boolean canUse() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi() || task != Task.NONE) return false;
        if (parentAvailable(entity) && !entity.isPassenger()) {
            var child = ((ServerLevel) entity.level()).getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10),
                    other -> entity.family().caresFor(other) && !other.isPassenger() && childAvailable(other)
                            && other.childhood().needsAssistedFirstToilet() && mayHelp(other)).stream()
                    .min(java.util.Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (child != null) { task = Task.FIRST_TOILET; careTarget = child.getUUID(); careTicks = taskTicks = 0; return true; }
            var learner = ((ServerLevel) entity.level()).getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(3),
                    other -> entity.family().caresFor(other) && other.childhood().readyToPractiseJump()
                            && !other.childhood().demonstrationSeen && entity.hasLineOfSight(other)).stream().findFirst().orElse(null);
            if (learner != null) { task = Task.DEMONSTRATE; careTarget = learner.getUUID(); taskTicks = 0; return true; }
        }
        if (!newborn || !landed || !childAvailable(entity) || entity.isPassenger()) return false;
        if (needsAssistedFirstToilet()) {
            var parent = availableParent();
            if (parent != null && parent.childhood().task == Task.FIRST_TOILET && entity.getUUID().equals(parent.childhood().careTarget)) {
                task = Task.WAIT_CARE; careTarget = parent.getUUID(); taskTicks = 0; return true;
            }
            return false;
        }
        if (!safePracticeState() || !firstToiletDone) return false;
        if (!crawled) { task = Task.CRAWL; taskTicks = 0; return true; }
        if (readyToPractiseJump()) { task = Task.JUMP; taskTicks = 0; return true; }
        return false;
    }

    private boolean readyToPractiseJump() {
        return newborn && landed && firstToiletDone && crawled && !shellForm() && !jumped
                && !entity.isPassenger() && safePracticeState();
    }

    private boolean mayHelp(YukkuriEntity child) {
        var helper = child.childhood().person(child.childhood().careTarget);
        return helper == null || helper == entity || helper.childhood().task != Task.FIRST_TOILET;
    }

    public boolean canContinue() {
        var target = person(careTarget);
        return switch (task) {
            case FIRST_TOILET -> taskTicks < 300 && parentAvailable(entity) && !entity.isPassenger() && target != null
                    && entity.family().caresFor(target) && !target.isPassenger() && childAvailable(target) && target.childhood().needsAssistedFirstToilet();
            case WAIT_CARE -> taskTicks < 300 && childAvailable(entity) && needsAssistedFirstToilet() && target != null
                    && target.childhood().task == Task.FIRST_TOILET && entity.getUUID().equals(target.childhood().careTarget);
            case DEMONSTRATE -> taskTicks < 40 && parentAvailable(entity) && target != null && target.childhood().readyToPractiseJump();
            case CRAWL -> !crawled && taskTicks < 240 && !entity.isPassenger() && safePracticeState() && firstToiletDone;
            case JUMP -> !jumped && taskTicks < 120 && !entity.isPassenger() && safePracticeState() && firstToiletDone;
            default -> false;
        };
    }

    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        taskTicks++;
        switch (task) {
            case FIRST_TOILET -> tickFirstToilet();
            case WAIT_CARE -> entity.getNavigation().stop();
            case DEMONSTRATE -> {
                entity.getNavigation().stop();
                var child = person(careTarget);
                if (child != null && taskTicks == 1 && entity.onGround()) {
                    entity.getLookControl().setLookAt(child, 30, 30);
                    entity.setDeltaMovement(0, .24, 0); entity.hasImpulse = true;
                    child.childhood().demonstrationSeen = true;
                }
            }
            case CRAWL -> tickCrawl();
            case JUMP -> tickJump();
            default -> { }
        }
    }

    public void stop() {
        stopHatPractice();
        if (task != Task.NONE) entity.getNavigation().stop();
        task = Task.NONE;
        careTarget = null;
        practiceTarget = null;
        careTicks = taskTicks = 0;
        jumpLaunched = jumpAirborne = false;
    }

    private void tickFirstToilet() {
        var child = person(careTarget);
        if (child == null) { stop(); return; }
        Vec3 forward = Vec3.directionFromRotation(0, child.getYRot());
        double reach = (entity.getBbWidth() + child.getBbWidth()) * .5 + .08;
        Vec3 spot = child.position().subtract(forward.scale(reach));
        entity.getLookControl().setLookAt(child, 30, 30);
        if (!toiletContact(entity, child)) {
            careTicks = 0;
            if (entity.distanceToSqr(spot) <= 4) {
                entity.getNavigation().stop(); entity.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, .65);
            } else if (taskTicks % 10 == 1 && !entity.getNavigation().moveTo(spot.x, spot.y, spot.z, .9)) stop();
            return;
        }
        entity.getNavigation().stop(); child.getNavigation().stop();
        float yaw = child.getYRot(); entity.setYRot(yaw); entity.yBodyRot = yaw; entity.setYHeadRot(yaw);
        if (++careTicks < FIRST_TOILET_CONTACT_TICKS) return;
        child.childhood().completeAssistedFirstToilet(entity);
        stop();
    }

    /** The caregiver's mouth reaches the child's rear; ordinary proximity or face-to-face contact is insufficient. */
    public static boolean toiletContact(YukkuriEntity parent, YukkuriEntity child) {
        if (parent.level() != child.level() || !parent.hasLineOfSight(child) || !child.hasLineOfSight(parent)) return false;
        Vec3 delta = parent.position().subtract(child.position()).multiply(1, 0, 1);
        double distance = delta.length(), radii = (parent.getBbWidth() + child.getBbWidth()) * .5;
        if (distance < radii * .75 || distance > radii + .18 || Math.abs(parent.getY() - child.getY()) > .65) return false;
        Vec3 forward = Vec3.directionFromRotation(0, child.getYRot());
        Vec3 parentForward = Vec3.directionFromRotation(0, parent.getYHeadRot());
        return delta.scale(1 / distance).dot(forward) <= -.75 && parentForward.dot(forward) >= .70;
    }

    /** Only a successful physical drop retires the first-toilet contract, ashore or in a dry hat. */
    public boolean completeAssistedFirstToilet(YukkuriEntity parent) {
        boolean hatAssistance = entity.getVehicle() instanceof HatBoatEntity boat && parent != null && parent.getVehicle() == boat
                && boat.protects(parent) && boat.protects(entity) && boat.toiletAssistanceValidated(entity) && boat.toiletSeatReady(entity);
        if (!needsAssistedFirstToilet() || !childAvailable(entity) || !parentAvailable(parent)
                || !parent.family().caresFor(entity) || !(hatAssistance || toiletContact(parent, entity))) return false;
        if (!entity.life().completeAssistedFirstToilet()) return false;
        firstToiletDone = true;
        entity.setPersistenceRequired();
        return true;
    }

    private void tickCrawl() {
        if (practiceTarget == null || entity.distanceToSqr(Vec3.atBottomCenterOf(practiceTarget)) < .09) practiceTarget = nextPracticeTarget();
        if (practiceTarget == null) { stop(); return; }
        if (taskTicks % 10 == 1 && !entity.getNavigation().moveTo(practiceTarget.getX() + .5, practiceTarget.getY(), practiceTarget.getZ() + .5, .40)) stop();
    }

    private void tickJump() {
        if (!entity.onGround()) return;
        entity.getNavigation().stop();
        if (!jumpLaunched && taskTicks >= 10 && entity.life().spendEnergy(.25f)) {
            // The pre-jump gate in YukkuriEntity deliberately rejects normal jumps. This is a small
            // physical practice impulse; observeMovement grants the ability only after a real takeoff.
            jumpLaunched = true; jumpAirborne = false; launchY = entity.getY();
            Vec3 velocity = entity.getDeltaMovement();
            entity.setDeltaMovement(velocity.x, Math.max(velocity.y, .24), velocity.z);
            entity.hasImpulse = true;
        }
    }

    private boolean movementReady() { return !newborn || landed && crawled && (shellForm() || jumped); }
    private boolean shellForm() { return entity.subspecies().kind() == SubspeciesLife.Kind.SHELL_MARISA; }

    private boolean safePracticeState() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.isOnFire() && !entity.isInWaterOrBubble() && !entity.life().isSleeping()
                && entity.life().mealTicks() == 0 && !entity.life().hasUrgentNeed();
    }

    private static boolean childAvailable(YukkuriEntity child) {
        return child.isAlive() && !child.isNoAi() && !child.isPanicking() && !child.isCommandPlaying()
                && !child.operations().active() && !child.isOnFire() && !child.isInWaterOrBubble()
                && !child.life().isSleeping() && child.life().mealTicks() == 0 && !child.family().awaitingLanding();
    }

    private static boolean parentAvailable(YukkuriEntity parent) {
        return parent != null && childAvailable(parent) && parent.family().adult() && !parent.life().hasUrgentNeed()
                && !parent.family().laboring() && !parent.predator().active() && !parent.independence().active();
    }

    private YukkuriEntity person(UUID id) {
        return id != null && entity.level() instanceof ServerLevel server && server.getEntity(id) instanceof YukkuriEntity other ? other : null;
    }

    private YukkuriEntity availableParent() {
        var mother = person(entity.family().mother());
        if (parentAvailable(mother) && !mother.isPassenger() && entity.distanceToSqr(mother) <= 144) return mother;
        var father = person(entity.family().father());
        return parentAvailable(father) && !father.isPassenger() && entity.distanceToSqr(father) <= 144 ? father : null;
    }

    private BlockPos nextPracticeTarget() {
        BlockPos center = entity.habitat().shelter() == null ? entity.blockPosition() : entity.habitat().shelter().bed(entity.getUUID());
        if (center == null) center = entity.blockPosition();
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = entity.getRandom().nextInt(5) - 2;
            int dz = entity.getRandom().nextInt(5) - 2;
            if (dx == 0 && dz == 0) continue;
            BlockPos candidate = center.offset(dx, 0, dz);
            if (entity.life().dryStand(candidate) && entity.life().reachable(candidate, false)) return candidate;
        }
        return null;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putBoolean("Newborn", newborn);
        tag.putBoolean("Landed", landed);
        tag.putBoolean("FirstToiletDone", firstToiletDone);
        tag.putBoolean("Crawled", crawled);
        tag.putBoolean("Jumped", jumped);
        tag.putBoolean("DemonstrationSeen", demonstrationSeen);
        tag.putDouble("CrawlDistance", crawlDistance);
        return tag;
    }

    /** Legacy entities have no recorded birth-care contract; only current births opt into it. */
    public void load(CompoundTag tag) {
        stop();
        lastPosition = null;
        if (!tag.contains("Version")) {
            newborn = false;
            landed = firstToiletDone = crawled = jumped = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
            return;
        }
        newborn = tag.getBoolean("Newborn");
        landed = tag.getBoolean("Landed");
        firstToiletDone = tag.getBoolean("FirstToiletDone");
        crawled = tag.getBoolean("Crawled");
        jumped = tag.getBoolean("Jumped");
        demonstrationSeen = tag.getBoolean("DemonstrationSeen");
        crawlDistance = Math.clamp(tag.getDouble("CrawlDistance"), 0, CRAWL_DISTANCE_REQUIRED);
        if (!newborn) {
            // A non-newborn has no first-toilet contract; never let missing booleans lock ordinary adults.
            firstToiletDone = crawled = jumped = true;
            landed = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
        }
    }
}
