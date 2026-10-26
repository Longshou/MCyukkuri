package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import java.util.Comparator;

/**
 * Server-owned newborn care and learned early movement.
 *
 * <p>The normal family age/nourishment clock remains the source of growth. This class records the
 * things that elapsed time alone must not grant: an actually settled birth, a successfully swallowed
 * first meal, the one assisted first toilet, grounded crawling and (except for shell forms) a real short jump. The subadult
 * capability gate is a practical qualification, not a fourth saved stage.</p>
 */
public final class ChildhoodLife {
    /** Nutrition remains a practical capacity gate; entering subadulthood itself is age-only. */
    public static final int INDEPENDENCE_NOURISHMENT_NUMERATOR = 9;
    public static final int INDEPENDENCE_NOURISHMENT_DENOMINATOR = 16;

    /** Uninterrupted rear-contact ticks before the newborn begins its separate release phase. */
    public static final int FIRST_TOILET_CONTACT_TICKS = 40;
    /** Ground distance, rather than a wait, required to learn basic crawling. */
    public static final double CRAWL_DISTANCE_REQUIRED = .75;

    private enum Task { NONE, FIRST_TOILET, WAIT_CARE, DEMONSTRATE, WATCH, CRAWL, JUMP }

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
    private boolean newborn, landed, firstMealDone = true, firstToiletDone = true, crawled, jumped;
    private boolean jumpLaunched, jumpAirborne, demonstrationSeen;
    private double launchY;
    private int jumpLandings,practiceRest,watchedTicks;
    private boolean demonstrationLaunched,demonstrationAirborne,demonstrationWitnessed;

    public ChildhoodLife(YukkuriEntity entity) { this.entity = entity; }

    /** Called only when a new birth has created this entity; existing saves use {@link #load}. */
    public void newborn() {
        if (entity.profile().houseBody() || entity.profile().dangerToYukkuri()) {
            newborn = false; landed = firstMealDone = firstToiletDone = crawled = jumped = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
            return;
        }
        newborn = true;
        landed = false;
        firstMealDone = false;
        firstToiletDone = false;
        crawled = false;
        jumped = false;
        jumpLaunched = jumpAirborne = demonstrationSeen = false;
        jumpLandings=practiceRest=watchedTicks=0;
        crawlDistance = 0;
        careTicks = taskTicks = 0;
        careTarget = null;
        practiceTarget = null;
        lastPosition = null;
        // The newborn arrives with its first stool already stored (project original). It is released only after the
        // first mash has actually been swallowed, so a prepared or interrupted serving cannot unlock rear care.
        entity.life().primeFirstStool();
        entity.setPersistenceRequired();
    }

    /** Updates settled-birth and actual movement evidence after the regular entity physics tick. */
    public void tick() {
        if (entity.level().isClientSide || !newborn) return;
        if(practiceRest>0)practiceRest--;
        if (hatPractice && (!(entity.getVehicle() instanceof HatBoatEntity boat)
                || !boat.getUUID().equals(practiceBoat) || !boat.protects(entity) || !safePracticeState())) stopHatPractice();
        if (!landed && settled()) {
            landed = true;
            GameplayLog.event(entity, "childhood", "birth settled vehicle={}", entity.getVehicle() == null ? null : entity.getVehicle().getUUID());
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
                int before=(int)(crawlDistance/.25);
                crawlDistance = Math.min(CRAWL_DISTANCE_REQUIRED, crawlDistance + moved);
                if((int)(crawlDistance/.25)>before&&!hatPractice){practiceRest=20;stop();}
                completeCrawl();
            }
        }
        if (task == Task.JUMP && jumpLaunched && safePracticeState() && !entity.isPassenger()) {
            if (!entity.onGround() && entity.getY() > launchY + .02) jumpAirborne = true;
            if (jumpAirborne && entity.onGround() && Math.abs(entity.getY() - launchY) < .5) {
                finishPracticeJump();
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
                completeCrawl();
            }
        }
        if (task == Task.JUMP && jumpLaunched && safePracticeState()) {
            double above = (here.y - launchY) * boat.unit();
            if (above > .02) jumpAirborne = true;
            if (jumpAirborne && !boat.practisingMove(entity) && Math.abs(above) < .04) finishPracticeJump();
        }
        lastHatPosition = here;
    }

    /** The aquatic goal calls this while the child is dry and supported; boat drift grants no skill. */
    public boolean practiseInHat(HatBoatEntity boat) {
        if (hatPractice) observeHatMovement();
        if(practiceRest>0&&!hatPractice)return false;
        if (!newborn || !landed || !firstToiletDone || movementReady() || !boat.protects(entity)
                || !safePracticeState() || boat.flooding() > 0) {
            stopHatPractice(); return false;
        }
        var parent = boat.getControllingPassenger() instanceof YukkuriEntity y ? y : null;
        if (parent == null || parent.aquaticLife().care().active() || parent.family().laboring()
                || parent.life().isSleeping() || parent.isPanicking()) { stopHatPractice(); return false; }
        if (hatPractice) {
            if (++taskTicks > 240 || !boat.getUUID().equals(practiceBoat)) { stopHatPractice(); return false; }
            if (!boat.practisingMove(entity) && task == Task.JUMP && jumpLaunched && !jumpAirborne) {
                // A reservation can end without an actual takeoff. Retire that failed attempt
                // immediately; waiting for airborne evidence from an already finished move can
                // never succeed. No landing or skill is awarded by this recovery.
                stopHatPractice(); practiceRest=24; return false;
            }
            if (!boat.practisingMove(entity) && (task != Task.JUMP || !jumpLaunched) && boat.completePracticeMove(entity)) {
                // Movement evidence was observed from the actual rider, before retiring the service berth.
                hatPractice = false; practiceBoat = null; lastHatPosition = null;
                task = Task.NONE; jumpLaunched = jumpAirborne = false; taskTicks = 0;
            }
            return true;
        }
        boolean hop = crawled && !shellForm();
        if (boat.changingSeat(entity) || !boat.beginPracticeMove(entity, hop)) return false;
        if (!boat.practisingMove(entity)) {
            // Accept a live physical move, not only a successful destination reservation.
            boat.cancelPracticeMove(entity); practiceRest=24; return false;
        }
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
    public boolean firstMealDone() { return firstMealDone; }
    public boolean firstToiletDone() { return firstToiletDone; }
    public boolean active() { return task != Task.NONE; }
    public CompoundTag debug() {
        var tag=save();tag.putString("Task",task.name());tag.putInt("TaskTicks",taskTicks);
        tag.putBoolean("HatPractice",hatPractice);tag.putBoolean("JumpLaunched",jumpLaunched);tag.putBoolean("JumpAirborne",jumpAirborne);
        if(practiceBoat!=null)tag.putUUID("PracticeBoat",practiceBoat);
        if(lastHatPosition!=null)tag.putString("LastHatPosition",lastHatPosition.toString());
        return tag;
    }
    /** Current parent being sought or providing care; null outside a live care action. */
    public UUID careTargetId() { return careTarget; }
    /** Consecutive verified first-toilet contact ticks, for presentation only. */
    public int careProgress() { return task == Task.FIRST_TOILET ? careTicks : 0; }

    /** Called only from the actual food-settlement step, never from a handoff or mouth presentation. */
    public void firstMealSwallowed() {
        if (!newborn || firstMealDone) return;
        firstMealDone = true;
        GameplayLog.event(entity, "childhood", "first meal swallowed nutrition={} bladder={}", entity.life().nutrition(), entity.life().bladder());
        entity.setPersistenceRequired();
    }

    /** The child asks for its assisted first toilet only after a settled first meal and its actual stored urge. */
    public boolean needsAssistedFirstToilet() {
        return newborn && landed && firstMealDone && entity.life().mealTicks() == 0
                && !firstToiletDone && entity.life().bladder() >= 10;
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
        return entity.family().subadult()
                && entity.family().nourishment() >= maturity * INDEPENDENCE_NOURISHMENT_NUMERATOR / INDEPENDENCE_NOURISHMENT_DENOMINATOR
                && canEatSolid() && firstToiletDone && movementReady();
    }

    /** Family-follow and the shared childhood bed apply until the child has this independent capability. */
    public boolean shouldStayWithParents() { return !entity.family().adult() && !canLiveIndependently()
            && !entity.independence().independent() && !entity.aquaticLife().firstVoyage().independent(); }

    /** Only a real current action surfaces as activity; identity/state never masks other systems. */
    public String activity() {
        return switch (task) {
            case FIRST_TOILET -> careTicks >= FIRST_TOILET_CONTACT_TICKS ? Behaviors.CARE_FINISH : careTicks > 0 ? Behaviors.FIRST_TOILET_ASSIST : Behaviors.FIRST_TOILET_SEEK;
            case WAIT_CARE -> rearCareResponse();
            case DEMONSTRATE -> Behaviors.LEARN_JUMP;
            case WATCH -> Behaviors.LEARN_WATCH;
            case CRAWL -> Behaviors.NEWBORN_CRAWL;
            case JUMP -> Behaviors.LEARN_JUMP;
            default -> Behaviors.IDLE;
        };
    }

    /** The receiver reacts only to this parent's current, verified contact count. */
    public String rearCareResponse() {
        var helper = person(careTarget);
        if (helper == null || helper.childhood().task != Task.FIRST_TOILET
                || !entity.getUUID().equals(helper.childhood().careTarget) || !toiletContact(helper, entity)) return Behaviors.FIRST_TOILET_WAIT;
        int contact = helper.childhood().careTicks;
        return contact >= FIRST_TOILET_CONTACT_TICKS ? Behaviors.GROUND_TOILET : contact > 0 ? Behaviors.FIRST_TOILET_STIMULATED : Behaviors.FIRST_TOILET_WAIT;
    }

    public boolean canUse() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi() || task != Task.NONE) return false;
        if (parentAvailable(entity) && !entity.isPassenger() && !entity.family().hasUrgentlyHungryChild() && !entity.family().birthDuty()) {
            var child = ((ServerLevel) entity.level()).getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10),
                    other -> entity.family().caresFor(other) && !other.isPassenger() && childAvailable(other)
                            && other.childhood().needsAssistedFirstToilet() && other.life().nutrition()>=30
                            && !careRefused(other) && entity.careApproach().mayTry(other) && mayHelp(other)).stream()
                    .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (child != null) {
                if (!acceptsCare(child)) return false;
                task = Task.FIRST_TOILET; careTarget = child.getUUID(); careTicks = taskTicks = 0;
                GameplayLog.event(entity, "childhood", "first toilet approach child={} bladder={}", careTarget, child.life().bladder());
                BirthStaging.caption(entity, "赤油已吞咽首餐；亲油走到后侧，开始舔舐促进第一次排便");
                return true;
            }
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
        if(practiceRest>0)return false;
        var demonstrator=availableParent();
        if(demonstrator!=null&&demonstrator.childhood().task==Task.DEMONSTRATE&&entity.getUUID().equals(demonstrator.childhood().careTarget)){
            task=Task.WATCH;careTarget=demonstrator.getUUID();taskTicks=0;return true;
        }
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

    private boolean careRefused(YukkuriEntity child) {
        return entity.family().careDecisions().refused(child,CareDecisionLedger.Need.FIRST_TOILET);
    }

    /** First-toilet help is chosen exactly once after a real child and safe contact path are available. */
    private boolean acceptsCare(YukkuriEntity child) {
        boolean urgent=child.life().bladder()>=70;
        return entity.family().careDecisions().decide(child,CareDecisionLedger.Need.FIRST_TOILET,urgent).accepted();
    }

    public boolean canContinue() {
        var target = person(careTarget);
        return switch (task) {
            case FIRST_TOILET -> taskTicks < 300 && parentAvailable(entity) && !entity.isPassenger() && target != null
                    && entity.family().caresFor(target) && !target.isPassenger() && childAvailable(target) && target.childhood().needsAssistedFirstToilet();
            case WAIT_CARE -> taskTicks < 300 && childAvailable(entity) && needsAssistedFirstToilet() && target != null
                    && target.childhood().task == Task.FIRST_TOILET && entity.getUUID().equals(target.childhood().careTarget);
            case DEMONSTRATE -> taskTicks < 100 && parentAvailable(entity) && target != null && target.childhood().readyToPractiseJump();
            case WATCH -> taskTicks<100&&safePracticeState()&&target!=null&&target.childhood().task==Task.DEMONSTRATE&&entity.getUUID().equals(target.childhood().careTarget);
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
                SurfaceApproach.halt(entity);
                var child = person(careTarget);
                boolean watches=child!=null&&child.childhood().task==Task.WATCH&&child.hasLineOfSight(entity)
                        &&child.getLookAngle().multiply(1,0,1).normalize().dot(entity.position().subtract(child.position()).multiply(1,0,1).normalize())>.8;
                if(watches)watchedTicks++;
                else if(!demonstrationLaunched)watchedTicks=0;
                if(!demonstrationLaunched&&watches&&watchedTicks>=10&&entity.onGround()){
                    launchY=entity.getY();demonstrationLaunched=demonstrationWitnessed=true;
                    entity.setDeltaMovement(0,.24,0);entity.hasImpulse=true;
                }
                if(demonstrationLaunched&&!watches)demonstrationWitnessed=false;
                if(demonstrationLaunched&&!entity.onGround()&&entity.getY()>launchY+.04){
                    demonstrationAirborne=true;
                }
                if(demonstrationAirborne&&entity.onGround()){
                    // A short hop has only a few airborne ticks. Require an observed takeoff and
                    // landing, rather than a timer longer than the actual demonstration can last.
                    if(watches&&demonstrationWitnessed)child.childhood().demonstrationSeen=true;
                    stop();
                }
            }
            case WATCH -> {
                var parent=person(careTarget);SurfaceApproach.halt(entity);
                if(parent!=null)SurfaceApproach.face(entity,SurfaceApproach.yaw(entity.position(),parent.position()));
            }
            case CRAWL -> tickCrawl();
            case JUMP -> tickJump();
            default -> { }
        }
    }

    public void stop() {
        if (task == Task.FIRST_TOILET) GameplayLog.limited(entity, "childhood", "care.stop", 200,
                "first toilet goal stopped child={} contact={} elapsed={}", careTarget, careTicks, taskTicks);
        stopHatPractice();
        if (task == Task.FIRST_TOILET) entity.careApproach().cancel();
        if (task != Task.NONE) entity.getNavigation().stop();
        task = Task.NONE;
        careTarget = null;
        practiceTarget = null;
        careTicks = taskTicks = 0;
        jumpLaunched = jumpAirborne = false;
        demonstrationLaunched=demonstrationAirborne=demonstrationWitnessed=false;watchedTicks=0;
    }

    private void tickFirstToilet() {
        var child = person(careTarget);
        if (child == null) { stop(); return; }
        var approach=entity.careApproach().tick(child,CareApproach.Kind.REAR);
        if(approach==CareApproach.Result.FAILED){
            GameplayLog.limited(entity, "childhood", "care.approach_failed", 200,
                    "first toilet approach failed child={} elapsed={}", careTarget, taskTicks);
            stop();return;
        }
        if(approach!=CareApproach.Result.CONTACT){careTicks=0;return;}
        entity.careApproach().hold();
        if (careTicks == 0) GameplayLog.limited(entity, "childhood", "care.contact", 200,
                "first toilet contact child={} elapsed={}", careTarget, taskTicks);
        if (++careTicks < FIRST_TOILET_CONTACT_TICKS + ToiletContact.RELEASE_TICKS) return;
        child.childhood().completeAssistedFirstToilet(entity);
        stop();
    }

    /** The caregiver's mouth reaches the child's rear; ordinary proximity or face-to-face contact is insufficient. */
    public static boolean toiletContact(YukkuriEntity parent, YukkuriEntity child) {
        return FamilyFeedingContact.rearContact(parent, child);
    }

    /** Only a successful physical drop retires the first-toilet contract, ashore or in a dry hat. */
    public boolean assistedReleaseReady(YukkuriEntity parent) {
        boolean hatAssistance = entity.getVehicle() instanceof HatBoatEntity boat && parent != null && parent.getVehicle() == boat
                && boat.protects(parent) && boat.protects(entity) && boat.toiletAssistanceValidated(entity) && boat.toiletSeatReady(entity)
                && parent.aquaticLife().care().firstToiletReleaseReady(entity);
        if (!needsAssistedFirstToilet() || !childAvailable(entity) || !parentAvailable(parent)
                || !parent.family().caresFor(entity) || !(hatAssistance || parent.childhood().task == Task.FIRST_TOILET
                    && entity.getUUID().equals(parent.childhood().careTarget)
                    && parent.childhood().careTicks >= FIRST_TOILET_CONTACT_TICKS + ToiletContact.RELEASE_TICKS
                    && toiletContact(parent, entity))) return false;
        return true;
    }

    public boolean completeAssistedFirstToilet(YukkuriEntity parent) {
        if (!assistedReleaseReady(parent) || !entity.life().completeAssistedFirstToilet(parent)) return false;
        firstToiletDone = true;
        parent.family().careDecisions().complete(entity,CareDecisionLedger.Need.FIRST_TOILET);
        GameplayLog.event(entity, "childhood", "first toilet completed parent={} bladder={}", parent.getUUID(), entity.life().bladder());
        BirthStaging.caption(parent, "赤油在吞咽首餐后排出了第一次便便，首便完成");
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
            entity.setDeltaMovement(velocity.x, Math.max(velocity.y, .16+.025*Math.min(3,jumpLandings)), velocity.z);
            entity.hasImpulse = true;
        }
    }

    private void finishPracticeJump(){
        boolean wasJumped = jumped;
        jumpLandings++;jumped=jumpLandings>=(demonstrationSeen?3:5);
        if (!wasJumped && jumped) GameplayLog.event(entity, "childhood",
                "jump learned landings={} demonstrationSeen={} boat={}", jumpLandings, demonstrationSeen, practiceBoat);
        jumpLaunched=jumpAirborne=false;practiceRest=24;entity.setPersistenceRequired();
        if(!hatPractice)stop();
    }
    private void completeCrawl() {
        if (crawlDistance < CRAWL_DISTANCE_REQUIRED) return;
        if (!crawled) GameplayLog.event(entity, "childhood", "crawl learned distance={} boat={}", crawlDistance, practiceBoat);
        crawled = true;
    }

    private boolean movementReady() { return !newborn || landed && crawled && (shellForm() || jumped); }
    private boolean shellForm() { return entity.subspecies().kind() == SubspeciesLife.Kind.SHELL_MARISA; }

    private boolean safePracticeState() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.isOnFire() && !entity.isInWaterOrBubble() && !entity.life().isSleeping()
                && entity.life().mealTicks() == 0 && !entity.life().hasUrgentNeed();
    }

    static boolean childAvailable(YukkuriEntity child) {
        return child.isAlive() && !child.isNoAi() && !child.isPanicking() && !child.isCommandPlaying()
                && !child.operations().active() && !child.isOnFire() && !child.isInWaterOrBubble()
                && !child.life().isSleeping() && child.life().mealTicks() == 0 && !child.family().awaitingLanding();
    }

    static boolean parentAvailable(YukkuriEntity parent) {
        return parent != null && childAvailable(parent) && parent.family().adult() && !parent.life().hasUrgentNeed()
                && parent.subspecies().congenital() != SubspeciesLife.Congenital.BEAN_BODY
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
        tag.putInt("Version", 2);
        tag.putBoolean("Newborn", newborn);
        tag.putBoolean("Landed", landed);
        tag.putBoolean("FirstMealDone", firstMealDone);
        tag.putBoolean("FirstToiletDone", firstToiletDone);
        tag.putBoolean("Crawled", crawled);
        tag.putBoolean("Jumped", jumped);
        tag.putBoolean("DemonstrationSeen", demonstrationSeen);
        tag.putInt("JumpLandings",jumpLandings);tag.putInt("PracticeRest",practiceRest);
        tag.putDouble("CrawlDistance", crawlDistance);
        return tag;
    }

    /** Entities without a recorded birth-care contract (spawn eggs, commands) are not newborns; only real births opt in. */
    public void load(CompoundTag tag) {
        stop();
        lastPosition = null;
        if (!tag.contains("Version")) {
            newborn = false;
            landed = firstMealDone = firstToiletDone = crawled = jumped = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
            return;
        }
        newborn = tag.getBoolean("Newborn");
        landed = tag.getBoolean("Landed");
        firstToiletDone = tag.getBoolean("FirstToiletDone");
        // Version 1 saved the first-toilet result but had no first-meal evidence. Preserve a completed
        // contract; every unfinished one waits for a newly observed swallow instead of inferring from bladder.
        firstMealDone = firstToiletDone || tag.contains("FirstMealDone") && tag.getBoolean("FirstMealDone");
        crawled = tag.getBoolean("Crawled");
        jumped = tag.getBoolean("Jumped");
        jumpLandings=Math.clamp(tag.getInt("JumpLandings"),0,5);
        if(jumped)jumpLandings=Math.max(3,jumpLandings);
        practiceRest=Math.clamp(tag.getInt("PracticeRest"),0,24);
        demonstrationSeen = tag.getBoolean("DemonstrationSeen");
        crawlDistance = Math.clamp(tag.getDouble("CrawlDistance"), 0, CRAWL_DISTANCE_REQUIRED);
        if (!newborn) {
            // A non-newborn has no first-toilet contract; never let missing booleans lock ordinary adults.
            firstMealDone = firstToiletDone = crawled = jumped = true;
            landed = true;
            crawlDistance = CRAWL_DISTANCE_REQUIRED;
        }
    }
}
