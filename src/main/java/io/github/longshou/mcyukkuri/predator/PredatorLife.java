package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A bounded, interruptible hunt. Movement uses the engine's flying paths and block collision. */
public final class PredatorLife implements EcologyRoutine {
    public enum Order { NATURAL, PEACEFUL, HUNT, RETURN }
    private enum Task { NONE, TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, LAND, PATROL }
    private final YukkuriEntity animal;
    private Task task = Task.NONE;
    private Order order = Order.NATURAL;
    private UUID commander;
    private YukkuriEntity prey;
    private ItemEntity food;
    private Vec3 destination;
    private int ticks, cooldown, biteTicks, patrolWait = 160, nestWait, reactWait, restrainWait;
    /** Prey with an owner that has already been reported to that owner (game time until which the alert is quiet). */
    private static final java.util.Map<UUID, Long> ALERTED = new java.util.HashMap<>();
    private boolean naturalSpawn;

    public PredatorLife(YukkuriEntity animal) { this.animal = animal; }
    /** Owner alerts never outlive their server. */
    public static void resetAlerts() { ALERTED.clear(); }
    public boolean active() { return task != Task.NONE; }
    public Order order() { return obedient() && validCommander() ? order : Order.NATURAL; }
    public String activity() {
        return switch (task) {
            case TAKEOFF -> Behaviors.PREDATOR_TAKEOFF;
            case CHASE -> Behaviors.PREDATOR_CHASE;
            case DIVE -> Behaviors.PREDATOR_DIVE;
            case SCAVENGE -> Behaviors.PREDATOR_FEED;
            case RETURN, LAND -> Behaviors.PREDATOR_RETURN;
            case PATROL -> Behaviors.PREDATOR_FLY;
            default -> Behaviors.IDLE;
        };
    }
    public String animation() {
        if (biteTicks > 0) return "predator_bite";
        if (!animal.predatorFlying()) return null;
        return task == Task.DIVE ? "predator_dive" : task == Task.LAND ? "predator_land" : "predator_fly";
    }
    /** The certificate belongs to this animal; putting on someone else's token cannot teach commands. */
    public boolean obedient() {
        return EcologyRoutines.predator(animal) && animal.pet().effectiveGrade().id() >= io.github.longshou.mcyukkuri.pet.BadgeGrade.SILVER.id()
                && animal.life().personality().knowsWait() && animal.pet().ability(io.github.longshou.mcyukkuri.pet.Ability.WITS) >= 60;
    }
    private boolean validCommander() { return commander != null && HumanCommerce.ownedBy(animal, commander); }
    public boolean command(Player player, Order requested) {
        if (!obedient()) { defy(player); return false; }
        if (!animal.pet().near(player) || !HumanCommerce.ownedBy(animal, player.getUUID())
                || animal.pet().busy() || animal.operations().active() || animal.isCommandPlaying() && reactWait == 0) return false; // its own bow/ignore reaction does not block the next order
        if (requested == Order.RETURN && animal.life().homeStand() == null) return false;
        stop(); order = requested; commander = player.getUUID(); cooldown = 0;
        animal.setPersistenceRequired();
        if (reactWait == 0) { reactWait = 60; PetScenes.act(animal, "bow", "happy", "predator_obey", 40, player); }
        return true;
    }
    /** An untrained predator ignores its owner's order. */
    private void defy(Player player) {
        if (reactWait > 0 || !EcologyRoutines.predator(animal) || active() || !animal.pet().near(player) || !HumanCommerce.ownedBy(animal, player.getUUID()) || animal.pet().busy()
                || animal.operations().active() || animal.isCommandPlaying() || animal.isPanicking() || animal.life().isSleeping()) return;
        reactWait = 100; PetScenes.act(animal, "ignore", "neutral", "predator_defy", 40, null);
    }
    /** An obedient predator that sees a protected yukkuri it would otherwise go for holds itself back, once a minute at most. */
    private boolean restrain() {
        if (restrainWait > 0 || !obedient() || animal.isCommandPlaying() || animal.life().isSleeping()) return false;
        var tempting = level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(10), y -> huntable(y, false) && HumanCommerce.protectedAnimal(y) && animal.hasLineOfSight(y))
                .stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (tempting == null) return false;
        restrainWait = 1200; PetScenes.act(animal, "restrain", "neutral", "predator_restrain", 60, tempting);
        return true;
    }
    /** The owner of a yukkuri that a predator has started to chase hears of it; a pet near its owner runs to them. */
    private void alertOwner() {
        if (prey == null || !prey.isAlive()) return;
        var id = prey.pet().owner(); if (id == null) return;
        var owner = level().getServer().getPlayerList().getPlayer(id);
        if (owner == null || owner.level() != level() || owner.distanceToSqr(prey) > 4096) return;
        long now = level().getGameTime();
        if (ALERTED.getOrDefault(prey.getUUID(), 0L) > now) return;
        if (ALERTED.size() > 128) { ALERTED.values().removeIf(t -> t <= now); if (ALERTED.size() > 1024) ALERTED.clear(); }
        ALERTED.put(prey.getUUID(), now + 600);
        owner.displayClientMessage(Component.translatable("pet.mcyukkuri.predator.alert", prey.getDisplayName()), true);
        if (owner.distanceToSqr(prey) <= 256) prey.showReaction("surprise", "afraid", 80);
    }
    private ServerLevel level() { return (ServerLevel)animal.level(); }
    private boolean ready() {
        return EcologyRoutines.predator(animal) && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger()
                && !animal.isPanicking() && !animal.isCommandPlaying() && !animal.operations().active()
                && !animal.pet().busy() && !animal.life().isSleeping() && animal.life().mealTicks() == 0
                && !animal.family().active() && animal.family().brood() == null
                && !animal.isInWaterOrBubble() && !animal.isOnFire();
    }
    public boolean wantsHunt() {
        if (!ready() || !animal.family().adult() || animal.life().energy() < 30) return false;
        if (order() == Order.PEACEFUL || order() == Order.RETURN) return false;
        int threshold = 45 + animal.life().personality().trait(Trait.BOLDNESS) / 5;
        if (order() == Order.HUNT) threshold = 75;
        return animal.life().nutrition() < threshold;
    }
    public boolean mayHunt(YukkuriEntity target) { return huntable(target, true); }
    /** The yukkuri this predator is currently chasing or diving at, or null. */
    public YukkuriEntity prey() { return task == Task.CHASE || task == Task.DIVE ? prey : null; }
    /** A parent steps in front of its child (grief/GriefLife sacrifice): the chase turns to the parent if the predator would hunt it. */
    public boolean divert(YukkuriEntity shield) {
        if (prey() == null || prey == shield || !mayHunt(shield)) return false;
        prey = shield; return true;
    }
    /** Whether the target is something this predator would go for; respect=false ignores the protection an educated predator honours. */
    private boolean huntable(YukkuriEntity target, boolean respect) {
        if (target == animal || !target.isAlive() || target.isInvulnerable() || EcologyRoutines.predator(target)
                || EcologyRoutines.house(target) || target.isPassenger() || target.operations().active()
                || target.getScale() * target.growthScale() > animal.getScale() * animal.growthScale() * 1.35) return false;
        // Educated animals respect protected pets; uneducated ones retain the wild risk after purchase.
        if (respect && obedient() && HumanCommerce.protectedAnimal(target)) return false;
        if (animal.family().caresFor(target) || target.getUUID().equals(animal.family().partner())) return false;
        var shelter = target.habitat().shelter();
        if (shelter != null && shelter.owns(target.getUUID()) && shelter.sound() && shelter.contains(target.blockPosition())) return false;
        return animal.distanceToSqr(target) <= 18 * 18 && animal.hasLineOfSight(target);
    }
    public void naturalSpawn() { naturalSpawn = true; }
    public void tick() { EcologyRoutines.tick(this); }
    @Override public boolean supportsPassiveTick() {
        return !animal.level().isClientSide && EcologyRoutines.predator(animal);
    }
    @Override public void passiveTick() {
        if (cooldown > 0) cooldown--;
        if (biteTicks > 0) biteTicks--;
        if (patrolWait > 0) patrolWait--;
        if (nestWait > 0) nestWait--;
        if (reactWait > 0) reactWait--;
        if (restrainWait > 0) restrainWait--;
        if (!ready() && active()) stop();
        if (animal.predatorFlying() && !active()) animal.setPredatorFlying(false);
        if (animal.isNoAi()) return;
        if (naturalSpawn && nestWait == 0) {
            nestWait = 400;
            if (findNest(true)) naturalSpawn = false;
        }
        if (animal.predatorFlying() && animal.tickCount % 20 == 0) animal.life().spendEnergy(1);
    }
    public boolean canFlyTo(BlockPos pos) {
        if (!level().hasChunkAt(pos) || !level().getFluidState(pos).isEmpty()) return false;
        var navigation = new FlyingPathNavigation(animal, level());
        var path = navigation.createPath(pos, 0);
        return path != null && path.canReach();
    }
    /** Claims real existing nests first. Wild founders can weave one on an already sheltered ledge. */
    public boolean findNest(boolean weave) {
        if (!EcologyRoutines.predator(animal) || animal.life().homeStand() != null) return false;
        for (var pos : BlockPos.betweenClosed(animal.blockPosition().offset(-10,-3,-10), animal.blockPosition().offset(10,8,10))) {
            if (level().hasChunkAt(pos) && level().getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get())
                    && animal.life().assignHome(GlobalPos.of(level().dimension(), pos.immutable()))) return true;
        }
        if (!weave || HumanCommerce.protectedAnimal(animal) || !EventHooks.canEntityGrief(level(), animal)) return false;
        for (var pos : BlockPos.betweenClosed(animal.blockPosition().offset(-8,1,-8), animal.blockPosition().offset(8,6,8))) {
            if (!level().hasChunkAt(pos) || !level().getBlockState(pos).isAir() || !animal.life().covered(pos)
                    || animal.life().entrance(pos) == null || !level().getFluidState(pos).isEmpty()) continue;
            var support = level().getBlockState(pos.below());
            if (!(support.is(BlockTags.LOGS) || support.is(BlockTags.BASE_STONE_OVERWORLD) || support.is(BlockTags.DIRT))) continue;
            var entrance = animal.life().entrance(pos);
            if (!canFlyTo(entrance)) continue;
            if (!level().setBlock(pos, ModBlocks.PREDATOR_NEST.get().defaultBlockState(), 3)) continue;
            if (animal.life().assignHome(GlobalPos.of(level().dimension(), pos.immutable()))) return true;
            level().removeBlock(pos, false);
        }
        return false;
    }
    private boolean canUse() {
        if (!ready()) return false;
        if (active()) return true;
        var home = animal.life().homeStand();
        if (home != null && (order() == Order.RETURN || animal.life().energy() < 35 || level().isNight() || level().isRaining())
                && animal.distanceToSqr(Vec3.atBottomCenterOf(home)) > .64) {
            destination = Vec3.atBottomCenterOf(home); task = Task.RETURN; return true;
        }
        if (cooldown > 0 || animal.life().energy() < 20) return false;
        if (animal.life().canEat()) {
            food = level().getEntitiesOfClass(ItemEntity.class, animal.getBoundingBox().inflate(10), this::edibleDrop).stream()
                    .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
            if (food != null) { task = Task.SCAVENGE; return true; }
        }
        if (wantsHunt()) {
            prey = level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(18), this::mayHunt).stream()
                    .min(Comparator.comparingDouble(y -> animal.distanceToSqr(y) + y.getHealth() - y.mind().preyAppeal())).orElse(null); // sweetened prey is preferred
            if (prey != null) { task = Task.TAKEOFF; return true; }
            if (restrain()) { cooldown = 20; return false; }
        }
        if (patrolWait == 0 && order() != Order.RETURN && animal.family().adult() && animal.life().energy() > 45
                && !level().isNight() && !level().isRaining() && !animal.life().hasUrgentNeed()) {
            patrolWait = 240 + animal.getRandom().nextInt(200);
            var center = home == null ? animal.position() : Vec3.atBottomCenterOf(home);
            var pos = BlockPos.containing(center.add(animal.getRandom().nextInt(13)-6, 2 + animal.getRandom().nextInt(2), animal.getRandom().nextInt(13)-6));
            if (canFlyTo(pos)) { destination = Vec3.atCenterOf(pos); task = Task.PATROL; return true; }
        }
        cooldown = 20; return false;
    }
    private boolean edibleDrop(ItemEntity item) {
        return item.isAlive() && !item.hasPickUpDelay() && animal.hasLineOfSight(item)
                && (FillingResource.isFilling(item.getItem()) && animal.life().acceptsFood(item.getItem())
                    || item.getItem().is(ModItems.REMAINS.get()) && FillingResource.data(item.getItem()).getInt("Filling") > 0);
    }
    private void start() {
        ticks = 0; animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        if (task != Task.SCAVENGE || food.getY() > animal.getY() + 1) animal.setPredatorFlying(true);
    }
    private void fly(Vec3 goal, double speed) {
        if (!level().hasChunkAt(BlockPos.containing(goal))) { land(); return; }
        // Path nodes end at block centers. Finish the last approach at the moving target's
        // exact position, otherwise the animal circles just outside bite or landing range.
        if (animal.distanceToSqr(goal) < 6.25 && level().clip(new net.minecraft.world.level.ClipContext(
                animal.getEyePosition(), goal.add(0,.2,0), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, animal)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            animal.getNavigation().stop();
            animal.getMoveControl().setWantedPosition(goal.x,goal.y,goal.z,speed);
            return;
        }
        if (ticks % 10 == 1 || animal.getNavigation().isDone()) {
            if (!animal.getNavigation().moveTo(goal.x, goal.y, goal.z, speed)) {
                // Let the pathfinder try a short rising waypoint before abandoning a blocked approach.
                if (ticks > 60) land();
            }
        }
    }
    private void land() {
        prey = null; food = null; task = Task.LAND;
        destination = null;
        for (int y = 0; y <= 8 && destination == null; y++) {
            var base = animal.blockPosition().below(y);
            for (var pos : BlockPos.betweenClosed(base.offset(-2,0,-2), base.offset(2,0,2)))
                if (animal.life().dryStand(pos)) { destination = Vec3.atBottomCenterOf(pos); break; }
        }
        if (destination == null) stop();
    }
    private void tickGoal() {
        ticks++;
        if (!ready() || ticks > 400) { stop(); return; }
        if (animal.life().energy() < 15 && task != Task.LAND) land();
        if (task == Task.NONE) return;
        if (task == Task.TAKEOFF || task == Task.CHASE || task == Task.DIVE) {
            if (!wantsHunt() || prey == null || !mayHunt(prey)) { land(); return; }
            if (ticks % 20 == 1) alertPrey();
            if (task == Task.TAKEOFF) {
                fly(animal.position().add(0, 1.2, 0), 1);
                if (ticks >= 15) { task = Task.CHASE; alertOwner(); }
            } else {
                double d = animal.distanceToSqr(prey);
                task = d < 16 ? Task.DIVE : Task.CHASE;
                fly(prey.position().add(0, task == Task.DIVE ? .12 : 1.2, 0), task == Task.DIVE ? 1.35 : 1.1);
                animal.getLookControl().setLookAt(prey, 30, 30);
                double reach = (animal.getBbWidth() + prey.getBbWidth()) * .5 + .18;
                if (d <= reach * reach && biteTicks == 0 && animal.hasLineOfSight(prey)) {
                    biteTicks = 16;
                    if (prey.hurt(level().damageSources().mobAttack(animal), 2)) {
                        if (prey.isAlive()) prey.body().releaseFilling("predation");
                        animal.life().spendEnergy(2);
                        // Recover and eat the real portion before considering another strike.
                        land(); cooldown = 25;
                    }
                }
            }
        } else if (task == Task.SCAVENGE) {
            if (food == null || !edibleDrop(food) || !animal.life().canEat()) { stop(); return; }
            fly(food.position(), 1);
            if (animal.distanceToSqr(food) < 1 && animal.hasLineOfSight(food)) {
                var stack = food.getItem();
                if (stack.is(ModItems.REMAINS.get())) {
                    var data = FillingResource.data(stack);
                    var portion = new ItemStack(ModItems.FILLING.get());
                    CustomData.update(DataComponents.CUSTOM_DATA, portion, t -> {
                        t.putInt("FillingVersion", 1); t.putUUID("Portion", UUID.randomUUID());
                        if (data.hasUUID("Identity")) t.putUUID("Source", data.getUUID("Identity"));
                        t.putString("Variant", data.getString("Variant")); t.putString("Method", "predation");
                        t.putInt("Nutrition", 5); t.putInt("Sweetness", 60); t.putInt("Odor", 0);
                        t.putFloat("SourceScale", data.getFloat("SourceScale"));
                    });
                    if (animal.life().beginMeal(portion, null, true)) {
                        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.putInt("Filling", data.getInt("Filling") - 1));
                        food.setItem(stack.copy());
                    }
                } else if (animal.life().beginMeal(stack.copyWithCount(1), null, true)) {
                    stack.shrink(1); if (stack.isEmpty()) food.discard(); else food.setItem(stack.copy());
                }
                stop();
            }
        } else {
            if (destination == null) { stop(); return; }
            fly(destination, .85);
            if (animal.position().distanceToSqr(destination) < .36) {
                if (task == Task.PATROL) land();
                else { stop(); if (order() == Order.RETURN) order = Order.PEACEFUL; }
            } else if (task == Task.PATROL && ticks > 140) land();
        }
    }
    private void alertPrey() {
        for (var target : level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(8),
                y -> !EcologyRoutines.predator(y) && y.isAlive() && !y.isNoAi() && y.hasLineOfSight(animal)))
            target.frightenFrom(animal);
    }
    public void stop() {
        task = Task.NONE; prey = null; food = null; destination = null;
        animal.getNavigation().stop(); animal.setPredatorFlying(false);
        cooldown = Math.max(cooldown, 20);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Order", order.name()); tag.putInt("Cooldown", cooldown);
        tag.putBoolean("NaturalSpawn", naturalSpawn); if (commander != null) tag.putUUID("Commander", commander);
        return tag;
    }
    public void load(CompoundTag tag) {
        stop(); try { order = Order.valueOf(tag.getString("Order")); } catch (IllegalArgumentException ignored) { order = Order.NATURAL; }
        commander = tag.hasUUID("Commander") ? tag.getUUID("Commander") : null;
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 400); naturalSpawn = tag.getBoolean("NaturalSpawn");
    }
    public static final class BehaviorGoal extends Goal {
        private final YukkuriEntity animal;
        public BehaviorGoal(YukkuriEntity animal) { this.animal = animal; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return animal.predator().canUse(); }
        @Override public boolean canContinueToUse() { return animal.predator().active() && animal.predator().ready(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { animal.predator().start(); }
        @Override public void tick() { animal.predator().tickGoal(); }
        @Override public void stop() { animal.predator().stop(); }
    }
}
