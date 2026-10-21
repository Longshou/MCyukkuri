package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;

/** Loaded-time thermal exposure and interruptible refuge seeking. All numbers are MC adaptation. */
public final class TemperatureLife {
    public record Conditions(double felt, boolean shade, boolean insulated, double warmth) {}
    private record Refuge(BlockPos pos, double score) {}
    /** Felt-temperature bonus of a swaddle in the cold and the largest combined bonus of swaddle and nest (project-original numbers). */
    public static final double SWADDLE_WARMTH = .5, SWADDLE_CAP = 1.5;
    private final YukkuriEntity entity;
    private Conditions current = new Conditions(.8, false, false, 0);
    private boolean initialSample = true;
    /** Sampling is staggered; exposure keeps its legacy 20 loaded-and-AI-enabled tick cadence. */
    private int sampleTicks, temperatureSampleTicks, coldExposure, damageTicks, coldTicks, hotTicks, cooldown, taskTicks, restTicks;
    private BlockPos target, temporarySleepSite;
    private boolean seekingWarmth;
    private boolean waterRefugeMissing;

    public TemperatureLife(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    private double coldLimit() { return YukkuriConfig.COLD_THRESHOLD.get(); }
    private double freezeLimit() { return Math.min(coldLimit(), YukkuriConfig.FREEZE_THRESHOLD.get()); }
    private double hotLimit() { return Math.max(coldLimit() + .5, YukkuriConfig.HOT_THRESHOLD.get()); }
    public int coldExposure() { return coldExposure; }
    private boolean freezing(double felt) { return felt <= freezeLimit(); }
    private boolean tooCold(double felt) { return felt < coldLimit() || freezing(felt); }
    private boolean comfortable(double felt) { return !tooCold(felt) && felt < hotLimit(); }
    public boolean cold() { return tooCold(current.felt()); }
    public boolean hot() { return current.felt() >= hotLimit(); }
    public boolean unsafeSleep() { return cold() || hot(); }
    public boolean comfortableAt(BlockPos pos) { return pos != null && level().hasChunkAt(pos) && comfortable(sample(pos).felt()); }
    public boolean active() { return target != null; }
    public boolean needsShoreRefuge() { return waterRefugeMissing && unsafeSleep(); }
    public String activity() { return restTicks > 0 ? seekingWarmth ? Behaviors.WARM_UP : Behaviors.SHADE_REST : seekingWarmth ? Behaviors.SEEK_WARMTH : Behaviors.SEEK_SHADE; }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi()) return;
        if (cooldown > 0) cooldown--;
        if (initialSample) {
            // A loaded entity has a useful current reading at once, without advancing exposure.
            current = sample(entity.blockPosition());
            initialSample = false;
            temperatureSampleTicks = Math.floorMod(entity.getUUID().hashCode(), 20);
        } else if (++temperatureSampleTicks >= 20) {
            temperatureSampleTicks = 0;
            current = sample(entity.blockPosition());
        }
        if (++sampleTicks < 20) return;
        sampleTicks = 0;
        if (freezing(current.felt())) {
            int increment = entity.family().stage() == FamilyLife.Stage.BABY ? 40 : entity.family().adult() ? 20 : 30;
            if (entity.pet().care().swaddled()) increment /= 2; // a swaddled young one takes half the cold exposure
            coldExposure = Math.min(1_728_000, coldExposure + increment);
            if (coldExposure >= YukkuriConfig.COLD_EXPOSURE_TICKS.get()) {
                damageTicks += 20;
                if (damageTicks >= YukkuriConfig.FREEZE_DAMAGE_INTERVAL.get()) {
                    damageTicks = 0;
                    entity.hurt(level().damageSources().freeze(), 1);
                }
            }
        } else {
            coldExposure = Math.max(0, coldExposure - 40);
            damageTicks = 0;
        }
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

    /** No offline time, pending route or remembered weather is restored. */
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putInt("ColdExposure", coldExposure); tag.putInt("DamageTicks", damageTicks);
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

    /** Public for observation and remote world fixtures; it never generates or loads a chunk. */
    public Conditions sample(BlockPos pos) { return sample(pos, heatSources(pos, 4)); }
    private Conditions sample(BlockPos pos, List<ThermalSourceCache.Source> sources) {
        if (!level().hasChunkAt(pos)) return new Conditions(.8, false, false, 0);
        boolean shade = shaded(pos), insulated = insulated(pos, shade);
        double biome = level().getBiome(pos).value().getBaseTemperature();
        double felt = SeasonClimate.ambient(biome, SeasonClimate.offset(level(), pos), level().dimensionType().hasSkyLight(),
                level().isNight(), shade, level().isRaining());
        felt -= entity.life().wetness() / 100.0 * .35;
        felt += RareForms.kotatsuWarmth(entity, felt); // a kotatsu Marisa carries her own warmth
        double bonus = insulated && tooCold(felt) ? YukkuriConfig.NEST_INSULATION.get() : 0;
        // The swaddle adds a little warmth on top of a nest, up to a combined bonus of 1.5 (docs/PET_SHOP.md section 12).
        if (entity.pet().care().swaddled() && tooCold(felt + bonus)) bonus = Math.max(bonus, Math.min(bonus + SWADDLE_WARMTH, SWADDLE_CAP));
        felt += bonus;
        double warmth = 0;
        for (var source : sources) {
            double distance = Math.sqrt(source.pos().distSqr(pos));
            if (distance > 4 || !level().hasChunkAt(source.pos())) continue;
            if (clearHeat(pos, source.pos())) warmth = Math.max(warmth, source.strength() * (1 - distance / 6));
        }
        return new Conditions(felt + warmth, shade, insulated, warmth);
    }
    private boolean shaded(BlockPos pos) {
        if (!level().hasChunkAt(pos)) return false;
        var shelter = entity.habitat().shelter();
        if (shelter != null && shelter.contains(pos) && shelter.sound() && shelter.rainSheltered(pos)) return true;
        return level().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY();
    }
    private boolean insulated(BlockPos pos, boolean shade) {
        if (!shade) return false;
        var shelter = entity.habitat().shelter();
        if (shelter != null && shelter.owns(entity.getUUID()) && shelter.contains(pos)
                && shelter.usable(entity) && shelter.insulated()) return true;
        var nest = entity.house().nest();
        if (nest != null && nest.built() && nest.dirt() < 6 && nest.usable(entity)
                && (pos.equals(nest.getBlockPos()) || pos.equals(nest.entrance()))) return true;
        var home = entity.life().home();
        return home != null && !entity.life().naturalHome() && home.dimension().equals(level().dimension())
                && level().hasChunkAt(home.pos()) && home.pos().distManhattan(pos) <= 1
                && level().getBlockEntity(home.pos()) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST
                && entity.getUUID().equals(care.resident()) && entity.life().dryStand(pos);
    }
    private List<ThermalSourceCache.Source> heatSources(BlockPos center, int radius) {
        return ThermalSourceCache.sources(level(), center, radius);
    }
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
                && (!entity.isPassenger() || EcologyMovement.captain(entity) && EcologyMovement.supported(entity))
                && !entity.family().active() && !entity.family().awaitingLanding()
                && (brood == null || brood.cancelled || brood.complete() || brood.progress() < .9f)
                && life.mealTicks() == 0 && life.drugSleep() == 0 && life.sprayDelay() == 0
                && life.nutrition() >= 30
                && (life.bladder() < 70 || life.constipated());
    }
    public boolean plan() {
        if (active()) return canContinue();
        if (cooldown > 0 || !available()) return false;
        cooldown = 100;
        current = sample(entity.blockPosition());
        if (!cold() && !hot()) return false;
        seekingWarmth = cold();
        if (EcologyMovement.captain(entity)) return planAfloat();
        var candidates = new ArrayList<Refuge>();
        var center = entity.blockPosition();
        var sources = heatSources(center, 10);
        for (var pos : BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 2, 6))) {
            if (!safeStand(pos)) continue;
            if (!mayUse(pos)) continue;
            var at = sample(pos, sources);
            if (!refuge(at)) continue;
            double distance = pos.distSqr(center);
            candidates.add(new Refuge(pos.immutable(), distance - (seekingWarmth && at.insulated() ? 8 : 0)));
        }
        candidates.sort(Comparator.comparingDouble(Refuge::score));
        for (int i = 0; i < Math.min(16, candidates.size()); i++) {
            var candidate = candidates.get(i);
            if (safePath(candidate.pos()) == null) continue;
            target = candidate.pos(); taskTicks = restTicks = 0;
            GameplayLog.limited(entity, "temperature", "refuge.start", 1200,
                    "refuge route selected warming={} target={} felt={} coldExposure={}", seekingWarmth, target, current.felt(), coldExposure);
            return true;
        }
        return false;
    }
    /** The same sampled climate, but only real reachable water is a place to park a hat. */
    private boolean planAfloat() {
        var boat = EcologyMovement.boat(entity);
        var center = BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ());
        var candidates = new ArrayList<Refuge>();
        var sources = heatSources(center, 10);
        for (var at : BlockPos.betweenClosed(center.offset(-8, 0, -8), center.offset(8, 0, 8))) {
            if (!HatBoatRoute.quietSurface(level(), at)) continue;
            var feet = at.above();
            if (!refuge(sample(feet, sources))) continue;
            var point = HatBoatRoute.surface(level(), at);
            if (!entity.aquaticLife().familyLife().allowsDestination(boat, point)
                    || !level().noCollision(boat, boat.getBoundingBox().move(point.subtract(boat.position())))) continue;
            candidates.add(new Refuge(feet.immutable(), feet.distSqr(entity.blockPosition())));
        }
        candidates.sort(Comparator.comparingDouble(Refuge::score));
        for (var candidate : candidates.stream().limit(12).toList()) {
            if (!EcologyMovement.reachable(entity, candidate.pos())) continue;
            target = candidate.pos(); taskTicks = restTicks = 0; waterRefugeMissing = false;
            GameplayLog.event(entity, "temperature", "water refuge selected hat={} warming={} target={} felt={}",
                    boat.getUUID(), seekingWarmth, target, current.felt());
            return true;
        }
        waterRefugeMissing = true;
        GameplayLog.limited(entity, "temperature", "water.no_refuge", 400,
                "no safe water refuge warming={} felt={} coldExposure={}", seekingWarmth, current.felt(), coldExposure);
        return false;
    }
    private boolean refuge(Conditions at) {
        return comfortable(at.felt()) && (seekingWarmth || at.shade());
    }
    private boolean mayUse(BlockPos pos) {
        if (!level().hasChunkAt(pos)) return false;
        var state = level().getBlockState(pos);
        if (state.getBlock() instanceof ShelterBlock && !level().hasChunkAt(ShelterBlock.root(pos, state))) return false;
        var owner = ShelterBlock.shelter(level(), pos);
        if (owner != null && !owner.owns(entity.getUUID())) return false;
        for (var near : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 0, 1))) {
            if (!level().hasChunkAt(near)) return false;
            var block = level().getBlockEntity(near);
            if (block instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST && care.resident() != null
                    && !entity.getUUID().equals(care.resident()) && near.distManhattan(pos) <= 1) return false;
            if (block instanceof HouseNestBlockEntity nest
                    && (pos.equals(near) || pos.equals(nest.entrance())) && !near.equals(entity.house().nestPosition())) return false;
        }
        return true;
    }
    private boolean safeStand(BlockPos pos) {
        if (EcologyMovement.captain(entity)) return HatBoatRoute.quietSurface(level(), pos.below());
        if (!entity.life().dryStand(pos)) return false;
        for (var near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 0, 1))) {
            if (!level().hasChunkAt(near)) return false;
            var state = level().getBlockState(near);
            if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                    || (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) && state.getValue(BlockStateProperties.LIT)) return false;
        }
        return true;
    }
    private Path safePath(BlockPos pos) {
        var path = entity.getNavigation().createPath(pos, 0);
        if (path == null || !path.canReach() || !entity.aquaticLife().shore().allows(path)) return null;
        // Permit departure from the starting block; no later node may enter a hazard.
        for (int i = 1; i < path.getNodeCount(); i++) {
            var node = path.getNode(i);
            if (!safeStand(new BlockPos(node.x, node.y, node.z))) return null;
        }
        return path;
    }
    public boolean canContinue() {
        return active() && available() && taskTicks < 400 && restTicks < 200 && safeStand(target) && mayUse(target)
                && (taskTicks % 20 != 0 || !waterTakesPriority() && refuge(sample(target)));
    }
    private boolean waterTakesPriority() {
        var life = entity.life();
        // When there is no reachable water, thirst must not strand an animal in direct sun.
        return (life.dryness() >= 40 || life.spicyTicks() > 0) && life.hasReachableDrink();
    }
    public boolean tickGoal() {
        if (!canContinue()) { stop(); return false; }
        taskTicks++;
        if (EcologyMovement.captain(entity)) {
            var boat = EcologyMovement.boat(entity);
            var point = HatBoatRoute.surface(level(), target.below());
            if (boat.position().distanceToSqr(point) <= .2) {
                boat.holdPosition(); restTicks++; return true;
            }
            restTicks = 0;
            if (taskTicks % 20 == 1 && !EcologyMovement.navigate(entity, point, 1)) { stop(); return false; }
            return true;
        }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= .2) {
            entity.getNavigation().stop(); restTicks++;
            // A short pause consumes no supplies and grants neither sleep growth nor free hydration.
            return true;
        }
        restTicks = 0;
        if (taskTicks % 20 == 1) {
            var path = safePath(target);
            if (path == null || !entity.getNavigation().moveTo(path, 1)) { stop(); return false; }
        }
        return true;
    }
    /** Transfers only a physically reached safe place, never home ownership or elapsed sleep. */
    public boolean beginSleepAtRefuge() {
        if (!active() || restTicks < 20 || !available() || waterTakesPriority()
                || entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > .2 || !safeStand(target) || !mayUse(target)
                || level().isRainingAt(target)) return false;
        current = sample(entity.blockPosition());
        if (!refuge(current)) return false;
        temporarySleepSite = target;
        GameplayLog.limited(entity, "temperature", "refuge.sleep", 1200,
                "refuge handed to sleep site={} felt={} restTicks={}", target, current.felt(), restTicks);
        target = null; taskTicks = restTicks = 0; cooldown = Math.max(cooldown, 60);
        return true;
    }
    public boolean hasTemporarySleepSite() { return temporarySleepSite != null; }
    public boolean canSleepAtRefuge() {
        return temporarySleepSite != null && available()
                && entity.distanceToSqr(Vec3.atBottomCenterOf(temporarySleepSite)) <= .2
                && safeStand(temporarySleepSite) && mayUse(temporarySleepSite)
                && !level().isRainingAt(temporarySleepSite) && comfortable(current.felt())
                && (sampleTicks != 0 || !waterTakesPriority());
    }
    public void stop() {
        if (target != null) GameplayLog.limited(entity, "temperature", "refuge.stop", 1200,
                "refuge stopped warming={} target={} elapsed={} restTicks={} felt={}", seekingWarmth, target, taskTicks, restTicks, current.felt());
        target = temporarySleepSite = null; taskTicks = restTicks = 0; cooldown = Math.max(cooldown, 60);
    }

    public Component observation() {
        current = sample(entity.blockPosition());
        String state = freezing(current.felt()) ? "freezing" : cold() ? "cold" : hot() ? "hot" : "comfortable";
        return Component.translatable("notebook.mcyukkuri.temperature", Component.translatable("temperature.mcyukkuri." + state),
                String.format(Locale.ROOT, "%.2f", current.felt()), coldExposure, YukkuriConfig.COLD_EXPOSURE_TICKS.get(),
                current.shade() ? "✓" : "—", current.insulated() ? "✓" : "—", current.warmth() > 0 ? "✓" : "—");
    }
}
