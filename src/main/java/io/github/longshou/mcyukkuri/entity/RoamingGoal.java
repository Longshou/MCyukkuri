package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.StreetDraw;
import io.github.longshou.mcyukkuri.settlement.VillageStrays;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.Locale;
import java.util.EnumSet;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/** Ordinary idle walking and a rare juvenile Marisa outing share real paths and interruption rules. */
public final class RoamingGoal extends Goal {
    private final YukkuriEntity animal;
    private BlockPos origin, destination;
    private BlockPos yard;
    private Choice yardChoice;
    private long nextWalk, nextExpedition;
    /** Runtime only (a reload falls back to the home anchor): the street spell's end, and when a new one may start. */
    private long streetUntil, streetBlockedUntil, streetFrom;
    private int streetStrolls, streetMisses;
    /** Why the last stroll choice found nothing: no point, refused site, anchor rule, dry/rain, no route. */
    private int[] refusals = new int[5];
    /** Where this stroll is drawn to instead of the home (the village heart in a spell, or a food holder), and how far it strolls there. */
    private BlockPos draw;
    private int radius, homeRadius, elapsed, failures, roll = -1, drawRadius;
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
                && !animal.pet().care().walk().onOwnerLead() && !ExamSession.running(animal)
                && (!animal.profile().houseBody() || animal.house().homeless())
                && !animal.life().hasUrgentNeed() && !animal.life().isSleeping() && !animal.body().weak()
                && !animal.family().active() && !animal.isCommandPlaying() && !animal.operations().active()
                && animal.reactionAction() != YukkuriAction.JOY_JUMP
                && !BehaviorContinuity.emergency(animal) && outsideFoundShelter(animal.blockPosition());
    }
    private boolean outsideFoundShelter(BlockPos pos) { return ShelterBlock.foundInterior(animal.level(), pos) == null; }
    private boolean qualifies() {
        return animal.getVariant() == YukkuriVariant.MARISA && animal.family().stage() == Stage.JUVENILE
                && !animal.family().subadult() && animal.life().personality().temperament().is(Gate.EXPLORER)
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
            var t = animal.life().personality().temperament();
            GameplayLog.event(animal,"explore","decision chance=5 roll={} accepted={} boldness={} intelligence={} nutrition={} energy={} normalRadius={} curious={}",
                    roll,expedition,t.boldness(),t.intelligence(),animal.life().nutrition(),animal.life().energy(),radius,
                    String.format(Locale.ROOT, "%.2f", t.curious()));
            if (expedition) radius *= 2;
        }
        // Parents of unweaned young stay near them; other adults with a home stroll around it (expeditions and migration excepted).
        homeRadius = animal.family().tendsUnweaned() ? TravelRange.NURSE_RADIUS : radius;
        drawToStreet(now);
        if (draw != null) homeRadius = drawRadius;
        destination = choose();
        if (destination == null && draw != null) {
            // One refused stroll is a bad draw of random points (water, fences, a rise): wait for the next. Three in a row
            // is no walkable way toward the aim: drop it for a while instead of standing pinned or walking back to the burrow.
            GameplayLog.limited(animal, "move", "street_refused", 200, "street stroll refused origin={} aim={} misses={} refused(noPoint,site,anchor,wet,route)={}",
                    origin, draw, streetMisses + 1, java.util.Arrays.toString(refusals));
            if (++streetMisses < StreetDraw.MISSES) return false;
            endSpell(now, "no_route");
            streetBlockedUntil = now + StreetDraw.BLOCKED_TICKS;
            draw = null;
            homeRadius = animal.family().tendsUnweaned() ? TravelRange.NURSE_RADIUS : radius;
            destination = choose();
        }
        if (destination != null) streetMisses = 0;
        yard = animal.life().homeStand();
        yardChoice = null;
        if (yard != null && (!insideYard(animal.position()) || !animal.pet().conscience().binds(HouseRule.NO_LEAVING_YARD))) yard = null;
        if (yard != null && destination != null && !insideYard(Vec3.atBottomCenterOf(destination))) {
            chooseYard();
            if (yardChoice == Choice.WAIT) return false;
            if (yardChoice == Choice.HOLD) {
                var center = Vec3.atBottomCenterOf(yard);
                var direction = Vec3.atBottomCenterOf(destination).subtract(center).multiply(1, 0, 1).normalize();
                var edge = BlockPos.containing(center.add(direction.scale(7))).atY(destination.getY());
                if (animal.getRandom().nextInt(4) == 0 && animal.life().dryStand(edge) && animal.life().reachable(edge, false)
                        && animal.family().allowsIdleWalk(Vec3.atBottomCenterOf(edge)) && outsideFoundShelter(edge)) destination = edge;
                else { hesitate(); return false; }
            }
        }
        result = destination == null ? "no_reachable_destination" : "planned";
        if (expedition && destination == null) GameplayLog.event(animal,"explore","start refused reason={} radius={}",result,radius);
        if (destination != null && draw != null && streetUntil > 0) streetStrolls++;
        return destination != null;
    }
    /** Whether a street spell is running (read by the food search, which pulls a starving stray toward the heart). */
    public boolean onStreetSpell() { return streetUntil > animal.level().getGameTime(); }
    private void endSpell(long now, String reason) {
        if (streetUntil == 0) return;
        streetUntil = 0;
        GameplayLog.event(animal, "move", "street spell end reason={} ticks={} strolls={} nutrition={}", reason, now - streetFrom,
                streetStrolls, animal.life().nutrition());
    }
    /**
     * A village stray in daylight is drawn to the street at every stroll start: a food holder it would react to (chance by hunger and
     * nerve) first, else the village bell while a spell lasts. A spell starts with chance {@link StreetDraw#chance} and ends at dusk,
     * in rain or cold, on a scold, or when the body stops being a free rooted stray (adopted, picked up, hurt, with young).
     */
    private void drawToStreet(long now) {
        draw = null;
        double daylight = StreetDraw.daylight(animal.level().getDayTime());
        if (!VillageStrays.walksStreet(animal) || daylight == 0) {
            endSpell(now, daylight == 0 ? "dusk" : "unfit");
            return;
        }
        if (streetUntil > 0 && now >= streetUntil) endSpell(now, "timeout");
        var life = animal.life();
        var traits = life.personality();
        float nutrition = life.nutrition();
        if (now < streetBlockedUntil) return;
        var holder = VillageStrays.holder(animal, life.foodNoticeRange());
        if (holder != null && animal.getRandom().nextDouble() < StreetDraw.holderChance(nutrition, traits.boldness(), daylight)) {
            draw = holder.blockPosition();
            drawRadius = StreetDraw.HOLDER_RADIUS;
            GameplayLog.limited(animal, "move", "street_holder", 600, "drawn to a food holder player={} distance={} nutrition={}",
                    holder.getUUID(), Math.round(animal.distanceTo(holder)), nutrition);
            return;
        }
        boolean starting = streetUntil == 0;
        double p = StreetDraw.chance(nutrition, traits.boldness(), traits.friendliness(), daylight);
        if (starting && animal.getRandom().nextDouble() >= p) return;
        var heart = VillageStrays.heart(animal);
        if (heart == null || life.avoidsField(heart)) return;
        if (starting) {
            int ticks = StreetDraw.ticks(nutrition);
            streetUntil = now + ticks;
            streetFrom = now;
            streetStrolls = 0;
            GameplayLog.event(animal, "move", "street spell start aim={} p={} ticks={} nutrition={} boldness={} friendliness={}",
                    heart, Math.round(p * 100) / 100.0, ticks, nutrition, traits.boldness(), traits.friendliness());
        }
        draw = heart.atY(animal.getBlockY());
        drawRadius = radius;
    }
    /**
     * An attached individual (with probability attachment/100) strolls near its mate, else the most trusted friend it sees
     * within 16 (a faltering friend ranks before trust), so companions stay within sight of each other instead of drifting apart.
     */
    private BlockPos companionSpot() {
        var p = animal.life().personality();
        if (expedition || animal.getRandom().nextInt(100) >= p.attachment()) return null;
        if (animal.family().partner() != null && animal.level() instanceof ServerLevel level
                && level.getEntity(animal.family().partner()) instanceof YukkuriEntity mate && mate.isAlive()
                && animal.life().canRecognize(mate) && animal.distanceToSqr(mate) <= 576) return mate.blockPosition();
        return animal.level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(16), peer -> peer != animal
                && peer.isAlive() && animal.hasLineOfSight(peer) && animal.life().canRecognize(peer) && peer.life().canRecognize(animal)
                && p.trust(peer.getUUID()) > 0).stream()
                .max(Comparator.comparing((YukkuriEntity peer) -> peer.life().faltering())
                        .thenComparingInt(peer -> p.trust(peer.getUUID())))
                .map(YukkuriEntity::blockPosition).orElse(null);
    }
    private BlockPos choose() {
        boolean baby = animal.family().stage() == Stage.BABY;
        var home = baby ? animal.life().homeStand() : null;
        var shelter = baby ? animal.habitat().shelter() : null;
        boolean preferInside = baby && home != null && animal.getRandom().nextInt(5) != 0;
        BlockPos anchor = baby || expedition || animal.settlement().underway() ? null : draw != null ? draw : animal.life().homeStand();
        // Already outside the home range: head back toward the home instead of choosing another point out there.
        boolean outside = anchor != null && origin.distSqr(anchor) > homeRadius * homeRadius;
        int paths = 0;
        BlockPos companion = companionSpot();
        var allowed = animal.life().quietSites(origin, radius);
        refusals = new int[5];
        for (int attempt = 0; attempt < 16; attempt++) {
            Vec3 point = outside ? LandRandomPos.getPosTowards(animal, radius, 3, Vec3.atBottomCenterOf(anchor))
                    : companion == null ? LandRandomPos.getPos(animal, radius, 3)
                    : LandRandomPos.getPosTowards(animal, radius, 3, Vec3.atBottomCenterOf(companion));
            if (point == null) { refusals[0]++; continue; }
            BlockPos pos = BlockPos.containing(point);
            if (!allowed.test(pos) || origin.distSqr(pos) > radius * radius || !outsideFoundShelter(pos) || !TravelRange.babyDestination(animal,pos)) { refusals[1]++; continue; }
            if (draw != null && animal.life().avoidsField(pos)) { refusals[1]++; continue; }
            if (anchor != null && (outside ? pos.distSqr(anchor) >= origin.distSqr(anchor) : pos.distSqr(anchor) > homeRadius * homeRadius)) { refusals[2]++; continue; }
            if (expedition && origin.distSqr(pos) <= radius * radius / 4) continue;
            if (!expedition && !animal.family().allowsIdleWalk(point)) continue;
            if (companion != null && companion.distSqr(pos) > 64) continue;
            if (preferInside && !(shelter != null ? shelter.contains(pos) : pos.distSqr(home) <= 4 && animal.life().covered(pos))) continue;
            if (Reactions.Weather.rainAt(animal,pos) || !animal.life().dryStand(pos)) { refusals[3]++; continue; }
            if (animal.level() instanceof ServerLevel level && RemainsRot.source(level,Vec3.atBottomCenterOf(pos)) != null) continue;
            if (++paths > 4) break;
            if (!animal.life().reachableAndBack(pos)) { refusals[4]++; continue; }
            return pos.immutable();
        }
        return null;
    }
    @Override public void start() {
        active = true; result = "outbound";
        progressPosition = animal.position(); detour = null; stuckTicks = detourTicks = 0;
        if (expedition) GameplayLog.event(animal,"explore","started origin={} destination={} radius={}",origin,destination,radius);
        if (!animal.life().navigate(destination,1,false)) failures++;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    private boolean insideYard(Vec3 point) {
        return point.subtract(Vec3.atBottomCenterOf(yard)).horizontalDistanceSqr() <= 64;
    }
    private void chooseYard() {
        double pressure = Math.max(animal.dailyLife().boredom() / 100.0, animal.life().personality().temperament().curious());
        yardChoice = animal.pet().conscience().tempted(HouseRule.NO_LEAVING_YARD, pressure, 1);
    }
    private void hesitate() {
        PetScenes.act(animal, YukkuriAction.LOOK_AROUND, "teary", "", 40);
        animal.getLookControl().setLookAt(yard.getX() + .5, animal.getEyeY(), yard.getZ() + .5);
    }
    @Override public boolean canContinueToUse() {
        if (!active || !available() || !outsideFoundShelter(destination) || detour != null && !outsideFoundShelter(detour)
                || elapsed >= (expedition ? 1600 : 600) || failures >= 4) return false;
        if (!expedition && !animal.family().allowsIdleWalk(Vec3.atBottomCenterOf(destination))) return false;
        return expedition || !animal.getNavigation().isDone();
    }
    @Override public void tick() {
        elapsed++;
        var path = animal.getNavigation().getPath();
        if (path != null && !path.isDone()
                && !animal.life().quietSite(BlockPos.containing(path.getNextEntityPos(animal)))) {
            stop();
            return;
        }
        if (yard != null) {
            if (yardChoice == null && path != null && !path.isDone() && !insideYard(path.getNextEntityPos(animal))) chooseYard();
            if ((yardChoice == Choice.HOLD || yardChoice == Choice.WAIT) && path != null && !path.isDone() && !insideYard(path.getNextEntityPos(animal))) {
                stop();
                if (yardChoice == Choice.HOLD) hesitate();
                return;
            }
            if (!insideYard(animal.position())) {
                if (yardChoice == Choice.BREAK) animal.pet().conscience().breach(HouseRule.NO_LEAVING_YARD);
                yard = null;
                yardChoice = null;
            }
        }
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
            if (!outsideFoundShelter(origin)) { stop(); return; }
            returning = true; destination = origin;
            GameplayLog.event(animal,"explore","return started reason={} nutrition={} energy={}",reached?"destination_reached":"needs",animal.life().nutrition(),animal.life().energy());
        } else if (reached) {
            if (yardChoice == Choice.HOLD) hesitate();
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
            if (!outsideFoundShelter(pos) || !TravelRange.babyDestination(animal,pos)
                    || !Reactions.Weather.allowsStep(animal,pos) || !animal.life().dryStand(pos)) continue;
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
