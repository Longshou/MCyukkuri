package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
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
                             BlockPos campfire) {}
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
    /** Sampling is staggered; exposure keeps its legacy 20 loaded-and-AI-enabled tick cadence. */
    private int sampleTicks, temperatureSampleTicks, damageTicks, coldTicks, hotTicks, cooldown, baskCooldown, taskTicks, restTicks;
    private double coldExposure, departureFelt;
    private BlockPos target, targetFire, temporarySleepSite;
    private boolean seekingWarmth, basking;

    public TemperatureLife(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    private double coldLimit() { return YukkuriConfig.COLD_THRESHOLD.get(); }
    private double freezeLimit() { return Math.min(coldLimit(), YukkuriConfig.FREEZE_THRESHOLD.get()); }
    private double hotLimit() { return Math.max(coldLimit() + .5, YukkuriConfig.HOT_THRESHOLD.get()); }
    public int coldExposure() { return (int)coldExposure; }
    /** Actual reached-refuge rest counter, read without evaluating a thermal task. */
    public int observedRestTicks() { return restTicks; }
    private boolean freezing(double felt) { return felt <= freezeLimit(); }
    private boolean tooCold(double felt) { return felt < coldLimit() || freezing(felt); }
    private boolean comfortable(double felt) { return !tooCold(felt) && felt < hotLimit(); }
    private double exposureRate(double felt) {
        double ageRate = entity.family().stage() == FamilyLife.Stage.BABY ? 2 : entity.family().adult() ? 1 : 1.5;
        return ThermalComfort.exposureRate(felt, coldLimit(), ageRate);
    }
    public boolean cold() { return tooCold(current.felt()); }
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

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi()) return;
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
        if (freezing(current.felt()) && coldExposure >= YukkuriConfig.COLD_EXPOSURE_TICKS.get()) {
            damageTicks += 20;
            if (damageTicks >= YukkuriConfig.FREEZE_DAMAGE_INTERVAL.get()) {
                damageTicks = 0;
                entity.hurt(level().damageSources().freeze(), 1);
            }
        } else damageTicks = 0;
        if (cold()) {
            coldTicks += 20;
            if (coldTicks >= 100) {
                coldTicks = 0;
                entity.life().applyEnvironment(0, 0, entity.family().adult() ? 1 : 2);
            }
        } else coldTicks = 0;
        if (hot()) {
            hotTicks += 20;
            if (hotTicks >= YukkuriConfig.HEAT_DRYNESS_INTERVAL.get()) {
                hotTicks = 0;
                entity.life().addDryness(1);
            }
        } else hotTicks = 0;
    }

    private void sampleHere() {
        boolean wasCold = !initialSample && cold();
        current = sample(entity.blockPosition(), baskingNow());
        if (cold() && !wasCold && !entity.life().isSleeping() && !entity.isPanicking()) {
            var parent = entity.family().coldCryParent();
            // A nearby cold baby gets the family's first turn to complain.
            boolean spoken = entity.family().coldChildNearby() == null && entity.speakCue("cold");
            if (spoken && parent != null) entity.showReaction("cry", "", 40);
            GameplayLog.event(entity, "temperature", "cold onset felt={} line={} parent={}", current.felt(),
                    spoken ? entity.getDialogueKey() : "silent", parent == null ? null : parent.getUUID());
        }
    }

    /** No offline time, pending route or remembered weather is restored. */
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putInt("ColdExposure", coldExposure()); tag.putInt("DamageTicks", damageTicks);
        tag.putInt("ColdTicks", coldTicks); tag.putInt("HotTicks", hotTicks);
        return tag;
    }
    public void load(CompoundTag tag) {
        coldExposure = Math.clamp(tag.getInt("ColdExposure"), 0, 1_728_000);
        damageTicks = Math.clamp(tag.getInt("DamageTicks"), 0, 23_999);
        coldTicks = Math.clamp(tag.getInt("ColdTicks"), 0, 99);
        hotTicks = Math.clamp(tag.getInt("HotTicks"), 0, 23_999);
        initialSample = true; sampleTicks = temperatureSampleTicks = 0; cooldown = 0; stop();
    }

    /** Block heat, and the internal heat of the kotatsu Marisas nearby. */
    private record HeatSources(List<ThermalSourceCache.Source> blocks, List<ThermalSourceCache.Source> bodies) {}
    /** Public for observation and remote world fixtures; it never generates or loads a chunk. */
    public Conditions sample(BlockPos pos) { return sample(pos, false); }
    /** {@code basking}: as for a body holding still and flat there, which the winter sun warms. */
    private Conditions sample(BlockPos pos, boolean basking) { return sample(pos, heatSources(pos, 4), basking); }
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
        BlockPos campfire = source != null && source.campfire() ? source.pos() : null;
        // A kotatsu Marisa carries an internal heater like a torch carries fire, but only into a body that is cold:
        // beside her a freezing neighbour gets properly warm, and in summer nobody does (wiki L23 内部发热, L22 耐寒怕热).
        if (tooCold(felt + warmth)) {
            var heater = heatSourceAt(pos, sources.bodies());
            if (heater != null) warmth = Math.max(warmth, heatAt(pos, heater));
        }
        // Pressed bodies and a parent directly behind a baby reduce heat loss while they are actually together.
        if (tooCold(felt + warmth) && pos.equals(entity.blockPosition())
                && (entity.status().reactions().weather().huddled() || campfire != null && shieldingParent(campfire) != null))
            felt += .25;
        // Where nothing else warms it enough, a chilled body basking flat in the direct sun of a clear day soaks up the sun.
        boolean sun = basking && tooCold(felt + warmth) && !shade && !level().isNight() && !level().isRaining()
                && level().dimensionType().hasSkyLight() && level().canSeeSky(pos);
        if (sun) felt += SUN_BASK;
        double uncoveredFelt = felt + warmth;
        return new Conditions(uncoveredFelt + (tooCold(uncoveredFelt) ? cover : 0), shade, insulated, warmth, sun, uncoveredFelt, campfire);
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
    private HeatSources heatSources(BlockPos center, int radius) {
        var bodies = new ArrayList<ThermalSourceCache.Source>();
        for (var kotatsu : level().getEntitiesOfClass(YukkuriEntity.class, new AABB(center).inflate(radius),
                other -> other != entity && RareForms.kotatsu(other)))
            bodies.add(new ThermalSourceCache.Source(kotatsu.blockPosition(), KOTATSU_NEIGHBOUR_HEAT, false));
        return new HeatSources(ThermalSourceCache.sources(level(), center, radius), bodies);
    }
    /** Warmth reaching this place from one group of heat sources; a wall blocks it like any other heat. */
    private ThermalSourceCache.Source heatSourceAt(BlockPos pos, List<ThermalSourceCache.Source> sources) {
        ThermalSourceCache.Source best = null;
        double warmth = 0;
        for (var source : sources) {
            if (source.pos().distSqr(pos) > 16 || !level().hasChunkAt(source.pos())) continue;
            double heat = heatAt(pos, source);
            if (heat <= warmth || !clearHeat(pos, source.pos())) continue;
            warmth = heat;
            best = source;
        }
        return best;
    }

    private static double heatAt(BlockPos pos, ThermalSourceCache.Source source) {
        return source.strength() * (1 - Math.sqrt(source.pos().distSqr(pos)) / 6);
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
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !entity.isPassenger()
                && !entity.family().active() && !entity.family().awaitingLanding()
                && (brood == null || brood.cancelled || brood.complete() || brood.progress() < .9f)
                && life.mealTicks() == 0 && life.drugSleep() == 0 && life.sprayDelay() == 0
                && life.nutrition() >= 30
                && (life.bladder() < 70 || life.constipated());
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
        var sources = heatSources(center, reach + 4);
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
            if (seekingWarmth && at.campfire() != null) {
                if (!fireStandFree(pos)) continue;
                score += fireRingScore(pos, at.campfire(), baby, parent);
            }
            candidates.add(new Refuge(pos.immutable(), score, at));
        }
        candidates.sort(Comparator.comparingDouble(Refuge::score));
        for (int i = 0; i < Math.min(16, candidates.size()); i++) {
            var candidate = candidates.get(i);
            if (safePath(candidate.pos()) == null) continue;
            target = candidate.pos(); taskTicks = restTicks = 0; basking = candidate.at().sun();
            targetFire = seekingWarmth ? candidate.at().campfire() : null;
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
        // At two blocks the adult clears the fire's keep-out. Less bold and smaller bodies prefer up to a block more.
        double bold = entity.life().personality().boldness() / 100.0;
        double dominance = (bold * 3 + Math.min(1, entity.getBbWidth())) / 4;
        double preferred = baby != null ? 3 : parent != null ? 2 : 3 - dominance;
        double offset = Math.sqrt(fire.distSqr(pos)) - preferred;
        double score = 12 * offset * offset - 3;
        var kin = baby != null ? baby : parent;
        if (kin == null) return score;
        var kinTemperature = kin.life().temperature();
        var anchor = fire.equals(kinTemperature.targetFire) ? kinTemperature.target : kin.blockPosition();
        int dx = anchor.getX() - fire.getX(), dz = anchor.getZ() - fire.getZ();
        double length = Math.hypot(dx, dz);
        if (length == 0) return score;
        // A parent comes up on the child's nearer flank, leaving its inward path open.
        double cross = dx * (entity.getZ() - fire.getZ() - .5) - dz * (entity.getX() - fire.getX() - .5);
        double flank = baby == null ? 0 : Math.copySign(1, cross);
        double x = pos.getX() - fire.getX() - (preferred * dx - flank * dz) / length;
        double z = pos.getZ() - fire.getZ() - (preferred * dz + flank * dx) / length;
        return score + x * x + z * z;
    }

    private boolean fireStandFree(BlockPos pos) {
        var feet = Vec3.atBottomCenterOf(pos);
        var box = entity.getBoundingBox().move(feet.subtract(entity.position())).deflate(.005);
        return level().getEntitiesOfClass(YukkuriEntity.class, box,
                other -> other != entity && other.isAlive() && other.isPushable() && !other.isPassenger()).isEmpty();
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
        if (!entity.life().dryStand(pos)) return false;
        for (var near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 0, 1))) {
            if (!level().hasChunkAt(near)) return false;
            var state = level().getBlockState(near);
            if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                    || ThermalSourceCache.litCampfire(state)) return false;
        }
        return true;
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
        if (!safeStand(target)) return "unsafe_stand";
        if (!mayUse(target)) return "not_ours";
        if (taskTicks % 20 == 0) {
            if (waterTakesPriority()) return "lost_refuge";
            var at = sample(target, basking);
            if (!refuge(at) || targetFire != null && (!targetFire.equals(at.campfire()) || !fireStandFree(target))) return "lost_refuge";
        }
        return null;
    }
    private boolean waterTakesPriority() {
        var life = entity.life();
        // When there is no reachable water, thirst must not strand an animal in direct sun.
        return (life.dryness() >= 40 || life.spicyTicks() > 0) && life.hasReachableDrink();
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
                GameplayLog.event(entity, "temperature", "campfire rest fire={} stand={} ring={} felt={} rate={} shield_parent={} ticks={}",
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
        if (occupancy == null && entity.getNavigation().isDone() && target.distManhattan(entity.blockPosition()) <= 1) {
            SurfaceApproach.walk(entity, Vec3.atBottomCenterOf(target), 1.15);
            return true;
        }
        if (taskTicks % 20 == 1) {
            var path = safePath(target);
            if (path == null || !entity.getNavigation().moveTo(path, 1)) { stop(); return false; }
        }
        return true;
    }
    /** A refuge is reached on a real bed only inside it; a stand without a bed is a whole block, and the body stands in it. */
    private boolean reached(BlockPos site, Occupancy occupancy) {
        return occupancy != null ? occupancy == Occupancy.INSIDE_SUPPORTED
                : entity.blockPosition().equals(site) && entity.onGround();
    }
    /** Transfers only a physically reached safe place, never home ownership or elapsed sleep. */
    public boolean beginSleepAtRefuge() {
        if (!active() || restTicks < 20 || !available() || waterTakesPriority()
                || !reached(target, FamilyNest.bedOccupancy(entity, target)) || !safeStand(target) || !mayUse(target)
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
                && safeStand(temporarySleepSite) && mayUse(temporarySleepSite)
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
        String state = freezing(at.felt()) ? "freezing" : tooCold(at.felt()) ? "cold" : at.felt() >= hotLimit() ? "hot" : "comfortable";
        return Component.translatable("notebook.mcyukkuri.temperature", Component.translatable("temperature.mcyukkuri." + state),
                String.format(Locale.ROOT, "%.2f", at.felt()), coldExposure(), YukkuriConfig.COLD_EXPOSURE_TICKS.get(),
                at.shade() ? "✓" : "—", at.insulated() ? "✓" : "—", at.warmth() > 0 ? "✓" : "—");
    }
}
