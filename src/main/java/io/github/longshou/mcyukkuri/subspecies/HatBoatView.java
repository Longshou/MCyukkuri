package io.github.longshou.mcyukkuri.subspecies;

import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Synchronized presentation of one hat its owner has set down to catch young. Read only; no server state is exposed. */
public interface HatBoatView {
    int getId();
    UUID getUUID();
    Level level();
    Vec3 position();
    Vec3 getPosition(float partial);
    float getYRot();
    float previousYaw();
    UUID ownerYukkuri();
    HatBoatEntity.State state();
    boolean isCradle();
    boolean catches();
    /** The hat's size: its owner's growth, larger for the aquatic Marisa. */
    float hullScale();
    /** Synchronized headwear damage of the deployed hat (0..2), drawn as on a worn hat. */
    int hullDamage();
}
