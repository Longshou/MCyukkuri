package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SearchBudget;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Perception and reaction arbitration for ordinary (prey) individuals.
 *
 * <p>Why this exists: before, a yukkuri only reacted to a predator once that predator had already started a hunt
 * (PredatorLife.alertPrey), and weather only touched the wetness number. Nothing looked at the world. Here every
 * eligible individual perceives (a visible predator, a warning from kin, an attacker, rain/storm/snow on its own
 * block) and a single arbiter decides what to do, by the order the goals are registered in
 * {@code YukkuriEntity.registerGoals}:
 * <pre>
 *   0 water / operation / house exit            (survival, owner contact)
 *   1 panic flee, shell, THREAT (this class)    (emergency)
 *   2 WEATHER (this class)                      (needs: get out of the rain)
 *   3 life (food, sleep, toilet, temperature) ... 4-6 family, settlement, social ... 7 stroll
 * </pre>
 * Threat outranks weather; both outrank every social and idle goal; real emergencies of the body (starving, a full
 * bowel) outrank the weather goal because it yields to them.
 */
public final class Reactions {
    private final YukkuriEntity entity;
    private final Threat threat;
    private final Weather weather;

    Reactions(YukkuriEntity entity) { this.entity = entity; threat = new Threat(entity); weather = new Weather(entity); }

    public Threat threat() { return threat; }
    public Weather weather() { return weather; }
    public boolean active() { return threat.stage != Threat.Stage.NONE || weather.stage != Weather.Stage.NONE; }
    /** Behavior id for the shared presentation chain (see EcologyPresentation). */
    public String activity() { return threat.stage != Threat.Stage.NONE ? threat.activity() : weather.activity(); }
    public String describe() {
        return threat.stage != Threat.Stage.NONE ? threat.describe() : weather.stage != Weather.Stage.NONE ? weather.describe() : "none";
    }
    void scan() { threat.scan(); weather.scan(); }
    void stop() { threat.stage = Threat.Stage.NONE; weather.stage = Weather.Stage.NONE; }

    /** Someone hurt this individual: kin who can perceive it take fright too. */
    void hurtBy(DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == entity) return;
        threat.warnKin(attacker);
    }

    // ---- shared predicates -------------------------------------------------------------------------------------

    /** An ordinary free-living or kept individual: not a predator, not a house yukkuri, not an aquatic crew member. */
    static boolean prey(YukkuriEntity y) {
        return y.isAlive() && !y.level().isClientSide && !y.getVariant().isPredator() && !y.getVariant().isHouse()
                && !y.isNoAi() && !y.isPassenger();
    }
    /** Whether this individual may be steered right now (not mid-contact, birth, rescue, water). */
    static boolean free(YukkuriEntity y) {
        return prey(y) && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy()
                && !y.isInWaterOrBubble() && !y.isOnFire() && !y.waterDistress().active()
                && !y.family().laboring() && !y.family().birthDuty() && !y.family().braidRescueActive()
                && !y.family().awaitingLanding() && y.life().mealTicks() == 0;
    }
    /** Fright reaches an individual busy eating or asleep too; only contact, command and water override it. */
    static boolean alarmable(YukkuriEntity y) {
        return prey(y) && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy() && !y.isInWaterOrBubble();
    }
    static boolean kin(YukkuriEntity a, YukkuriEntity b) {
        if (a.family().caresFor(b) || b.family().caresFor(a)) return true;
        if (b.getUUID().equals(a.family().partner()) || a.getUUID().equals(b.family().partner())) return true;
        var group = a.settlement().groupId();
        if (group != null && group.equals(b.settlement().groupId())) return true;
        var level = (ServerLevel) a.level();
        return FamilyData.get(level).socialKin(a.getUUID(), b.getUUID())
                || YukkuriWorldData.get(level).affinity(a.getUUID(), b.getUUID()) >= 10;
    }

    // ---- threat -----------------------------------------------------------------------------------------------

    public static final class Threat {
        public enum Stage { NONE, WATCH, RETREAT, HIDE }
        private final YukkuriEntity y;
        private Stage stage = Stage.NONE;
        private YukkuriEntity seen;
        private LivingEntity heard;
        private long heardUntil, alarmUntil, noticeUntil, cooldownUntil, lostSince;
        private int ticks;

        Threat(YukkuriEntity y) { this.y = y; }

        public Stage stage() { return stage; }
        /** The predator (or attacker) that currently matters, or null. */
        public LivingEntity source() {
            if (seen != null && seen.isAlive()) return seen;
            long now = y.level().getGameTime();
            return heard != null && heard.isAlive() && now < heardUntil && heard.level() == y.level() && heard.distanceToSqr(y) < 576 ? heard : null;
        }
        String activity() { return switch (stage) { case WATCH -> "threat_watch"; case RETREAT -> "threat_retreat"; case HIDE -> "threat_hide"; default -> "idle"; }; }
        String describe() {
            var s = source();
            return "threat:" + stage.name().toLowerCase(java.util.Locale.ROOT) + (s == null ? "" : " from #" + s.getId() + " d=" + Math.round(s.distanceTo(y)));
        }

        private boolean menacing(YukkuriEntity p) {
            if (p == y || !p.isAlive() || !p.getVariant().isPredator() || p.isPassenger()) return false;
            var owner = y.pet().owner();
            if (owner != null && owner.equals(p.pet().owner())) return false;
            if (p.predator().obedient() && HumanCommerce.protectedAnimal(y)) return false;   // an educated predator honours a protected pet
            return p.distanceToSqr(y) < 36 || y.hasLineOfSight(p);
        }
        private double alarmRange() {
            double range = 9 - y.life().personality().trait(Trait.BOLDNESS) / 20.0;
            return y.family().adult() ? range : range + 2;
        }

        /** Every few ticks: look around, then alarm or notice. */
        void scan() {
            if (!prey(y)) { seen = null; return; }
            long now = y.level().getGameTime();
            YukkuriEntity best = null; double bestD = Double.MAX_VALUE;
            for (var p : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(16, 6, 16), this::menacing)) {
                double d = p.distanceToSqr(y);
                if (d < bestD) { bestD = d; best = p; }
            }
            seen = best;
            if (best == null) return;
            double d = Math.sqrt(bestD);
            if (y.life().isSleeping() && d < 8) y.life().interrupt();
            boolean hunting = best.predator().active();
            if ((d <= alarmRange() || hunting && d <= 14) && now >= alarmUntil && !y.isPanicking() && alarmable(y)) {
                alarmUntil = now + 120;
                y.showReaction("surprise", "afraid", 80);
                y.frightenFrom(best);
                warnKin(best);
            }
        }

        /** Kin and friends within earshot learn of the danger even without seeing it. */
        void warnKin(LivingEntity danger) {
            for (var z : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 5, 12),
                    o -> o != y && prey(o) && kin(y, o))) z.status().reactions().threat().heard(danger);
        }
        void heard(LivingEntity danger) {
            if (danger == y || !danger.isAlive()) return;
            long now = y.level().getGameTime();
            heard = danger; heardUntil = now + 160;
            if (now >= noticeUntil && stage == Stage.NONE) { noticeUntil = now + 400; y.showReaction("surprise", "afraid", 60); }
        }

        boolean wants() {
            var s = source();
            if (s == null || !free(y) || y.isPanicking() || y.level().getGameTime() < cooldownUntil) return false;
            return stage != Stage.NONE || s.distanceToSqr(y) <= 256 || s == heard;
        }
        boolean keeps() { return stage != Stage.NONE && free(y) && !y.isPanicking(); }
        void start() {
            stage = Stage.WATCH; ticks = 0; lostSince = 0;
            y.life().interrupt();
            long now = y.level().getGameTime();
            if (now >= noticeUntil) { noticeUntil = now + 400; y.showReaction("surprise", "afraid", 60); }
        }
        void end(int cooldown) {
            stage = Stage.NONE; ticks = 0; cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        void tick() {
            ticks++;
            var s = source();
            long now = y.level().getGameTime();
            if (s != null) y.getLookControl().setLookAt(s, 30, 30);
            if (s == null || s.distanceToSqr(y) > 576) { if (lostSince == 0) lostSince = now; } else lostSince = 0;
            switch (stage) {
                case WATCH -> {
                    y.getNavigation().stop();
                    if (s == null) { end(60); return; }
                    if (ticks >= 25) { stage = Stage.RETREAT; ticks = 0; }
                }
                case RETREAT -> {
                    if (s == null) { stage = Stage.HIDE; ticks = 0; return; }
                    double d = s.distanceTo(y);
                    if (ticks % 10 == 1 || y.getNavigation().isDone()) retreat(s);
                    var home = y.life().homeStand();
                    if (home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home)) < 2.25) { stage = Stage.HIDE; ticks = 0; return; }
                    if (d > 20 || ticks > 400) { stage = Stage.HIDE; ticks = 0; }
                }
                case HIDE -> {
                    y.getNavigation().stop();
                    if (lostSince != 0 && now - lostSince > 80) { end(100); return; }
                    if (ticks > 1200) end(600);
                }
                default -> { }
            }
        }
        private void retreat(LivingEntity s) {
            LivingEntity owner = y.pet().owner() == null ? null : y.level().getPlayerByUUID(y.pet().owner());
            if (owner == null && y.pet().owner() != null && y.level() instanceof net.minecraft.server.level.ServerLevel sl
                    && sl.getEntity(y.pet().owner()) instanceof io.github.longshou.mcyukkuri.human.HumanNpc resident) owner = resident; // a resident's pet runs to its keeper
            if (owner != null && owner.isAlive() && !owner.isSpectator() && owner.distanceToSqr(y) < 256) {
                if (owner.distanceToSqr(y) > 6.25) y.getNavigation().moveTo(owner, 1.5);
                else { y.getNavigation().stop(); stage = Stage.HIDE; ticks = 0; }
                return;
            }
            var home = y.life().homeStand();
            if (home != null && home.distSqr(s.blockPosition()) > 25 && y.life().navigate(home, 1.4, false)) return;
            Vec3 away = DefaultRandomPos.getPosAway(y, 10, 4, s.position());
            if (away != null) y.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
        }
    }

    // ---- weather ----------------------------------------------------------------------------------------------

    public static final class Weather {
        public enum Stage { NONE, RUN, WAIT, HUDDLE, SHAKE }
        private final YukkuriEntity y;
        private Stage stage = Stage.NONE;
        private Sky sky = Sky.CLEAR;
        private BlockPos cover;
        private long cooldownUntil, searchAfter;
        private int ticks;
        private boolean spoke;

        Weather(YukkuriEntity y) { this.y = y; }

        public Stage stage() { return stage; }
        public Sky sky() { return sky; }
        String activity() {
            return switch (stage) { case RUN -> "rain_run"; case WAIT -> "rain_wait"; case HUDDLE -> "rain_huddle"; case SHAKE -> "rain_shake"; default -> "idle"; };
        }
        String describe() { return "weather:" + stage.name().toLowerCase(java.util.Locale.ROOT) + " sky=" + sky.name().toLowerCase(java.util.Locale.ROOT)
                + (cover == null ? "" : " cover=" + cover.toShortString()); }

        void scan() {
            sky = prey(y) ? Sky.at(y.level(), y.blockPosition()) : Sky.CLEAR;
            if (sky == Sky.CLEAR && stage == Stage.NONE && !y.level().isRaining()) spoke = false;
        }
        private boolean bothered() {
            return sky.wet() || sky == Sky.SNOW && y.life().temperature().cold();
        }
        /** Body emergencies are the life goal's business; this goal does not hold a starving individual in place. */
        private boolean pressing() {
            var life = y.life();
            return life.nutrition() < 20 || life.bladder() >= 90 || life.energy() < 10;
        }
        boolean wants() {
            if (stage != Stage.NONE) return true;
            long now = y.level().getGameTime();
            if (!bothered() || now < cooldownUntil || !free(y) || y.isPanicking() || pressing()) return false;
            if (now >= searchAfter) {
                if (!SearchBudget.take((ServerLevel) y.level())) return false;
                cover = findCover();
                searchAfter = now + 80;
            }
            return true;
        }
        boolean keeps() {
            if (stage == Stage.NONE) return false;
            if (!free(y) || y.isPanicking() || pressing()) return false;
            return stage == Stage.SHAKE || y.level().isRaining();
        }
        void start() {
            ticks = 0;
            y.life().interrupt();
            stage = cover != null ? Stage.RUN : Stage.HUDDLE;
            if (!spoke) {
                spoke = true;
                boolean storm = sky == Sky.STORM;
                y.showReaction(storm ? "surprise" : "neutral", "cold", 80);
            }
        }
        void end(int cooldown) {
            stage = Stage.NONE; ticks = 0; cover = null;
            cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        private BlockPos findCover() {
            var level = (ServerLevel) y.level();
            var life = y.life();
            var home = life.homeStand();
            if (home != null && life.covered(home) && Sky.at(level, home) == Sky.CLEAR && life.reachable(home, false)) return home;
            var origin = y.blockPosition();
            var candidates = new ArrayList<BlockPos>();
            for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
                if ((Math.abs(dx) > 8 || Math.abs(dz) > 8) && ((dx + dz) & 1) != 0) continue;
                int x = origin.getX() + dx, z = origin.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) continue;
                int roof = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int ry = origin.getY() + 1; ry >= origin.getY() - 2; ry--) {
                    if (roof <= ry) continue;                          // open sky at this height
                    var pos = new BlockPos(x, ry, z);
                    if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) continue;
                    if (!life.dryStand(pos) || !life.covered(pos)) continue;
                    var owner = ShelterBlock.shelter(level, pos);
                    if (owner != null && !owner.owns(y.getUUID())) continue;
                    candidates.add(pos.immutable());
                    break;
                }
            }
            candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
            int tried = 0;
            for (var pos : candidates) {
                if (tried++ >= 8) break;
                if (life.reachable(pos, false)) return pos;
            }
            return null;
        }

        void tick() {
            ticks++;
            var level = (ServerLevel) y.level();
            var life = y.life();
            boolean raining = level.isRaining();
            // Reaching any roof ends the run, whether or not it was the chosen one.
            if ((stage == Stage.RUN || stage == Stage.HUDDLE) && raining && Sky.at(level, y.blockPosition()) == Sky.CLEAR
                    && life.covered(y.blockPosition()) && ticks > 20) { y.getNavigation().stop(); stage = Stage.WAIT; ticks = 0; return; }
            switch (stage) {
                case RUN -> {
                    if (cover == null || !life.dryStand(cover)) { stage = Stage.HUDDLE; ticks = 0; return; }
                    Vec3 spot = Vec3.atBottomCenterOf(cover);
                    double reach = y.distanceToSqr(spot);
                    // The path stops up to half a block short; the last step is walked on the supported surface.
                    if (reach < .16 || reach < 2.25 && Sky.at(level, y.blockPosition()) == Sky.CLEAR) {
                        io.github.longshou.mcyukkuri.life.SurfaceApproach.halt(y); stage = Stage.WAIT; ticks = 0; return;
                    }
                    if (reach < 6.25) io.github.longshou.mcyukkuri.life.SurfaceApproach.walk(y, spot, 1.3);
                    else if (ticks % 10 == 1 || y.getNavigation().isDone()) {
                        if (!life.navigate(cover, sky == Sky.STORM ? 1.7 : 1.4, false)) { stage = Stage.HUDDLE; ticks = 0; return; }
                    }
                    if (ticks > 500) { stage = Stage.HUDDLE; ticks = 0; }
                }
                case WAIT -> {
                    y.getNavigation().stop();
                    if (raining && Sky.at(level, y.blockPosition()) != Sky.CLEAR && cover != null && ticks > 10) { stage = Stage.RUN; ticks = 0; return; }
                    if (!raining) { stage = Stage.SHAKE; ticks = 0; }
                    else if (life.energy() < 35 || ticks >= 1200) end(life.energy() < 35 ? 0 : 600);   // let the life goal put it to bed under the roof
                }
                case HUDDLE -> {
                    var companion = level.getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 3, 12),
                            o -> o != y && prey(o) && !o.life().isSleeping()).stream()
                            .min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
                    if (companion != null && y.distanceToSqr(companion) > 4) {
                        if (ticks % 10 == 1 || y.getNavigation().isDone()) y.getNavigation().moveTo(companion, 1.2);
                    } else y.getNavigation().stop();
                    if (ticks % 100 == 0 && SearchBudget.take(level)) {
                        cover = findCover();
                        if (cover != null) { stage = Stage.RUN; ticks = 0; return; }
                    }
                    if (!raining) { stage = Stage.SHAKE; ticks = 0; }
                    else if (ticks >= 600) end(300);
                }
                case SHAKE -> {
                    y.getNavigation().stop();
                    if (ticks >= 40) end(0);
                }
                default -> { }
            }
        }
    }

    // ---- goals ------------------------------------------------------------------------------------------------

    /** Emergency tier: stop what you are doing, watch, back away, hide. */
    public static final class ThreatGoal extends Goal {
        private final YukkuriEntity y;
        public ThreatGoal(YukkuriEntity y) { this.y = y; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private Threat threat() { return y.status().reactions().threat; }
        @Override public boolean canUse() { return threat().wants(); }
        @Override public boolean canContinueToUse() { return threat().keeps(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { threat().start(); }
        @Override public void tick() { threat().tick(); }
        @Override public void stop() { if (threat().stage != Threat.Stage.NONE) threat().end(60); }
    }

    /** Needs tier: find a roof, wait under it, huddle when there is none, shake off afterwards. */
    public static final class WeatherGoal extends Goal {
        private final YukkuriEntity y;
        public WeatherGoal(YukkuriEntity y) { this.y = y; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private Weather weather() { return y.status().reactions().weather; }
        @Override public boolean canUse() { return weather().wants(); }
        @Override public boolean canContinueToUse() { return weather().keeps(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { if (weather().stage == Weather.Stage.NONE) weather().start(); }
        @Override public void tick() { weather().tick(); }
        @Override public void stop() { if (weather().stage != Weather.Stage.NONE) weather().end(40); }
    }
}
