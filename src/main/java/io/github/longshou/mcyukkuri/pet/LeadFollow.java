package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.geom.LeadPace;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The body's side of a player's lead (docs/features/care-and-life.md). Vanilla keeps the tether (close-range follow, elastic past 6
 * blocks, break past 10); this class only decides the gait of that follow and the visible answers, in two kinds:
 * <ul>
 * <li>an owner's pet that trails beyond its comfort hops to catch up (the hop-run request; a frail or baked body keeps its walk), shows
 * a hurried face when it starts, an alarmed one when the rope goes taut, and relief once it is close again;</li>
 * <li>an unowned individual strains against the lead in bouts: it turns away and walks the lead's slack out, then digs in. Each bout
 * costs energy, so the bouts get shorter, shorter-reaching and rarer, and end for good at the tired line (one spent line), after which
 * the lead only tows it (a tired body does not follow, the vanilla elastic pulls it past 6 blocks).</li>
 * </ul>
 * Every state ends: the hop when it has caught up, a bout by its tick budget, the taut distance or an interruption, the whole thing when
 * the lead comes off. Bodies and positions are only ever driven through the navigation and shared faces.
 */
final class LeadFollow {
    private static final int FIRST_BOUT_DELAY = 35, HURRY_GAP = 100, ALARM_GAP = 80, HURRY_HOLD = 20;
    /** Past the elastic pull: at this distance the body is being dragged, not leaning on the lead. */
    private static final double BOUT_ABORT_DISTANCE = LeadPace.ELASTIC + .6;
    private static final double SUSTAINED_PULL_DISTANCE = LeadPace.ELASTIC + .5;

    private final YukkuriEntity animal;
    private UUID holder;
    private boolean trailing, hopping, hurried, alarmed, shown, petAnswered;
    private long hurryAt = Long.MIN_VALUE / 2, alarmAt = Long.MIN_VALUE / 2;
    private long nextBout, boutEnd;
    private Vec3 stand;
    private boolean struggled, spentSaid, armed;
    private int ownLine = -1;
    private double boutFrom, boutNow;
    private Vec3 lastAt;
    private int stillTicks;

    LeadFollow(YukkuriEntity animal) { this.animal = animal; }

    /** The request of the lead's close-range follow: the walk, or the hop-run while trailing. */
    double speed() { return LeadPace.request(hopping); }

    /** Per tick, whoever holds the lead: a player's lead drives it, anything else (or none) ends every state. */
    void tick(Player player) {
        if (player == null || !player.getUUID().equals(holder)) {
            if (boutEnd != 0) endBout("released"); // the lead came off mid-bout: it is paid for and logged like any other
            reset();
            holder = player == null ? null : player.getUUID();
            if (player == null) return;
        }
        if (animal.status().identity().free()) strain(player);
        else if (animal.pet().ownedBy(player.getUUID())) follow(player);
    }

    private void reset() {
        trailing = hopping = hurried = alarmed = shown = petAnswered = false;
        lastAt = null; stillTicks = 0;
        boutEnd = 0; nextBout = 0; stand = null; struggled = spentSaid = armed = false;
    }

    /** The snap-on answer to a new lead was shown: the bouts begin after it, never before. */
    void leashAnswered() {
        armed = true;
        nextBout = animal.level().getGameTime() + FIRST_BOUT_DELAY;
    }

    /** What vanilla's close-range follow would already do: only while it is free to follow, never over a need, a meal, sleep, a lock or a panic. */
    private boolean free() {
        return !animal.life().hasUrgentNeed() && !animal.pet().busy() && animal.life().mealTicks() == 0 && !animal.isCommandPlaying()
                && !animal.operations().active() && !animal.isPanicking() && !animal.life().isSleeping()
                && animal.onGround() && !animal.isPassenger();
    }

    /**
     * A leash beat may cut only the body's own previous leash line (hurry, then alarm, then relief; the struggle, then the spent cry,
     * then the release answer), never another line, and every leash event ranks 2, so no hurt or distress line is ever refused or cut by it.
     */
    void clearOwnLine() {
        if (ownLine == animal.speechSequence() && !animal.quiet()) animal.interruptSpeech();
    }
    void noteOwnLine(int before) { if (animal.speechSequence() != before) ownLine = animal.speechSequence(); }

    // ---- an owner's pet: hop to catch up, hurry, alarm, relief ----

    private void follow(Player owner) {
        var personality = animal.life().personality();
        double distance = animal.distanceTo(owner);
        double comfort = LeadPace.comfort(BodyAbility.growthPace(animal), personality.attachment(), personality.boldness());
        // The exit that never depends on where vanilla's follow happens to stop: a body that has stood still has caught up as far as the lead lets it.
        var at = animal.position();
        stillTicks = lastAt != null && at.distanceToSqr(lastAt) < 1e-4 ? stillTicks + 1 : 0;
        lastAt = at;
        boolean settled = LeadPace.settled(stillTicks);
        trailing = free() && !settled && LeadPace.trailing(trailing, distance, comfort);
        hopping = trailing && !BodyAbility.cannotSpring(animal) && animal.childhood().canJump();
        long now = animal.level().getGameTime();
        if (!petAnswered) {
            petAnswered = true;
            if (free() && distance <= comfort) {
                boolean scaredOn = scared(owner);
                boolean warm = !scaredOn && (personality.attachment() >= 55 || personality.friendliness() >= 60);
                String face = scaredOn ? "surprise" : warm ? "happy" : "closed_smile";
                YukkuriAction clip = warm && !BodyAbility.cannotSpring(animal) && animal.childhood().canJump() ? YukkuriAction.JOY_HOP : null;
                beat("lead_pet_on", clip, face, "lead_pet_on", FaceTier.Tier.SHORT, distance, comfort);
            }
        }
        if (!trailing) {
            // Caught up after a hurry or a pull: the relief is asked again next tick if a held face refused it. An episode that ends with the
            // body standing still far out is a blocked body, not a relieved one: it is logged as stuck and gets no relief. Interrupted ends are silent.
            if (shown && free()) {
                if (distance <= LeadPace.CAUGHT_UP) { if (!relief(owner, distance)) return; }
                else if (settled) GameplayLog.event(animal, "lead", "hop ended reason=stuck distance={} still={}", String.format("%.2f", distance), stillTicks);
            }
            shown = hurried = alarmed = false;
            return;
        }
        boolean scared = scared(owner);
        // A yank that finds it already past the taut point is one beat, the alarm; the hurry is never painted over in the same tick.
        double taut = LeadPace.tautAt(personality.boldness(), comfort, LeadPace.ELASTIC);
        if (!hurried && distance < taut && now - hurryAt >= HURRY_GAP) {
            String face = !hopping ? "teary_pant" : scared ? "afraid" : "surprise";
            if (beat("lead_hurry", null, face, "lead_hurry", FaceTier.Tier.MEDIUM, distance, comfort)) {
                hurried = shown = true;
                hurryAt = now;
            }
        }
        boolean sustainedPull = alarmed && distance >= SUSTAINED_PULL_DISTANCE;
        if ((!alarmed || sustainedPull) && distance >= taut && now - alarmAt >= ALARM_GAP && (!hurried || now - hurryAt >= HURRY_HOLD)) {
            String face = !hopping ? "teary_pant" : scared ? "horrified" : "afraid";
            if (beat("lead_alarm", null, face, "lead_alarm", FaceTier.Tier.MEDIUM, distance, comfort)) {
                alarmed = shown = hurried = true;
                alarmAt = now;
            }
        }
    }

    /** It is close again after hurrying or being pulled: a breath of relief, warmer in a clingy or frightened one. */
    private boolean relief(Player owner, double distance) {
        var personality = animal.life().personality();
        String face = scared(owner) || personality.attachment() >= 50 ? "teary_smile" : "happy";
        return beat("lead_relief", null, face, "lead_relief", FaceTier.Tier.MEDIUM, distance, 0);
    }

    private boolean scared(Player holder) {
        var personality = animal.life().personality();
        return personality.temperament().is(Gate.TIMID) || personality.fear(holder.getUUID()) >= 50;
    }

    private boolean beat(String name, YukkuriAction clip, String face, String line, FaceTier.Tier tier, double distance, double comfort) {
        int said = animal.speechSequence();
        clearOwnLine();
        boolean ok = clip == null ? animal.showReaction(face, line, tier)
                : animal.showReaction(clip, face, line, clip.durationTicks(), tier);
        if (!ok) return false;
        noteOwnLine(said);
        animal.beat(name, face, line);
        GameplayLog.event(animal, "lead", "beat={} face={} line={} spoken={} distance={} comfort={} energy={} hop={}", name, face, line, animal.speechSequence() != said,
                String.format("%.2f", distance), String.format("%.2f", comfort), animal.life().energy(), hopping);
        return true;
    }

    // ---- an unowned individual: strain in bouts until the energy runs out ----

    private void strain(Player holder) {
        long now = animal.level().getGameTime();
        if (BodyAbility.frail(animal)) {
            if (boutEnd != 0) endBout("exhausted");
            if (struggled && !spentSaid && beat("lead_spent", YukkuriAction.DROOP, "teary_pant", "lead_spent", FaceTier.Tier.MEDIUM, animal.distanceTo(holder), 0))
                spentSaid = true;
            return;
        }
        if (!armed) return;
        double distance = animal.distanceTo(holder);
        boutNow = distance;
        if (boutEnd != 0) {
            if (!free() || distance > BOUT_ABORT_DISTANCE) endBout(!free() ? "interrupted" : "taut");
            else if (now >= boutEnd) endBout("done");
            return;
        }
        if (now < nextBout || !free() || distance > BOUT_ABORT_DISTANCE) return;
        beginBout(holder, now, distance);
    }

    private void beginBout(Player holder, long now, double distance) {
        var personality = animal.life().personality();
        var traits = personality.temperament();
        boolean scared = scared(holder);
        boolean bold = !scared && personality.friendliness() < 60 && traits.is(Gate.BOLDISH);
        double reserve = LeadPace.reserve(animal.life().energy(), BodyAbility.TIRED_ENERGY);
        boolean winded = struggled && reserve <= .45;
        String face = winded ? (bold ? "squeezed_frown" : "teary_pant")
                : scared ? "horrified" : bold ? "fierce" : "teary";
        String line = winded ? "lead_struggle_tired" : "lead_struggle";
        double resist = LeadPace.resist(personality.friendliness());
        int ticks = LeadPace.boutTicks(reserve);
        int said = animal.speechSequence();
        clearOwnLine();
        if (!animal.showReaction(YukkuriAction.STRUGGLE, face, line, ticks, FaceTier.Tier.MEDIUM)) return;
        noteOwnLine(said);
        animal.beat("lead_struggle", face, line);
        double elastic = LeadPace.ELASTIC;
        // Always a visible pull outward from where it stands, still short of the elastic start.
        double reach = Math.min(Math.max(LeadPace.boutReach(reserve, elastic), distance + .6), elastic - .3);
        var away = animal.position().subtract(holder.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = Vec3.directionFromRotation(0, animal.getYRot()).multiply(1, 0, 1);
        stand = SurfaceApproach.floor(animal, holder.position().add(away.normalize().scale(reach)));
        boutEnd = now + ticks;
        boutFrom = boutNow = distance;
        struggled = true;
        GameplayLog.event(animal, "lead", "beat=lead_struggle face={} line={} spoken={} distance={} reach={} ticks={} energy={} resist={} walk_out={}",
                face, line, animal.speechSequence() != said, String.format("%.2f", distance), String.format("%.2f", reach), ticks, animal.life().energy(),
                String.format("%.2f", resist), stand != null);
    }

    private void endBout(String why) {
        SurfaceApproach.stopWalking(animal);
        long now = animal.level().getGameTime();
        var personality = animal.life().personality();
        // The effort is paid when it ends, so a bout that began with strength is never cut off by its own cost.
        animal.life().spendEnergy(LeadPace.boutCost(LeadPace.resist(personality.friendliness())));
        double reserve = LeadPace.reserve(animal.life().energy(), BodyAbility.TIRED_ENERGY);
        int gap = LeadPace.boutGap(reserve, LeadPace.resist(personality.friendliness()));
        boutEnd = 0; stand = null;
        nextBout = now + gap;
        GameplayLog.event(animal, "lead", "struggle ended reason={} energy={} next_in={} pulled={}", why, animal.life().energy(), gap,
                String.format("%.2f", boutNow - boutFrom));
    }

    /**
     * A bout takes the place of vanilla's close-range follow: the body walks to its stand away from the holder (turning round first,
     * as every walk does), then stands and digs in. False when no bout runs, and vanilla's follow goes on.
     */
    boolean pull() {
        if (boutEnd == 0) return false;
        if (stand != null) {
            var result = SurfaceApproach.walkResult(animal, stand, null, LeadPace.WALK_REQUEST);
            if (result != SurfaceApproach.Result.MOVING && result != SurfaceApproach.Result.WAITING) stand = null;
        }
        if (stand == null) SurfaceApproach.stopWalking(animal);
        return true;
    }
}
