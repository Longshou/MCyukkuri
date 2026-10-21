package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.human.Custody;
import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
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
import java.util.Set;

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
 * <li>In a led community the forager first carries the levy ({@link Levy}, a fifth of a family load, a third of a
 *     hunter's) to the common store and then the rest home; a household short of food pays nothing. Each finished
 *     outing is reported to the community ({@link Commons#outing}) for the leader's food check.</li>
 * </ul>
 * The goal is registered at selector priority {@value #GOAL_PRIORITY}, below the life needs (3). Perception, weather and
 * attacks decide in the status module; it may call {@link #recall} to send a forager home and reads {@link #outing()},
 * {@link #role()}, {@link #away()} and {@link #debug()}.
 */
public final class ForageTrip extends Goal {
    public static final int GOAL_PRIORITY = 4;
    /** CHIEF and HUNTER: the leader and members of a communal hunt ({@link CommonHunt}), delivering to the common store. */
    public enum Role { NONE, FATHER, MOTHER, CHIEF, HUNTER }
    /** MUSTER: a hunter waiting at the gathering point for the party to leave. */
    public enum Phase { NONE, MUSTER, OUT, GATHER, BACK }
    public static final int FATHER_BASE = 48, MOTHER_BASE = 20, MAX_RADIUS = 192, MOTHER_MAX = 128;
    public static final int FATHER_LOAD = 6, MOTHER_LOAD = 4;
    /** Game ticks the father may stay away before the mother counts him as not coming back (about 2.5 minutes). */
    public static final int LONG_AWAY = 3000;
    private static final int FATHER_MAX_TICKS = 7200, MOTHER_MAX_TICKS = 4800, FATHER_INTERVAL = 4000, MOTHER_INTERVAL = 2400;

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
    /** This trip is part of a communal hunt: {@link #home} is then the muster point and the load goes to the common store. */
    private boolean hunting;
    /** Where a returning forager is heading to pay the levy (the common store pile), for the progress watchdog. */
    private BlockPos storeAim;
    /** Portions of the load still owed to the common store; -1 until assessed on the way back. */
    private int levy = -1;
    /** Portions gathered into the load on this trip (eaten ones excluded). */
    private int gathered;
    private long noMeadowAt = Long.MIN_VALUE / 2;

    public ForageTrip(YukkuriEntity entity) {
        this.entity = entity; this.plants = new PlantBedding(entity); this.contact = new PileContact(entity);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        cooldown = 100 + entity.getRandom().nextInt(200);
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }

    // ---- observation (read by the status module and the museum) ----
    public boolean outing() { return phase != Phase.NONE; }
    /** The trip survives interruption: the arbiter must also require the actual running Goal. */
    @Nullable public String activity() {
        if (phase == Phase.NONE) return null;
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        return switch (phase) {
            case MUSTER -> Behaviors.FORAGE_MUSTER;
            case OUT -> Behaviors.FORAGE_OUT;
            case GATHER -> plants.harvesting() ? Behaviors.FORAGE_HARVEST
                    : plants.active() ? Behaviors.FORAGE_GATHER : Behaviors.FORAGE_SEARCH;
            // Being near a pile does not establish a reservation or a completed deposit.
            case BACK -> storeAim != null ? Behaviors.FORAGE_LEVY : Behaviors.FORAGE_RETURN;
            default -> null;
        };
    }

    public Phase phase() { return phase; }
    public Role role() { return role; }
    /** Ticks since this trip started; 0 when at home. */
    public long away() { return outing() ? level().getGameTime() - startedAt : 0; }
    public int radius() { return radius; }
    public ItemStack load() { return load; }
    public int trips() { return trips; }
    public int delivered() { return delivered; }
    public double farthest() { return far; }
    public boolean hunting() { return hunting && phase != Phase.NONE; }
    /** Body and circumstances allow a trip now (the same gate as for family trips); read by {@link CommonHunt}. */
    boolean able() { return entity.level() instanceof ServerLevel && body(); }
    public CompoundTag debug() {
        var tag = new CompoundTag();
        tag.putString("Phase", phase.name()); tag.putString("Role", role.name()); tag.putString("Reason", reason);
        tag.putInt("Radius", radius); tag.putLong("Away", away()); tag.putInt("Load", load.getCount()); tag.putInt("Trips", trips);
        tag.putInt("Delivered", delivered); tag.putInt("Farthest", far); tag.putBoolean("Hunting", hunting);
        if (dest != null) tag.putString("Dest", dest.toShortString());
        return tag;
    }
    /** Sends the forager home now (rain, a predator, nightfall...). The load, if any, is still delivered. */
    public void recall(String why) {
        if (outing() && phase != Phase.BACK) {
            GameplayLog.event(entity,"forage","phase={} -> BACK reason={} gathered={} carried={} home={}",phase,why,gathered,load.getCount(),home);
            phase = Phase.BACK; reason = why; ticks = 0; failures = 0; plants.stop();
            if (hunting && role == Role.CHIEF) CommonHunt.chiefRecall(entity, why);
        }
    }

    // ---- who is who ----
    private ShelterBlockEntity shelter() { var value = entity.habitat().shelter(); return value != null && value.usable(entity) ? value : null; }
    private boolean wild() {
        var human = Custody.view(entity);
        return human.wild();
    }
    private boolean body() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && entity.family().adult()
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri() && wild()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.family().active() && !entity.family().awaitingLanding() && entity.family().recovery() == 0
                && !entity.life().isSleeping() && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && migrationAllowsForage() && !entity.wintering().active() && !entity.independence().householdOuting()
                && !entity.settlement().duties().active()
                && !SettlementEvents.homeClaimHeld(entity)
                && EventHooks.canEntityGrief(level(), entity);
    }
    private boolean migrationAllowsForage() {
        boolean underway = Migration.underway(entity);
        if (underway && entity.life().nutrition() < 30)
            GameplayLog.limited(entity, "forage", "migration.hungry", 1200,
                    "forage refused reason=migration_unsettled group={} nutrition={} phase={} carried={}",
                    entity.settlement().groupId(), entity.life().nutrition(), phase, load.getCount());
        return !underway;
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
    private boolean recoveringNeeds;
    private boolean urgent() {
        return entity.life().nutrition() < 15 || entity.life().energy() < 15 || entity.isOnFire()
                || entity.isInWaterOrBubble() || entity.life().wetness() > 40;
    }
    private int radiusFor(Role role, int shortage) {
        int base = role == Role.FATHER ? FATHER_BASE : MOTHER_BASE, max = role == Role.FATHER ? MAX_RADIUS : MOTHER_MAX;
        float hunger = Math.clamp((70 - entity.life().nutrition()) / 70f, 0f, 1f);
        float want = Math.clamp(shortage / 3f * .75f + hunger * .5f, 0f, 1f);
        return Math.round((base + (max - base) * want) * entity.life().personality().exploreMultiplier());
    }

    /** A bounded sample of actual plants near home, taken only when a budgeted trip is about to begin. */
    @Nullable private BlockPos nearbyMeadow(BlockPos home) {
        int plants = 0;
        for (int dx = -12; dx <= 12; dx += 3) for (int dz = -12; dz <= 12; dz += 3) {
            if (dx * dx + dz * dz > 12 * 12) continue;
            var column = home.offset(dx, 0, dz);
            if (!level().hasChunkAt(column) || level().isVillage(column)) continue;
            int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            if (Math.abs(y - home.getY()) > 6) continue;
            var stand = new BlockPos(column.getX(), y, column.getZ());
            if (!level().getFluidState(stand).isEmpty() || !level().getFluidState(stand.below()).isEmpty()) continue;
            for (int dy = -1; dy <= 1; dy++) {
                var pos = new BlockPos(column.getX(), y + dy, column.getZ());
                if (level().getFluidState(pos).isEmpty() && Grazing.edible(level().getBlockState(pos)) && ++plants >= 8)
                    return stand;
            }
        }
        return null;
    }

    /**
     * A meadow {@code 0.75..1.0 x radius} blocks from home with grazable plants on dry ground and not in a village: the
     * best of several random bearings. Only loaded chunks are considered; the trip itself walks there in legs.
     */
    private BlockPos chooseDestination(ShelterBlockEntity home, int r) { return destinationFrom(home.getBlockPos(), r, .75); }
    /** A meadow {@code 0.5..1.0 x r} blocks from {@code origin}; used by the leader to pick the hunting ground. */
    BlockPos destinationFrom(BlockPos origin, int r) { return destinationFrom(origin, r, .5); }
    private BlockPos destinationFrom(BlockPos origin, int r, double inner) {
        var level = level(); BlockPos best = null; int bestCount = 3;
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = entity.getRandom().nextDouble() * Math.PI * 2, dist = r * (inner + entity.getRandom().nextDouble() * (1 - inner));
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
        if (entity.profile().aquatic() && entity.getVehicle() instanceof HatBoatEntity boat) {
            // Water-side picking is tried by AquaticLife first. A real shore request survives until landing.
            return false;
        }
        if (!body()) return false;
        if (entity.profile().aquatic()) return aquaticPlan();
        if (recoveringNeeds) {
            if (entity.life().nutrition() < 30 || entity.life().energy() < 30) return false;
            recoveringNeeds = false;
        }
        // A suspended trip must yield to the same needs that stopped it, including emergency grazing.
        if (urgent() || entity.life().mealTicks() != 0) return false;
        if (phase != Phase.NONE) {
            if (hunting) return true; // a hunt trip ends through its own phases (tick)
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
        var hunt = CommonHunt.drafted(entity);
        if (hunt != null) { beginHunt(hunt); return true; }
        if (cooldown-- > 0) return false;
        cooldown = 80 + entity.getRandom().nextInt(60);
        var home = shelter();
        if (home == null || urgent() || !fair() || entity.life().mealTicks() != 0 || SettlementSeasons.confined(entity)) return false;
        if (entity.life().nutrition() < 35 || entity.life().energy() < 40 || entity.getHealth() < entity.getMaxHealth() * .6f) return false;
        if (!SearchBudget.take(level(),entity,Purpose.FORAGE_TRIP)) { cooldown = 5 + entity.getRandom().nextInt(10); return false; }
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
        BlockPos nearby = nearbyMeadow(home.getBlockPos());
        int r = nearby == null ? radiusFor(who, shortage) : 12;
        BlockPos target = nearby == null ? chooseDestination(home, r) : nearby;
        if (target == null) {
            cooldown = 600;
            GameplayLog.limited(entity,"forage","no_meadow",1200,"start refused reason=no_meadow role={} radius={} home={}",who,r,home.getBlockPos());
            // Nothing left to graze within reach: a failed outing for the leader's check, at most once per quarter day.
            var group = entity.settlement().group();
            if (group != null && now - noMeadowAt >= 6000) { noMeadowAt = now; group.commons.outing(false); }
            return false;
        }
        begin(who, target, r, who == Role.FATHER ? (shortage >= 2 ? "father_short" : "father_routine") : (fatherAway(home) ? "mother_father_away" : "mother_empty"));
        return true;
    }
    /** The same finite outing and levy, with the remembered landing bank instead of a burrow door. */
    private boolean aquaticPlan() {
        if (!entity.life().hasAccessory() || entity.life().mealTicks() != 0 || urgent()) return false;
        if (phase != Phase.NONE) {
            entity.aquaticLife().holdShoreWork(ShoreWork.FORAGE);
            return true;
        }
        if (!load.isEmpty()) {
            begin(Role.MOTHER, entity.blockPosition(), 0, "aquatic_leftover");
            phase = Phase.BACK; return true;
        }
        if (entity.aquaticLife().availableFood() >= entity.aquaticLife().stockTarget() || !fair()
                || entity.life().energy() < 35 || entity.life().nutrition() < 30) return false;
        if (level().getGameTime() - lastTrip < 200) return false;
        entity.aquaticLife().holdShoreWork(ShoreWork.FORAGE);
        if (!SearchBudget.take(level(), entity, Purpose.FORAGE_TRIP)) return false;
        var bank = entity.aquaticLife().boardingBank();
        if (bank == null) bank = entity.blockPosition();
        var meadow = nearbyMeadow(bank);
        if (meadow == null) meadow = destinationFrom(bank, 16);
        if (meadow == null) {
            lastTrip = level().getGameTime();
            entity.aquaticLife().releaseShoreWork(ShoreWork.FORAGE);
            GameplayLog.limited(entity, "forage", "aquatic.no_meadow", 600,
                    "start refused reason=no_meadow bank={} food={} target={}", bank,
                    entity.aquaticLife().availableFood(), entity.aquaticLife().stockTarget());
            return false;
        }
        begin(Role.MOTHER, meadow, 16, "aquatic_stores");
        return true;
    }
    private void begin(Role who, BlockPos target, int r, String why) {
        var home = shelter();
        this.role = who; this.dest = target; this.radius = r; this.home = entity.profile().aquatic() && entity.aquaticLife().boardingBank() != null
                ? entity.aquaticLife().boardingBank() : home == null ? entity.blockPosition() : home.getBlockPos();
        this.phase = Phase.OUT; this.reason = why; this.startedAt = level().getGameTime();
        this.deadline = startedAt + (who == Role.FATHER ? FATHER_MAX_TICKS : MOTHER_MAX_TICKS);
        this.ticks = failures = barren = watchdog = far = 0; this.watchBest = Double.MAX_VALUE; trips++;
        this.levy = -1; this.gathered = 0; this.storeAim = null;
        GameplayLog.event(entity,"forage","begin role={} phase={} reason={} home={} destination={} radius={} deadline={}",role,phase,reason,this.home,dest,radius,deadline);
    }
    /** Joins the communal hunt: walk to the gathering point, then out behind the leader. */
    private void beginHunt(Commons.Hunt hunt) {
        boolean chief = entity.getUUID().equals(hunt.chief);
        this.role = chief ? Role.CHIEF : Role.HUNTER; this.dest = hunt.dest; this.radius = hunt.radius; this.home = hunt.muster;
        this.hunting = true; this.reason = chief ? "hunt_chief" : "hunt_member";
        this.phase = hunt.phase == Commons.HuntPhase.MUSTER ? Phase.MUSTER : Phase.OUT;
        this.startedAt = level().getGameTime();
        this.deadline = Math.max(hunt.deadline, startedAt + CommonHunt.HUNT_TICKS);
        this.ticks = failures = barren = watchdog = far = 0; this.watchBest = Double.MAX_VALUE; trips++;
        this.levy = -1; this.gathered = 0; this.storeAim = null;
        GameplayLog.event(entity,"forage","begin role={} phase={} reason={} muster={} destination={} deadline={}",role,phase,reason,this.home,dest,deadline);
    }
    @Override public boolean canContinueToUse() {
        if (phase == Phase.NONE || !body()) return false;
        if (urgent()) { recoveringNeeds = true; return false; }
        if (entity.life().mealTicks() != 0) return false; // the state survives; the goal resumes afterwards
        return true;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        ticks = 0;
        GameplayLog.limited(entity,"forage","active",200,"goal active phase={} reason={} carried={} elapsed={}",phase,reason,load.getCount(),away());
    }
    @Override public void stop() {
        SearchBudget.cancel(level(),entity,Purpose.FORAGE_TRIP,0);
        plants.stop(); contact.reset(); entity.getNavigation().stop();
        // A trip is not cancelled by an interruption; it lapses only through its own deadline (see tick).
        if (phase != Phase.NONE && level().getGameTime() > deadline + 2400) giveUp("lapsed");
        else if(phase!=Phase.NONE)GameplayLog.limited(entity,"forage","paused",200,
                "goal paused phase={} reason={} carried={} nutrition={} energy={} mealTicks={} panic={}",
                phase,reason,load.getCount(),entity.life().nutrition(),entity.life().energy(),entity.life().mealTicks(),entity.isPanicking());
    }

    private void finish(boolean ok) {
        GameplayLog.event(entity,"forage","end ok={} phase={} reason={} role={} gathered={} carried={} elapsed={} farthest={}",
                ok,phase,reason,role,gathered,load.getCount(),away(),far);
        if (hunting) { CommonHunt.done(entity, reason); hunting = false; }
        // A family trip reports to the community: food brought back, or an empty return from a barren or unreachable meadow.
        else if (role == Role.FATHER || role == Role.MOTHER) {
            var group = entity.settlement().group();
            if (group != null && (gathered > 0 || FAILED.contains(reason))) group.commons.outing(gathered > 0);
        }
        entity.aquaticLife().releaseShoreWork(ShoreWork.FORAGE);
        gathered = 0; levy = -1;
        storeAim = null;
        if (ok) lastTrip = level().getGameTime();
        else lastTrip = level().getGameTime() - FATHER_INTERVAL / 2;
        phase = Phase.NONE; ticks = 0; dest = null; plants.stop(); contact.reset();
    }
    private static final Set<String> FAILED = Set.of("barren", "late", "no_route", "stuck", "stuck_home", "no_route_home", "lapsed");
    private void giveUp(String why) { reason = why; dropLoad(); finish(false); }
    private void dropLoad() {
        if (load.isEmpty() || entity.level().isClientSide) { load = ItemStack.EMPTY; return; }
        var drop = new ItemEntity(level(), entity.getX(), entity.getY(), entity.getZ(), load.copy()); drop.setPickUpDelay(100);
        if (level().addFreshEntity(drop)) {
            GameplayLog.event(entity,"forage","drop item={} count={} reason={} drop={}",load.getItem(),load.getCount(),reason,drop.getUUID());
            load = ItemStack.EMPTY;
        } else GameplayLog.limited(entity,"forage","drop_refused",200,"drop refused count={} reason={}",load.getCount(),reason);
    }

    // ---- the trip itself ----
    @Override public void tick() {
        if (phase == Phase.NONE) return;
        ticks++;
        if (entity.profile().aquatic()) entity.aquaticLife().holdShoreWork(ShoreWork.FORAGE);
        var level = level(); long now = level.getGameTime();
        var home = shelter();
        if (!hunting && !entity.profile().aquatic() && (home == null || !home.getBlockPos().equals(this.home))) { giveUp("home_lost"); return; }
        far = (int) Math.max(far, Math.sqrt(entity.blockPosition().distSqr(this.home)));
        if (hunting) {
            var hunt = CommonHunt.of(entity);
            if (hunt == null || hunt.phase == Commons.HuntPhase.IDLE || !hunt.party.contains(entity.getUUID())) {
                if (phase == Phase.MUSTER || load.isEmpty()) { finish(false); return; }
                if (phase != Phase.BACK) recall("hunt_over");
            } else if (hunt.phase == Commons.HuntPhase.RETURN && phase != Phase.BACK) recall("chief_recall");
            if (phase == Phase.MUSTER) { muster(hunt); watchdog(); return; }
        }
        if (phase != Phase.BACK) {
            String why = now >= deadline ? "late" : level.getDayTime() % 24000 >= 12000 ? "dusk" : level.isRaining() ? "rain"
                    : entity.life().nutrition() < 30 ? "hungry" : null;
            if (why != null) { recall(why); }
        }
        switch (phase) {
            case OUT -> {
                // From inside the burrow the straight line to the meadow runs through the hill: walk out of the mouth first.
                if (!hunting && home != null && home.contains(entity.blockPosition())) { travel(outside(home)); return; }
                if (horizontal(dest) < 6) {
                    phase = Phase.GATHER; ticks = 0; watchdog = 0; barren = 0; plants.stop();
                    GameplayLog.event(entity,"forage","phase=OUT -> GATHER destination={} elapsed={}",dest,away());return;
                }
                // A hunter keeps behind its leader on the way out.
                var chief = role == Role.HUNTER ? CommonHunt.chief(entity) : null;
                if (chief != null && chief.forageTrip() != null && chief.forageTrip().phase() == Phase.OUT && entity.distanceToSqr(chief) > 7 * 7) { travel(chief.blockPosition()); return; }
                travel(dest);
            }
            case GATHER -> gather(home);
            case BACK -> { if (hunting) backToStore(); else if (entity.profile().aquatic()) backToHat(); else back(home); }
            default -> { }
        }
        watchdog();
    }
    private double horizontal(BlockPos pos) { return Math.sqrt(Math.max(0, entity.distanceToSqr(Vec3.atBottomCenterOf(pos)) - Math.pow(entity.getY() - pos.getY(), 2))); }
    /** Gives up on a leg that makes no progress for ten seconds three times in a row. */
    private void watchdog() {
        if (ticks % 200 != 0 || phase == Phase.GATHER || phase == Phase.MUSTER) return;
        // Close to the common store the real path may wind into the hollow; the trip deadline bounds it instead.
        if (phase == Phase.BACK && storeAim != null && horizontal(storeAim) <= 14) { watchdog = 0; return; }
        BlockPos goal = phase == Phase.OUT ? dest : storeAim != null ? storeAim : this.home;
        double d = horizontal(goal);
        if (d > watchBest - 2) {
            if (++watchdog >= 3) {
                watchdog = 0;
                if (phase == Phase.OUT) recall("stuck");
                else if (storeAim != null) skipLevy("levy_unreachable"); // the store cannot be reached: the whole load goes home
                else giveUp("stuck_home");
            }
        }
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
        if (++failures >= 4) { failures = 0; if (phase == Phase.OUT) recall("no_route"); else if (storeAim != null) skipLevy("levy_no_route"); else giveUp("no_route_home"); }
    }
    private void gather(ShelterBlockEntity home) {
        // Cheek pouches (Reimu only) carry two extra portions per extra cheek slot.
        int cap = (role == Role.MOTHER ? MOTHER_LOAD : FATHER_LOAD) + (entity.species().cheekCapacity() - 1) * 2;
        if (entity.profile().aquatic()) cap = Math.max(1, Math.min(cap, entity.aquaticLife().stockTarget() - entity.aquaticLife().availableFood()));
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
        gathered++;
    }
    private void backToHat() {
        if (!load.isEmpty() && levy < 0) levy = Levy.portions(load.getCount(), Levy.rate(entity, false));
        if (levy > 0 && !load.isEmpty() && payLevy(null)) return;
        if (horizontal(this.home) > 2) { travel(this.home); return; }
        int moved = entity.aquaticLife().receiveProvisions(load, load.getCount());
        Levy.kept(entity, moved);
        GameplayLog.event(entity, "forage", "returned to bank={} stored={} carried={} reserve={}",
                this.home, moved, load.getCount(), entity.aquaticLife().availableFood());
        delivered++;
        if (!load.isEmpty()) dropLoad();
        finish(true);
    }
    private void back(ShelterBlockEntity home) {
        if (!load.isEmpty() && levy < 0) levy = Levy.portions(load.getCount(), Levy.rate(entity, false));
        if (levy > 0 && !load.isEmpty() && payLevy(home)) return;
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
        int before = load.getCount();
        load = home.returnFood(load);
        Levy.kept(entity, before - load.getCount());
        GameplayLog.event(entity,"forage","deposit home={} moved={} carried={} stock={}",home.getBlockPos(),before-load.getCount(),load.getCount(),home.food());
        arrived(home);
    }

    /**
     * On the way back: carry {@link #levy} portions into the common store first. True while still walking or at the
     * pile; false once paid or skipped (store full or the own pile, unreachable, too late), the rest then goes home.
     */
    private boolean payLevy(ShelterBlockEntity own) {
        var level = level();
        var group = entity.settlement().group();
        var held = load;
        var pile = group == null ? null : CommonStore.nearest(level, group, entity.blockPosition(), p -> p.foodSpace(held) > 0);
        var ownPile = own == null ? null : own.pile();
        if (pile == null || ownPile != null && ownPile.getBlockPos().equals(pile.getBlockPos())) { levy = 0; storeAim = null; return false; }
        if (level.getGameTime() > deadline + 1200) { skipLevy("levy_late"); return false; }
        if (!pile.standCell().equals(storeAim)) { storeAim = pile.standCell(); watchBest = Double.MAX_VALUE; watchdog = 0; }
        if (horizontal(pile.standCell()) > 14) { travel(pile.standCell()); return true; }
        if (!contact.near(pile)) {
            contact.reset();
            if (entity.getNavigation().isDone() || ticks % 40 == 1) entity.life().navigate(pile.standCell(), 1, false);
            return true;
        }
        if (!contact.tick(pile)) return true;
        int owed = Math.min(levy, load.getCount());
        var rest = pile.returnFood(load.copyWithCount(owed));
        int paid = owed - rest.getCount();
        load.shrink(paid);
        if (hunting) CommonHunt.delivered(entity, paid);
        Levy.paid(entity, paid, hunting);
        GameplayLog.event(entity,"forage","levy store={} requested={} paid={} carried={} hunting={}",pile.getBlockPos(),owed,paid,load.getCount(),hunting);
        levy = 0; storeAim = null; contact.reset(); watchBest = Double.MAX_VALUE; watchdog = 0;
        return false;
    }
    private void skipLevy(String why) {
        GameplayLog.event(entity,"forage","levy skipped reason={} owed={} store={} carried={}",why,levy,storeAim,load.getCount());
        levy = 0; storeAim = null; contact.reset(); watchBest = Double.MAX_VALUE; watchdog = 0;
        if (hunting) { var own = shelter(); if (own != null) toHome(own, why); else giveUp(why); }
    }
    /** A hunter that has paid its levy takes the rest of the load home like a family forager. */
    private void toHome(ShelterBlockEntity own, String why) {
        CommonHunt.kept(entity, load.getCount());
        CommonHunt.done(entity, why);
        hunting = false; this.home = own.getBlockPos(); reason = why; storeAim = null; levy = 0;
        GameplayLog.event(entity,"forage","hunt handoff home={} reason={} carried={}",home,why,load.getCount());
        contact.reset(); watchBest = Double.MAX_VALUE; watchdog = 0;
    }
    /** At the gathering point until the leader sets off. */
    private void muster(Commons.Hunt hunt) {
        if (hunt != null && hunt.phase == Commons.HuntPhase.OUT) {
            phase = Phase.OUT; ticks = 0; deadline = Math.max(deadline, hunt.deadline);
            GameplayLog.event(entity,"forage","phase=MUSTER -> OUT destination={} deadline={}",dest,deadline);return;
        }
        if (horizontal(this.home) > 4) { failures = 0; travel(this.home); } // waiting never counts as a failed route
        else if (!entity.getNavigation().isDone()) entity.getNavigation().stop();
    }
    /**
     * A hunter's way back: the levy into the common store (a granary pile of the site, or the leader's store), then the
     * rest home. A hunter without a home of its own gives everything to the commons.
     */
    private void backToStore() {
        if (load.isEmpty()) { arrivedHunt(); return; }
        var own = shelter();
        if (levy < 0) levy = own == null ? load.getCount() : Levy.portions(load.getCount(), Levy.rate(entity, true));
        if (levy > 0) {
            if (payLevy(own)) return;
            if (!hunting) return; // the levy could not be paid and the hunter was sent home
        }
        if (load.isEmpty()) { arrivedHunt(); return; }
        if (own == null) { dropLoad(); arrivedHunt(); return; }
        toHome(own, "levy_paid");
    }
    private void arrivedHunt() {
        delivered++;
        if (!load.isEmpty()) dropLoad();
        if (role != Role.CHIEF) entity.mind().felt(EmotionEvent.COMFORTED); entity.showReaction("happy", role == Role.CHIEF ? "hunt_back" : "gentle", 60);
        finish(true);
    }
    /** A standing point two cells out from the mouth, on the open side. */
    private static BlockPos outside(ShelterBlockEntity home) { return home.entrance().relative(home.facing(), 2); }
    private void arrived(ShelterBlockEntity home) {
        delivered++;
        if (!load.isEmpty()) dropLoad(); // the store is full: whatever is left stays on the floor at the burrow mouth
        entity.mind().felt(EmotionEvent.COMFORTED); entity.showReaction("happy", "gentle", 60);
        finish(true);
    }
}
