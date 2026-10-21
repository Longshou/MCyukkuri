package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
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

    public RoamingGoal(YukkuriEntity animal) {
        this.animal = animal;
        setFlags(EnumSet.of(Flag.MOVE));
    }
    public boolean exploring() { return active && expedition; }
    public int radius() { return radius; }
    public String activity() { return exploring() ? Behaviors.MARISA_EXPLORE : null; }
    public String describe() {
        return "active=" + active + " expedition=" + exploring() + " returning=" + returning
                + " normalRadius=" + TravelRange.stroll(animal) + " radius=" + radius + " roll=" + roll
                + " result=" + result + " origin=" + origin + " destination=" + destination;
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
        boolean reached = animal.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 1;
        if (expedition && !returning && (reached || animal.life().energy() < 40 || animal.life().nutrition() < 35)) {
            returning = true; destination = origin;
            GameplayLog.event(animal,"explore","return started reason={} nutrition={} energy={}",reached?"destination_reached":"needs",animal.life().nutrition(),animal.life().energy());
        } else if (reached) {
            result = expedition ? "returned" : "arrived"; active = false; animal.getNavigation().stop(); return;
        }
        if (elapsed % 20 == 0 && !animal.life().navigate(destination,1,false)) failures++;
    }
    @Override public void stop() {
        if (active) result = failures >= 4 ? "path_failed" : elapsed >= (expedition ? 1600 : 600) ? "timeout" : "interrupted";
        if (expedition) GameplayLog.event(animal,"explore","ended result={} returning={} elapsed={} destination={} distance={}",
                result,returning,elapsed,destination,destination==null?0:animal.distanceToSqr(Vec3.atBottomCenterOf(destination)));
        active = false; animal.getNavigation().stop();
    }
}
