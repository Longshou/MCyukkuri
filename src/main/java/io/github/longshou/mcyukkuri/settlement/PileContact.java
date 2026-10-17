package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The last steps of depositing at or withdrawing from a forage pile: walk to the feeding side, face the heap and keep the
 * mouth on it for {@link #TICKS}. Only one animal holds the pile at a time; the others wait a little way off.
 */
public final class PileContact {
    public static final int TICKS = 20;
    private static final double NEAR_SQR = 2.5 * 2.5;
    private final YukkuriEntity entity;
    private int contact;
    public PileContact(YukkuriEntity entity) { this.entity = entity; }
    public void reset() { contact = 0; }
    /** Within the distance at which this animal takes (or waits for) the pile's single mouth slot. */
    public boolean near(ForagePileBlockEntity pile) { return entity.distanceToSqr(pile.standPoint(entity)) < NEAR_SQR; }
    /** One tick of the final approach and contact; true when the mouth has stayed on the pile long enough. */
    public boolean tick(ForagePileBlockEntity pile) {
        long now = entity.level().getGameTime();
        Vec3 stand = pile.standPoint(entity);
        if (near(pile) && !pile.reserve(entity.getUUID(), now)) { SurfaceApproach.halt(entity); contact = 0; return false; }
        boolean arrived = SurfaceApproach.walk(entity, stand, .65);
        // A heap or an uneven floor can make the exact last step impossible; a body standing on the spot is close enough.
        if (!arrived && entity.onGround() && Math.abs(entity.getY() - stand.y) < .6
                && entity.position().subtract(stand).horizontalDistanceSqr() < .09) { SurfaceApproach.halt(entity); arrived = true; }
        if (!arrived) { contact = 0; return false; }
        Vec3 touch = pile.touchPoint();
        float yaw = SurfaceApproach.yaw(entity.position(), touch);
        SurfaceApproach.face(entity, yaw);
        if (Math.abs(Mth.wrapDegrees(yaw - entity.getYRot())) > 15) { contact = 0; return false; }
        pile.reserve(entity.getUUID(), now);
        return ++contact >= TICKS;
    }
}
