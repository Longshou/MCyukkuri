package io.github.longshou.mcyukkuri.perception;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Did this body perceive that? The one answer for sight (range, view cone, line of sight with one glass layer) and hearing (radius by
 * loudness, halved through each wall). Hearing counts as seeing: whoever perceives an event reacts as if it saw it. Sight is one rule:
 * a thing seen at the moment of an event and a still thing noticed on the way are judged alike, so a body that looks away sees neither.
 * The world's real state may score or drive a physical contact; it never decides what a body knows.
 * <p>A {@code need} (nullable) makes the body see and hear farther for what would answer a need it feels now.
 */
public final class Senses {
    public enum Via { SAW, HEARD }

    public record Witness(YukkuriEntity body, Via via) {}

    /** The longest any body can see or hear a cue: the keenest sight at full salience. */
    private static final double SEARCH = Sight.range(100, true);
    /** Food below this nutrition is felt as a need (the request line). */
    private static final float HUNGRY = 45;

    private Senses() {}

    public static boolean awake(YukkuriEntity body) {
        return body.isAlive() && !body.body().dying() && !body.life().isSleeping();
    }

    /** A need the body feels now (none for a null need). */
    public static boolean felt(YukkuriEntity body, Cue.Need need) {
        if (need == null) return false;
        var life = body.life();
        return need == Cue.Need.WATER ? life.dryness() >= YukkuriLife.THIRST_THRESHOLD : life.nutrition() < HUNGRY;
    }

    public static double urgency(YukkuriEntity body, Cue.Need need) {
        var life = body.life();
        return need == Cue.Need.WATER ? Belief.waterUrgency(life.dryness()) : Belief.foodUrgency(life.nutrition());
    }

    // sight

    /** An entity seen now: awake, in range, in the view cone (or very near) and in line of sight. */
    public static boolean sees(YukkuriEntity body, Entity target) { return sees(body, target, null); }

    public static boolean sees(YukkuriEntity body, Entity target, Cue.Need need) {
        return inView(body, target.getBoundingBox().getCenter(), need) && GlassSight.sees(body, target);
    }

    /** A fixture or water cell seen now. */
    public static boolean sees(YukkuriEntity body, BlockPos fixture, Cue.Need need) {
        return sees(body, Vec3.atCenterOf(fixture), fixture, need);
    }

    /** A point seen now; a ray that ends on {@code accept}'s own shape (a fixture) is clear. */
    public static boolean sees(YukkuriEntity body, Vec3 point, BlockPos accept, Cue.Need need) {
        return inView(body, point, need) && GlassSight.clearTo(body, point, accept);
    }

    private static boolean inView(YukkuriEntity body, Vec3 at, Cue.Need need) {
        var personality = body.life().personality();
        double facing = body.getViewVector(1).dot(at.subtract(body.getEyePosition()).normalize());
        return Sight.inView(awake(body), body.distanceToSqr(at), facing, Sight.range(personality.intelligence(), felt(body, need)),
                Sight.coneCos(personality.boldness(), body.isPanicking()));
    }

    // sound

    /** How this body perceived a sound made at a fixture; null when it neither saw nor heard it. */
    public static Via perceives(YukkuriEntity body, BlockPos source, Cue cue) {
        return perceives(body, Vec3.atCenterOf(source), source, cue);
    }

    /** How this body perceived a sound made at {@code at} ({@code accept}: the block it comes from, if any); null when it neither saw nor heard it. */
    public static Via perceives(YukkuriEntity body, Vec3 at, BlockPos accept, Cue cue) {
        if (!body.isAlive() || body.body().dying()) return null;
        if (sees(body, at, accept, cue.need)) return Via.SAW;
        boolean asleep = !awake(body);
        if (asleep && !cue.loud) return null; // a mild sound does not wake a sleeper, so it cannot be answered in its sleep
        var personality = body.life().personality();
        boolean salient = felt(body, cue.need), panicking = body.isPanicking();
        double distance = body.distanceToSqr(at);
        if (!Hearing.audible(Hearing.radius(cue.loudness, personality.boldness(), salient, asleep, panicking, 0), distance)) return null;
        int layers = layers(body.level(), at, accept, body);
        return Hearing.audible(Hearing.radius(cue.loudness, personality.boldness(), salient, asleep, panicking, layers), distance) ? Via.HEARD : null;
    }

    /** Every body that saw or heard the cue, each with how. One call at the moment of the event. */
    public static List<Witness> witnesses(ServerLevel level, BlockPos source, Cue cue) {
        return witnesses(level, Vec3.atCenterOf(source), source, cue);
    }

    public static List<Witness> witnesses(ServerLevel level, Vec3 at, BlockPos accept, Cue cue) {
        var found = new ArrayList<Witness>();
        for (var body : level.getEntitiesOfClass(YukkuriEntity.class, new AABB(at, at).inflate(SEARCH))) {
            var via = perceives(body, at, accept, cue);
            if (via != null) found.add(new Witness(body, via));
        }
        return found;
    }

    /** Solid cells (full blocks, glass, shut doors) on the straight line from the source to the listener's ear. */
    private static int layers(Level level, Vec3 at, BlockPos accept, YukkuriEntity body) {
        var source = BlockPos.containing(at);
        var ear = BlockPos.containing(body.getEyePosition());
        int[] walls = {0};
        BlockGetter.traverseBlocks(at, body.getEyePosition(), level, (world, pos) -> {
            if (!pos.equals(source) && !pos.equals(accept) && !pos.equals(ear) && wall(world, pos)) walls[0]++;
            return null;
        }, world -> null);
        return walls[0];
    }

    private static boolean wall(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isCollisionShapeFullBlock(level, pos) || state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)
                || state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }
}
