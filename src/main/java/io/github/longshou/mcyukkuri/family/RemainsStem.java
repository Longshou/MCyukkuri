package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.body.RemainsWater;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** An inert view of the last attached brood. The remains stack owns it after death. */
public record RemainsStem(UUID brood, float progress, float variation, YukkuriVariant mother, float growthScale,
                          float entityScale, int absentMask, int types, int[] sizes) {
    public static final String KEY = "Stem";
    public static final String FOLDED = "StemFolded";
    private static final YukkuriVariant[] VARIANTS = YukkuriVariant.values();

    public static RemainsStem capture(YukkuriEntity mother) {
        var brood = mother.family().brood();
        if (brood == null || brood.kind != FamilyData.BirthKind.STEM || brood.cancelled || brood.complete()) return null;
        int absent = 0, types = 0;
        int[] sizes = new int[brood.children.size()];
        for (int i = 0; i < sizes.length; i++) {
            var child = brood.children.get(i);
            if (child.born || child.eggLost) absent |= 1 << i;
            types |= child.variant.ordinal() << (4 * i);
            sizes[i] = Math.round(10000 * GrowthSize.physicalBirth(brood.kind, child));
        }
        return new RemainsStem(brood.id, brood.progress(), brood.variation, mother.getVariant(),
                mother.growthScale(), mother.getScale(), absent, types, sizes);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Brood", brood);
        tag.putFloat("Progress", progress);
        tag.putFloat("Variation", variation);
        tag.putInt("Mother", mother.ordinal());
        tag.putFloat("Scale", growthScale);
        tag.putFloat("EntityScale", entityScale);
        tag.putInt("Absent", absentMask);
        tag.putInt("Types", types);
        tag.putIntArray("Sizes", sizes);
        return tag;
    }

    public static RemainsStem visible(CompoundTag remains) {
        if (!remains.contains(KEY, Tag.TAG_COMPOUND) || RemainsRot.stage(remains) == RemainsRot.ROTTEN
                || RemainsWater.stage(remains) >= RemainsWater.DISSOLVING) return null;
        var tag = remains.getCompound(KEY);
        int[] sizes = tag.getIntArray("Sizes");
        int parent = tag.getInt("Mother");
        float p = tag.getFloat("Progress");
        if (!tag.hasUUID("Brood") || sizes.length < 1 || sizes.length > 5 || parent < 0 || parent >= VARIANTS.length
                || !(p > 0 && p <= 1)) return null;
        return new RemainsStem(tag.getUUID("Brood"), p, tag.getFloat("Variation"), VARIANTS[parent],
                tag.getFloat("Scale"), tag.getFloat("EntityScale"), tag.getInt("Absent"), tag.getInt("Types"), sizes);
    }

    public int count() { return sizes.length; }
    public boolean attached(int index) { return (absentMask & 1 << index) == 0; }
    public YukkuriVariant child(int index) { return VARIANTS[Math.min(VARIANTS.length - 1, (types >> (4 * index)) & 15)]; }
    public float birthScale(int index) { return sizes[index] / 10000f; }

    /** Conservative envelope used only at placement and by frustum culling. */
    public AABB extent(Vec3 at, float yaw) {
        float growth = FamilyGeometry.stalk(progress);
        var arc = FamilyGeometry.arc(count(), growth, growthScale, variation, mother);
        AABB bounds = new AABB(at, at);
        for (Vec3 point : arc) {
            Vec3 world = FamilyGeometry.toWorld(at, yaw, entityScale, point);
            bounds = bounds.minmax(new AABB(world, world));
        }
        double radius = .04;
        for (int i = 0; i < count(); i++) {
            if (attached(i)) radius = Math.max(radius, FamilyGeometry.fruitScale(progress, birthScale(i)));
        }
        return new AABB(bounds.minX - radius, at.y + .01, bounds.minZ - radius,
                bounds.maxX + radius, bounds.maxY + radius, bounds.maxZ + radius);
    }
}
