package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.human.GarbageBinBlock;
import io.github.longshou.mcyukkuri.human.GarbageBinBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.Toxins;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One real serving from a street bin. A standing bin keeps its contents out of reach: the body shoves it from one side and it
 * falls the other way only if the shove beats its load. A fallen bin is rummaged at its open mouth.
 */
public final class GarbageSearch {
    private static final String RAM = "lunge_ram";
    private static final int RADIUS = 8, RAMS = 3;
    /** Width of the selected adult body export (12.8 units, body_geometry.json): a shove's mass is relative to it. */
    private static final double ADULT_WIDTH = .8;
    private enum Phase { WALK, SNIFF, RAM, NOSE_IN, CARRY, RETREAT }

    /** The stand to work from; {@code fall} is where a standing bin goes when shoved from {@code stand}, null at a fallen bin's mouth. */
    private record Point(GarbageBinBlockEntity bin, BlockPos stand, Direction fall) {}
    private final YukkuriEntity body;
    private final LinkedHashMap<BlockPos, Long> refused = new LinkedHashMap<>();
    private Point point;
    private Vec3 aim;
    private Phase phase;
    private long nextScan, nextTake, deadline;
    private int rams;

    public GarbageSearch(YukkuriEntity body) { this.body = body; }
    private ServerLevel level() { return (ServerLevel)body.level(); }
    private long now() { return level().getGameTime(); }
    public boolean targeting(BlockPos pos) { return point != null && point.bin().getBlockPos().equals(pos) && phase != Phase.RETREAT && phase != Phase.CARRY; }

    private boolean eligible() {
        var identity = body.status().identity();
        var brood = body.family().brood();
        if (body.life().nutrition() >= 40 || body.family().stage() == Stage.BABY
                || brood != null && !brood.cancelled && !brood.complete()
                || body.profile().dangerToYukkuri() || body.profile().houseBody() || body.isPassenger()
                || body.isInWaterOrBubble() || body.isOnFire() || body.settlement().underway()
                || body.settlement().settlementMember() || SettlementEvents.shelterBarred(body)
                || !body.habitat().mouthFreeForLoad()) return false;
        if (identity == Identity.STRAY) return true;
        var family = FamilyData.get(level());
        return identity == Identity.WILD_FAMILY && !hasKin(family, body.family().partner())
                && !hasKin(family, body.family().mother()) && !hasKin(family, body.family().father())
                && family.children(body.getUUID()).stream().noneMatch(child -> hasKin(family, child))
                && (body.habitat().shelter() == null || !body.habitat().shelter().hasFoodFor(body));
    }

    private static boolean hasKin(FamilyData family, UUID id) {
        if (id == null) return false;
        var person = family.person(id);
        return person == null || person.status != FamilyData.Status.DEAD && person.status != FamilyData.Status.REMOVED;
    }

    public boolean plan() {
        if (now() < nextScan || now() < nextTake || !eligible()) return false;
        nextScan = now() + 80;
        var here = body.blockPosition();
        var options = new ArrayList<Point>();
        var legacy = new ArrayList<BarrelBlockEntity>();
        for (int z = (here.getZ() - RADIUS) >> 4; z <= (here.getZ() + RADIUS) >> 4; z++)
            for (int x = (here.getX() - RADIUS) >> 4; x <= (here.getX() + RADIUS) >> 4; x++) {
                var chunk = level().getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var candidate : chunk.getBlockEntities().values()) {
                    if (GarbageBinBlock.legacy(candidate) && here.distSqr(candidate.getBlockPos()) <= RADIUS * RADIUS) legacy.add((BarrelBlockEntity) candidate);
                    if (!(candidate instanceof GarbageBinBlockEntity bin) || bin.isRemoved()) continue;
                    var pos = bin.getBlockPos();
                    if (here.distSqr(pos) > RADIUS * RADIUS || refused.getOrDefault(pos, 0L) > now() || serving(bin) < 0) continue;
                    for (var point : points(bin)) if (allowedByFamily(point.stand()) && body.life().dryStand(point.stand())) options.add(point);
                }
            }
        // An old garbage barrel in sight becomes a bin now (after the scan); it is a candidate from the next scan on.
        for (var barrel : legacy) GarbageBinBlock.adopt(level(), barrel);
        point = options.stream().sorted(Comparator.comparingDouble(p -> here.distSqr(p.stand()))).limit(4)
                .filter(p -> SurfaceApproach.canApproach(body, Vec3.atBottomCenterOf(p.stand()))).findFirst().orElse(null);
        if (point == null) return false;
        aim = Vec3.atBottomCenterOf(point.stand());
        phase = Phase.WALK; deadline = now() + 280; rams = 0;
        GameplayLog.event(body, "forage", "garbage approach point={} tipped={}", point.bin().getBlockPos(), point.fall() == null);
        return true;
    }

    /** A fallen bin is worked at its mouth; a standing one from any side whose opposite side is open for it to fall into. */
    private ArrayList<Point> points(GarbageBinBlockEntity bin) {
        var out = new ArrayList<Point>();
        var pos = bin.getBlockPos();
        if (bin.tipped()) out.add(new Point(bin, pos.relative(bin.getBlockState().getValue(GarbageBinBlock.FACING)), null));
        else for (var side : Direction.Plane.HORIZONTAL)
            if (GarbageBinBlock.canFall(level(), pos, side.getOpposite())) out.add(new Point(bin, pos.relative(side), side.getOpposite()));
        return out;
    }

    private boolean allowedByFamily(BlockPos target) {
        var data = FamilyData.get(level());
        for (var child : data.children(body.getUUID()))
            if (level().getEntity(child) instanceof YukkuriEntity young && !young.family().adult() && young.life().foundShelter() != null
                    && young.blockPosition().distSqr(target) > TravelRange.NURSE_RADIUS * TravelRange.NURSE_RADIUS) return false;
        return true;
    }

    /** Food first; a junk headwear only for a bare head that would wear it; then loose scraps a stray carries off. */
    private int serving(GarbageBinBlockEntity bin) {
        int junk = -1, scrap = -1;
        for (int slot = 0; slot < bin.getContainerSize(); slot++) {
            var stack = bin.getItem(slot);
            if (edible(stack)) return slot;
            if (junk < 0 && JunkHeadwear.isItem(stack) && wantsJunk()) junk = slot;
            if (scrap < 0 && scrap(stack)) scrap = slot;
        }
        return junk >= 0 ? junk : scrap;
    }
    private boolean edible(ItemStack stack) {
        return !stack.isEmpty() && !stack.is(Items.ROTTEN_FLESH) && Toxins.dose(stack) == 0 && body.life().seeksFood(stack);
    }
    private boolean wantsJunk() {
        return JunkHeadwear.fits(body) && JunkHeadwear.available(body) && JunkHeadwear.accepts(body, SocialDisposition.contact(body, null));
    }
    private static boolean scrap(ItemStack stack) { return stack.is(Items.PAPER) || stack.is(Items.STICK) || stack.is(Items.FEATHER); }

    public boolean tick() {
        if (point == null) return false;
        if (phase == Phase.CARRY || phase == Phase.RETREAT) {
            if (aim != null && now() < deadline) {
                var move = walk();
                if (move == SurfaceApproach.Result.MOVING || move == SurfaceApproach.Result.WAITING) return true;
            }
            stop(); return false;
        }
        var bin = point.bin();
        var pos = bin.getBlockPos();
        if (!level().hasChunkAt(pos) || level().getBlockEntity(pos) != bin || serving(bin) < 0) {
            retreat(Vec3.atCenterOf(pos), "emptied_or_broken"); return true;
        }
        if (body.life().personality().boldness() < 45) {
            var person = level().getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(3),
                    y -> (y instanceof AbstractVillager || y instanceof Player && !y.isSpectator()) && body.distanceToSqr(y) <= 9
                            && body.hasLineOfSight(y)).stream().findFirst().orElse(null);
            if (person != null) { retreat(person.position(), "person_near"); return true; }
        }
        if (!eligible() || !allowedByFamily(point.stand())) { stop(); return false; }
        // Someone stood the bin up, or it fell meanwhile: work from where the bin now is.
        if (bin.tipped() != (point.fall() == null)) {
            var next = points(bin).stream().filter(p -> body.life().dryStand(p.stand()))
                    .min(Comparator.comparingDouble(p -> body.distanceToSqr(Vec3.atBottomCenterOf(p.stand())))).orElse(null);
            if (next == null) { decline("bin_blocked"); return false; }
            walkTo(next, "bin_changed");
            return true;
        }
        if (phase == Phase.WALK) {
            var winner = level().getEntitiesOfClass(YukkuriEntity.class, new AABB(point.stand()).inflate(1),
                    y -> y != body && y.life().usingGarbage(pos) && y.distanceToSqr(aim) <= 1
                            && (y.distanceToSqr(aim) < body.distanceToSqr(aim) || y.distanceToSqr(aim) == body.distanceToSqr(aim) && y.getId() < body.getId()));
            if (!winner.isEmpty()) { decline("other_at_mouth"); return false; }
            var move = walk();
            // Coming down off a fallen bin it hopped onto, the last step is refused until the body lands beside the stand and
            // the route is planned again; the leg's deadline still ends a stand that stays out of reach.
            boolean landing = body.getY() > aim.y + .1 || body.position().subtract(aim).horizontalDistanceSqr() < 1;
            if (now() >= deadline || move == SurfaceApproach.Result.BLOCKED && !landing) { decline("no_route"); return false; }
            if (move != SurfaceApproach.Result.ARRIVED) return true;
            SurfaceApproach.halt(body);
            SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), Vec3.atCenterOf(pos)));
            phase = Phase.SNIFF; deadline = now() + clip(Behaviors.HOUSE_SNIFF);
            GameplayLog.event(body, "forage", "garbage sniff point={} tipped={}", pos, bin.tipped());
            return true;
        }
        if (body.distanceToSqr(aim) > .25) { retreat(Vec3.atCenterOf(pos), "lost_contact"); return true; }
        if (now() < deadline) return true;
        if (phase == Phase.NOSE_IN) return take(bin);
        if (bin.tipped()) {
            phase = Phase.NOSE_IN; deadline = now() + clip(Behaviors.HOUSE_SCAVENGE);
            GameplayLog.event(body, "forage", "garbage nose_in point={}", pos);
            return true;
        }
        if (phase == Phase.RAM && shove(bin)) return true;
        if (rams == RAMS) { decline("too_heavy"); return false; }
        phase = Phase.RAM; deadline = now() + clip(RAM); rams++;
        return true;
    }

    /**
     * Resolves the ram that just landed. The shove is the body's mass (width squared, an adult is 1) times its effort (boldness),
     * varied ±10% per ram, plus the rocking left by earlier rams; the bin resists with its own weight and its load. So a big bold
     * stray topples a half-empty bin at once, an ordinary one after rocking it, and a small or timid one gives up.
     */
    private boolean shove(GarbageBinBlockEntity bin) {
        double mass = Math.pow(body.getBbWidth() / ADULT_WIDTH, 2);
        double push = mass * (.5 + body.life().personality().boldness() / 100.) * (.9 + .2 * body.getRandom().nextDouble()) + .15 * (rams - 1);
        double resist = 1 + .05 * bin.load();
        boolean falls = push >= resist && GarbageBinBlock.canFall(level(), bin.getBlockPos(), point.fall());
        GameplayLog.event(body, "forage", "garbage ram point={} count={} push={} resist={} tipped={}", bin.getBlockPos(), rams,
                String.format(Locale.ROOT, "%.2f", push), String.format(Locale.ROOT, "%.2f", resist), falls);
        if (!falls) return false;
        GarbageBinBlock.knockOver(level(), bin.getBlockPos(), point.fall());
        body.showReaction("surprise", "", 30);
        walkTo(new Point(bin, bin.getBlockPos().relative(point.fall()), null), "knocked_over");
        return true;
    }

    private void walkTo(Point next, String why) {
        point = next;
        aim = Vec3.atBottomCenterOf(next.stand());
        phase = Phase.WALK; deadline = now() + 280;
        GameplayLog.event(body, "forage", "garbage reroute point={} to={} why={}", next.bin().getBlockPos(), next.stand().toShortString(), why);
    }

    private boolean take(GarbageBinBlockEntity bin) {
        int slot = serving(bin);
        if (slot < 0) { retreat(Vec3.atCenterOf(bin.getBlockPos()), "emptied"); return true; }
        var one = bin.getItem(slot).copyWithCount(1);
        boolean food = edible(one), wear = !food && JunkHeadwear.isItem(one);
        // Contact commit: the bin loses the piece in the same tick the mouth or the head takes it.
        boolean taken = food ? body.life().beginMeal(one, null, false)
                : wear ? JunkHeadwear.fit(body, null, SocialDisposition.contact(body, null), one)
                : body.habitat().receiveMouthLoad(one);
        if (!taken) { decline(wear ? "head_busy" : "mouth_busy"); return false; }
        bin.removeItem(slot, 1);
        nextTake = now() + 1200;
        GameplayLog.event(body, "forage", "garbage pickup item={} count=1 source={} holder={} food={} left={}",
                one.getItem(), bin.getBlockPos(), wear && body.life().custody().junk() != null ? "head" : "mouth", food, bin.load());
        if (food || wear) { stop(); return false; }
        phase = Phase.CARRY;
        deadline = now() + 100;
        aim = DefaultRandomPos.getPosAway(body, 3, 1, Vec3.atCenterOf(bin.getBlockPos()));
        return true;
    }

    private void retreat(Vec3 from, String reason) {
        remember(reason);
        body.showReaction("surprise", "", 50);
        phase = Phase.RETREAT; deadline = now() + 100;
        aim = DefaultRandomPos.getPosAway(body, 5, 2, from);
    }
    private void decline(String reason) { remember(reason); stop(); }
    private void remember(String reason) {
        var pos = point.bin().getBlockPos();
        if (refused.size() >= 16) refused.pollFirstEntry();
        refused.put(pos, now() + 2400);
        GameplayLog.event(body, "forage", "garbage gave_up point={} reason={} rams={} cooldown=2400", pos, reason, rams);
    }
    private static int clip(String action) { return Math.max(1, ClipMarkers.of(action).length()); }
    private SurfaceApproach.Result walk() { return SurfaceApproach.walkResult(body, aim, null, 1); }
    public String activity() {
        return switch (phase) {
            case SNIFF -> Behaviors.HOUSE_SNIFF;
            case RAM -> RAM;
            case NOSE_IN -> Behaviors.HOUSE_SCAVENGE;
            case CARRY -> Behaviors.SHELTER_CARRY;
            case null, default -> Behaviors.SEEK_FOOD;
        };
    }
    public void stop() {
        if (point == null) return;
        if (phase == Phase.CARRY) body.habitat().putDownMouthLoad();
        point = null; aim = null; phase = null; rams = 0;
        SurfaceApproach.stopWalking(body);
    }
}
