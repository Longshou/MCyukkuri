package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import io.github.longshou.mcyukkuri.settlement.PlantBedding;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The leaf swaddle and the swaddled child's bowel care (docs/CHILDHOOD_LIFECYCLE.md, 树叶襁褓与幼体排便照护; project design).
 *
 * <p>One component plays both roles. On the child it owns the wrap: whether it is worn and closed, the real leaves inside and
 * the stool loads it holds. On a parent it owns the leaves actually harvested from leaf blocks, the folded wrap and the
 * current care task. Effects go through the existing accounts only (YukkuriLife hygiene and bladder, MindLife unease,
 * five-axis friendliness experience); nothing here keeps a second ledger. A closed wrap stops the child's own toileting, so
 * only a parent who really comes, opens it, helps, cleans and wraps again empties the urge; without that care the stool
 * goes into the wrap and the wrap turns from a comfort into a burden. Care happens on the ground only: a wrapped child
 * riding in a hat keeps soiling the wrap until it is ashore again.</p>
 */
public final class LeafSwaddle {
    /** Parents who make and put on a wrap: friendliness and intelligence at least these (project design). */
    public static final int MAKER_FRIENDLINESS = 60, MAKER_INTELLIGENCE = 50;
    /** Leaf blocks harvested for one wrap: the swaddle is a single leaf worn like a diaper (user ruling, docs/CHILDHOOD_LIFECYCLE.md). */
    public static final int LEAVES_PER_WRAP = 1;
    /** A closed wrap asks for care from this bowel load (the ordinary toilet minimum); at the urgent load it soils the wrap. */
    public static final int CARE_URGE = 40, SOIL_URGE = 90;
    /** Stool loads a wrap holds; a further load overflows onto the ground. */
    public static final int MAX_SOIL = 3;
    /** Effect period of a worn wrap, ticks: a clean wrap restores 1 hygiene and eases 1 unease; each soiled load costs as much. */
    static final int EFFECT_TICKS = 600;
    /** Clean wear that counts as one unit of friendliness experience (eight units make one point). */
    static final int FRIENDLY_TICKS = 2400;
    /** Hygiene the parent's cleaning restores. */
    static final int CLEAN_HYGIENE = 25;
    /** An opened wrap that nobody closes again within this time falls off. */
    static final int OPEN_LIMIT = 1200;
    /** Leaves a parent still carries this long without any wrap task and without a dependent child in range are put down. */
    static final int CARRY_LIMIT = 2400;
    static final int FOLD_TICKS = 60, WRAP_TICKS = 40, UNWRAP_TICKS = 30, CLEAN_TICKS = 40, REWRAP_TICKS = 30;
    private static final int SEARCH_RADIUS = 12, LEAF_RADIUS = 8, REFUSAL_TICKS = 1200, PHASE_LIMIT = 300, GATHER_LIMIT = 900, SOIL_RETRY = 100;

    private enum Task { NONE, GATHER, FOLD, WRAP, UNWRAP, ASSIST, CLEAN, REWRAP }

    private final YukkuriEntity entity;
    private final PlantBedding plants;
    // child side: the wrap on this body
    private boolean worn, open;
    private int soil, wearClock, openClock;
    private Vec3 openAt;
    private long soilRetryAt;
    private final List<ItemStack> material = new ArrayList<>();
    private UUID wrapper;
    // parent side: real harvested leaves, the folded wrap and the current task
    private final List<ItemStack> leaves = new ArrayList<>();
    private boolean folded;
    private Task task = Task.NONE;
    private UUID target;
    private int contactTicks, taskTicks, pollWait, carryIdle;
    private final Map<UUID, Long> refusedUntil = new HashMap<>();

    public LeafSwaddle(YukkuriEntity entity) { this.entity = entity; this.plants = new PlantBedding(entity); }

    // ---- child side -----------------------------------------------------------------------------------------------
    public boolean worn() { return worn; }
    public boolean open() { return worn && open; }
    public int soil() { return soil; }
    /** A closed wrap: the child cannot relieve itself and an involuntary release goes into the wrap. */
    public boolean wrapped() { return worn && !open; }
    /**
     * What the client draws: 4 worn, 8 opened and lying flat under the child (only while the child is still on the spot where it was
     * opened; elsewhere the opened wrap is not drawn), bits 4-5 the stool loads inside.
     */
    public int wearBits() {
        if (!worn) return 0;
        if (open) return openAt != null && entity.position().distanceToSqr(openAt) < .25 ? 4 | 8 : 0;
        return 4 | Math.min(3, soil) << 4;
    }
    /** A closed wrap catches an involuntary release (LifeDoses). */
    public boolean catchesRelease() { return wrapped() && entity.isAlive(); }
    /** The swaddled child needs a parent: a real urge to go (not a dry, stuck bowel), stool in the wrap, or a wrap left open. */
    public boolean needsCare() {
        return worn && (open || soil > 0 || entity.life().bladder() >= CARE_URGE && !entity.life().constipated());
    }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        if (pollWait > 0) pollWait--;
        tickCarry();
        if (!worn) return;
        String off = !swaddleAge(entity) ? "outgrown"
                : entity.isOnFire() ? "fire" : entity.isInWaterOrBubble() ? "water" : entity.pet().care().swaddled() ? "cloth_swaddle"
                : open && ++openClock > OPEN_LIMIT ? "left_open" : null;
        if (off != null) { remove(off); return; }
        if (open) return;
        var life = entity.life();
        if (life.bladder() >= SOIL_URGE && !life.constipated() && entity.level().getGameTime() >= soilRetryAt) soil("held");
        wearClock++;
        if (wearClock % EFFECT_TICKS == 0) {
            float hygiene = life.hygiene(), unease = entity.mind().unease();
            if (soil == 0) {
                life.changeHygiene(1); entity.mind().add(-1, "leaf_swaddle");
                if (wearClock % FRIENDLY_TICKS == 0) entity.life().personality().experienced(Trait.FRIENDLINESS, 1);
            } else {
                life.changeHygiene(-soil); entity.mind().add(soil, "soiled_swaddle");
                if (!life.isSleeping()) entity.showReaction("teary", "leak", 60);
            }
            if (wearClock % FRIENDLY_TICKS == 0) GameplayLog.limited(entity, "family", "leaf_swaddle.wear", FRIENDLY_TICKS,
                    "leaf swaddle wear soil={} hygiene={} -> {} unease={} -> {} friendliness={}", soil, hygiene, life.hygiene(),
                    unease, entity.mind().unease(), life.personality().friendliness());
        }
        if (needsCare() && entity.tickCount % 120 == 0 && !life.isSleeping() && !entity.isPanicking()) {
            entity.showReaction("teary", "need_toilet", 50);
            GameplayLog.limited(entity, "family", "leaf_swaddle.waiting", 1200,
                    "leaf swaddle waiting for care soil={} bladder={} hygiene={} vehicle={}", soil, life.bladder(), life.hygiene(),
                    entity.getVehicle() == null ? null : entity.getVehicle().getUUID());
        }
    }

    /**
     * The stool goes into the closed wrap; once it is full, the load overflows onto the ground (or over a hat's brim). False when
     * nothing was released: a blocked overflow keeps the load and waits a few seconds before trying again.
     */
    public boolean soil(String cause) {
        var life = entity.life();
        float bladder = life.bladder(), hygiene = life.hygiene();
        if (soil < MAX_SOIL) {
            life.releaseIntoWrap(); soil++;
            entity.mind().add(6, "soiled_swaddle");
            entity.showReaction("teary", "leak", 80);
            GameplayLog.event(entity, "family", "leaf swaddle soiled cause={} soil={} bladder={} -> {} hygiene={} -> {}",
                    cause, soil, bladder, life.bladder(), hygiene, life.hygiene());
            return true;
        }
        if (!dropStool(true)) {
            soilRetryAt = entity.level().getGameTime() + SOIL_RETRY;
            GameplayLog.limited(entity, "family", "leaf_swaddle.overflow_blocked", 200,
                    "leaf swaddle overflow blocked cause={} soil={} bladder={} retryIn={}", cause, soil, bladder, SOIL_RETRY);
            return false;
        }
        life.releaseIntoWrap(); life.changeHygiene(-10);
        entity.mind().add(8, "soiled_swaddle");
        entity.showReaction("teary", "leak", 80);
        GameplayLog.event(entity, "family", "leaf swaddle overflowed cause={} soil={} bladder={} -> {} hygiene={} -> {} dropped=1",
                cause, soil, bladder, life.bladder(), hygiene, life.hygiene());
        return true;
    }

    /** One real stool from this child: from the anus, else just above the feet. */
    private boolean dropStool(boolean overflow) {
        Vec3 outlet = ToiletContact.anus(entity, 0);
        return ToiletContact.drop(entity, outlet) || ToiletContact.drop(entity, entity.position().add(0, .05, 0));
    }

    private void putOn(YukkuriEntity parent, List<ItemStack> wrap) {
        material.clear(); wrap.forEach(stack -> material.add(stack.copy()));
        worn = true; open = false; openAt = null; soil = wearClock = openClock = 0; wrapper = parent.getUUID();
        entity.mind().add(-2, "leaf_swaddle");
        entity.life().personality().experienced(Trait.FRIENDLINESS, 1);
        entity.showReaction("happy", "swaddle_on", 80);
    }

    /** Opens the wrap for care. A soiled wrap is thrown away with its stool: each load lands as real waste and the leaves are spent. */
    private int openForCare() {
        int dropped = 0;
        if (soil > 0) {
            for (int i = 0; i < soil; i++) if (dropStool(false)) dropped++;
            worn = false; open = false; openAt = null; soil = 0; material.clear(); wearClock = openClock = 0;
        } else { open = true; openClock = 0; openAt = entity.position(); }
        return dropped;
    }

    private void remove(String reason) {
        int leafCount = material.stream().mapToInt(ItemStack::getCount).sum();
        boolean fire = reason.equals("fire"), drop = soil == 0 && !fire;
        int waste = 0;
        if (drop) material.forEach(stack -> entity.spawnAtLocation(stack.copy()));
        else if (!fire) for (int i = 0; i < soil; i++) if (dropStool(false)) waste++;
        // Fire burns the leaves together with the stool they held; those loads are gone, not dropped.
        GameplayLog.event(entity, "family", "leaf swaddle off reason={} leaves={} droppedLeaves={} soil={} droppedWaste={} burnedLoads={}",
                reason, leafCount, drop, soil, waste, fire ? soil : 0);
        worn = open = false; openAt = null; soil = wearClock = openClock = 0; material.clear(); wrapper = null;
    }

    // ---- parent side ----------------------------------------------------------------------------------------------
    /** Only a friendly and clever parent makes a wrap; any caring parent may still answer a swaddled child. */
    public boolean maker() {
        var p = entity.life().personality();
        return p.friendliness() >= MAKER_FRIENDLINESS && p.intelligence() >= MAKER_INTELLIGENCE;
    }
    public boolean caring() { return target != null && task.ordinal() >= Task.WRAP.ordinal(); }
    public boolean caringFor(YukkuriEntity child) { return caring() && child.getUUID().equals(target) && entity.careApproach().cooperatingWith(child); }
    public YukkuriEntity careTarget() { return person(target); }
    public float contactProgress() {
        int length = switch (task) { case WRAP -> WRAP_TICKS; case UNWRAP -> UNWRAP_TICKS; case CLEAN -> CLEAN_TICKS; case REWRAP -> REWRAP_TICKS;
            case ASSIST -> ChildhoodLife.FIRST_TOILET_CONTACT_TICKS; default -> 1; };
        return Math.min(1f, contactTicks / (float) length);
    }
    /** The first carried leaf, shown in the mouth like other carried material. */
    public ItemStack carried() { return leaves.isEmpty() ? ItemStack.EMPTY : leaves.getFirst(); }
    public boolean active() { return task != Task.NONE; }

    /**
     * Leaves nobody needs any more are put down, not kept forever. The idle clock only runs while no dependent child of this parent is in
     * range: a child that is merely asleep, eating or on a refusal cooldown keeps the leaves in the mouth for later.
     */
    private void tickCarry() {
        if (leaves.isEmpty() || task != Task.NONE) { carryIdle = 0; return; }
        if (carryIdle % 200 == 0 && dependentInRange()) { carryIdle = 1; return; }
        if (++carryIdle < CARRY_LIMIT) return;
        int count = leafCount();
        leaves.forEach(stack -> entity.spawnAtLocation(stack.copy()));
        GameplayLog.event(entity, "family", "leaf swaddle carried leaves put down reason=no_dependent_child count={} folded={} idle={}", count, folded, carryIdle);
        leaves.clear(); folded = false; carryIdle = 0;
    }

    public String activity() {
        return switch (task) {
            case GATHER -> plants.harvesting() ? Behaviors.LEAF_SWADDLE_HARVEST : Behaviors.LEAF_SWADDLE_SEEK;
            case FOLD -> Behaviors.LEAF_SWADDLE_FOLD;
            case WRAP -> contactTicks > 0 ? Behaviors.LEAF_SWADDLE_WRAP : Behaviors.LEAF_SWADDLE_APPROACH;
            case UNWRAP -> contactTicks > 0 ? Behaviors.SWADDLE_UNWRAP : Behaviors.LEAF_SWADDLE_APPROACH;
            case ASSIST -> contactTicks > 0 ? Behaviors.SWADDLE_TOILET_ASSIST : Behaviors.LEAF_SWADDLE_APPROACH;
            case CLEAN -> contactTicks > 0 ? Behaviors.SWADDLE_CLEAN : Behaviors.LEAF_SWADDLE_APPROACH;
            case REWRAP -> contactTicks > 0 ? Behaviors.SWADDLE_REWRAP : Behaviors.LEAF_SWADDLE_APPROACH;
            default -> Behaviors.IDLE;
        };
    }

    /** The receiving child's response to this parent's current, verified contact. */
    public String receiverActivity(YukkuriEntity child) {
        boolean touching = contactTicks > 0 && ChildhoodLife.toiletContact(entity, child);
        if (task == Task.ASSIST) return !touching ? Behaviors.FIRST_TOILET_WAIT
                : contactTicks >= ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? Behaviors.GROUND_TOILET : Behaviors.FIRST_TOILET_STIMULATED;
        return touching ? Behaviors.SWADDLE_RECEIVE : Behaviors.CARE_WAIT;
    }

    private static boolean canAssist(YukkuriEntity child) { return child.life().bladder() >= 10 && !child.life().constipated(); }

    public boolean canUse() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi() || task != Task.NONE || pollWait > 0) return false;
        pollWait = 40;
        if (!ChildhoodLife.parentAvailable(entity) || entity.isPassenger() || entity.family().hasUrgentlyHungryChild()
                || entity.family().birthDuty() || entity.family().active() || entity.childhood().active() || entity.habitat().active()) return false;
        var level = (ServerLevel) entity.level();
        var swaddled = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(SEARCH_RADIUS),
                child -> entity.family().caresFor(child) && child.leafSwaddle().needsCare() && ready(child)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if (swaddled != null) {
            var wrap = swaddled.leafSwaddle();
            boolean urgent = wrap.soil > 0 || swaddled.life().bladder() >= 70;
            if (!accepts(swaddled, "care", urgent)) return false;
            begin(swaddled, !wrap.open ? Task.UNWRAP : canAssist(swaddled) ? Task.ASSIST : Task.CLEAN);
            GameplayLog.event(entity, "family", "leaf swaddle care start child={} open={} soil={} bladder={} hygiene={}",
                    target, wrap.open, wrap.soil, swaddled.life().bladder(), swaddled.life().hygiene());
            return true;
        }
        if (!maker()) return false;
        var bare = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(SEARCH_RADIUS),
                child -> entity.family().caresFor(child) && wrapCandidate(child) && ready(child)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if (bare == null) return false;
        // The leaf source is found before the decision is rolled, so a busy shared search budget never re-rolls a decision.
        boolean gather = !folded && leafCount() < LEAVES_PER_WRAP;
        if (gather && !plants.find(entity.blockPosition(), LEAF_RADIUS, pos -> entity.level().getBlockState(pos).is(BlockTags.LEAVES))) {
            if (!plants.refused()) {
                GameplayLog.limited(entity, "family", "leaf_swaddle.no_leaves." + bare.getUUID(), 1200,
                        "leaf swaddle refused child={} reason=no_reachable_leaf_block carried={}", bare.getUUID(), leafCount());
                refusedUntil.put(bare.getUUID(), level.getGameTime() + REFUSAL_TICKS);
            }
            return false;
        }
        if (!accepts(bare, "make", false)) { if (gather) plants.stop(); return false; }
        begin(bare, folded ? Task.WRAP : gather ? Task.GATHER : Task.FOLD);
        GameplayLog.event(entity, "family", "leaf swaddle make start child={} task={} carried={} folded={} source={}",
                target, task, leafCount(), folded, gather ? plants.source() : null);
        return true;
    }

    private void begin(YukkuriEntity child, Task first) { task = first; target = child.getUUID(); contactTicks = taskTicks = 0; }

    private boolean dependentInRange() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(SEARCH_RADIUS),
                child -> entity.family().caresFor(child) && swaddleAge(child) && !child.pet().care().swaddled()
                        && !child.profile().houseBody() && !child.profile().dangerToYukkuri()).isEmpty();
    }

    /** Only a baby (赤油) or a young child (子油) wears the leaf: a subadult or an adult does not, whether or not it lives on its own yet. */
    static boolean swaddleAge(YukkuriEntity child) {
        return !child.family().adult() && !child.family().subadult();
    }

    /** A bare dependent land baby or young child on its own feet, after its first toilet, who does not need to go right now. */
    private static boolean wrapCandidate(YukkuriEntity child) {
        return !child.leafSwaddle().worn && swaddleAge(child)
                && child.childhood().landed() && child.childhood().firstToiletDone() && child.getVehicle() == null
                && !child.pet().care().swaddled() && !child.profile().houseBody()
                && !child.profile().dangerToYukkuri() && child.life().bladder() < CARE_URGE;
    }

    private boolean ready(YukkuriEntity child) {
        if (!ChildhoodLife.childAvailable(child) || child.isPassenger() || child.careApproach().waiting() || refused(child)
                || !entity.careApproach().mayTry(child)) return false;
        for (UUID id : new UUID[]{child.family().mother(), child.family().father()}) {
            var other = id == null || id.equals(entity.getUUID()) ? null : person(id);
            if (other != null && other.leafSwaddle().active() && child.getUUID().equals(other.leafSwaddle().target)) return false;
        }
        return true;
    }

    private boolean refused(YukkuriEntity child) {
        long now = entity.level().getGameTime();
        refusedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        return refusedUntil.getOrDefault(child.getUUID(), 0L) > now;
    }

    /** The willingness roll of every other parental care decision; a refusal stands for a while and has its consequences. */
    private boolean accepts(YukkuriEntity child, String kind, boolean urgent) {
        float chance = entity.life().personality().helpProbability(true, urgent), roll = entity.getRandom().nextFloat();
        GameplayLog.limited(entity, "family", "leaf_swaddle.decision." + kind + "." + child.getUUID(), 200,
                "leaf swaddle decision kind={} child={} urgent={} chance={} roll={} accepted={}", kind, child.getUUID(), urgent, chance, roll, roll < chance);
        if (roll < chance) return true;
        refusedUntil.put(child.getUUID(), entity.level().getGameTime() + REFUSAL_TICKS);
        return false;
    }

    /** Why the current task cannot go on, or null. */
    private String blocker() {
        if (task == Task.NONE) return "no_task";
        var child = person(target);
        if (child == null) return "child_gone";
        if (!ChildhoodLife.parentAvailable(entity)) return "parent_unavailable";
        if (entity.isPassenger()) return "parent_passenger";
        if (!entity.family().caresFor(child)) return "care_relationship_lost";
        if (!ChildhoodLife.childAvailable(child)) return "child_unavailable";
        if (child.isPassenger()) return "child_passenger";
        var wrap = child.leafSwaddle();
        if (taskTicks >= (task == Task.GATHER ? GATHER_LIMIT : PHASE_LIMIT)) return "timeout";
        return switch (task) {
            case GATHER, FOLD, WRAP -> wrap.worn ? "already_wrapped" : task == Task.FOLD && leafCount() < LEAVES_PER_WRAP ? "leaves_missing"
                    : task == Task.WRAP && !folded ? "not_folded" : null;
            case UNWRAP -> wrap.wrapped() ? null : "wrap_not_closed";
            case ASSIST -> canAssist(child) ? null : child.life().constipated() ? "constipated" : "no_load";
            case REWRAP -> wrap.open() ? null : "wrap_not_open";
            default -> null;
        };
    }

    public boolean canContinue() { return blocker() == null; }

    public void tickGoal() {
        String why = blocker();
        if (why != null) { stop(why); return; }
        taskTicks++;
        var child = person(target);
        switch (task) {
            case GATHER -> tickGather(child);
            case FOLD -> {
                SurfaceApproach.halt(entity);
                SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), child.position()));
                if (!entity.onGround() || taskTicks < FOLD_TICKS) return;
                folded = true;
                GameplayLog.event(entity, "family", "leaf swaddle folded child={} leaves={}", target, describe(leaves));
                next(Task.WRAP);
            }
            default -> tickContact(child);
        }
    }

    private void tickGather(YukkuriEntity child) {
        if (!plants.active() && !plants.find(entity.blockPosition(), LEAF_RADIUS, pos -> entity.level().getBlockState(pos).is(BlockTags.LEAVES))) {
            if (plants.refused()) return;
            refusedUntil.put(child.getUUID(), entity.level().getGameTime() + REFUSAL_TICKS);
            stop("no_reachable_leaf_block"); return;
        }
        var source = plants.source();
        var portion = plants.tick();
        if (portion.isEmpty()) return;
        leaves.add(portion.copyWithCount(1));
        GameplayLog.event(entity, "family", "leaf swaddle leaf harvested item={} at={} carried={} child={}", portion.getItem(), source, leafCount(), target);
        if (leafCount() >= LEAVES_PER_WRAP) next(Task.FOLD);
    }

    private void tickContact(YukkuriEntity child) {
        var approach = entity.careApproach().tick(child, CareApproach.Kind.REAR);
        if (approach == CareApproach.Result.FAILED) { stop("approach_failed"); return; }
        if (approach != CareApproach.Result.CONTACT) { contactTicks = 0; return; }
        entity.careApproach().hold();
        if (contactTicks == 0) GameplayLog.limited(entity, "family", "leaf_swaddle.contact." + task, 200,
                "leaf swaddle contact child={} task={} elapsed={}", target, task, taskTicks);
        contactTicks++;
        var wrap = child.leafSwaddle();
        var life = child.life();
        switch (task) {
            case WRAP -> {
                if (contactTicks < WRAP_TICKS) return;
                String used = describe(leaves);
                wrap.putOn(entity, takeLeaves());
                folded = false;
                GameplayLog.event(entity, "family", "leaf swaddle wrapped child={} leaves={} unease={} friendliness={}",
                        target, used, child.mind().unease(), life.personality().friendliness());
                stop("done");
            }
            case UNWRAP -> {
                if (contactTicks < UNWRAP_TICKS) return;
                int soiled = wrap.soil, leafCount = wrap.material.stream().mapToInt(ItemStack::getCount).sum();
                int dropped = wrap.openForCare();
                GameplayLog.event(entity, "family", "leaf swaddle opened child={} soil={} droppedWaste={} discardedLeaves={} bladder={} constipated={}",
                        target, soiled, dropped, soiled > 0 ? leafCount : 0, life.bladder(), life.constipated());
                next(canAssist(child) ? Task.ASSIST : Task.CLEAN);
            }
            case ASSIST -> {
                if (contactTicks < ChildhoodLife.FIRST_TOILET_CONTACT_TICKS + ToiletContact.RELEASE_TICKS) return;
                float before = life.bladder();
                if (!life.assistedRelease()) { stop("release_blocked"); return; }
                GameplayLog.event(entity, "family", "leaf swaddle assisted toilet child={} bladder={} -> {}", target, before, life.bladder());
                next(Task.CLEAN);
            }
            case CLEAN -> {
                if (contactTicks < CLEAN_TICKS) return;
                float hygiene = life.hygiene(), unease = child.mind().unease();
                life.changeHygiene(CLEAN_HYGIENE); child.mind().add(-2, "swaddle_cleaned");
                life.personality().washed(entity.getUUID());
                GameplayLog.event(entity, "family", "leaf swaddle cleaned child={} hygiene={} -> {} unease={} -> {}",
                        target, hygiene, life.hygiene(), unease, child.mind().unease());
                if (wrap.open()) next(Task.REWRAP);
                else if (folded) next(Task.WRAP);
                else if (leafCount() >= LEAVES_PER_WRAP) next(Task.FOLD);
                else stop("no_fresh_leaves");
            }
            case REWRAP -> {
                if (contactTicks < REWRAP_TICKS) return;
                wrap.open = false; wrap.openClock = 0; wrap.openAt = null;
                child.mind().add(-1, "leaf_swaddle");
                life.personality().experienced(Trait.FRIENDLINESS, 1);
                GameplayLog.event(entity, "family", "leaf swaddle rewrapped child={} leaves={} hygiene={} bladder={}",
                        target, describe(wrap.material), life.hygiene(), life.bladder());
                stop("done");
            }
            default -> stop("unknown_phase");
        }
    }

    private void next(Task phase) { task = phase; contactTicks = taskTicks = 0; }

    /** Pre-empted by another goal (the bridge's stop). */
    public void stop() { stop("preempted"); }

    public void stop(String reason) {
        if (task != Task.NONE && !reason.equals("done")) GameplayLog.limited(entity, "family", "leaf_swaddle.stop." + reason, 200,
                "leaf swaddle task stopped reason={} child={} task={} contact={} elapsed={} carried={} folded={}",
                reason, target, task, contactTicks, taskTicks, leafCount(), folded);
        if (task == Task.GATHER && reason.equals("timeout") && target != null)
            refusedUntil.put(target, entity.level().getGameTime() + REFUSAL_TICKS);
        if (task.ordinal() >= Task.WRAP.ordinal()) entity.careApproach().cancel();
        if (task == Task.GATHER) plants.stop();
        if (task != Task.NONE) entity.getNavigation().stop();
        task = Task.NONE; target = null; contactTicks = taskTicks = 0;
    }

    /** Death drops what really exists: the parent's carried leaves, the child's clean leaves or its stool. */
    public void died() {
        stop("death");
        if (worn) remove("death");
        int dropped = leafCount();
        if (dropped > 0) {
            leaves.forEach(stack -> entity.spawnAtLocation(stack.copy()));
            GameplayLog.event(entity, "family", "leaf swaddle carried leaves dropped reason=death count={} folded={}", dropped, folded);
        }
        leaves.clear(); folded = false;
    }

    private List<ItemStack> takeLeaves() {
        var wrap = new ArrayList<ItemStack>();
        int need = LEAVES_PER_WRAP;
        while (need > 0 && !leaves.isEmpty()) { var stack = leaves.removeFirst(); need -= stack.getCount(); wrap.add(stack); }
        return wrap;
    }
    private int leafCount() { return leaves.stream().mapToInt(ItemStack::getCount).sum(); }
    private static String describe(List<ItemStack> stacks) {
        var out = new StringBuilder();
        for (var stack : stacks) out.append(out.isEmpty() ? "" : ",").append(stack.getCount()).append('x').append(stack.getItem());
        return out.isEmpty() ? "none" : out.toString();
    }
    private YukkuriEntity person(UUID id) {
        return id != null && entity.level() instanceof ServerLevel server && server.getEntity(id) instanceof YukkuriEntity other ? other : null;
    }

    /** One status line for both roles (status key needs.leaf_swaddle). */
    public String statusDetail() {
        String child = worn ? (open ? "open" : "closed") + " soil=" + soil + " leaves=" + describe(material) : "none";
        return "wrap=" + child + " task=" + task.name().toLowerCase() + " carried=" + describe(leaves) + " folded=" + folded;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putBoolean("Worn", worn); tag.putBoolean("Open", open); tag.putInt("Soil", soil); tag.putInt("WearClock", wearClock); tag.putInt("OpenClock", openClock);
        if (openAt != null) { tag.putDouble("OpenX", openAt.x); tag.putDouble("OpenY", openAt.y); tag.putDouble("OpenZ", openAt.z); }
        tag.put("Material", stacks(material));
        if (wrapper != null) tag.putUUID("Wrapper", wrapper);
        tag.put("Leaves", stacks(leaves)); tag.putBoolean("Folded", folded);
        return tag;
    }

    public void load(CompoundTag tag) {
        stop("load");
        worn = tag.getBoolean("Worn"); open = worn && tag.getBoolean("Open"); soil = worn ? Math.clamp(tag.getInt("Soil"), 0, MAX_SOIL) : 0;
        wearClock = Math.max(0, tag.getInt("WearClock")); openClock = Math.clamp(tag.getInt("OpenClock"), 0, OPEN_LIMIT);
        openAt = open && tag.contains("OpenX") ? new Vec3(tag.getDouble("OpenX"), tag.getDouble("OpenY"), tag.getDouble("OpenZ")) : null;
        read(tag.getList("Material", Tag.TAG_COMPOUND), material);
        wrapper = tag.hasUUID("Wrapper") ? tag.getUUID("Wrapper") : null;
        read(tag.getList("Leaves", Tag.TAG_COMPOUND), leaves);
        folded = tag.getBoolean("Folded") && leafCount() >= LEAVES_PER_WRAP;
        soilRetryAt = 0;
    }

    private ListTag stacks(List<ItemStack> stacks) {
        var list = new ListTag();
        for (var stack : stacks) if (!stack.isEmpty()) list.add(stack.save(entity.registryAccess()));
        return list;
    }
    private void read(ListTag list, List<ItemStack> into) {
        into.clear();
        for (var raw : list) if (raw instanceof CompoundTag entry) {
            var stack = ItemStack.parseOptional(entity.registryAccess(), entry);
            if (!stack.isEmpty()) into.add(stack);
        }
    }
}
