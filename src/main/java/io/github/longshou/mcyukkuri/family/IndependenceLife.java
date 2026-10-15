package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A child builds with real loose materials before transferring its existing identity and bed. */
public final class IndependenceLife extends Goal {
    private enum Task { NONE, FAREWELL, PICKUP, HARVEST, DELIVER, WORK, VISIT, GREET, RETURN }
    private record Material(UUID entityId, UUID token) {}
    private static final String MATERIAL_KEY = "YukkuriConstruction";
    private static final int UNIT_WORK = 120, CHECK_INTERVAL = 1200, VISIT_INTERVAL = 12000;
    private final YukkuriEntity entity;
    private final List<Material> materials = new ArrayList<>();
    private GlobalPos origin, site, establishedHome;
    private UUID originalGroup, visitor;
    private ShelterBlock.Kind kind = ShelterBlock.Kind.BURROW;
    private Direction facing = Direction.SOUTH;
    private ItemStack carrying = ItemStack.EMPTY;
    private ItemEntity pickup;
    private BlockPos target;
    private Task task = Task.NONE;
    private int wait = CHECK_INTERVAL, elapsed, work, projectTicks, visitWait = VISIT_INTERVAL, visits, rainLosses, loadGrace;
    private boolean running, farewell, returning;

    public IndependenceLife(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean active() { return running && task != Task.NONE; }
    public boolean building() { return site != null; }
    public boolean independent() { return establishedHome != null; }
    public ItemStack carrying() { return carrying; }
    public int work() { return work; }
    public int visits() { return visits; }
    public int rainLosses() { return rainLosses; }
    public GlobalPos site() { return site; }
    public GlobalPos origin() { return origin; }
    public String activity() {
        return switch (task) {
            case FAREWELL -> "independence_farewell";
            case PICKUP, HARVEST -> "independence_gather";
            case DELIVER -> "independence_carry";
            case WORK -> "independence_build";
            case VISIT, GREET -> "independence_visit";
            case RETURN -> "independence_return";
            default -> "idle";
        };
    }
    /** Ordinary household maintenance yields, but eating remains a higher-priority need. */
    public boolean householdOuting() {
        boolean localWork = building() && loaded(site) && loaded(origin);
        boolean localVisit = returning && loaded(establishedHome) || task == Task.VISIT || task == Task.GREET;
        return (localWork || localVisit) && wild() && SettlementSociety.accepted(entity);
    }
    private boolean loaded(GlobalPos pos) {
        return pos != null && pos.dimension().equals(level().dimension()) && level().hasChunkAt(pos.pos());
    }
    private ShelterBlockEntity originalShelter() { return loaded(origin) ? ShelterBlock.shelter(level(), origin.pos()) : null; }
    private boolean wild() {
        var human = HumanCommerce.data(entity);
        return !entity.getVariant().isHouse() && !entity.getVariant().isPredator()
                && !io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)
                && !human.hasUUID("Owner") && !human.contains("PendingRole") && !human.contains("FactoryStage")
                && entity.life().naturalHome() && entity.life().home() != null;
    }
    /** Unknown/unloaded living children are conservatively treated as dependent. */
    private boolean hasDependents() {
        var data = FamilyData.get(level());
        if (data.busyParent(entity.getUUID())) return true;
        for (var id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person == null || person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED) continue;
            if (!(level().getEntity(id) instanceof YukkuriEntity child) || !child.family().adult()) return true;
        }
        return false;
    }
    public boolean eligible() {
        if (!wild() || !entity.family().adult() || entity.family().elder() || entity.family().partner() != null
                || entity.family().recovery() > 0 || hasDependents()) return false;
        var home = entity.habitat().shelter();
        var group = entity.settlement().group();
        var role = entity.settlement().society().record();
        // Do not escape lost-accessory consequences by creating a fresh community record.
        return home != null && home.usable(entity) && group != null && role != null && role.role == SettlementData.Role.MEMBER
                && role.rejections == 0 && (home.owns(entity.family().mother()) || home.owns(entity.family().father()));
    }
    private boolean ready() {
        return wild() && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && !entity.isLeashed()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.family().active() && !hasDependents() && entity.family().recovery() == 0
                && !entity.life().hasUrgentNeed() && !entity.life().isSleeping() && entity.life().mealTicks() == 0
                && entity.life().nutrition() >= 45 && entity.life().energy() >= 40 && entity.life().dryness() < 40
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.shellRetraction().active()
                && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity)
                && entity.habitat().carrying().isEmpty() && entity.settlement().duties().carrying().isEmpty();
    }
    /** Loaded-time clock only; a paused or captured builder never gets offline construction. */
    public void clock() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (loadGrace > 0) { loadGrace--; return; }
        if (wait > 0) wait--;
        if (visitWait > 0) visitWait--;
        if (!wild()) { if (building() || !carrying.isEmpty()) abandon(); returning = false; return; }
        if (!building() || !loaded(site) || !loaded(origin)) return;
        if (++projectTicks > 24000 || !eligible() || !originMatches()) { abandon(); return; }
        if (entity.tickCount % 20 != 0) return;
        reconcile();
        // Only a real, unfinished, exposed material can be damaged. A cave roof shields its pile.
        if (level().isThundering() && entity.tickCount % 200 == 0) {
            for (var material : List.copyOf(materials)) {
                var item = materialEntity(material);
                if (item != null && level().isRainingAt(item.blockPosition())) {
                    item.discard(); materials.remove(material); work = Math.max(0, work - UNIT_WORK); rainLosses++;
                    level().sendParticles(ParticleTypes.SPLASH, item.getX(), item.getY() + .15, item.getZ(), 5, .15, .1, .15, .01);
                    break;
                }
            }
        }
        // Short reservation expires naturally if the builder is unloaded, captured or interrupted.
        if (ready()) for (var material : materials) { var item = materialEntity(material); if (item != null) item.setPickUpDelay(40); }
    }
    private boolean originMatches() {
        var home = entity.habitat().shelter(); var group = entity.settlement().group();
        return home != null && home.getBlockPos().equals(origin.pos()) && group != null && group.id.equals(originalGroup);
    }
    public int departureChance() {
        var home = entity.habitat().shelter();
        if (home == null) return 0;
        // Original balancing, not a density inferred from EH11's panels.
        int chance = home.occupants() >= home.capacity() ? 30 : 10;
        if (entity.life().personality().attachment() >= 65) chance -= 5;
        return chance;
    }
    private boolean chooseSite() {
        var home = entity.habitat().shelter();
        if (home == null || entity.distanceToSqr(Vec3.atBottomCenterOf(home.getBlockPos())) > 16) return false;
        var candidates = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(home.getBlockPos().offset(-12,-2,-12), home.getBlockPos().offset(12,2,12))) {
            double distance = pos.distSqr(home.getBlockPos());
            if (distance >= 64 && distance <= 144 && NaturalShelter.enclosed(level(), pos)) candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> entity.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (var pos : candidates) for (var direction : Direction.Plane.HORIZONTAL)
            if (begin(new ShelterPlacement.Plan(pos, ShelterBlock.Kind.BURROW, direction))) return true;
        // A cardboard proposal requires actual nearby paper; terrain never supplies paper.
        int paper = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(14), item ->
                item.isAlive() && !item.hasPickUpDelay() && item.getItem().is(Items.PAPER)).stream().mapToInt(i -> i.getItem().getCount()).sum();
        if (paper < 4) return false;
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = entity.getRandom().nextInt(25) - 12, dz = entity.getRandom().nextInt(25) - 12;
            if (dx * dx + dz * dz < 64 || dx * dx + dz * dz > 144) continue;
            for (int dy : new int[]{0, 1, -1}) for (var direction : Direction.Plane.HORIZONTAL)
                if (begin(new ShelterPlacement.Plan(home.getBlockPos().offset(dx,dy,dz), ShelterBlock.Kind.CARDBOARD, direction))) return true;
        }
        return false;
    }
    private boolean begin(ShelterPlacement.Plan plan) {
        if (!ShelterPlacement.canPlace(level(), plan) || !entity.life().reachable(plan.center(), false)
                || !entity.life().reachable(plan.entrance(level()), false)) return false;
        var home = entity.habitat().shelter();
        origin = GlobalPos.of(level().dimension(), home.getBlockPos()); originalGroup = entity.settlement().groupId();
        site = GlobalPos.of(level().dimension(), plan.center()); kind = plan.kind(); facing = plan.facing();
        work = projectTicks = 0; farewell = false; entity.setPersistenceRequired(); return true;
    }
    @Override public boolean canUse() {
        if (loadGrace > 0 || !ready() || wait > 0 || !SettlementSociety.accepted(entity)) return false;
        if (building()) {
            if (!loaded(site) || !loaded(origin) || !eligible() || !originMatches()) return false;
            if (!EventHooks.canEntityGrief(level(), entity)) return false;
            reconcile();
            if (!ShelterPlacement.canPlace(level(), plan())) { abandon(); return false; }
            if (!farewell) {
                var parent = relativeAtOrigin();
                farewell = true;
                if (parent != null) { visitor = parent.getUUID(); task = Task.FAREWELL; return true; }
            }
            if (!carrying.isEmpty()) { task = Task.DELIVER; return true; }
            if (materialCount(true) >= paperNeeded() && materialCount(false) >= beddingNeeded()) { task = Task.WORK; return true; }
            if (findMaterial()) return true;
            wait = 100; return false;
        }
        if (independent()) return planVisit();
        wait = CHECK_INTERVAL;
        if (!eligible() || level().isNight() || entity.getRandom().nextInt(100) >= departureChance()) return false;
        if (chooseSite()) wait = 0;
        return false;
    }
    @Override public void start() { running = true; elapsed = 0; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public boolean canContinueToUse() {
        return active() && ready() && elapsed < 1000 && SettlementSociety.accepted(entity)
                && (!building() || loaded(site) && loaded(origin) && eligible() && originMatches() && EventHooks.canEntityGrief(level(), entity));
    }
    @Override public void tick() {
        if (!canContinueToUse()) { stop(); return; }
        elapsed++;
        switch (task) {
            case FAREWELL, VISIT, GREET -> visit();
            case PICKUP -> {
                if (pickup == null || !pickup.isAlive() || pickup.hasPickUpDelay() || !needed(pickup.getItem())) { stop(); return; }
                if (approach(pickup.blockPosition()) && entity.distanceToSqr(pickup) < 2.25) {
                    carrying = pickup.getItem().split(1); if (pickup.getItem().isEmpty()) pickup.discard();
                    pickup = null; task = Task.DELIVER; elapsed = 0;
                }
            }
            case HARVEST -> {
                if (target == null || !level().hasChunkAt(target) || !level().getBlockState(target).is(Blocks.SHORT_GRASS)) { stop(); return; }
                if (approach(target) && level().setBlock(target, Blocks.AIR.defaultBlockState(), 3)) {
                    carrying = new ItemStack(Items.SHORT_GRASS); task = Task.DELIVER; elapsed = 0;
                }
            }
            case DELIVER -> { if (approach(site.pos())) { deliver(); stop(); wait = 10; } }
            case WORK -> {
                reconcile();
                if (materialCount(true) < paperNeeded() || materialCount(false) < beddingNeeded()) { stop(); return; }
                if (approach(site.pos())) {
                    work++;
                    if (work % 40 == 0) { entity.life().spendEnergy(.25f); entity.life().spendNutrition(.1f); }
                    if (work % 20 == 0) level().sendParticles(ParticleTypes.COMPOSTER, entity.getX(), entity.getY() + .15, entity.getZ(), 2, .2, .1, .2, 0);
                    if (work >= workNeeded()) finish();
                }
            }
            case RETURN -> {
                var home = entity.habitat().homeStand();
                if (home == null) { stop(); return; }
                if (approach(home)) { returning = false; stop(); visitWait = VISIT_INTERVAL; }
            }
            default -> stop();
        }
    }
    private ShelterPlacement.Plan plan() { return new ShelterPlacement.Plan(site.pos(), kind, facing); }
    private int paperNeeded() { return kind == ShelterBlock.Kind.CARDBOARD ? 4 : 0; }
    private int beddingNeeded() { return kind == ShelterBlock.Kind.CARDBOARD ? 2 : 3; }
    private int workNeeded() { return (paperNeeded() + beddingNeeded()) * UNIT_WORK; }
    private static boolean bedding(ItemStack item) { return !item.is(Items.PAPER) && ShelterBlockEntity.bedding(item); }
    private ItemEntity materialEntity(Material material) {
        if (!(level().getEntity(material.entityId) instanceof ItemEntity item) || !item.isAlive() || item.getItem().getCount() != 1
                || item.distanceToSqr(Vec3.atBottomCenterOf(site.pos())) > 2.25) return null;
        var data = item.getItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.hasUUID(MATERIAL_KEY) && data.getUUID(MATERIAL_KEY).equals(material.token) ? item : null;
    }
    private void reconcile() {
        if (!loaded(site)) return;
        int before = materials.size(); materials.removeIf(material -> materialEntity(material) == null);
        work = Math.max(0, Math.min(work - (before - materials.size()) * UNIT_WORK, materials.size() * UNIT_WORK));
    }
    private int materialCount(boolean paper) {
        int count = 0;
        for (var material : materials) { var item = materialEntity(material); if (item != null && (paper ? item.getItem().is(Items.PAPER) : bedding(item.getItem()))) count++; }
        return count;
    }
    private boolean needed(ItemStack stack) {
        return stack.is(Items.PAPER) ? materialCount(true) < paperNeeded() : bedding(stack) && materialCount(false) < beddingNeeded();
    }
    private boolean findMaterial() {
        pickup = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(12), item ->
                item.isAlive() && !item.hasPickUpDelay() && needed(item.getItem())
                        && materials.stream().noneMatch(m -> m.entityId.equals(item.getUUID())))
                .stream().sorted(Comparator.comparingDouble(entity::distanceToSqr))
                .filter(item -> entity.life().reachable(item.blockPosition(), false)).findFirst().orElse(null);
        if (pickup != null) { task = Task.PICKUP; return true; }
        if (materialCount(false) >= beddingNeeded()) return false;
        for (var pos : BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6), entity.blockPosition().offset(6,2,6))) {
            if (level().hasChunkAt(pos) && level().getBlockState(pos).is(Blocks.SHORT_GRASS)
                    && entity.life().dryStand(pos) && entity.life().reachable(pos, false)) {
                target = pos.immutable(); task = Task.HARVEST; return true;
            }
        }
        return false;
    }
    private void deliver() {
        if (carrying.isEmpty() || !needed(carrying)) { dropCarrying(); return; }
        var token = UUID.randomUUID(); var stack = carrying.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(MATERIAL_KEY, token));
        var pos = Vec3.atBottomCenterOf(site.pos()).add((materials.size() % 3 - 1) * .25, .1, (materials.size() / 3) * .25);
        var item = new ItemEntity(level(), pos.x, pos.y, pos.z, stack); item.setDeltaMovement(Vec3.ZERO); item.setPickUpDelay(40);
        if (level().addFreshEntity(item)) { carrying = ItemStack.EMPTY; materials.add(new Material(item.getUUID(), token)); }
    }
    private void finish() {
        reconcile();
        if (!eligible() || !originMatches() || !loaded(origin) || materialCount(true) != paperNeeded() || materialCount(false) != beddingNeeded()) { stop(); return; }
        var placed = ShelterPlacement.place(level(), plan());
        if (placed == null) { stop(); return; }
        if (!entity.habitat().choose(placed.shelter())) { placed.rollback(level()); stop(); return; }
        // Placement and claim succeeded in this server tick. No inventory or entity is copied.
        for (var material : materials) {
            var item = materialEntity(material);
            if (item != null) { if (bedding(item.getItem())) placed.shelter().deposit(item.getItem()); item.discard(); }
        }
        materials.clear();
        establishedHome = GlobalPos.of(level().dimension(), placed.shelter().getBlockPos());
        entity.settlement().establishHousehold(placed.shelter());
        site = null; work = 0; returning = true; visitWait = VISIT_INTERVAL;
        entity.showReaction("happy", "gentle", 60); stop(); wait = 0;
    }
    private YukkuriEntity relativeAtOrigin() {
        var home = originalShelter();
        if (home == null || !home.sound()) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, home.interior().inflate(3), other ->
                other != entity && other.isAlive() && home.owns(other.getUUID()) && wildRelative(other)
                        && FamilyData.get(level()).related(entity.getUUID(), other.getUUID())
                        && !other.isPanicking() && !other.isCommandPlaying() && !other.operations().active()
                        && !other.family().active() && !other.life().isSleeping() && !other.life().hasUrgentNeed()
                        && entity.life().canRecognize(other) && other.life().canRecognize(entity))
                .stream().filter(other -> entity.life().reachable(other.blockPosition(), false))
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    private static boolean wildRelative(YukkuriEntity other) {
        return !HumanCommerce.data(other).hasUUID("Owner") && other.life().naturalHome() && SettlementSociety.accepted(other);
    }
    private boolean planVisit() {
        var home = entity.habitat().shelter();
        if (!loaded(establishedHome) || home == null || !home.getBlockPos().equals(establishedHome.pos()) || !home.usable(entity)) return false;
        if (returning) { task = Task.RETURN; return true; }
        if (visitWait > 0 || level().isNight() || level().isRaining()) return false;
        visitWait = 1200;
        if (!loaded(origin) || origin.pos().distSqr(establishedHome.pos()) > 16 * 16 || entity.distanceToSqr(Vec3.atBottomCenterOf(home.getBlockPos())) > 9) return false;
        var relative = relativeAtOrigin();
        if (relative == null) return false;
        visitor = relative.getUUID(); task = Task.VISIT; returning = true; return true;
    }
    private void visit() {
        if (!(level().getEntity(visitor) instanceof YukkuriEntity other) || !other.isAlive() || other.isPanicking() || other.isCommandPlaying()
                || other.operations().active() || other.family().active() || other.life().isSleeping() || other.life().hasUrgentNeed() || !wildRelative(other)
                || !entity.life().canRecognize(other) || !other.life().canRecognize(entity)) { stop(); return; }
        var home = originalShelter();
        if (home == null || !home.owns(other.getUUID()) || other.distanceToSqr(Vec3.atBottomCenterOf(home.getBlockPos())) > 16) { stop(); return; }
        if (entity.distanceToSqr(other) > 2.25) { approach(other.blockPosition()); return; }
        if (!entity.hasLineOfSight(other)) { stop(); return; }
        entity.getNavigation().stop(); entity.getLookControl().setLookAt(other, 25, 25);
        if (task != Task.GREET) {
            boolean goodbye = task == Task.FAREWELL;
            entity.showReaction("happy", "known_peer", 60); other.showReaction("happy", "known_peer", 60);
            if (goodbye) { stop(); wait = 20; return; }
            task = Task.GREET; elapsed = 0;
        }
        if (elapsed >= 100) { visits++; task = Task.RETURN; elapsed = 0; visitor = null; }
    }
    private boolean approach(BlockPos pos) {
        if (!level().hasChunkAt(pos) || !entity.life().dryStand(pos)) { stop(); return false; }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(pos)) < .64) { entity.getNavigation().stop(); return true; }
        if (elapsed % 20 == 1 && !entity.life().navigate(pos, 1, false)) stop();
        return false;
    }
    @Override public void stop() {
        if (task == Task.WORK) work -= work % UNIT_WORK;
        task = Task.NONE; running = false; pickup = null; target = null; visitor = null; elapsed = 0; wait = 40;
        entity.getNavigation().stop();
    }
    public void dropCarrying() {
        if (carrying.isEmpty()) return;
        var drop = entity.spawnAtLocation(carrying.copy());
        if (drop != null) { carrying = ItemStack.EMPTY; drop.setPickUpDelay(40); }
    }
    /** Existing piles stay in the world; abandoning never refunds already lost materials. */
    public void abandon() {
        if (loaded(site)) for (var material : materials) {
            var item = materialEntity(material);
            if (item != null) { CustomData.update(DataComponents.CUSTOM_DATA, item.getItem(), tag -> tag.remove(MATERIAL_KEY)); item.setPickUpDelay(0); }
        }
        materials.clear(); site = null; work = projectTicks = 0; dropCarrying(); stop(); wait = CHECK_INTERVAL;
    }
    public Component observation() {
        String status = building() ? "building" : independent() ? returning ? "returning" : "independent" : eligible() ? "eligible" : "family";
        return Component.translatable("notebook.mcyukkuri.independence", Component.translatable("independence.mcyukkuri." + status),
                origin == null ? "—" : origin.pos().toShortString(), site == null ? "—" : site.pos().toShortString(),
                building() ? materials.size() : 0, building() ? paperNeeded() + beddingNeeded() : 0,
                building() ? work * 100 / workNeeded() : 0, rainLosses, visits);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); FamilyData.putPosition(tag,"Origin",origin); FamilyData.putPosition(tag,"Site",site);
        FamilyData.putPosition(tag,"EstablishedHome",establishedHome);
        if (originalGroup != null) tag.putUUID("OriginalGroup", originalGroup);
        tag.putString("Kind",kind.name()); tag.putString("Facing",facing.getName()); tag.putInt("Work",work - work % UNIT_WORK);
        tag.putInt("ProjectTicks",projectTicks); tag.putInt("Wait",wait); tag.putInt("VisitWait",visitWait);
        tag.putInt("Visits",visits); tag.putInt("RainLosses",rainLosses); tag.putBoolean("Farewell",farewell); tag.putBoolean("Returning",returning);
        if (!carrying.isEmpty()) tag.put("Carrying",carrying.save(entity.registryAccess()));
        var list = new ListTag();
        for (var material : materials) { var item = new CompoundTag(); item.putUUID("Entity",material.entityId); item.putUUID("Token",material.token); list.add(item); }
        tag.put("Materials",list); return tag;
    }
    public void load(CompoundTag tag) {
        origin = FamilyData.position(tag,"Origin"); site = FamilyData.position(tag,"Site"); establishedHome = FamilyData.position(tag,"EstablishedHome");
        originalGroup = tag.hasUUID("OriginalGroup") ? tag.getUUID("OriginalGroup") : null;
        kind = "CARDBOARD".equals(tag.getString("Kind")) ? ShelterBlock.Kind.CARDBOARD : ShelterBlock.Kind.BURROW;
        var direction = Direction.byName(tag.getString("Facing")); facing = direction != null && direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
        work = Math.clamp(tag.getInt("Work"),0,workNeeded()); work -= work % UNIT_WORK;
        projectTicks = Math.clamp(tag.getInt("ProjectTicks"),0,24000); wait = Math.clamp(tag.getInt("Wait"),0,CHECK_INTERVAL);
        if (tag.isEmpty()) wait = CHECK_INTERVAL;
        visitWait = tag.contains("VisitWait") ? Math.clamp(tag.getInt("VisitWait"),0,VISIT_INTERVAL) : VISIT_INTERVAL;
        visits = Math.max(0,tag.getInt("Visits")); rainLosses = Math.max(0,tag.getInt("RainLosses")); farewell = tag.getBoolean("Farewell");
        returning = tag.getBoolean("Returning"); carrying = tag.contains("Carrying") ? ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")) : ItemStack.EMPTY;
        materials.clear();
        for (Tag raw : tag.getList("Materials",Tag.TAG_COMPOUND)) { var item = (CompoundTag)raw;
            if (materials.size() < 6 && item.hasUUID("Entity") && item.hasUUID("Token") && materials.stream().noneMatch(m -> m.entityId.equals(item.getUUID("Entity"))))
                materials.add(new Material(item.getUUID("Entity"),item.getUUID("Token")));
        }
        if (origin == null || originalGroup == null || establishedHome != null) site = null;
        task = Task.NONE; running = false; visitor = null; pickup = null; target = null; elapsed = 0; loadGrace = 100;
    }
}
