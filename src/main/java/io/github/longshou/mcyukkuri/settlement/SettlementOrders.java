package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Per-entity, transient. A leader hands out forage / scout / message errands to idle adult members;
 * a member runs at most one order at a time. Errands lapse on unload; their real cargo is saved.
 */
public final class SettlementOrders {
    public enum Kind { NONE, FORAGE, SCOUT, MESSAGE }
    private static final int CENTER_RANGE = 16, ISSUE_RANGE = 12, MESSAGE_RANGE = 32, MESSAGE_TARGETS = 3;
    private static final int COOLDOWN = 3600, SUMMON_TICKS = 600, LOOKOUT_TICKS = 60; // [original] pacing
    private final YukkuriEntity entity;
    private Kind kind = Kind.NONE;
    private long deadline, cooldownUntil, nextIssue, summonUntil;
    private int stage, timer, failures;
    private BlockPos dest, threatAt;
    private BlockPos foodPos;
    private UUID foodItem;
    private ItemStack carried = ItemStack.EMPTY;
    private final PlantBedding plants;
    private int contact;
    private final List<UUID> targets = new ArrayList<>();
    public SettlementOrders(YukkuriEntity entity) { this.entity = entity; plants = new PlantBedding(entity); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public Kind kind() { return kind; }
    public boolean active() { return kind != Kind.NONE; }
    public ItemStack carrying() { return carried; }
    public String activity() {
        return !carried.isEmpty() ? "shelter_carry" : plants.harvesting() ? "nest_harvest"
                : kind == Kind.FORAGE ? "shelter_gather" : "settlement_return";
    }
    /** Also expires orders that never obtained the movement goal; retry failed cargo drops. */
    public void clock() {
        if (active() && !runnable()) cancel();
        if (!active() && !carried.isEmpty()) dropCarried();
    }
    public boolean summoned() { return entity.level().getGameTime() < summonUntil; }
    // ---- leader side ----
    /** Called about once a second by {@link SettlementLife}; the leader may issue one order per interval. */
    void tickLeader(SettlementData.Group group) {
        if (!entity.getUUID().equals(SettlementData.get(level()).activeLeader(group)) || !entity.family().adult()
                || entity.settlement().duties().active() || entity.wintering().active() || entity.independence().householdOuting()) return;
        long now = level().getGameTime();
        if (nextIssue == 0) { nextIssue = now + interval(); return; }
        if (now < nextIssue) return;
        if (!level().isDay() || !level().hasChunkAt(group.center) || entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) > CENTER_RANGE * CENTER_RANGE
                || entity.life().hasUrgentNeed() || Seasons.confined(entity) || !WildEvents.available(entity)) return;
        var box = entity.getBoundingBox().inflate(ISSUE_RANGE);
        List<YukkuriEntity> pool = level().getEntitiesOfClass(YukkuriEntity.class, box, e -> e != entity && eligible(e, group));
        nextIssue = now + interval();
        if (pool.isEmpty()) return;
        int roll = entity.getRandom().nextInt(3);
        YukkuriEntity pick = null; Kind order = roll == 0 ? Kind.FORAGE : roll == 1 ? Kind.SCOUT : Kind.MESSAGE;
        if (order == Kind.SCOUT) pick = pool.stream().max(Comparator.comparingInt(SettlementOrders::bravery)).orElse(null);
        else if (order == Kind.MESSAGE) pick = pool.stream().filter(e -> "chen".equals(e.getVariant().dialogueSpecies())).findFirst().orElse(null);
        if (pick == null) pick = pool.get(entity.getRandom().nextInt(pool.size()));
        issue(pick, order);
    }
    /** The command and natural scheduler use the same real leader/member eligibility gates. */
    public boolean issue(YukkuriEntity member, Kind order) {
        var group = entity.settlement().group();
        if (order == Kind.NONE || group == null || member == entity || member.level() != entity.level()
                || !entity.getUUID().equals(SettlementData.get(level()).activeLeader(group))
                || !entity.family().adult() || !WildEvents.available(entity) || entity.life().hasUrgentNeed()
                || !level().isDay() || Seasons.confined(entity) || Migration.moving(group)
                || entity.wintering().active() || entity.independence().householdOuting()
                || entity.settlement().duties().active() || entity.distanceToSqr(member) > ISSUE_RANGE * ISSUE_RANGE
                || entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) > CENTER_RANGE * CENTER_RANGE
                || !eligible(member, group)) return false;
        if (order == Kind.FORAGE && (member.habitat().homeStand() == null
                || !EventHooks.canEntityGrief(level(), member))) return false;
        if (!member.settlement().orders().receive(order, level().getGameTime())) return false;
        entity.getLookControl().setLookAt(member, 30, 30);
        entity.showReaction("neutral", "busy", 60);
        member.showReaction("surprise", "heard", 40);
        return true;
    }
    private static int bravery(YukkuriEntity e) { return e.life().personality().trait(Trait.CURIOSITY) + e.life().personality().trait(Trait.BOLDNESS); }
    private int interval() {
        int min = YukkuriConfig.ORDER_MIN_INTERVAL.get(), max = Math.max(min, YukkuriConfig.ORDER_MAX_INTERVAL.get());
        return min + entity.getRandom().nextInt(max - min + 1);
    }
    /** An idle, recognised adult member of the same group who is not already working an order. */
    private boolean eligible(YukkuriEntity e, SettlementData.Group group) {
        return group.id.equals(e.settlement().groupId()) && e.settlement().role() == SettlementData.Role.MEMBER && e.family().adult()
                && e.settlement().orders().free(level().getGameTime()) && WildEvents.available(e) && !WildEvents.reserved(e)
                && !e.settlement().duties().active() && !e.wintering().active() && !e.independence().householdOuting()
                && !e.life().hasUrgentNeed() && !Seasons.confined(e) && WildEvents.recognizes(entity, e) && entity.hasLineOfSight(e);
    }
    private boolean free(long now) { return kind == Kind.NONE && carried.isEmpty() && entity.habitat().carrying().isEmpty()
            && entity.settlement().duties().carrying().isEmpty() && now >= cooldownUntil; }
    private boolean receive(Kind order, long now) {
        if (!free(now)) return false;
        kind = order; stage = 0; timer = 0; failures = 0; dest = null; threatAt = null; foodPos = null; foodItem = null;
        targets.clear(); plants.stop(); contact = 0; deadline = now + YukkuriConfig.ORDER_TIMEOUT.get();
        return true;
    }
    /** A messenger asked this member to gather around the leader for a while. */
    private void summon(long until) { summonUntil = Math.max(summonUntil, until); }

    // ---- member side (OrderGoal) ----
    private boolean runnable() {
        return kind != Kind.NONE && level().getGameTime() < deadline && WildEvents.safe(entity)
                && entity.family().adult() && entity.settlement().role() == SettlementData.Role.MEMBER
                && !entity.life().hasUrgentNeed() && !entity.life().isSleeping() && !WildEvents.reserved(entity) && !HomeClaim.holds(entity)
                && !Seasons.confined(entity) && !entity.settlement().duties().active() && !entity.wintering().active()
                && !entity.independence().householdOuting() && entity.settlement().group() != null
                && !Migration.moving(entity.settlement().group()) && entity.life().mealTicks() == 0 && !entity.isInWaterOrBubble();
    }
    public boolean canUse() {
        if (kind == Kind.NONE) return false;
        if (!runnable()) { cancel(); return false; }
        return true;
    }
    public boolean canContinue() { return kind != Kind.NONE && runnable(); }
    public void stop() { if (kind != Kind.NONE) cancel(); else dropCarried(); entity.getNavigation().stop(); }
    public void tick() {
        if (!runnable()) { cancel(); return; }
        timer++;
        switch (kind) {
            case FORAGE -> forage();
            case SCOUT -> scout();
            case MESSAGE -> message();
            default -> { }
        }
    }
    private void cancel() { dropCarried(); finish(); }
    private void finish() { plants.stop(); kind = Kind.NONE; stage = contact = 0; cooldownUntil = level().getGameTime() + COOLDOWN; entity.getNavigation().stop(); }
    public void dropCarried() {
        if (carried.isEmpty() || entity.level().isClientSide) return;
        var drop = new ItemEntity(level(), entity.getX(), entity.getY(), entity.getZ(), carried.copy());
        drop.setPickUpDelay(60);
        if (level().addFreshEntity(drop)) carried = ItemStack.EMPTY;
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        if (!carried.isEmpty()) tag.put("Carrying", carried.save(entity.registryAccess()));
        tag.putLong("CooldownUntil", cooldownUntil);
        return tag;
    }
    public void load(CompoundTag tag) {
        carried = tag.contains("Carrying") ? ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("Carrying")) : ItemStack.EMPTY;
        cooldownUntil = tag.getLong("CooldownUntil");
        kind = Kind.NONE; stage = timer = failures = contact = 0; dest = threatAt = foodPos = null;
        foodItem = null; targets.clear(); plants.stop(); deadline = nextIssue = summonUntil = 0;
    }
    /** Walk toward a spot with the shared bounded-segment navigation; false after repeated failures. */
    private boolean walk(BlockPos goal) {
        if (timer % 20 != 1 && !entity.getNavigation().isDone()) return true;
        if (entity.settlement().navigateToward(goal)) { failures = 0; return true; }
        return ++failures < 5;
    }
    private boolean near(BlockPos goal, double distance) { return entity.distanceToSqr(Vec3.atBottomCenterOf(goal)) <= distance * distance; }

    // FORAGE: one real plant or dropped food becomes one portion, carried home to the shelter.
    private void forage() {
        var group = entity.settlement().group();
        var shelter = entity.habitat().shelter();
        if (group == null || shelter == null || !shelter.usable(entity)) { cancel(); return; }
        if (stage == 0) {
            if (!EventHooks.canEntityGrief(level(), entity)) { cancel(); return; }
            var around = new AABB(group.center).inflate(CENTER_RANGE);
            var item = level().getEntitiesOfClass(ItemEntity.class, around, i -> collectible(i, shelter)
                    && i.blockPosition().distSqr(group.center) <= CENTER_RANGE * CENTER_RANGE)
                    .stream().sorted(Comparator.comparingDouble(entity::distanceToSqr))
                    .filter(i -> entity.life().dryStand(i.blockPosition()) && entity.life().reachable(i.blockPosition(), false))
                    .findFirst().orElse(null);
            if (item != null) {
                foodItem = item.getUUID(); foodPos = item.blockPosition(); stage = 1; return;
            }
            // Use the same physical approach and sustained mouth contact as household gathering.
            if (shelter.foodSpace(new ItemStack(Items.SHORT_GRASS)) > 0
                    && plants.find(group.center, CENTER_RANGE, pos -> level().getBlockState(pos).is(Blocks.SHORT_GRASS))) {
                stage = 1; foodItem = null; return;
            }
            cancel(); return;
        }
        if (stage == 1) {
            if (!EventHooks.canEntityGrief(level(), entity)) { cancel(); return; }
            if (foodItem == null) {
                carried = plants.tick();
                if (carried.isEmpty()) { if (!plants.active()) cancel(); return; }
            } else {
                var found = level().getEntity(foodItem);
                if (!(found instanceof ItemEntity item) || !collectible(item, shelter)
                        || item.blockPosition().distSqr(group.center) > CENTER_RANGE * CENTER_RANGE) { cancel(); return; }
                // Track the real item each tick; never collect remotely from its previous position.
                foodPos = item.blockPosition();
                if (!entity.life().dryStand(foodPos)) { cancel(); return; }
                if (entity.distanceToSqr(item) >= 2.25 && !walk(foodPos)) { cancel(); return; }
                double reach = entity.getBbWidth() * .5 + item.getBbWidth() * .5 + .12;
                if (entity.position().subtract(item.position()).horizontalDistanceSqr() > reach * reach
                        || Math.abs(entity.getY() - item.getY()) > .45 || !entity.hasLineOfSight(item)) {
                    contact = 0;
                    if (entity.distanceToSqr(item) < 2.25) SurfaceApproach.walk(entity, item.position(), .65);
                    return;
                }
                SurfaceApproach.halt(entity);
                SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), item.position()));
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(SurfaceApproach.yaw(entity.position(), item.position())
                        - entity.getYRot())) > 15) { contact = 0; return; }
                if (++contact < 12) return;
                var remaining = item.getItem().copy(); carried = remaining.split(1);
                if (remaining.isEmpty()) item.discard(); else item.setItem(remaining);
            }
            stage = 2; timer = contact = 0; return;
        }
        BlockPos home = shelter.bed(entity.getUUID());
        if (home == null) { cancel(); return; }
        if (!near(home, 1.5)) { if (!walk(home)) cancel(); return; }
        Vec3 bed = Vec3.atBottomCenterOf(home);
        if (!SurfaceApproach.walk(entity, bed, .65)) return;
        // An explicit food errand must increase food stock, not silently turn grass into lining.
        carried = shelter.returnFood(carried);
        if (carried.isEmpty()) entity.showReaction("happy", "share", 40);
        else dropCarried();
        finish();
    }
    private boolean collectible(ItemEntity item, ShelterBlockEntity shelter) {
        return item.isAlive() && !item.hasPickUpDelay() && item.getItem().is(YukkuriEntity.FOOD)
                && entity.life().acceptsFood(item.getItem()) && shelter.foodSpace(item.getItem()) > 0
                && entity.hasLineOfSight(item);
    }

    // SCOUT: look out from 16-24 blocks away, then report back to the leader.
    private void scout() {
        var group = entity.settlement().group();
        if (group == null) { cancel(); return; }
        if (stage == 0) {
            for (int tries = 0; tries < 16 && dest == null; tries++) {
                double angle = entity.getRandom().nextDouble() * Math.PI * 2, radius = 16 + entity.getRandom().nextDouble() * 8;
                BlockPos column = group.center.offset((int)Math.round(Math.cos(angle) * radius), 0, (int)Math.round(Math.sin(angle) * radius));
                if (!level().hasChunkAt(column)) continue;
                for (int dy = 5; dy >= -8; dy--) {
                    BlockPos spot = column.offset(0, dy, 0);
                    if (entity.life().dryStand(spot)) { dest = spot.immutable(); break; }
                }
            }
            if (dest == null) { cancel(); return; }
            stage = 1; return;
        }
        if (timer % 20 == 0) spotPredator();
        if (stage == 1) {
            if (!walk(dest)) { cancel(); return; }
            if (near(dest, 3)) { stage = 2; timer = 0; entity.getNavigation().stop(); }
            return;
        }
        if (stage == 2) {
            Vec3 outward = Vec3.atBottomCenterOf(dest).subtract(Vec3.atBottomCenterOf(group.center)).normalize().scale(8);
            entity.getLookControl().setLookAt(entity.getX() + outward.x, entity.getEyeY(), entity.getZ() + outward.z, 10, 10);
            if (timer >= LOOKOUT_TICKS) { stage = 3; timer = 0; }
            return;
        }
        BlockPos back = reportSpot(group);
        if (back == null) { cancel(); return; }
        if (!walk(back)) { cancel(); return; }
        if (!near(back, 4)) return;
        if (!(level().getEntity(group.leader) instanceof YukkuriEntity chief) || !entity.hasLineOfSight(chief)) return;
        if (threatAt != null) {
            entity.showReaction("surprise", "afraid", 80);
            SettlementData.get(level()).reportThreat(group, threatAt, level().getGameTime());
            if (level().getEntity(group.leader == null ? entity.getUUID() : group.leader) instanceof YukkuriEntity leader && leader != entity) leader.showReaction("surprise", "heard", 40);
        } else entity.showReaction("happy", "heard", 60);
        finish();
    }
    private BlockPos reportSpot(SettlementData.Group group) {
        UUID leaderId = SettlementData.get(level()).activeLeader(group);
        if (leaderId != null && level().getEntity(leaderId) instanceof YukkuriEntity leader && leader.isAlive()
                && SettlementSociety.sameGroup(entity, leader) && WildEvents.recognizes(entity, leader)) return leader.blockPosition();
        return null;
    }
    private void spotPredator() {
        var seen = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(CENTER_RANGE), p -> p.isAlive() && p.getVariant().isPredator());
        seen.stream().filter(entity::hasLineOfSight).min(Comparator.comparingDouble(entity::distanceToSqr))
                .ifPresent(predator -> threatAt = predator.blockPosition());
    }

    // MESSAGE: speak to up to three other members, who then gather around the leader for a while.
    private void message() {
        var group = entity.settlement().group();
        if (group == null) { cancel(); return; }
        if (stage == 0) {
            List<YukkuriEntity> heard = new ArrayList<>(level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(MESSAGE_RANGE), e -> e != entity && e.isAlive()
                    && group.id.equals(e.settlement().groupId()) && !e.getUUID().equals(group.leader) && e.family().adult()
                    && e.settlement().role() == SettlementData.Role.MEMBER && !e.getVariant().isPredator()));
            heard.sort((a, b) -> Double.compare(entity.distanceToSqr(a), entity.distanceToSqr(b)));
            for (YukkuriEntity e : heard) if (targets.size() < MESSAGE_TARGETS) targets.add(e.getUUID());
            if (targets.isEmpty()) { cancel(); return; }
            stage = 1; return;
        }
        int index = stage - 1;
        if (index >= targets.size()) { finish(); return; }
        if (!(level().getEntity(targets.get(index)) instanceof YukkuriEntity other) || !other.isAlive()
                || !SettlementSociety.sameGroup(entity, other) || !SettlementSociety.accepted(other)
                || !WildEvents.recognizes(entity, other)) { stage++; timer = 0; return; }
        if (!walk(other.blockPosition())) { stage++; timer = 0; failures = 0; return; }
        if (entity.distanceToSqr(other) > 2.5 * 2.5 || !entity.hasLineOfSight(other)) return;
        entity.getLookControl().setLookAt(other, 30, 30); other.getLookControl().setLookAt(entity, 30, 30);
        entity.speak("known_peer"); other.showReaction("surprise", "heard", 40);
        other.settlement().orders().summon(level().getGameTime() + SUMMON_TICKS);
        stage++; timer = 0;
    }
}
