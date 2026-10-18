package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.season.AutumnStock;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Foraging trips of a household with young (project design; the wiki names no hunting prey for ordinary yukkuri, only
 * catching insects as a lesson, so the trip is plain gathering of grass the family eats).
 * <ul>
 * <li>The father goes out on a schedule whenever the burrow store is below its target, far from home
 *     ({@link #FATHER_BASE} to {@link #MAX_RADIUS} blocks), gathers a load of up to {@link #FATHER_LOAD} real portions
 *     (each one a real plant stepped back a growth stage, see {@link Grazing}) and carries it to the forage pile.</li>
 * <li>The mother goes out when the father has been away too long ({@link #LONG_AWAY} ticks) or is gone for good, or when the
 *     store runs short; her range is smaller ({@link #MOTHER_BASE}) but widens with hunger and shortage, up to {@link #MOTHER_MAX}.</li>
 * <li>Children (anyone not adult) and babies never go. Night, rain, need for food or sleep, and any other duty end or
 *     postpone a trip; the animal then walks home, and a load still in its mouth is delivered at the next chance.</li>
 * </ul>
 * The goal is registered at selector priority {@value #GOAL_PRIORITY}, below the life needs (3). Perception, weather and
 * attacks decide in the status module; it may call {@link #recall} to send a forager home and reads {@link #outing()},
 * {@link #role()}, {@link #away()} and {@link #debug()}.
 */
public final class ForageTrip extends Goal {
    public static final int GOAL_PRIORITY = 4;
    public enum Role { NONE, FATHER, MOTHER }
    public enum Phase { NONE, OUT, GATHER, BACK }
    public static final int FATHER_BASE = 24, MOTHER_BASE = 10, MAX_RADIUS = 56, MOTHER_MAX = 40;
    public static final int FATHER_LOAD = 6, MOTHER_LOAD = 4;
    /** Game ticks the father may stay away before the mother counts him as not coming back (about 2.5 minutes). */
    public static final int LONG_AWAY = 3000;
    private static final int FATHER_MAX_TICKS = 3600, MOTHER_MAX_TICKS = 2400, FATHER_INTERVAL = 4000, MOTHER_INTERVAL = 2400;

    private final YukkuriEntity entity;
    private final PlantBedding plants;
    private final PileContact contact;
    private Phase phase = Phase.NONE;
    private Role role = Role.NONE;
    private BlockPos home, dest;
    private int radius;
    private long startedAt, deadline, lastTrip = Long.MIN_VALUE / 2;
    private ItemStack load = ItemStack.EMPTY;
    private int cooldown, ticks, failures, barren, watchdog, far;
    private double watchBest = Double.MAX_VALUE;
    private String reason = "";
    private int trips, delivered;

    public ForageTrip(YukkuriEntity entity) {
        this.entity = entity; this.plants = new PlantBedding(entity); this.contact = new PileContact(entity);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        cooldown = 100 + entity.getRandom().nextInt(200);
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }

    // ---- observation (read by the status module and the museum) ----
    public boolean outing() { return phase != Phase.NONE; }
    public Phase phase() { return phase; }
    public Role role() { return role; }
    /** Ticks since this trip started; 0 when at home. */
    public long away() { return outing() ? level().getGameTime() - startedAt : 0; }
    public int radius() { return radius; }
    public ItemStack load() { return load; }
    public int trips() { return trips; }
    public int delivered() { return delivered; }
    public double farthest() { return far; }
    public CompoundTag debug() {
        var tag = new CompoundTag();
        tag.putString("Phase", phase.name()); tag.putString("Role", role.name()); tag.putString("Reason", reason);
        tag.putInt("Radius", radius); tag.putLong("Away", away()); tag.putInt("Load", load.getCount()); tag.putInt("Trips", trips);
        tag.putInt("Delivered", delivered); tag.putInt("Farthest", far);
        if (dest != null) tag.putString("Dest", dest.toShortString());
        return tag;
    }
    /** Sends the forager home now (rain, a predator, nightfall...). The load, if any, is still delivered. */
    public void recall(String why) { if (outing() && phase != Phase.BACK) { phase = Phase.BACK; reason = why; ticks = 0; failures = 0; plants.stop(); } }

    // ---- who is who ----
    private ShelterBlockEntity shelter() { var value = entity.habitat().shelter(); return value != null && value.usable(entity) ? value : null; }
    private boolean wild() {
        var human = HumanCommerce.data(entity);
        return !human.hasUUID("Owner") && !human.contains("PendingRole") && !human.contains("FactoryStage");
    }
    private boolean body() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && entity.family().adult() && !entity.family().elder()
                && !entity.getVariant().isHouse() && !entity.getVariant().isPredator() && !HatVessel.aquatic(entity) && wild()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.family().active() && !entity.family().awaitingLanding() && entity.family().recovery() == 0
                && !entity.life().isSleeping() && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !Migration.underway(entity) && !entity.wintering().active() && !entity.independence().householdOuting()
                && !entity.settlement().duties().active()
                && !io.github.longshou.mcyukkuri.event.HomeClaim.holds(entity)
                && EventHooks.canEntityGrief(level(), entity);
    }
    /** Dependants of this animal living in the same burrow: loaded children that have not grown up. */
    private int young(ShelterBlockEntity home) {
        var data = FamilyData.get(level()); int count = 0;
        for (UUID id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person == null || person.status != FamilyData.Status.ALIVE) continue;
            if (level().getEntity(id) instanceof YukkuriEntity child && child.isAlive() && !child.family().adult() && home.owns(id)) count++;
        }
        return count;
    }
    /** The father of this animal's young, whichever of the two parents it is; null when unknown. */
    private UUID fatherOf() {
        var data = FamilyData.get(level());
        for (UUID id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person != null && person.status == FamilyData.Status.ALIVE && person.father != null) return person.father;
        }
        return null;
    }
    private Role roleOf(ShelterBlockEntity home) {
        if (young(home) == 0) return Role.NONE;
        var data = FamilyData.get(level());
        for (UUID id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person == null || person.status != FamilyData.Status.ALIVE) continue;
            if (entity.getUUID().equals(person.father)) return Role.FATHER;
            if (entity.getUUID().equals(person.mother)) return Role.MOTHER;
        }
        return Role.NONE;
    }
    /** The father has gone for good (dead, left the burrow) or has been out on a trip longer than {@link #LONG_AWAY}. */
    private boolean fatherAway(ShelterBlockEntity home) {
        UUID id = fatherOf();
        if (id == null) return true; // no known father: the mother is on her own
        var person = FamilyData.get(level()).person(id);
        if (person == null || person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED || person.status == FamilyData.Status.LOST) return true;
        if (!home.owns(id)) return true;
        if (level().getEntity(id) instanceof YukkuriEntity father) {
            if (!father.isAlive()) return true;
            return father.forageTrip().outing() && father.forageTrip().away() >= LONG_AWAY;
        }
        return false; // out of the loaded area: no news, no judgement
    }

    // ---- need ----
    /** 0 = the store is fine, 1 = below target, 2 = short, 3 = empty. */
    private int shortage(ShelterBlockEntity home) {
        int food = home.food(), people = Math.max(1, home.occupants());
        if (food == 0) return 3;
        if (food < people * 2) return 2;
        return food < AutumnStock.target(level(), home) ? 1 : 0;
    }
    private boolean fair() {
        var level = level();
        long time = level.getDayTime() % 24000;
        return time < 11500 && !level.isRaining() && !level.isThundering();
    }
    private boolean urgent() { return entity.life().hasUrgentNeed(); }
    private int radiusFor(Role role, int shortage) {
        int base = role == Role.FATHER ? FATHER_BASE : MOTHER_BASE, max = role == Role.FATHER ? MAX_RADIUS : MOTHER_MAX;
        float hunger = Math.clamp((70 - entity.life().nutrition()) / 70f, 0f, 1f);
        float want = Math.clamp(shortage / 3f * .75f + hunger * .5f, 0f, 1f);
        return Math.round(base + (max - base) * want);
    }

    /**
     * A meadow {@code 0.5..1.0 x radius} blocks from home with grazable plants on dry ground and not in a village: the
     * best of several random bearings. Only loaded chunks are considered; the trip itself walks there in legs.
     */
    private BlockPos chooseDestination(ShelterBlockEntity home, Role who, int r) {
        var level = level(); BlockPos origin = home.getBlockPos(); BlockPos best = null; int bestCount = 3;
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = entity.getRandom().nextDouble() * Math.PI * 2, dist = r * (.5 + entity.getRandom().nextDouble() * .5);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist), z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            var column = new BlockPos(x, origin.getY(), z);
            if (!level.hasChunkAt(column) || level.isVillage(column)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            var spot = new BlockPos(x, y, z);
            if (Math.abs(y - origin.getY()) > 12 || !level.getFluidState(spot.below()).isEmpty() || !level.getFluidState(spot).isEmpty()) continue;
            int count = 0;
            for (int dx = -6; dx <= 6; dx += 2) for (int dz = -6; dz <= 6; dz += 2) for (int dy = -2; dy <= 2; dy++) {
                var p = spot.offset(dx, dy, dz);
                if (level.hasChunkAt(p) && Grazing.edible(level.getBlockState(p))) count++;
            }
            if (count > bestCount) { bestCount = count; best = spot; }
        }
        return best;
    }

    // ---- selector ----
    @Override public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (phase != Phase.NONE && level().getGameTime() > deadline + 3600) { giveUp("lapsed"); return false; }
        if (!body()) return false;
        if (phase != Phase.NONE) {
            var inside = shelter();
            if (inside == null) { giveUp("home_lost"); return false; }
            // Sent home (rain, dusk) and already indoors with nothing to deliver: the trip is over; it must not hold the next one back.
            if (phase == Phase.BACK && load.isEmpty() && inside.contains(entity.blockPosition())) { finish(true); return false; }
            return true;
        }
        if (!load.isEmpty()) { // a load left over from an interrupted trip: bring it home first
            if (shelter() == null) { dropLoad(); return false; }
            begin(role == Role.NONE ? Role.MOTHER : role, entity.blockPosition(), 0, "leftover_load"); phase = Phase.BACK; return true;
        }
        if (cooldown-- > 0) return false;
        cooldown = 80 + entity.getRandom().nextInt(60);
        var home = shelter();
        if (home == null || urgent() || !fair() || entity.life().mealTicks() != 0 || Seasons.confined(entity)) return false;
        if (entity.life().nutrition() < 35 || entity.life().energy() < 40 || entity.getHealth() < entity.getMaxHealth() * .6f) return false;
        if (!SearchBudget.take(level())) { cooldown = 5 + entity.getRandom().nextInt(10); return false; }
        Role who = roleOf(home);
        if (who == Role.NONE) return false;
        int shortage = shortage(home); long now = level().getGameTime();
        boolean go;
        if (who == Role.FATHER) {
            // On a schedule while the store is below target; sooner and without the interval when it is short.
            go = shortage >= 1 && now - lastTrip >= (shortage >= 2 ? FATHER_INTERVAL / 3 : FATHER_INTERVAL) * entity.species().traits().forage();
        } else {
            boolean absent = fatherAway(home);
            // The mother never leaves a full store, and with a working father only when the store runs dry.
            go = now - lastTrip >= MOTHER_INTERVAL * entity.species().traits().forage() && (absent && shortage >= 1 || shortage >= 3);
        }
        if (!go) return false;
        int r = radiusFor(who, shortage);
        BlockPos target = chooseDestination(home, who, r);
        if (target == null) { cooldown = 600; return false; }
        begin(who, target, r, who == Role.FATHER ? (shortage >= 2 ? "father_short" : "father_routine") : (fatherAway(home) ? "mother_father_away" : "mother_empty"));
        return true;
    }
    private void begin(Role who, BlockPos target, int r, String why) {
        var home = shelter();
        this.role = who; this.dest = target; this.radius = r; this.home = home == null ? entity.blockPosition() : home.getBlockPos();
        this.phase = Phase.OUT; this.reason = why; this.startedAt = level().getGameTime();
        this.deadline = startedAt + (who == Role.FATHER ? FATHER_MAX_TICKS : MOTHER_MAX_TICKS);
        this.ticks = failures = barren = watchdog = far = 0; this.watchBest = Double.MAX_VALUE; trips++;
    }
    @Override public boolean canContinueToUse() {
        if (phase == Phase.NONE || !body()) return false;
        if (urgent() || entity.life().mealTicks() != 0) return false; // the state survives; the goal resumes afterwards
        return true;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { ticks = 0; }
    @Override public void stop() {
        plants.stop(); contact.reset(); entity.getNavigation().stop();
        // A trip is not cancelled by an interruption; it lapses only through its own deadline (see tick).
        if (phase != Phase.NONE && level().getGameTime() > deadline + 2400) giveUp("lapsed");
    }

    private void finish(boolean ok) {
        if (ok) lastTrip = level().getGameTime();
        else lastTrip = level().getGameTime() - FATHER_INTERVAL / 2;
        phase = Phase.NONE; ticks = 0; dest = null; plants.stop(); contact.reset();
    }
    private void giveUp(String why) { reason = why; dropLoad(); finish(false); }
    private void dropLoad() {
        if (load.isEmpty() || entity.level().isClientSide) { load = ItemStack.EMPTY; return; }
        var drop = new ItemEntity(level(), entity.getX(), entity.getY(), entity.getZ(), load.copy()); drop.setPickUpDelay(100);
        if (level().addFreshEntity(drop)) load = ItemStack.EMPTY;
    }

    // ---- the trip itself ----
    @Override public void tick() {
        if (phase == Phase.NONE) return;
        ticks++;
        var level = level(); long now = level.getGameTime();
        var home = shelter();
        if (home == null || !home.getBlockPos().equals(this.home)) { giveUp("home_lost"); return; }
        far = (int) Math.max(far, Math.sqrt(entity.blockPosition().distSqr(this.home)));
        if (phase != Phase.BACK) {
            String why = now >= deadline ? "late" : level.getDayTime() % 24000 >= 12000 ? "dusk" : level.isRaining() ? "rain"
                    : entity.life().nutrition() < 30 ? "hungry" : null;
            if (why != null) { recall(why); }
        }
        switch (phase) {
            case OUT -> {
                if (horizontal(dest) < 6) { phase = Phase.GATHER; ticks = 0; watchdog = 0; barren = 0; plants.stop(); return; }
                // From inside the burrow the straight line to the meadow runs through the hill: walk out of the mouth first.
                if (home.contains(entity.blockPosition())) { travel(outside(home)); return; }
                travel(dest);
            }
            case GATHER -> gather(home);
            case BACK -> back(home);
            default -> { }
        }
        watchdog();
    }
    private double horizontal(BlockPos pos) { return Math.sqrt(Math.max(0, entity.distanceToSqr(Vec3.atBottomCenterOf(pos)) - Math.pow(entity.getY() - pos.getY(), 2))); }
    /** Gives up on a leg that makes no progress for ten seconds three times in a row. */
    private void watchdog() {
        if (ticks % 200 != 0 || phase == Phase.GATHER) return;
        BlockPos goal = phase == Phase.OUT ? dest : this.home;
        double d = horizontal(goal);
        if (d > watchBest - 2) { if (++watchdog >= 3) { watchdog = 0; if (phase == Phase.OUT) { recall("stuck"); } else giveUp("stuck_home"); } }
        else { watchdog = 0; watchBest = d; }
    }
    /** Stepwise travel: the pathfinder only sees about sixteen blocks, so aim for a waypoint on the way. */
    private void travel(BlockPos goal) {
        if (!entity.getNavigation().isDone() && ticks % 40 != 1) return;
        double dx = goal.getX() + .5 - entity.getX(), dz = goal.getZ() + .5 - entity.getZ(), d = Math.hypot(dx, dz);
        if (d <= 10) {
            if (entity.life().navigate(goal, 1, false)) { failures = 0; return; }
        } else {
            double base = Math.atan2(dz, dx);
            for (int turn : new int[] {0, 35, -35, 70, -70, 110, -110}) {
                double angle = base + Math.toRadians(turn);
                int x = (int) Math.floor(entity.getX() + Math.cos(angle) * 9), z = (int) Math.floor(entity.getZ() + Math.sin(angle) * 9);
                var probe = new BlockPos(x, entity.getBlockY(), z);
                if (!level().hasChunkAt(probe)) continue;
                var way = new BlockPos(x, level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Math.abs(way.getY() - entity.getBlockY()) > 6 || !entity.life().dryStand(way)) continue;
                if (entity.life().navigate(way, 1, false)) { failures = 0; return; }
            }
        }
        if (++failures >= 4) { failures = 0; if (phase == Phase.OUT) recall("no_route"); else giveUp("no_route_home"); }
    }
    private void gather(ShelterBlockEntity home) {
        // Cheek pouches (Reimu only) carry two extra portions per extra cheek slot.
        int cap = (role == Role.FATHER ? FATHER_LOAD : MOTHER_LOAD) + (entity.species().cheekCapacity() - 1) * 2;
        if (load.getCount() >= cap) { recall("full"); return; }
        if (!plants.active()) {
            if (ticks % 5 != 0) return;
            if (!plants.find(dest, 9, pos -> Grazing.edible(level().getBlockState(pos)))) {
                if (!plants.refused() && ++barren >= 4) recall(load.isEmpty() ? "barren" : "enough");
                else if (ticks > 40 && !plants.refused() && horizontal(dest) > 5) travel(dest);
                return;
            }
            barren = 0;
        }
        var portion = plants.tick();
        if (portion.isEmpty()) return;
        // Eats on the spot when hungry; the rest goes into the load. Either way the plant has fallen back one stage.
        var grass = new ItemStack(Items.SHORT_GRASS);
        if (entity.life().nutrition() < 55 && entity.life().canEat() && entity.life().acceptsFood(grass) && entity.life().beginMeal(grass, null, false)) return;
        if (load.isEmpty()) load = portion.copy(); else load.grow(1);
    }
    private void back(ShelterBlockEntity home) {
        var pile = load.isEmpty() ? home.pile() : home.ensurePile();
        if (load.isEmpty() || pile == null) {
            BlockPos door = home.entrance();
            if (horizontal(door) < 3 || horizontal(this.home) < 3) { arrived(home); return; }
            travel(door); return;
        }
        // Back from the meadow: to the mouth first (the store is inside the hill), then in to the pile.
        if (!home.contains(entity.blockPosition()) && horizontal(outside(home)) > 2.5) { travel(outside(home)); return; }
        if (horizontal(pile.standCell()) > 6) { travel(pile.standCell()); return; }
        if (!contact.near(pile)) { contact.reset(); entity.life().navigate(pile.standCell(), 1, false); return; }
        if (!contact.tick(pile)) return;
        load = home.returnFood(load);
        arrived(home);
    }
    /** A standing point two cells out from the mouth, on the open side. */
    private static BlockPos outside(ShelterBlockEntity home) { return home.entrance().relative(home.facing(), 2); }
    private void arrived(ShelterBlockEntity home) {
        delivered++;
        if (!load.isEmpty()) dropLoad(); // the store is full: whatever is left stays on the floor at the burrow mouth
        entity.showReaction("happy", "gentle", 60);
        finish(true);
    }
}
