package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Sneeze;
import io.github.longshou.mcyukkuri.status.SneezeRules;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.SceneHold;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.perception.Knowledge;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.geom.SweptBody.Box;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.season.SeasonClimate;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.rare.RareForms;

/** Loaded-time thermal exposure and interruptible refuge seeking. All numbers are MC adaptation. */
public final class TemperatureLife {
    /** {@code sun}: the felt temperature counts the winter sun on a body basking flat in it ({@link #SUN_BASK}). */
    public record Conditions(double felt, boolean shade, boolean insulated, double warmth, boolean sun, double uncoveredFelt,
                             BlockPos fire) {}
    private record Refuge(BlockPos pos, double score, Conditions at) {}
    /** Felt-temperature bonus of a swaddle in the cold and the largest combined bonus of swaddle and nest (project-original numbers). */
    public static final double SWADDLE_WARMTH = .5, SWADDLE_CAP = 1.5;
    /** Warmth the direct sun of a clear cold day gives a chilled body that holds still and flat in it (project design). */
    public static final double SUN_BASK = .3;
    /** After a whole bask it waits this long before seeking the sun again; other thermal refuges remain available (project design). */
    static final int BASK_REST = 1200;
    /** A bask lasts the authored sunbathe clip: settle, soak, round up. */
    static final int BASK_TICKS = ClipMarkers.of("sunbathe").length();
    private final YukkuriEntity entity;
    private Conditions current = new Conditions(.8, false, false, 0, false, .8, null);
    private boolean initialSample = true;
    private boolean coldComplaint;
    /** Sampling is staggered; exposure keeps its legacy 20 loaded-and-AI-enabled tick cadence. */
    private int sampleTicks, temperatureSampleTicks, coldTicks, hotTicks, cooldown, baskCooldown, taskTicks, restTicks;
    private double coldExposure, departureFelt;
    /** Cold stiffening: while {@code stiff} the body is {@link YukkuriEntity#inert} and only this layer runs; {@code stiffLeft} is its window in
     *  ticks, {@code stiffMax} the window it entered with (see {@link ColdStiffness}). The exposure stays where it was until the thaw. */
    private boolean stiff;
    private double stiffLeft, stiffMax;
    private BlockPos target, targetFire, temporarySleepSite;
    private boolean seekingWarmth, basking;

    public TemperatureLife(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    double felt() { return current.felt(); }
    double coldLimit() { return YukkuriConfig.COLD_THRESHOLD.get(); }
    private double freezeLimit() { return Math.min(coldLimit(), YukkuriConfig.FREEZE_THRESHOLD.get()); }
    double hotLimit() { return Math.max(coldLimit() + .5, YukkuriConfig.HOT_THRESHOLD.get()); }
    public int coldExposure() { return (int)coldExposure; }
    /** Controlled write for the status registry (scenarios, commands): the chill the body starts from. */
    public void setColdExposure(int exposure) { coldExposure = Math.clamp(exposure, 0, 1_728_000); }
    /** Actual reached-refuge rest counter, read without evaluating a thermal task. */
    public int observedRestTicks() { return restTicks; }
    private boolean freezing(double felt) { return felt <= freezeLimit(); }
    private boolean tooCold(double felt) { return felt < coldLimit() || freezing(felt); }
    private boolean comfortable(double felt) { return !tooCold(felt) && felt < hotLimit(); }
    private double ageRate() { return entity.family().stage() == FamilyLife.Stage.BABY ? 2 : entity.family().adult() ? 1 : 1.5; }
    private double exposureRate(double felt) { return ThermalComfort.exposureRate(felt, coldLimit(), ageRate()); }
    /** Frozen stiff by the cold; the body is inert and cannot be fed, tended or moved by its own will until it thaws or the window is spent. */
    public boolean stiff() { return stiff; }
    /** Seconds the window has left, for the observation card. */
    public int stiffSeconds() { return (int) Math.ceil(stiffLeft / 20); }
    /** A stiff body is rewarming when it stands in a place that is no longer cold. */
    public boolean rewarming() { return stiff && !tooCold(current.felt()); }
    public boolean cold() { return tooCold(current.felt()); }
    public double coldDeficit() { return Math.max(0, coldLimit() - current.felt()); }
    public boolean needsKinWarmth() {
        return targetFire != null || (initialSample ? tooCold(sample(entity.blockPosition()).felt()) : cold());
    }
    public boolean hot() { return current.felt() >= hotLimit(); }
    public boolean unsafeSleep() { return cold() || hot(); }
    public boolean comfortableAt(BlockPos pos) { return pos != null && level().hasChunkAt(pos) && comfortable(sample(pos).felt()); }
    public boolean active() { return target != null; }
    public String activity() {
        if (restTicks == 0) return seekingWarmth ? Behaviors.SEEK_WARMTH : Behaviors.SEEK_SHADE;
        if (!seekingWarmth) return Behaviors.SHADE_REST;
        if (basking) return Behaviors.SUNBATHE;
        return cold() ? Behaviors.COLD_SHIVER : Behaviors.WARM_UP;
    }

    /** The one layer that runs in a stiff body: {@code inert()} also holds a stiff body, so it is asked of the scene hold and NoAI alone. */
    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive() || SceneHold.inert(entity)) return;
        if (stiff) { tickStiff(); return; }
        if (cooldown > 0) cooldown--;
        if (baskCooldown > 0) baskCooldown--;
        if (initialSample) {
            // A loaded entity has a useful current reading at once, without advancing exposure.
            sampleHere();
            initialSample = false;
            temperatureSampleTicks = Math.floorMod(entity.getUUID().hashCode(), 20);
        } else if (++temperatureSampleTicks >= 20) {
            temperatureSampleTicks = 0;
            sampleHere();
        }
        if (++sampleTicks < 20) return;
        sampleTicks = 0;
        if (entity.life().usingPaperCoverAt(entity.blockPosition()) && !tooCold(current.uncoveredFelt()))
            entity.life().releasePaperCover("warmed");
        coldExposure = Math.clamp(coldExposure + 20 * exposureRate(current.felt()), 0, 1_728_000);
        int stiffens = YukkuriConfig.COLD_EXPOSURE_TICKS.get();
        if (freezing(current.felt()) && coldExposure >= stiffens) {
            double share = windowShare();
            if (canStiffen()) { beginStiff(share); return; }
            // A body that cannot stiffen yet (swimming, riding, in labour) keeps chilling: past the window it freezes outright.
            if (coldExposure >= ColdStiffness.deathExposure(stiffens, share)) { freezeToDeath("unable_to_stiffen"); return; }
        }
        if (cold()) {
            coldTicks += 20;
            if (coldTicks >= 100) {
                coldTicks = 0;
                entity.life().applyEnvironment(0, 0, entity.family().adult() ? 1 : 2);
            }
        } else coldTicks = 0;
        double coldSneeze = SneezeRules.coldIntensity(cold(), coldDeficit(), current.felt(), coldLimit(), entity.life().wetness());
        if (coldSneeze > 0) Sneeze.consider(entity, SneezeRules.Trigger.COLD, coldSneeze);
        if (hot()) {
            hotTicks += 20;
            if (hotTicks >= YukkuriConfig.HEAT_DRYNESS_INTERVAL.get()) {
                hotTicks = 0;
                entity.life().addDryness(1);
            }
        } else hotTicks = 0;
    }

    private double windowShare() {
        return ColdStiffness.windowShare(YukkuriConfig.STIFF_WINDOW_FACTOR.get(), entity.life().nutrition(),
                entity.growthScale() * entity.getScale(), entity.body().healthRatio());
    }

    /**
     * A body stiffens only where it can stand still for good: on the ground, dry, not in a performance, an operation, the water, on a
     * ride, a hat, or in the last stretch of a birth. Otherwise the exposure keeps rising ({@link ColdStiffness#deathExposure}).
     */
    private boolean canStiffen() {
        var brood = entity.family().brood();
        return Attach.of(entity) instanceof AttachState.Free free && !free.inWater() && entity.onGround()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.isPassenger() && !entity.isPanicking()
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.pet().busy()
                && !entity.family().awaitingLanding() && !entity.family().flying() && !entity.predatorFlying() && !entity.family().birthDuty()
                && (brood == null || brood.cancelled || brood.complete() || brood.progress() < .9f)
                && !entity.body().dying();
    }

    /**
     * The body takes in all the cold it can and freezes where it stands. The interrupt ends everything it held or was doing (mouth, young
     * on the head or in the arms, errands, tables, the warmth walk); from here the body is inert and only {@link #tickStiff} runs.
     */
    private void beginStiff(double share) {
        double deficit = coldLimit() - current.felt();
        stiffMax = stiffLeft = ColdStiffness.windowTicks(YukkuriConfig.COLD_EXPOSURE_TICKS.get(), share, ageRate(), deficit);
        entity.interrupt(Interrupt.COMMAND);
        stop();
        stiff = true;
        coldComplaint = false;
        entity.getNavigation().stop();
        GameplayLog.event(entity, "temperature", "stiff begin felt={} deficit={} exposure={} window={} share={} ageRate={} nutrition={} health={}",
                current.felt(), deficit, coldExposure(), stiffLeft, share, ageRate(), entity.life().nutrition(), entity.getHealth());
        entity.beat("stiff_enter", "sleep", "");
    }

    /** The window drains in the cold, refills in the warm; fire thaws at once. Nothing else of the body advances. */
    private void tickStiff() {
        if (entity.isOnFire()) { thaw("fire"); return; }
        if (initialSample) { current = sample(entity.blockPosition()); initialSample = false; }
        if (++sampleTicks < ColdStiffness.SAMPLE) return;
        sampleTicks = 0;
        current = sample(entity.blockPosition());
        double before = stiffLeft;
        stiffLeft = ColdStiffness.advance(stiffLeft, stiffMax, ColdStiffness.change(current.felt(), coldLimit(), freezeLimit(), YukkuriConfig.THAW_RATE.get()));
        GameplayLog.limited(entity, "temperature", "stiff.sample", 200, "stiff window felt={} left={} -> {} of {} rewarming={}",
                current.felt(), before, stiffLeft, stiffMax, rewarming());
        if (ColdStiffness.spent(stiffLeft)) freezeToDeath("window_spent");
        else if (ColdStiffness.thawed(stiffLeft, stiffMax, !tooCold(current.felt()))) thaw("warmed");
    }

    /** The shell lets go: the exposure starts again from just under the stiffening line and the body is spent (project design). */
    private void thaw(String reason) {
        stiff = false;
        double window = stiffLeft;
        stiffLeft = stiffMax = 0;
        coldExposure = ColdStiffness.THAWED_EXPOSURE * YukkuriConfig.COLD_EXPOSURE_TICKS.get();
        cooldown = 0; coldComplaint = false;
        var life = entity.life();
        life.spendNutrition(Math.min(ColdStiffness.THAW_NUTRITION, life.nutrition()));
        life.applyEnvironment(0, 0, Math.round(ColdStiffness.THAW_ENERGY));
        GameplayLog.event(entity, "temperature", "stiff end reason={} felt={} window={} exposure={} nutrition={} energy={}",
                reason, current.felt(), window, coldExposure(), life.nutrition(), life.energy());
    }

    /** The window ran out (or a body that could not stiffen chilled through): the cold kills it and it leaves frozen remains. */
    private void freezeToDeath(String reason) {
        boolean wasStiff = stiff;
        stiff = false;
        double window = stiffLeft;
        stiffLeft = stiffMax = 0;
        boolean accepted = entity.hurt(level().damageSources().freeze(), entity.getMaxHealth() * 2);
        GameplayLog.event(entity, "temperature", "freeze death reason={} stiff={} felt={} window={} exposure={} accepted={} alive={}",
                reason, wasStiff, current.felt(), window, coldExposure(), accepted, entity.isAlive());
    }

    private void sampleHere() {
        current = sample(entity.blockPosition(), baskingNow());
        if (!cold()) { coldComplaint = false; return; }
        if (coldComplaint || entity.life().isSleeping() || entity.isPanicking()) return;
        var parent = entity.family().coldCryParent();
        // An addressed cry can break into ambient chatter while an ordinary complaint takes its turn.
        if (!entity.speakCue("cold", null, parent == null ? 0 : 2)) return;
        coldComplaint = true;
        if (parent != null) entity.showReaction("cry", "", FaceTier.Tier.SHORT);
        GameplayLog.event(entity, "temperature", "cold complaint felt={} line={} parent={}", current.felt(),
                entity.getDialogueKey(), parent == null ? null : parent.getUUID());
    }

    /** No offline time, pending route or remembered weather is restored. */
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putInt("ColdExposure", coldExposure());
        tag.putInt("ColdTicks", coldTicks); tag.putInt("HotTicks", hotTicks);
        if (stiff) { tag.putBoolean("Stiff", true); tag.putDouble("StiffLeft", stiffLeft); tag.putDouble("StiffMax", stiffMax); }
        return tag;
    }
    /** A stiff body keeps its window across a reload; {@code stop()} only ends the warmth walk and leaves the stiffness alone. */
    public void load(CompoundTag tag) {
        coldExposure = Math.clamp(tag.getInt("ColdExposure"), 0, 1_728_000);
        coldTicks = Math.clamp(tag.getInt("ColdTicks"), 0, 99);
        hotTicks = Math.clamp(tag.getInt("HotTicks"), 0, 23_999);
        stiff = tag.getBoolean("Stiff");
        stiffMax = stiff ? Math.clamp(tag.getDouble("StiffMax"), 1, ColdStiffness.MAX_LEFT) : 0;
        stiffLeft = stiff ? Math.clamp(tag.getDouble("StiffLeft"), 1, stiffMax) : 0;
        initialSample = true; coldComplaint = false; sampleTicks = temperatureSampleTicks = 0; cooldown = 0; stop();
    }

    /** Block heat, and the internal heat of the kotatsu Marisas nearby. */
    private record HeatSources(List<ThermalSourceCache.Source> blocks, List<ThermalSourceCache.Source> bodies) {}
    /** Public for observation and remote world fixtures; it never generates or loads a chunk. */
    public Conditions sample(BlockPos pos) { return sample(pos, false); }
    /** {@code basking}: as for a body holding still and flat there, which the winter sun warms. */
    private Conditions sample(BlockPos pos, boolean basking) { return sample(pos, heatSources(pos, 0, 0), basking); }
    private Conditions sample(BlockPos pos, HeatSources sources, boolean basking) {
        if (!level().hasChunkAt(pos)) return new Conditions(.8, false, false, 0, false, .8, null);
        boolean shade = shaded(pos), nestInsulated = nestInsulated(pos, shade);
        boolean dryPaper = shade && !level().isRainingAt(pos) && entity.life().wetness() < 50;
        double bedding = !nestInsulated && dryPaper && entity.life().usingPaperBedding(pos)
                ? YukkuriConfig.PAPER_BEDDING_INSULATION.get() : 0;
        double cover = !nestInsulated && dryPaper && entity.life().usingPaperCoverAt(pos)
                ? YukkuriConfig.PAPER_COVER_INSULATION.get() : 0;
        boolean insulated = nestInsulated || bedding > 0 || cover > 0;
        double biome = level().getBiome(pos).value().getBaseTemperature();
        double felt = SeasonClimate.ambient(biome, SeasonClimate.offset(level(), pos), level().dimensionType().hasSkyLight(),
                level().isNight(), shade, level().isRaining());
        felt -= entity.life().wetness() / 100.0 * .35;
        felt += RareForms.kotatsuWarmth(entity, felt); // a kotatsu Marisa carries her own warmth
        double bonus = tooCold(felt) ? (nestInsulated ? YukkuriConfig.NEST_INSULATION.get() : bedding) : 0;
        // The swaddle adds a little warmth on top of a nest, up to a combined bonus of 1.5 (docs/FEATURES.md).
        if (entity.pet().care().swaddled() && tooCold(felt + bonus)) bonus = Math.max(bonus, Math.min(bonus + SWADDLE_WARMTH, SWADDLE_CAP));
        felt += bonus;
        var source = heatSourceAt(pos, sources.blocks());
        double warmth = source == null ? 0 : heatAt(pos, source);
        BlockPos fire = source != null && source.fire() ? source.pos() : null;
        // A kotatsu Marisa carries an internal heater like a torch carries fire, but only into a body that is cold:
        // beside her a freezing neighbour gets properly warm, and in summer nobody does (wiki L23 内部发热, L22 耐寒怕热).
        if (tooCold(felt + warmth)) {
            var heater = heatSourceAt(pos, sources.bodies());
            if (heater != null) warmth = Math.max(warmth, heatAt(pos, heater));
        }
        // Pressed bodies and a parent directly behind a baby reduce heat loss while they are actually together.
        if (tooCold(felt + warmth) && pos.equals(entity.blockPosition())
                && (entity.status().reactions().weather().huddled() || fire != null && shieldingParent(fire) != null))
            felt += .25;
        // Where nothing else warms it enough, a chilled body basking flat in the direct sun of a clear day soaks up the sun.
        boolean sun = basking && tooCold(felt + warmth) && !shade && !level().isNight() && !level().isRaining()
                && level().dimensionType().hasSkyLight() && level().canSeeSky(pos);
        if (sun) felt += SUN_BASK;
        double uncoveredFelt = felt + warmth;
        return new Conditions(uncoveredFelt + (tooCold(uncoveredFelt) ? cover : 0), shade, insulated, warmth, sun, uncoveredFelt, fire);
    }
    /** Holding still and flat in the sun right now: the reached refuge of a warmth walk that the sun warms. */
    private boolean baskingNow() { return active() && basking && restTicks > 0; }
    private boolean shaded(BlockPos pos) {
        if (!level().hasChunkAt(pos)) return false;
        var shelter = entity.habitat().shelter();
        if (shelter != null && shelter.contains(pos) && shelter.sound() && shelter.rainSheltered(pos)) return true;
        return level().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY();
    }
    private boolean nestInsulated(BlockPos pos, boolean shade) {
        if (!shade) return false;
        if (entity.life().ownsStreetCover(pos) && entity.life().usingPaperBedding(pos)) return true;
        var shelter = entity.habitat().shelter();
        if (shelter == null) shelter = entity.life().foundShelter();
        if (shelter != null && shelter.owns(entity.getUUID()) && shelter.contains(pos)
                && shelter.usable(entity) && shelter.insulated()) return true;
        var nest = entity.house().nest();
        if (nest != null && nest.built() && nest.dirt() < 6 && nest.usable(entity)
                && (pos.equals(nest.getBlockPos()) || pos.equals(nest.entrance()))) return true;
        var home = entity.life().home();
        return home != null && !entity.life().naturalHome() && home.dimension().equals(level().dimension())
                && level().hasChunkAt(home.pos()) && home.pos().distManhattan(pos) <= 1
                && level().getBlockEntity(home.pos()) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST
                && care.owns(entity.getUUID()) && entity.life().dryStand(pos);
    }
    private HeatSources heatSources(BlockPos center, int reach, int climb) {
        int radius = reach + ThermalComfort.HEAT_RADIUS;
        int height = climb + ThermalComfort.HEAT_HEIGHT;
        var bodies = new ArrayList<ThermalSourceCache.Source>();
        for (var kotatsu : level().getEntitiesOfClass(YukkuriEntity.class, new AABB(center).inflate(radius),
                other -> other != entity && RareForms.kotatsu(other)))
            bodies.add(new ThermalSourceCache.Source(kotatsu.blockPosition(), KOTATSU_NEIGHBOUR_HEAT, false, ThermalComfort.HEAT_RADIUS));
        return new HeatSources(ThermalSourceCache.sources(level(), center, radius, height), bodies);
    }
    /** Warmth reaching this place from one group of heat sources; a wall blocks it like any other heat. */
    private ThermalSourceCache.Source heatSourceAt(BlockPos pos, List<ThermalSourceCache.Source> sources) {
        ThermalSourceCache.Source best = null;
        double warmth = 0;
        for (var source : sources) {
            double heat = heatAt(pos, source);
            if (heat <= warmth || !level().hasChunkAt(source.pos()) || !clearHeat(pos, source.pos())) continue;
            warmth = heat;
            best = source;
        }
        return best;
    }

    private static double heatAt(BlockPos pos, ThermalSourceCache.Source source) {
        return ThermalComfort.radiantWarmth(source.strength(), source.pos().distSqr(pos),
                source.pos().getY() - pos.getY(), source.verticalReach());
    }
    /** Heat a kotatsu Marisa radiates to a body huddled against her: properly warm within three blocks, gone by four. */
    static final double KOTATSU_NEIGHBOUR_HEAT = 2.4;
    private boolean clearHeat(BlockPos from, BlockPos source) {
        // A ray may cross a third chunk even when both endpoints are already loaded.
        for (var pos : BlockPos.betweenClosed(from, source)) if (!level().hasChunkAt(pos)) return false;
        Vec3 start = Vec3.atBottomCenterOf(from).add(0, Math.min(.8, entity.getBbHeight() * .5), 0);
        var hit = level().clip(new ClipContext(start, Vec3.atCenterOf(source), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(source);
    }

    private boolean available() {
        var life = entity.life();
        var brood = entity.family().brood();
        return entity.isAlive() && !entity.inert() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !entity.isPassenger()
                && !entity.family().active() && !entity.family().awaitingLanding()
                && (brood == null || brood.cancelled || brood.complete() || brood.progress() < .9f)
                && life.mealTicks() == 0 && life.drugSleep() == 0 && life.sprayDelay() == 0
                && life.nutrition() >= 30
                && life.toiletPressure() < 70;
    }
    public boolean plan() { return plan(false); }
    /** Preempts an active wind-gap shelter only for a real heat source, insulated nest, or winter sun — never bare biome-edge ground. */
    public boolean planRealWarmth() { return plan(true); }
    private boolean plan(boolean requireHeatOrNest) {
        if (active()) return canContinue();
        if (cooldown > 0 || !available()) return false;
        cooldown = 100;
        var here = sample(entity.blockPosition());
        if (comfortable(here.felt())) return false;
        seekingWarmth = tooCold(here.felt());
        departureFelt = here.felt();
        // A chilled body counts the sun it could bask in, its own sunny spot included (it then just settles where it is).
        var center = entity.blockPosition();
        int reach = TravelRange.search(entity, 6);
        var sources = heatSources(center, reach, 2);
        var candidates = new ArrayList<Refuge>();
        var baby = seekingWarmth ? entity.family().coldChildNearby() : null;
        var parent = seekingWarmth ? entity.family().coldCryParent() : null;
        for (var pos : BlockPos.betweenClosed(center.offset(-reach, -2, -reach), center.offset(reach, 2, reach))) {
            if (!safeStand(pos)) continue;
            if (!mayUse(pos)) continue;
            var at = sample(pos, sources, seekingWarmth && baskCooldown == 0);
            if (!refuge(at)) continue;
            if (requireHeatOrNest && seekingWarmth && !(at.warmth() > 0 || at.insulated() || at.sun())) continue;
            double distance = pos.distSqr(center);
            double score = seekingWarmth ? distance * .18 - 8 * (at.felt() - departureFelt) : distance;
            if (seekingWarmth && at.fire() != null) {
                if (!fireStandFree(bodyAt(Vec3.atBottomCenterOf(pos)))) continue;
                score += fireRingScore(pos, at.fire(), baby, parent);
            }
            candidates.add(new Refuge(pos.immutable(), score, at));
        }
        candidates.sort(Comparator.comparingDouble(Refuge::score));
        for (int i = 0; i < Math.min(16, candidates.size()); i++) {
            var candidate = candidates.get(i);
            if (safePath(candidate.pos()) == null) continue;
            target = candidate.pos(); taskTicks = restTicks = 0; basking = candidate.at().sun();
            targetFire = seekingWarmth ? candidate.at().fire() : null;
            GameplayLog.limited(entity, "temperature", "refuge.start", 1200,
                    "refuge route selected warming={} target={} felt={} target_felt={} target_heat={} sun={} coldExposure={} fire={}",
                    seekingWarmth, target, departureFelt, candidate.at().felt(), candidate.at().warmth(), basking, coldExposure(), targetFire);
            return true;
        }
        return false;
    }
    private boolean refuge(Conditions at) {
        return seekingWarmth ? ThermalComfort.warmerRefuge(departureFelt, at.felt(), hotLimit())
                : comfortable(at.felt()) && at.shade();
    }

    private double fireRingScore(BlockPos pos, BlockPos fire, YukkuriEntity baby, YukkuriEntity parent) {
        double bold = entity.life().personality().boldness() / 100.0;
        double preferred = ThermalComfort.preferredFireDistance(bold, entity.getBbWidth(), coldLimit() - departureFelt,
                entity.body().remembersBurn(), baby != null, parent != null);
        double offset = Math.sqrt(fire.distSqr(pos)) - preferred;
        double score = 12 * offset * offset - 3;
        var kin = baby != null ? baby : parent;
        if (kin == null) return score;
        var kinTemperature = kin.life().temperature();
        var anchor = fire.equals(kinTemperature.targetFire) ? kinTemperature.target : kin.blockPosition();
        int dx = anchor.getX() - fire.getX(), dz = anchor.getZ() - fire.getZ();
        double length = Math.hypot(dx, dz);
        if (length == 0) return score;
        // The parent comes up directly outward of the child, between it and the cold; a one-block flank rounds to the
        // child's side instead, where a shielding check fails.
        double x = pos.getX() - fire.getX() - preferred * dx / length;
        double z = pos.getZ() - fire.getZ() - preferred * dz / length;
        return score + x * x + z * z;
    }

    private AABB bodyAt(Vec3 feet) {
        return entity.getBoundingBox().move(feet.subtract(entity.position()));
    }
    private static Box volume(AABB box) {
        return new Box(new Vec(box.minX, box.minY, box.minZ), new Vec(box.maxX, box.maxY, box.maxZ));
    }
    private boolean fireStandFree(AABB box) {
        var footprint = volume(box);
        return level().getEntitiesOfClass(YukkuriEntity.class, box,
                other -> other != entity && other.isAlive() && other.isPushable() && !other.isPassenger()
                        && RefugeSpace.blocksStand(footprint, volume(other.getBoundingBox()), other.life().temperature().resting())).isEmpty();
    }
    private boolean resting() {
        return restTicks > 0 || temporarySleepSite != null || entity.life().restingInFoundCover() || entity.life().isSleeping();
    }

    private YukkuriEntity shieldingParent(BlockPos fire) {
        var parent = entity.family().coldCryParent();
        if (parent == null || !parent.onGround() || parent.distanceToSqr(entity) > 1.65 * 1.65 || !entity.hasLineOfSight(parent)) return null;
        var outward = entity.position().subtract(Vec3.atBottomCenterOf(fire));
        var toParent = parent.position().subtract(entity.position());
        return outward.x * toParent.x + outward.z * toParent.z >= .6 * outward.horizontalDistance() * toParent.horizontalDistance()
                && toParent.horizontalDistanceSqr() > .04 ? parent : null;
    }
    private boolean mayUse(BlockPos pos) {
        if (!TravelRange.babyDestination(entity,pos) || !level().hasChunkAt(pos)) return false;
        var state = level().getBlockState(pos);
        if (state.getBlock() instanceof ShelterBlock && !level().hasChunkAt(ShelterBlock.root(pos, state))) return false;
        var owner = ShelterBlock.shelter(level(), pos);
        if (owner != null && !owner.owns(entity.getUUID())) return false;
        for (var near : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 0, 1))) {
            if (!level().hasChunkAt(near)) return false;
            var block = level().getBlockEntity(near);
            if (block instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST && care.resident() != null
                    && !care.owns(entity.getUUID()) && near.distManhattan(pos) <= 1) return false;
            if (block instanceof HouseNestBlockEntity nest
                    && (pos.equals(near) || pos.equals(nest.entrance())) && !near.equals(entity.house().nestPosition())) return false;
        }
        return true;
    }
    private boolean safeStand(BlockPos pos) {
        return safeStand(pos, bodyAt(Vec3.atBottomCenterOf(pos)));
    }
    private boolean safeStand(BlockPos pos, AABB body) {
        if (!entity.life().dryStand(pos)) return false;
        for (var near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 0, 1))) {
            if (unsafeCell(near)) return false;
        }
        var cell = new BlockPos.MutableBlockPos();
        return !RefugeSpace.touchesHazard(volume(body), (x, y, z) -> unsafeCell(cell.set(x, y, z)));
    }
    private boolean unsafeCell(BlockPos pos) {
        if (!level().hasChunkAt(pos)) return true;
        var state = level().getBlockState(pos);
        return ThermalSourceCache.burning(state) || state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS);
    }
    private Path safePath(BlockPos pos) {
        var path = entity.getNavigation().createPath(pos, 0);
        if (path == null || !path.canReach()) return null;
        // A stand without a bed is walked into: a route ending beside it (a body in the way) would park the body at the neighbour.
        if (FamilyNest.bedOccupancy(entity, pos) == null && !pos.equals(path.getNodePos(path.getNodeCount() - 1))) return null;
        // Permit departure from the starting block; no later node may enter a hazard.
        for (int i = 1; i < path.getNodeCount(); i++) {
            var node = path.getNode(i);
            if (!safeStand(new BlockPos(node.x, node.y, node.z))) return null;
        }
        return path;
    }
    public boolean canContinue() { return continueGate() == null; }
    /** Which admission ends the walk; the stop log alone cannot tell a reached refuge from a lost one. */
    private String continueGate() {
        if (!active()) return "inactive";
        if (!available()) return "unavailable";
        if (taskTicks >= 400) return "elapsed";
        if (restTicks >= (basking ? BASK_TICKS : 200)) return "rested";
        boolean arrived = reached(target, FamilyNest.bedOccupancy(entity, target));
        if (!safeStand(target, arrived ? entity.getBoundingBox() : bodyAt(Vec3.atBottomCenterOf(target)))) return "unsafe_stand";
        if (!mayUse(target)) return "not_ours";
        // A moving body does not occupy a stand. A new arrival must clear the actual volume of every resting body.
        if (targetFire != null && arrived && !fireStandFree(entity.getBoundingBox())) return "occupied_stand";
        if (taskTicks % 20 == 0) {
            if (waterTakesPriority()) return "lost_refuge";
            var at = sample(target, basking);
            if (!refuge(at) || targetFire != null && !targetFire.equals(at.fire())) return "lost_refuge";
        }
        return null;
    }
    private boolean waterTakesPriority() {
        var life = entity.life();
        // When there is no reachable water, thirst must not strand an animal in direct sun.
        return (life.dryness() >= 40 || life.spicyTicks() > 0) && life.hasDrink(Knowledge.WORTH_A_LOOK);
    }
    public boolean tickGoal() {
        if (!canContinue()) { stop(); return false; }
        taskTicks++;
        var occupancy = FamilyNest.bedOccupancy(entity, target);
        if (reached(target, occupancy)) {
            if (occupancy != null) SurfaceApproach.halt(entity); else entity.getNavigation().stop();
            restTicks++;
            if (targetFire != null && (restTicks == 1 || restTicks == 60)) {
                var at = sample(entity.blockPosition());
                var parent = shieldingParent(targetFire);
                GameplayLog.event(entity, "temperature", "fire rest fire={} stand={} ring={} felt={} rate={} shield_parent={} ticks={}",
                        targetFire, entity.blockPosition(), Math.sqrt(entity.blockPosition().distSqr(targetFire)), at.felt(), exposureRate(at.felt()),
                        parent == null ? null : parent.getUUID(), restTicks);
            }
            // A short pause consumes no supplies and grants neither sleep growth nor free hydration.
            return true;
        }
        restTicks = 0;
        // A nest refuge admits only a whole footprint inside it: the last two blocks go onto the place its claim assigned.
        if (occupancy != null && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 4) {
            var place = FamilyNest.restPosition(entity, target);
            SurfaceApproach.walk(entity, place != null ? place : Vec3.atBottomCenterOf(target), 1.15);
            return true;
        }
        // Vanilla advances a node once the body is within 0.75 - width/2 of its corner, so a narrow body can call itself
        // arrived while its block is still outside the stand; the last step onto a bed-less stand is walked.
        if (occupancy == null && entity.getNavigation().isDone() && nextToStand(target)) {
            SurfaceApproach.walk(entity, Vec3.atBottomCenterOf(target), 1.15);
            return true;
        }
        if (taskTicks % 20 == 1) {
            var path = safePath(target);
            if (path == null || !entity.getNavigation().moveTo(path, 1)) { stop(); return false; }
        }
        return true;
    }
    /** The stand or a block touching it: the navigation may park a small body at the stand's corner. */
    private boolean nextToStand(BlockPos stand) {
        var at = entity.blockPosition();
        return Math.abs(stand.getX() - at.getX()) <= 1 && Math.abs(stand.getY() - at.getY()) <= 1
                && Math.abs(stand.getZ() - at.getZ()) <= 1;
    }
    /** A refuge is reached on a real bed only inside it; a stand without a bed is a whole block, and the body stands in it. */
    private boolean reached(BlockPos site, Occupancy occupancy) {
        return occupancy != null ? occupancy == Occupancy.INSIDE_SUPPORTED
                : entity.blockPosition().equals(site) && entity.onGround();
    }
    /** Transfers only a physically reached safe place, never home ownership or elapsed sleep. */
    public boolean beginSleepAtRefuge() {
        if (!active() || restTicks < 20 || !available() || waterTakesPriority()
                || !reached(target, FamilyNest.bedOccupancy(entity, target)) || !safeStand(target, entity.getBoundingBox()) || !mayUse(target)
                || level().isRainingAt(target)) return false;
        var at = sample(entity.blockPosition());
        if (!comfortable(at.felt()) || !refuge(at)) return false;
        temporarySleepSite = target;
        GameplayLog.limited(entity, "temperature", "refuge.sleep", 1200,
                "refuge handed to sleep site={} felt={} restTicks={}", target, at.felt(), restTicks);
        target = targetFire = null; taskTicks = restTicks = 0; basking = false; cooldown = Math.max(cooldown, 60);
        return true;
    }
    public boolean hasTemporarySleepSite() { return temporarySleepSite != null; }
    public boolean canSleepAtRefuge() {
        return temporarySleepSite != null && available()
                && reached(temporarySleepSite, FamilyNest.bedOccupancy(entity, temporarySleepSite))
                && safeStand(temporarySleepSite, entity.getBoundingBox()) && mayUse(temporarySleepSite)
                && !level().isRainingAt(temporarySleepSite) && comfortable(current.felt())
                && (sampleTicks != 0 || !waterTakesPriority());
    }
    public void stop() {
        String gate = active() ? continueGate() : null;
        if (gate != null) GameplayLog.limited(entity, "temperature", "refuge.gate." + gate, 1200,
                "refuge ended gate={} warming={} target={} felt={} exposure={}", gate, seekingWarmth, target, current.felt(), coldExposure);
        if (target != null) GameplayLog.limited(entity, "temperature", "refuge.stop", 1200,
                "refuge stopped warming={} sun={} target={} elapsed={} restTicks={} felt={}", seekingWarmth, basking, target, taskTicks, restTicks, current.felt());
        // Only another sun bask rests for a minute: a real heat source or shade can still meet a new thermal need.
        if ("rested".equals(gate) && basking) baskCooldown = BASK_REST;
        cooldown = "lost_refuge".equals(gate) ? 0 : Math.max(cooldown, 60);
        target = targetFire = temporarySleepSite = null; taskTicks = restTicks = 0; basking = false;
    }

    public Component observation() {
        var at = sample(entity.blockPosition(), baskingNow());
        Component state = stiff
                ? Component.translatable(rewarming() ? "temperature.mcyukkuri.stiff_rewarming" : "temperature.mcyukkuri.stiff", stiffSeconds())
                : Component.translatable("temperature.mcyukkuri." + (freezing(at.felt()) ? "freezing" : tooCold(at.felt()) ? "cold"
                        : at.felt() >= hotLimit() ? "hot" : "comfortable"));
        return Component.translatable("notebook.mcyukkuri.temperature", state,
                String.format(Locale.ROOT, "%.2f", at.felt()), coldExposure(), YukkuriConfig.COLD_EXPOSURE_TICKS.get(),
                at.shade() ? "✓" : "—", at.insulated() ? "✓" : "—", at.warmth() > 0 ? "✓" : "—");
    }
}
