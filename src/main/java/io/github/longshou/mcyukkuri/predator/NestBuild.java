package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.intent.Attempts;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A founding errand. The frame is the work already done; the mouth is the twig in transit. */
final class NestBuild {
    private enum Phase { SURVEY, SOURCE, GATHER, PLACE }
    private static final int DAY = 24000;
    private static final int SURVEY_RADIUS = 4;
    private static final String LOOK = YukkuriAction.LOOK_AROUND.id();
    private final YukkuriEntity body;
    private final PredatorLife flight;
    private long day = Long.MIN_VALUE;
    private Attempts sites = new Attempts(3);
    private boolean surveyed;
    private PredatorNestSearch search;
    private CanopySite site;
    private Phase phase;
    private Vec3 crown;
    private int corner, corners, elapsed, gesture;
    private ItemEntity stick;
    private BlockPos leaf;
    private Vec3 sourceFeet;

    NestBuild(YukkuriEntity body, PredatorLife flight) { this.body = body; this.flight = flight; }

    private ServerLevel level() { return (ServerLevel) body.level(); }
    private MouthOccupancy mouth() { return body.component(YukkuriComponents.MOUTH); }

    boolean eligible() {
        return (body.life().home() == null || body.life().naturalHome() && !body.habitat().hasMemory())
                && body.family().adult() && body.status().identity().wild()
                && !HumanCommerce.protectedAnimal(body) && EventHooks.canEntityGrief(level(), body);
    }

    boolean wants() {
        long today = level().getGameTime() / DAY;
        if (day != today) { day = today; sites = new Attempts(3); surveyed = false; }
        return !surveyed && !sites.exhausted() && !body.life().drowsy() && !level().isRaining()
                && !body.life().hasUrgentNeed() && (mouth().purpose() == Purpose.NEST || mouth().freeForExternalWork())
                && eligible();
    }

    /** Keep ordinary ground activities until the sliced search actually sees a tree worth surveying. */
    boolean canStart() {
        if (!wants()) return false;
        if (search != null && !search.current(level(), body)) cancelSearch();
        if (search == null) search = new PredatorNestSearch(level(), body, true);
        var result = scan();
        if (result == Result.NOT_FOUND) {
            surveyed = true;
            GameplayLog.limited(body, "predator", "nest_build_no_canopy_site", 200, "nest build refused reason=no_canopy_site");
        }
        return site != null || search != null && search.seesCanopySite();
    }

    private Result scan() {
        var result = search.advance(body, pos -> sites.excluded(pos.toShortString(), null), flight::lostNestPenalty);
        if (result != Result.DEFERRED) {
            site = search.selected();
            cancelSearch();
        }
        return result;
    }

    void start() {
        phase(Phase.SURVEY);
        crown = null;
        corners = body.life().personality().temperament().curious() >= .5 ? 8 : 4;
    }

    String activity() {
        return switch (phase) {
            case SURVEY -> Behaviors.PREDATOR_SURVEY;
            case SOURCE, GATHER -> Behaviors.PREDATOR_GATHER;
            case PLACE -> Behaviors.PREDATOR_BUILD;
        };
    }

    String animation() {
        if (phase == Phase.SURVEY && !body.predatorFlying()) return LOOK;
        if (gesture == 0) return body.predatorFlying() ? "predator_fly" : null;
        return phase == Phase.PLACE ? Behaviors.NEST_PLACE : Behaviors.NEST_HARVEST;
    }

    private void phase(Phase next) { phase = next; elapsed = gesture = 0; }

    void tick(int legLimit) {
        if (++elapsed > legLimit) { failed("leg_timeout", Reason.NO_PATH); return; }
        if (!eligible() || level().isNight() || level().isRaining() || body.life().hasUrgentNeed()) { flight.stop(); return; }
        switch (phase) {
            case SURVEY -> survey();
            case SOURCE -> source();
            case GATHER -> gather();
            case PLACE -> place();
        }
    }

    private Vec3 crown(BlockPos tree) {
        int y = tree.getY();
        int radius = SURVEY_RADIUS + Mth.ceil(body.getBbWidth() / 2);
        for (var column : BlockPos.betweenClosed(tree.offset(-radius, 0, -radius), tree.offset(radius, 0, radius))) {
            if (!level().hasChunkAt(column)) continue;
            y = Math.max(y, level().getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()));
        }
        return Vec3.atBottomCenterOf(tree.atY(y + 1));
    }

    private void survey() {
        if (search != null) {
            var result = scan();
            if (result == Result.NOT_FOUND) { failed("no_canopy_site", Reason.NO_ROOM); return; }
            if (result == Result.DEFERRED) return;
        }
        if (level().getBlockState(site.cell()).is(ModBlocks.PREDATOR_NEST_FRAME.get())) {
            startTrips();
            return;
        }
        if (elapsed <= ClipMarkers.of(LOOK).length()) return;
        if (crown == null) {
            crown = crown(site.cell());
            corner = IntStream.range(0, 4).boxed().sorted(Comparator.comparingDouble(i -> body.distanceToSqr(surveyPoint(i))))
                    .filter(i -> flight.canFlyToFeet(surveyPoint(i))).findFirst().orElse(-1);
            if (corner < 0) { failed("no_survey_route", Reason.NO_PATH); return; }
            GameplayLog.event(body, "predator", "nest survey begin crown={} circles={}", crown, corners / 4);
            corners += corner;
        }
        // Complete the loop, then look down over the chosen site before searching for a twig.
        if (corner <= corners + 1) {
            body.setPredatorFlying(true);
            var goal = corner > corners ? crown : surveyPoint(corner);
            if (!CanopySite.clearBody(level(), body, goal)) { failed("survey_obstructed", Reason.NO_ROOM); return; }
            if (body.onGround() || body.distanceToSqr(goal) > 1) {
                flight.flyToFeet(goal, false);
                return;
            }
            corner++;
            return;
        }
        startTrips();
    }

    private Vec3 surveyPoint(int index) {
        double angle = index * Math.PI / 2;
        return crown.add(Math.cos(angle) * SURVEY_RADIUS, 0, Math.sin(angle) * SURVEY_RADIUS);
    }

    private void startTrips() {
        GameplayLog.event(body, "predator", "nest site selected site={} stand={} tree_height={}", site.cell(), site.stand(), site.height());
        phase(mouth().purpose() == Purpose.NEST ? Phase.PLACE : Phase.SOURCE);
    }

    private void source() {
        stick = level().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(16), this::looseStick).stream()
                .min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
        if (stick != null) {
            sourceFeet = itemStand(stick);
            if (sourceFeet != null) { phase(Phase.GATHER); return; }
            stick = null;
        }
        var stand = BlockPos.containing(site.stand());
        var leaves = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(stand.offset(-4, -2, -4), stand.offset(4, 5, 4))) {
            if (level().hasChunkAt(pos) && level().getBlockState(pos).is(BlockTags.LEAVES)
                    && SurfaceApproach.supported(body, leafFeet(pos))) leaves.add(pos.immutable());
        }
        leaves.sort(Comparator.comparingDouble(pos -> site.stand().distanceToSqr(leafFeet(pos))));
        leaf = leaves.stream().limit(8).filter(pos -> flight.canFlyToFeet(leafFeet(pos))).findFirst().orElse(null);
        if (leaf == null) { failed("no_reachable_twig_source", Reason.NO_CONTACT); return; }
        sourceFeet = leafFeet(leaf);
        phase(Phase.GATHER);
    }

    private Vec3 leafFeet(BlockPos pos) { return Vec3.atBottomCenterOf(pos.above()); }

    private boolean looseStick(ItemEntity item) {
        return item.isAlive() && !item.hasPickUpDelay() && item.getItem().is(Items.STICK) && body.distanceToSqr(item) <= 16 * 16;
    }

    private Vec3 itemStand(ItemEntity item) {
        double reach = MouthPlacement.position(body).subtract(body.position()).horizontalDistance();
        return SurfaceApproach.contactStands(body, item.position(), reach, null, at -> Math.abs(at.y - item.getY()) < .15)
                .stream().limit(4).filter(flight::canFlyToFeet).findFirst().orElse(null);
    }

    private void gather() {
        if (stick != null && (!stick.isAlive() || !stick.getItem().is(Items.STICK))
                || leaf != null && (!level().hasChunkAt(leaf) || !level().getBlockState(leaf).is(BlockTags.LEAVES))
                || !SurfaceApproach.supported(body, sourceFeet)) { failed("twig_source_changed", Reason.NO_CONTACT); return; }
        if (!flight.landForBuild(sourceFeet)) return;
        if (!mouth().freeForExternalWork()) { flight.stop(); return; }
        if (stick != null) {
            if (!face(stick.position())) return;
            if (!ItemContact.reaches(body, stick)) { failed("stick_out_of_reach", Reason.NO_CONTACT); return; }
            var stack = stick.getItem();
            mouth().set(Purpose.NEST, stack.split(1));
            if (stack.isEmpty()) stick.discard(); else stick.setItem(stack.copy());
            GameplayLog.event(body, "predator", "nest twig taken source=stick drop={} remaining={}", stick.getUUID(), stack.getCount());
        } else {
            if (!face(Vec3.atBottomCenterOf(site.cell()))) return;
            if (++gesture < ClipMarkers.of(Behaviors.NEST_HARVEST).length()) return;
            var point = MouthPlacement.position(body);
            level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level().getBlockState(leaf)),
                    point.x, sourceFeet.y + .015, point.z, 6, .04, .03, .04, .02);
            mouth().set(Purpose.NEST, new ItemStack(Items.STICK));
            GameplayLog.event(body, "predator", "nest twig taken source=leaf block={}", leaf);
        }
        stick = null;
        leaf = null;
        phase(Phase.PLACE);
    }

    private void place() {
        var cell = site.cell();
        var current = CanopySite.inspect(level(), body, cell);
        if (current == null || !CanopySite.canBuild(body, level().getBlockState(cell)) || !current.stand().equals(site.stand())) {
            failed("site_changed", Reason.NO_ROOM); return;
        }
        if (!flight.landForBuild(current.stand()) || !face(current.pad())) return;
        if (++gesture < ClipMarkers.of(Behaviors.NEST_PLACE).length()) return;
        if (!mouth().handOne(Purpose.NEST, twig -> lay(cell))) { failed("placement_or_claim_refused", Reason.NO_ROOM); return; }
        if (level().getBlockState(cell).is(ModBlocks.PREDATOR_NEST.get()) || body.life().restAfterWork()) flight.stop();
        else phase(Phase.SOURCE);
    }

    private boolean lay(BlockPos pos) {
        var old = level().getBlockState(pos);
        int twigs = old.isAir() ? 0 : old.getValue(PredatorNestFrameBlock.TWIGS);
        if (twigs < PredatorNestFrameBlock.MAX_TWIGS) {
            var frame = ModBlocks.PREDATOR_NEST_FRAME.get().defaultBlockState().setValue(PredatorNestFrameBlock.TWIGS, twigs + 1);
            if (!level().setBlock(pos, frame, Block.UPDATE_ALL)) return false;
            GameplayLog.event(body, "predator", "nest frame twigs={} site={}", twigs + 1, pos);
            return true;
        }
        if (!level().setBlock(pos, ModBlocks.PREDATOR_NEST.get().defaultBlockState(), Block.UPDATE_ALL)) return false;
        if (!body.life().assignHome(GlobalPos.of(level().dimension(), pos))) {
            level().setBlock(pos, old, Block.UPDATE_ALL);
            return false;
        }
        GameplayLog.event(body, "predator", "nest built home={} twigs={}", pos, twigs + 1);
        return true;
    }

    private boolean face(Vec3 contact) {
        float yaw = SurfaceApproach.yaw(body.position(), contact);
        SurfaceApproach.face(body, yaw);
        return Math.abs(Mth.wrapDegrees(body.bodyYaw() - yaw)) <= 10;
    }

    void failed(String reason, Reason why) {
        if (site == null) surveyed = true;
        else sites.fail(site.cell().toShortString(), why, 0, null);
        GameplayLog.limited(body, "predator", "nest_build_" + reason, 200,
                "nest build refused reason={} site={} used={}", reason, site == null ? "none" : site.cell(), sites.used());
        flight.stop();
    }

    void cancelSearch() {
        if (search != null) { search.cancel(body); search = null; }
    }

    void stop() {
        cancelSearch();
        site = null; stick = null; leaf = null; gesture = 0;
        if (body.onGround()) mouth().releaseItems(Purpose.NEST, 20);
    }
}
