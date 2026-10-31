package io.github.longshou.mcyukkuri.headwear;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Synchronized presentation of one hat its owner has set down to catch young. Read only; no server state is exposed. */
public interface PlacedHatView {
    int getId();
    UUID getUUID();
    Level level();
    Vec3 position();
    Vec3 getPosition(float partial);
    float getYRot();
    float previousYaw();
    UUID ownerYukkuri();
    PlacedHatEntity.State state();
    boolean isCradle();
    boolean catches();
    /** The hat's size, the same in every form (HatScale). */
    float hullScale();
    String formId();
    default HullProfile profile() { return HullProfile.of(formId()); }
    /** The hull's tilt (degrees, HostFrame roll and pitch) between the last two ticks. */
    float roll(float partial);
    float pitch(float partial);
    /** Synchronized headwear damage of the deployed hat (0..2), drawn as on a worn hat. */
    int hullDamage();
    /** What its behaviour keeps in the hollow besides riders (an add-on's stores), as that behaviour synced it. */
    CompoundTag contents();
}
