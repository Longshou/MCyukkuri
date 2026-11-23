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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Did this body perceive that? The one answer for sight (range, cone, line of sight with one glass layer) and hearing (radius by
 * loudness, halved through each wall). Hearing counts as seeing: whoever perceives an event reacts as if it saw it.
 * The world's real state may score or drive a physical contact; it never decides what a body knows.
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

    /** A need the body feels now: it looks and listens farther for what would answer it. */
    public static boolean felt(YukkuriEntity body, Cue.Need need) {
        var life = body.life();
        return need == Cue.Need.WATER ? life.dryness() >= YukkuriLife.THIRST_THRESHOLD : life.nutrition() < HUNGRY;
    }

    public static double urgency(YukkuriEntity body, Cue.Need need) {
        var life = body.life();
        return need == Cue.Need.WATER ? Belief.waterUrgency(life.dryness()) : Belief.foodUrgency(life.nutrition());
    }

    /** A stationary fixture or water cell seen now: awake, in range, with a clear line (one glass layer allowed); facing does not matter. */
    public static boolean sees(YukkuriEntity body, BlockPos fixture, Cue.Need need) {
        var at = Vec3.atCenterOf(fixture);
        return Sight.inRange(awake(body), body.distanceToSqr(at), range(body, need))
                && GlassSight.clearTo(body, at, fixture);
    }

    /** A stationary thing (loose food) seen now, under the same rules. */
    public static boolean sees(YukkuriEntity body, Entity thing, Cue.Need need) {
        return Sight.inRange(awake(body), body.distanceToSqr(thing), range(body, need)) && GlassSight.sees(body, thing);
    }

    private static double range(YukkuriEntity body, Cue.Need need) {
        return Sight.range(body.life().personality().intelligence(), felt(body, need));
    }

    /** How this body perceived a sound made at {@code source}; null when it neither saw nor heard it. */
    public static Via perceives(YukkuriEntity body, BlockPos source, Cue cue) {
        if (!body.isAlive() || body.body().dying()) return null;
        var at = Vec3.atCenterOf(source);
        double distance = body.distanceToSqr(at);
        var personality = body.life().personality();
        boolean salient = felt(body, cue.need), awake = awake(body), panicking = body.isPanicking();
        double facing = body.getViewVector(1).dot(at.subtract(body.getEyePosition()).normalize());
        if (Sight.inView(awake, distance, facing, Sight.range(personality.intelligence(), salient),
                Sight.coneCos(personality.boldness(), panicking)) && GlassSight.clearTo(body, at, source)) return Via.SAW;
        double open = Hearing.radius(cue.loudness, personality.boldness(), salient, !awake, panicking, 0);
        if (!Hearing.audible(open, distance)) return null;
        double heard = Hearing.radius(cue.loudness, personality.boldness(), salient, !awake, panicking, layers(body.level(), source, body));
        return Hearing.audible(heard, distance) ? Via.HEARD : null;
    }

    /** Every body that saw or heard the cue, each with how. One call at the moment of the event. */
    public static List<Witness> witnesses(ServerLevel level, BlockPos source, Cue cue) {
        var found = new ArrayList<Witness>();
        for (var body : level.getEntitiesOfClass(YukkuriEntity.class, new AABB(source).inflate(SEARCH))) {
            var via = perceives(body, source, cue);
            if (via != null) found.add(new Witness(body, via));
        }
        return found;
    }

    /** Solid cells (full blocks, glass, shut doors) on the straight line from the source to the listener's ear. */
    private static int layers(Level level, BlockPos source, YukkuriEntity body) {
        var ear = BlockPos.containing(body.getEyePosition());
        int[] walls = {0};
        BlockGetter.traverseBlocks(Vec3.atCenterOf(source), body.getEyePosition(), level, (world, pos) -> {
            if (!pos.equals(source) && !pos.equals(ear) && wall(world, pos)) walls[0]++;
            return null;
        }, world -> null);
        return walls[0];
    }

    private static boolean wall(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isCollisionShapeFullBlock(level, pos) || state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN);
    }
}
