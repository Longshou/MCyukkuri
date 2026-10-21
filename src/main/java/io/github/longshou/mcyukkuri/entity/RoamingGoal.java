package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/** Ordinary idle walking and a rare juvenile Marisa outing share real paths and interruption rules. */
public final class RoamingGoal extends Goal {
    private final YukkuriEntity animal;
    private BlockPos origin, destination;
    private long nextWalk, nextExpedition;
    private int radius, elapsed, failures, roll = -1;
    private boolean expedition, active, returning;
    private String result = "none";
    private Vec3 progressPosition;
    private BlockPos detour;
    private int stuckTicks, detourTicks;

    public RoamingGoal(YukkuriEntity animal) {
        this.animal = animal;
        setFlags(EnumSet.of(Flag.MOVE));
    }
    public boolean exploring() { return active && expedition; }
    public int radius() { return radius; }
    public String activity() { return active ? exploring() ? Behaviors.MARISA_EXPLORE : Behaviors.ROAM : null; }
    public String describe() {
        return "active=" + active + " expedition=" + exploring() + " returning=" + returning
                + " normalRadius=" + TravelRange.stroll(animal) + " radius=" + radius + " roll=" + roll
                + " result=" + result + " origin=" + origin + " destination=" + destination + " detour=" + detour + " stuckTicks=" + stuckTicks;
    }
    private boolean available() {
        return animal.isAlive() && !animal.isNoAi() && !animal.isPassenger() && animal.childhood().canLearn()
                && (!animal.profile().houseBody() || animal.house().homeless())
                && !animal.life().hasUrgentNeed() && !animal.life().isSleeping()
                && !animal.family().active() && !animal.isCommandPlaying() && !animal.operations().active()
                && !BehaviorContinuity.emergency(animal);
    }
    private boolean qualifies() {
        var p = animal.life().personality();
        return animal.getVariant() == YukkuriVariant.MARISA && animal.family().stage() == Stage.JUVENILE
                && !animal.family().subadult() && p.boldness() >= 65 && p.intelligence() >= 40
                && animal.life().energy() >= 60 && animal.life().nutrition() >= 50 && !animal.level().isRaining();
    }
    @Override public boolean canUse() {
        long now = animal.level().getGameTime();
        if (now < nextWalk || !available()) return false;
        nextWalk = now + 80 + animal.getRandom().nextInt(80);
        origin = animal.blockPosition().immutable();
        radius = TravelRange.stroll(animal);
        expedition = false; returning = false; failures = elapsed = 0;
        if (now >= nextExpedition && qualifies()) {
            nextExpedition = now + 1200;
            roll = animal.getRandom().nextInt(100);
            expedition = roll < 5;
            GameplayLog.event(animal,"explore","decision chance=5 roll={} accepted={} boldness={} intelligence={} nutrition={} energy={} normalRadius={}",
                    roll,expedition,animal.life().personality().boldness(),animal.life().personality().intelligence(),
                    animal.life().nutrition(),animal.life().energy(),radius);
            if (expedition) radius *= 2;
        }
        destination = choose();
        result = destination == null ? "no_reachable_destination" : "planned";
        if (expedition && destination == null) GameplayLog.event(animal,"explore","start refused reason={} radius={}",result,radius);
        return destination != null;
    }
    private BlockPos choose() {
        BlockPos fallback = null;
        boolean baby = animal.family().stage() == Stage.BABY;
        var home = baby ? animal.life().homeStand() : null;
        var shelter = baby ? animal.habitat().shelter() : null;
        boolean preferInside = baby && home != null && animal.getRandom().nextInt(5) != 0;
        int paths = 0;
        for (int attempt = 0; attempt < 16; attempt++) {
            Vec3 point = LandRandomPos.getPos(animal, radius, 3);
            if (point == null) continue;
            BlockPos pos = BlockPos.containing(point);
            if (origin.distSqr(pos) > radius * radius || !TravelRange.babyDestination(animal,pos)) continue;
            if (expedition && origin.distSqr(pos) <= radius * radius / 4) continue;
            if (!expedition && !animal.family().allowsIdleWalk(point)) continue;
            if (preferInside && !(shelter != null ? shelter.contains(pos) : pos.distSqr(home) <= 4 && animal.life().covered(pos))) continue;
            if (Reactions.Weather.rainAt(animal,pos) || !animal.life().dryStand(pos)) continue;
            if (++paths > 4) break;
            if (!animal.life().reachable(pos,false)) continue;
            if (fallback == null) fallback = pos.immutable();
            if (!animal.profile().aquatic() || animal.aquaticLife().shore().coastal(pos)) return pos.immutable();
        }
        return fallback;
    }
    @Override public void start() {
        active = true; result = "outbound";
        progressPosition = animal.position(); detour = null; stuckTicks = detourTicks = 0;
        if (expedition) GameplayLog.event(animal,"explore","started origin={} destination={} radius={}",origin,destination,radius);
        if (!animal.life().navigate(destination,1,false)) failures++;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public boolean canContinueToUse() {
        if (!active || !available() || elapsed >= (expedition ? 1600 : 600) || failures >= 4) return false;
        return expedition || !animal.getNavigation().isDone();
    }
    @Override public void tick() {
        elapsed++;
        if (elapsed % 20 == 0) {
            stuckTicks = animal.position().distanceToSqr(progressPosition) < .04 ? stuckTicks + 20 : 0;
            progressPosition = animal.position();
            if (stuckTicks >= 20 && detour == null) routeAroundPeer();
        }
        if (detour != null) {
            if (++detourTicks > 100 || animal.distanceToSqr(Vec3.atBottomCenterOf(detour)) < .5) { detour = null; stuckTicks = 0; }
            else { if (elapsed % 10 == 0) animal.life().navigate(detour,1,false); return; }
        }
        boolean reached = animal.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 1;
        if (expedition && !returning && (reached || animal.life().energy() < 40 || animal.life().nutrition() < 35)) {
            returning = true; destination = origin;
            GameplayLog.event(animal,"explore","return started reason={} nutrition={} energy={}",reached?"destination_reached":"needs",animal.life().nutrition(),animal.life().energy());
        } else if (reached) {
            result = expedition ? "returned" : "arrived"; active = false; animal.getNavigation().stop(); return;
        }
        if (elapsed % 20 == 0 && !animal.life().navigate(destination,1,false)) failures++;
        if (stuckTicks >= 100) { result = "blocked"; active = false; animal.getNavigation().stop(); }
    }
    private void routeAroundPeer() {
        var peers = animal.level().getEntitiesOfClass(YukkuriEntity.class,animal.getBoundingBox().inflate(.65,.1,.65),
                p -> p != animal && p.isAlive() && !p.isPassenger());
        if (peers.isEmpty()) return;
        var peer = peers.stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElseThrow();
        // Both actors turn to their own right; opposing traffic separates instead of choosing the same world-side cell.
        Vec3 forward = Vec3.atBottomCenterOf(destination).subtract(animal.position()).multiply(1,0,1).normalize();
        Vec3 side = new Vec3(-forward.z,0,forward.x);
        for (int sign : new int[]{1,-1}) {
            var point = animal.position().add(side.scale(sign * (animal.getBbWidth()+peer.getBbWidth()+1.2))).subtract(forward.scale(.6));
            var pos = BlockPos.containing(point);
            if (!TravelRange.babyDestination(animal,pos) || !Reactions.Weather.allowsStep(animal,pos) || !animal.life().dryStand(pos)) continue;
            if (!animal.level().getEntitiesOfClass(YukkuriEntity.class,animal.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(animal.position())),p -> p!=animal).isEmpty()) continue;
            if (animal.life().navigate(pos,1,false)) {
                detour = pos; detourTicks = 0;
                GameplayLog.limited(animal,"explore","peer_detour",200,"walking detour peer={} destination={} detour={} stuckTicks={}",peer.getUUID(),destination,detour,stuckTicks);
                return;
            }
        }
    }
    @Override public void stop() {
        if (active) result = failures >= 4 ? "path_failed" : elapsed >= (expedition ? 1600 : 600) ? "timeout" : "interrupted";
        if (expedition) GameplayLog.event(animal,"explore","ended result={} returning={} elapsed={} destination={} distance={}",
                result,returning,elapsed,destination,destination==null?0:animal.distanceToSqr(Vec3.atBottomCenterOf(destination)));
        active = false; animal.getNavigation().stop();
    }
}
